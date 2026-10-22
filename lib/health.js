'use strict';
// Checks every channel link in the background so the apps only list channels that play.

const { spawn } = require('child_process');
const { headersFor, hostOf, isHttpUrl, readLimited, Limiter, log } = require('./util');

const TIMEOUT = 10000;

/** The best quality of an HLS master playlist (highest bitrate up to 1080p). */
function pickVariant(text, baseUrl, maxHeight = 1080) {
  const lines = text.split(/\r?\n/);
  const variants = [];
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i].trim();
    if (!l.startsWith('#EXT-X-STREAM-INF')) continue;
    let uri = '';
    for (let j = i + 1; j < lines.length; j++) {
      const n = lines[j].trim();
      if (!n) continue;
      if (n.startsWith('#')) continue;
      uri = n;
      break;
    }
    if (!uri) continue;
    const bw = +(/BANDWIDTH=(\d+)/i.exec(l) || [])[1] || 0;
    const h = +(/RESOLUTION=\d+x(\d+)/i.exec(l) || [])[1] || 0;
    const audioGroup = (/AUDIO="([^"]+)"/i.exec(l) || [])[1] || '';
    try {
      variants.push({ url: new URL(uri, baseUrl).href, bw, h, audioGroup });
    } catch (e) {
      /* skip a broken line */
    }
  }
  if (!variants.length) return null;
  const fit = variants.filter((v) => !v.h || v.h <= maxHeight);
  const pool = fit.length ? fit : variants;
  pool.sort((a, b) => b.bw - a.bw || b.h - a.h);
  return pool[0];
}

/** True when the master playlist keeps the sound in separate playlists (EXT-X-MEDIA with a URI). */
function hasSeparateAudio(text) {
  return /#EXT-X-MEDIA:[^\n]*TYPE=AUDIO[^\n]*URI="/i.test(text);
}

function segmentsOf(text, baseUrl) {
  const out = [];
  for (const raw of text.split(/\r?\n/)) {
    const l = raw.trim();
    if (!l || l.startsWith('#')) continue;
    try {
      out.push(new URL(l, baseUrl).href);
    } catch (e) {
      /* skip */
    }
  }
  return out;
}

