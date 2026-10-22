'use strict';
// Programme guide (EPG).
//
// The guide itself is collected by the iptv-org "epg" container (see docker-compose.yml): this
// server writes the list of wanted channels to data/epg/channels.xml, the container fetches their
// programmes from the TV guide sites and writes data/epg/guide.xml, and this server serves it to
// the apps as xmltv.php. Extra XMLTV links set in the control panel are merged in.

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const crypto = require('crypto');
const { downloadFile, fileAgeMs, writeFileAtomic, xmlEscape, xmlUnescape, parseXmltvTime, xmltvTime, log } = require('./util');

const GUIDES_URL = 'https://iptv-org.github.io/api/guides.json';
// Guide sites tried first (Arabic programme titles).
const PREFERRED_SITES = ['shahid.mbc.net', 'elcinema.com', 'sat.tv', 'rotana.net', 'osn.com', 'ayn.om'];
const ELEMENT = /<(programme|channel)\b([^>]*)>([\s\S]*?)<\/\1>/g;

function attr(s, name) {
  const m = new RegExp('\\b' + name + '="([^"]*)"').exec(s);
  return m ? xmlUnescape(m[1]) : '';
}

function inner(s, tag) {
  const m = new RegExp('<' + tag + '\\b[^>]*>([\\s\\S]*?)</' + tag + '>').exec(s);
  return m ? xmlUnescape(m[1]).replace(/\s+/g, ' ').trim() : '';
}

/**
 * The channel list for the iptv-org guide collector: for each wanted guide id (like
 * "AlSaudiya.sa@SD") the best guide site, Arabic sites first. `guides` is the iptv-org guides.json.
 */
function channelsXml(guides, ids) {
  const wanted = new Map(); // base id → full ids
  for (const full of ids) {
    const base = full.split('@')[0];
    if (!wanted.has(base)) wanted.set(base, new Set());
    wanted.get(base).add(full);
  }
  const byBase = new Map();
  for (const g of guides) {
    if (!g || !g.channel || !wanted.has(g.channel) || !g.site || !g.site_id) continue;
    if (!byBase.has(g.channel)) byBase.set(g.channel, []);
    byBase.get(g.channel).push(g);
  }
  const rank = (g) => {
    const lang = g.lang === 'ar' ? 0 : g.lang === 'en' ? 1 : 2;
    const site = PREFERRED_SITES.indexOf(g.site);
    return lang * 100 + (site < 0 ? 50 : site);
  };
  const lines = ['<?xml version="1.0" encoding="UTF-8"?>', '<channels>'];
  let mapped = 0;
  for (const [base, fullIds] of wanted) {
    const all = byBase.get(base);
    if (!all) continue;
    for (const full of fullIds) {
      const feed = full.split('@')[1] || null;
      const pool = all.filter((g) => !feed || g.feed === feed || !g.feed);
      const g = (pool.length ? pool : all).slice().sort((a, b) => rank(a) - rank(b))[0];
      lines.push(
        `  <channel site="${xmlEscape(g.site)}" lang="${xmlEscape(g.lang || 'en')}" xmltv_id="${xmlEscape(full)}" site_id="${xmlEscape(
          g.site_id
        )}">${xmlEscape(g.site_name || base)}</channel>`
      );
      mapped++;
    }
  }
  lines.push('</channels>', '');
  return { text: lines.join('\n'), mapped };
}

/** Reads an XMLTV file piece by piece, keeping only wanted channels inside the time window. */
async function readXmltv(file, wanted, from, to, into) {
  let stream = fs.createReadStream(file);
  const fd = fs.openSync(file, 'r');
  const head = Buffer.alloc(2);
  fs.readSync(fd, head, 0, 2, 0);
  fs.closeSync(fd);
  if (head[0] === 0x1f && head[1] === 0x8b) stream = stream.pipe(zlib.createGunzip());
  stream.setEncoding('utf8');
  let buf = '';
  let count = 0;
  for await (const chunk of stream) {
    buf += chunk;
    ELEMENT.lastIndex = 0;
    let m;
    let end = 0;
    while ((m = ELEMENT.exec(buf))) {
      end = ELEMENT.lastIndex;
      if (m[1] !== 'programme') continue;
      const id = attr(m[2], 'channel').toLowerCase();
      if (!wanted.has(id)) continue;
      const start = parseXmltvTime(attr(m[2], 'start'));
      const stop = parseXmltvTime(attr(m[2], 'stop'));
      if (!start || stop <= start || stop < from || start > to) continue;
      const title = inner(m[3], 'title');
      if (!title) continue;
      let list = into.get(id);
      if (!list) into.set(id, (list = []));
      list.push({ start, stop, title, desc: inner(m[3], 'desc').slice(0, 500) });
      count++;
    }
    buf = buf.slice(end);
    if (buf.length > 8 * 1024 * 1024) buf = buf.slice(-1024 * 1024); // broken file: do not grow forever
  }
  return count;
}

class Epg {
  constructor({ store, dataDir, cacheDir }) {
    this.store = store;
    this.dir = path.join(dataDir, 'epg');
    this.cacheDir = cacheDir;
    this.index = new Map();
    this.version = 0;
    this.guideMtime = 0;
    this.extraAt = 0;
    this.cache = null;
    this.status = { mapped: 0, mappedAt: 0, guideAt: 0, programmes: 0, withGuide: 0, extra: [], error: '' };
    this.busy = false;
  }

  get guideFile() {
    return path.join(this.dir, 'guide.xml');
  }
  get channelsFile() {
    return path.join(this.dir, 'channels.xml');
  }

