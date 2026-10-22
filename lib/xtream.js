'use strict';
// The Xtream Codes API that RATV (and TiviMate, IPTV Smarters…) speak, plus M3U and XMLTV.

const { safeEqual, localDateTime } = require('./util');
const { liveLineup, lineupCategories } = require('./catalog');

function json(res, body) {
  const data = Buffer.from(JSON.stringify(body));
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': data.length, 'Cache-Control': 'no-cache' });
  res.end(data);
}

const b64 = (s) => Buffer.from(String(s || ''), 'utf8').toString('base64');
const q = (s) => String(s == null ? '' : s).replace(/"/g, "'").replace(/[\r\n]+/g, ' ');

/** Counts wrong logins per address and makes it wait after too many. */
class Guard {
  constructor(max, windowMs, blockMs) {
    this.max = max;
    this.windowMs = windowMs;
    this.blockMs = blockMs;
    this.map = new Map();
  }
  blocked(ip) {
    const e = this.map.get(ip);
    return !!(e && e.until > Date.now());
  }
  fail(ip) {
    const now = Date.now();
    let e = this.map.get(ip);
    if (!e || now - e.first > this.windowMs) e = { first: now, n: 0, until: 0 };
    e.n++;
    if (e.n >= this.max) e.until = now + this.blockMs;
    this.map.set(ip, e);
    if (this.map.size > 5000) this.map.clear();
  }
  ok(ip) {
    this.map.delete(ip);
  }
}

class Xtream {
  constructor({ store, hub, epg }) {
    this.store = store;
    this.hub = hub;
    this.epg = epg;
    this.guard = new Guard(30, 10 * 60000, 15 * 60000);
  }

  auth(username, password, ip) {
    if (this.guard.blocked(ip)) return null;
    const u = String(username || '').trim();
    const p = String(password || '').trim();
    const user = u && p ? this.store.users.find((x) => x.username === u) : null;
    if (!user || !user.enabled || !safeEqual(user.password, p)) {
      if (u || p) this.guard.fail(ip);
      return null;
    }
    this.guard.ok(ip);
    return user;
  }

  baseUrl(req) {
    const pub = String(this.store.settings.publicUrl || '').trim().replace(/\/+$/, '');
    if (pub) return pub;
    const proto = String(req.headers['x-forwarded-proto'] || 'http').split(',')[0].trim();
    return proto + '://' + (req.headers['x-forwarded-host'] || req.headers.host || 'localhost');
  }

  info(req, user) {
    const base = new URL(this.baseUrl(req));
    const now = Date.now();
    const tz = this.store.settings.timezone || 'Asia/Riyadh';
    return {
      user_info: {
        username: user.username,
        password: user.password,
        message: this.store.settings.serverName || '',
        auth: 1,
        status: 'Active',
        exp_date: null,
        is_trial: '0',
        active_cons: String(this.hub.activeCount(user.id)),
        created_at: String(Math.floor((user.createdAt || now) / 1000)),
        max_connections: String(user.maxConnections || 1),
        allowed_output_formats: ['ts'],
      },
      server_info: {
        url: base.hostname,
        port: base.port || (base.protocol === 'https:' ? '443' : '80'),
        https_port: '443',
        server_protocol: base.protocol.replace(':', ''),
        rtmp_port: '0',
        timezone: tz,
        timestamp_now: Math.floor(now / 1000),
        time_now: localDateTime(now, tz),
        process: true,
      },
    };
  }

  playerApi(req, res, query, ip) {
    const user = this.auth(query.username, query.password, ip);
    if (!user) return json(res, { user_info: { auth: 0 } });
    user.lastSeen = Date.now();
    const action = String(query.action || '');
    switch (action) {
      case '':
        return json(res, this.info(req, user));
      case 'get_live_categories': {
        const lineup = liveLineup(this.store);
        return json(
          res,
          lineupCategories(this.store, lineup).map((c) => ({ category_id: String(c.id), category_name: c.name, parent_id: 0 }))
        );
      }
      case 'get_live_streams': {
        let lineup = liveLineup(this.store);
        if (query.category_id) lineup = lineup.filter((x) => String(x.ch.categoryId) === String(query.category_id));
        return json(
          res,
          lineup.map(({ ch, num }) => ({
            num,
            name: ch.name,
            stream_type: 'live',
            stream_id: ch.id,
            stream_icon: ch.logo || '',
            epg_channel_id: ch.epgId || '',
            added: String(Math.floor((ch.createdAt || 0) / 1000)),
            is_adult: 0,
            category_id: String(ch.categoryId),
            category_ids: [ch.categoryId],
            custom_sid: '',
            tv_archive: 0,
            direct_source: '',
            tv_archive_duration: 0,
          }))
        );
      }
      case 'get_short_epg':
      case 'get_simple_data_table': {
        const ch = this.store.channel(query.stream_id);
        const now = Date.now();
        const tz = this.store.settings.timezone || 'Asia/Riyadh';
        let list = ch ? this.epg.programmes(ch.epgId).filter((p) => p.stop > now) : [];
        if (action === 'get_short_epg') list = list.slice(0, Math.max(1, Math.min(50, Number(query.limit) || 4)));
        return json(res, {
          epg_listings: list.map((p, i) => ({
            id: String(i + 1),
            epg_id: '0',
            title: b64(p.title),
            lang: 'ar',
            start: localDateTime(p.start, tz),
            end: localDateTime(p.stop, tz),
            description: b64(p.desc),
            channel_id: ch.epgId,
            start_timestamp: String(Math.floor(p.start / 1000)),
            stop_timestamp: String(Math.floor(p.stop / 1000)),
            now_playing: p.start <= now && now < p.stop ? 1 : 0,
            has_archive: 0,
          })),
        });
      }
      case 'get_vod_info':
        return json(res, { info: {}, movie_data: {} });
      case 'get_series_info':
        return json(res, { seasons: [], info: {}, episodes: {} });
      default:
        // Films and series are not part of this server: empty lists.
        return json(res, []);
    }
  }

  m3u(req, res, query, ip) {
    const user = this.auth(query.username, query.password, ip);
    if (!user) {
      res.writeHead(401, { 'Content-Type': 'text/plain' });
      return res.end('Wrong user name or password');
    }
    const base = this.baseUrl(req);
    const u = encodeURIComponent(user.username);
    const p = encodeURIComponent(user.password);
    const ext = /m3u8|hls/i.test(String(query.output || '')) ? 'm3u8' : 'ts';
    const names = new Map(this.store.categories.map((c) => [c.id, c.name]));
    const guide = `${base}/xmltv.php?username=${u}&password=${p}`;
    const lines = [`#EXTM3U url-tvg="${guide}" x-tvg-url="${guide}"`];
    for (const { ch } of liveLineup(this.store)) {
      lines.push(
        `#EXTINF:-1 tvg-id="${q(ch.epgId)}" tvg-name="${q(ch.name)}" tvg-logo="${q(ch.logo)}" group-title="${q(names.get(ch.categoryId) || 'أخرى')}",${q(ch.name)}`
      );
      lines.push(`${base}/live/${u}/${p}/${ch.id}.${ext}`);
    }
    const body = Buffer.from(lines.join('\n') + '\n');
    res.writeHead(200, {
      'Content-Type': 'audio/x-mpegurl; charset=utf-8',
      'Content-Disposition': 'inline; filename="ratv.m3u"',
      'Content-Length': body.length,
      'Cache-Control': 'no-cache',
    });
    res.end(body);
  }

  xmltv(req, res, query, ip) {
    const user = this.auth(query.username, query.password, ip);
    if (!user) {
      res.writeHead(401, { 'Content-Type': 'text/plain' });
      return res.end('Wrong user name or password');
    }
    const doc = this.epg.xmltv(liveLineup(this.store));
    const gz = /\bgzip\b/.test(String(req.headers['accept-encoding'] || ''));
    const body = gz ? doc.gz : doc.body;
    const headers = { 'Content-Type': 'application/xml; charset=utf-8', 'Content-Length': body.length, 'Cache-Control': 'no-cache' };
    if (gz) headers['Content-Encoding'] = 'gzip';
    res.writeHead(200, headers);
    res.end(body);
  }
}

module.exports = { Xtream, Guard, json };