function targetDuration(text) {
  return +(/#EXT-X-TARGETDURATION:\s*(\d+)/i.exec(text) || [])[1] || 0;
}

async function getStart(url, headers, maxBytes) {
  const res = await fetch(url, { headers, redirect: 'follow', signal: AbortSignal.timeout(TIMEOUT) });
  if (!res.ok) {
    res.body && res.body.cancel().catch(() => {});
    return { ok: false, status: res.status };
  }
  const buf = await readLimited(res.body, maxBytes);
  return { ok: true, status: res.status, url: res.url || url, type: res.headers.get('content-type') || '', buf };
}

class Health {
  constructor({ store, ffprobe }) {
    this.store = store;
    this.ffprobe = ffprobe; // path to ffprobe, or null
    this.limiter = new Limiter(12, 3);
    this.running = false;
    this.progress = null;
    this.lastRun = 0;
    this.listeners = new Set();
  }

  start() {
    setTimeout(() => this.runDue().catch((e) => log('Health check failed:', e)), 15000);
    this.timer = setInterval(() => this.runDue().catch((e) => log('Health check failed:', e)), 60000);
  }

  due(s, now) {
    if (!s.checkedAt) return true;
    const every = Math.max(30, Number(this.store.settings.healthEveryMin) || 180) * 60000;
    if (s.status === 'warn') return now - s.checkedAt > 10 * 60000;
    if (s.status === 'dead') return now - s.checkedAt > Math.min(every, 60 * 60000);
    return now - s.checkedAt > every;
  }

  /** Checks the links that are due (or all of them with force, or only some channels). */
  async runDue({ force = false, channelIds = null } = {}) {
    if (this.running) return false;
    const now = Date.now();
    const jobs = [];
    for (const ch of this.store.channels) {
      if (channelIds ? !channelIds.includes(ch.id) : !ch.enabled) continue;
      for (const s of ch.sources) if (force || this.due(s, now)) jobs.push(s);
    }
    if (!jobs.length) return false;
    this.running = true;
    this.progress = { total: jobs.length, done: 0, ok: 0, startedAt: now, finishedAt: 0 };
    try {
      await Promise.all(
        jobs.map((s) =>
          this.limiter.run(hostOf(s.url), async () => {
            const r = await this.check(s);
            this.apply(s, r);
            this.progress.done++;
            if (r.ok) this.progress.ok++;
          })
        )
      );
    } finally {
      this.running = false;
      this.lastRun = Date.now();
      this.progress.finishedAt = this.lastRun;
      this.store.save();
    }
    log(`Checked ${jobs.length} links: ${this.progress.ok} playing, in ${Math.round((Date.now() - now) / 1000)}s`);
    return true;
  }

  apply(s, r) {
    s.checkedAt = Date.now();
    if (r.skip) {
      if (!s.status) s.status = 'unknown';
      return;
    }
    s.ms = r.ms || 0;
    if (r.ok) {
      s.status = 'ok';
      s.fails = 0;
      s.error = '';
      if (r.td) s.td = r.td;
    } else {
      s.fails = (s.fails || 0) + 1;
      s.status = s.fails >= 2 ? 'dead' : 'warn';
      s.error = r.error || 'لا يعمل';
    }
  }

  /** What the player side saw: a link that just played, or failed to start. */
  report(s, ok, error) {
    if (!s) return;
    s.checkedAt = Date.now();
    if (ok) {
      s.status = 'ok';
      s.fails = 0;
      s.error = '';
    } else {
      s.fails = (s.fails || 0) + 1;
      s.status = s.fails >= 2 ? 'dead' : 'warn';
      s.error = error || 'لا يعمل';
    }
    this.store.save();
  }

  async check(s) {
    const t0 = Date.now();
    const fail = (error) => ({ ok: false, error, ms: Date.now() - t0 });
    try {
      if (!isHttpUrl(s.url)) return await this.probeOther(s, t0);
      const headers = headersFor(s);
      const first = await getStart(s.url, headers, 256 * 1024);
      if (!first.ok) return fail('HTTP ' + first.status);
      const head = first.buf.toString('utf8', 0, Math.min(first.buf.length, 256 * 1024));
      if (/^\s*#EXTM3U/.test(head)) {
        let text = head;
        let base = first.url;
        if (/#EXT-X-STREAM-INF/i.test(text)) {
          const v = pickVariant(text, base);
          if (!v) return fail('قائمة بدون جودات');
          const r = await getStart(v.url, headers, 256 * 1024);
          if (!r.ok) return fail('HTTP ' + r.status);
          text = r.buf.toString('utf8');
          base = r.url;
        }
        const segs = segmentsOf(text, base);
        if (!segs.length) return fail('القائمة فارغة');
        const seg = await getStart(segs[segs.length - 1], headers, 2048);
        if (!seg.ok) return fail('HTTP ' + seg.status);
        if (!seg.buf.length) return fail('المقطع فارغ');
        return { ok: true, ms: Date.now() - t0, td: targetDuration(text) };
      }
      if (/<MPD[\s>]/i.test(head)) return { ok: true, ms: Date.now() - t0 };
      const b = first.buf;
      if (b.length >= 189 && b[0] === 0x47 && b[188] === 0x47) return { ok: true, ms: Date.now() - t0 };
      if (/video|audio|mp2t|octet-stream|mpegurl/i.test(first.type) && b.length > 0) return { ok: true, ms: Date.now() - t0 };
      return fail('ليس بثاً');
    } catch (e) {
      const msg = String((e && (e.cause && e.cause.code)) || (e && e.message) || e);
      if (/timeout|abort/i.test(msg) || (e && e.name === 'TimeoutError')) return fail('انتهت المهلة');
      if (/ENOTFOUND|EAI_AGAIN/i.test(msg)) return fail('العنوان غير موجود');
      if (/ECONNREFUSED/i.test(msg)) return fail('السيرفر رفض الاتصال');
      return fail(msg.slice(0, 80));
    }
  }

  /** rtmp / rtsp / udp links: asked through ffprobe when it is installed. */
  probeOther(s, t0) {
    if (!this.ffprobe) return Promise.resolve({ skip: true });
    return new Promise((resolve) => {
      const p = spawn(this.ffprobe, ['-v', 'error', '-rw_timeout', '10000000', '-show_entries', 'stream=codec_type', '-of', 'csv=p=0', s.url], {
        stdio: ['ignore', 'pipe', 'ignore'],
      });
      let out = '';
      const timer = setTimeout(() => p.kill('SIGKILL'), 15000);
      p.stdout.on('data', (d) => (out += d));
      p.on('error', () => resolve({ skip: true }));
      p.on('close', () => {
        clearTimeout(timer);
        const ok = /video|audio/.test(out);
        resolve(ok ? { ok: true, ms: Date.now() - t0 } : { ok: false, error: 'لا يعمل', ms: Date.now() - t0 });
      });
    });
  }
}

module.exports = { Health, pickVariant, hasSeparateAudio, segmentsOf, targetDuration };