  start() {
    fs.mkdirSync(this.dir, { recursive: true });
    setTimeout(() => this.reload().catch((e) => log('Guide reload failed:', e)), 4000);
    setInterval(() => this.reload().catch((e) => log('Guide reload failed:', e)), 5 * 60000);
  }

  wanted() {
    const s = new Set();
    for (const ch of this.store.channels) if (ch.enabled && ch.epgId) s.add(ch.epgId.toLowerCase());
    return s;
  }

  /** Writes the channel list for the guide collector (only when it changed). */
  async writeChannelsXml() {
    if (!this.store.settings.epg.enabled) return 0;
    const file = path.join(this.cacheDir, 'guides.json');
    if (fileAgeMs(file) > 7 * 86400000) {
      try {
        await downloadFile(GUIDES_URL, file, { timeout: 300000 });
      } catch (e) {
        log('Could not download the guide sources list:', e.message);
      }
    }
    if (!fs.existsSync(file)) return 0;
    let guides;
    try {
      guides = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (e) {
      log('Guide sources list is damaged; it will be downloaded again.');
      fs.rmSync(file, { force: true });
      return 0;
    }
    const ids = new Set(this.store.channels.filter((ch) => ch.enabled && ch.epgId).map((ch) => ch.epgId));
    const { text, mapped } = channelsXml(guides, ids);
    guides = null;
    let old = '';
    try {
      old = fs.readFileSync(this.channelsFile, 'utf8');
    } catch (e) {
      /* first time */
    }
    if (old !== text) writeFileAtomic(this.channelsFile, text);
    this.status.mapped = mapped;
    this.status.mappedAt = Date.now();
    log(`Guide: ${mapped} channels sent to the guide collector`);
    return mapped;
  }

  /** Re-reads guide.xml when the collector wrote a new one, and the extra XMLTV links every 6 hours. */
  async reload({ force = false } = {}) {
    if (this.busy) return;
    const s = this.store.settings.epg;
    if (!s.enabled) {
      this.index = new Map();
      this.cache = null;
      return;
    }
    let mtime = 0;
    try {
      mtime = fs.statSync(this.guideFile).mtimeMs;
    } catch (e) {
      /* the collector has not written it yet */
    }
    const extraDue = (s.extraUrls || []).length && (force || Date.now() - this.extraAt > 6 * 3600000);
    if (!force && mtime === this.guideMtime && !extraDue) return;
    this.busy = true;
    try {
      const wanted = this.wanted();
      const from = Date.now() - 12 * 3600000;
      const to = Date.now() + 4 * 86400000;
      const index = new Map();
      let count = 0;
      if (mtime) count += await readXmltv(this.guideFile, wanted, from, to, index);
      const extra = [];
      for (const url of s.extraUrls || []) {
        const file = path.join(this.cacheDir, 'xmltv-' + crypto.createHash('md5').update(url).digest('hex').slice(0, 10) + '.xml');
        try {
          if (extraDue || !fs.existsSync(file)) await downloadFile(url, file, { timeout: 180000 });
          const part = new Map();
          const n = await readXmltv(file, wanted, from, to, part);
          // The extra links only fill channels the main guide does not have.
          for (const [id, list] of part) if (!index.has(id)) index.set(id, list);
          count += n;
          extra.push({ url, ok: true, programmes: n });
        } catch (e) {
          extra.push({ url, ok: false, error: e.message });
        }
      }
      if (extraDue) this.extraAt = Date.now();
      for (const list of index.values()) list.sort((a, b) => a.start - b.start);
      this.index = index;
      this.guideMtime = mtime;
      this.version++;
      this.cache = null;
      this.status.guideAt = mtime;
      this.status.programmes = count;
      this.status.withGuide = index.size;
      this.status.extra = extra;
      log(`Guide loaded: ${index.size} channels, ${count} programmes`);
    } finally {
      this.busy = false;
    }
  }

  programmes(epgId) {
    return (epgId && this.index.get(epgId.toLowerCase())) || [];
  }

  /** The XMLTV document for the apps (cached until the guide or the lineup changes). */
  xmltv(lineup) {
    const ids = new Map();
    for (const { ch } of lineup) {
      if (ch.epgId && !ids.has(ch.epgId.toLowerCase())) ids.set(ch.epgId.toLowerCase(), ch);
    }
    const key = this.version + '|' + [...ids.keys()].join(',');
    if (this.cache && this.cache.key === key) return this.cache;
    const out = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="RATV Server">'];
    for (const ch of ids.values()) {
      out.push(
        `<channel id="${xmlEscape(ch.epgId)}"><display-name>${xmlEscape(ch.name)}</display-name>${
          ch.logo ? `<icon src="${xmlEscape(ch.logo)}"/>` : ''
        }</channel>`
      );
    }
    const now = Date.now() - 6 * 3600000;
    for (const [lid, ch] of ids) {
      for (const p of this.index.get(lid) || []) {
        if (p.stop < now) continue;
        out.push(
          `<programme start="${xmltvTime(p.start)}" stop="${xmltvTime(p.stop)}" channel="${xmlEscape(ch.epgId)}"><title>${xmlEscape(
            p.title
          )}</title>${p.desc ? `<desc>${xmlEscape(p.desc)}</desc>` : ''}</programme>`
        );
      }
    }
    out.push('</tv>', '');
    const body = Buffer.from(out.join('\n'));
    this.cache = { key, body, gz: zlib.gzipSync(body) };
    return this.cache;
  }
}

module.exports = { Epg, readXmltv, channelsXml, GUIDES_URL };
