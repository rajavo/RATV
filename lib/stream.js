'use strict';
// Live streaming: one ffmpeg per channel being watched, shared by every TV on it.
//
// Each source is remuxed (never re-encoded) to MPEG-TS. The last seconds are kept in memory, so a
// TV that joins, or reconnects after a hiccup, starts at once with a burst of recent picture.
// When a source stalls or dies, the next working link of the channel takes over and the stream's
// clock carries on where it was (-output_ts_offset), so the player does not freeze or restart.

const { spawn, spawnSync } = require('child_process');
const { headersFor, isHttpUrl, isStreamUrl, oneLine, log, DEFAULT_UA } = require('./util');
const { pickVariant, hasSeparateAudio, targetDuration } = require('./health');

const START_MS = { seg: 12000, ts: 10000 }; // a source has this long to send its first picture
// ...and may then pause this long before the next link takes over. Segment sources are read at
// play speed from a few seconds ffmpeg holds in hand, so a pause here means that reserve ran out.
const STALL_MS = { seg: 4000, ts: 8000 };
const GRACE_MS = 12000; // ffmpeg keeps running this long after the last TV leaves (channel flicks, reconnects)
const RING_MAX_BYTES = 24 * 1024 * 1024;
const CLIENT_MAX_BACKLOG = 24 * 1024 * 1024;
const MPEGTS_START_S = 1.4; // ffmpeg's mpegts muxer starts its clock at 1.4 s

const VIDEO_TYPES = new Set([0x01, 0x02, 0x10, 0x1b, 0x24, 0x42, 0xd1, 0xea]);
const AUDIO_TYPES = new Set([0x03, 0x04, 0x0f, 0x11, 0x81, 0x87, 0x8a]);

function detectFfmpeg(bin) {
  bin = bin || process.env.FFMPEG || 'ffmpeg';
  try {
    const v = spawnSync(bin, ['-hide_banner', '-version'], { encoding: 'utf8', timeout: 15000 });
    if (v.error || v.status !== 0) return { ok: false, bin };
    const version = ((v.stdout || '').split('\n')[0] || '').replace(/^ffmpeg version\s*/, '').split(' ')[0];
    const h = spawnSync(bin, ['-hide_banner', '-h', 'full'], { encoding: 'utf8', timeout: 20000, maxBuffer: 64 * 1024 * 1024 });
    const help = (h.stdout || '') + (h.stderr || '');
    return { ok: true, bin, version, readrate: /-readrate\b/.test(help), burst: /readrate_initial_burst/.test(help) };
  } catch (e) {
    return { ok: false, bin };
  }
}

function detectFfprobe() {
  const bin = process.env.FFPROBE || 'ffprobe';
  try {
    const r = spawnSync(bin, ['-version'], { timeout: 10000 });
    return r.error || r.status !== 0 ? null : bin;
  } catch (e) {
    return null;
  }
}

// ------------------------------------------------------------------ TS parsing

function readPts(d, p, end) {
  if (end - p < 14 || d[p] !== 0 || d[p + 1] !== 0 || d[p + 2] !== 1) return -1;
  if (!(d[p + 7] & 0x80)) return -1;
  const q = p + 9;
  return ((d[q] >> 1) & 7) * 1073741824 + (((d[q + 1] << 22) | ((d[q + 2] >> 1) << 15) | (d[q + 3] << 7) | (d[q + 4] >> 1)) >>> 0);
}

/**
 * Cuts ffmpeg's output into whole 188-byte packets and notes what the server needs:
 * PAT/PMT, the video and audio PIDs, where each picture group (key frame) starts, and the clock.
 */
class TsParser {
  constructor() {
    this.left = null;
    this.pmtPid = -1;
    this.videoPid = -1;
    this.audioPid = -1;
    this.pat = null;
    this.pmt = null;
    this.sig = '';
    this.lastTs = -1;
    this.lastAudioKey = -1;
  }

  noteTs(ts) {
    if (this.lastTs < 0 || ts > this.lastTs || this.lastTs - ts > 4294967296) this.lastTs = ts;
  }

  /** Returns { buf, keys: [{ off, ts }] } with whole packets only, or null. */
  push(chunk) {
    let d = this.left ? Buffer.concat([this.left, chunk]) : chunk;
    this.left = null;
    let start = 0;
    if (d[0] !== 0x47) {
      start = -1;
      for (let i = 0; i + 188 < d.length; i++) {
        if (d[i] === 0x47 && d[i + 188] === 0x47) {
          start = i;
          break;
        }
      }
      if (start < 0) {
        this.left = Buffer.from(d.subarray(Math.max(0, d.length - 376)));
        return null;
      }
    }
    let end = start + Math.floor((d.length - start) / 188) * 188;
    const keys = [];
    for (let off = start; off < end; off += 188) {
      if (d[off] !== 0x47) {
        end = off; // lost sync: keep the rest for the next round
        break;
      }
      const pid = ((d[off + 1] & 0x1f) << 8) | d[off + 2];
      const pusi = (d[off + 1] & 0x40) !== 0;
      const afc = (d[off + 3] >> 4) & 3;
      let p = off + 4;
      let rai = false;
      if (afc & 2) {
        const alen = d[p];
        if (alen > 0) rai = (d[p + 1] & 0x40) !== 0;
        p += 1 + alen;
      }
      if (!(afc & 1) || p >= off + 188) continue;
      if (pid === 0) {
        if (pusi) {
          this.pat = Buffer.from(d.subarray(off, off + 188));
          this.parsePat(d, p, off + 188);
        }
        continue;
      }
      if (pid === this.pmtPid) {
        if (pusi) {
          this.pmt = Buffer.from(d.subarray(off, off + 188));
          this.parsePmt(d, p, off + 188);
        }
        continue;
      }
      if (!pusi) continue;
      if (pid === this.videoPid) {
        const ts = readPts(d, p, off + 188);
        if (ts >= 0) this.noteTs(ts);
        if (rai) keys.push({ off: off - start, ts });
      } else if (pid === this.audioPid) {
        const ts = readPts(d, p, off + 188);
        if (ts >= 0) {
          if (this.videoPid < 0) {
            this.noteTs(ts);
            // Radio: a new group about every second.
            if (this.lastAudioKey < 0 || ts - this.lastAudioKey >= 90000 || ts < this.lastAudioKey) {
              this.lastAudioKey = ts;
              keys.push({ off: off - start, ts });
            }
          }
        }
      }
    }
    if (end < d.length) this.left = Buffer.from(d.subarray(end));
    if (end <= start) return null;
    return { buf: d.subarray(start, end), keys };
  }

  parsePat(d, p, end) {
    const s = p + 1 + d[p];
    if (s + 12 > end || d[s] !== 0x00) return;
    const len = ((d[s + 1] & 0x0f) << 8) | d[s + 2];
    const last = Math.min(s + 3 + len - 4, end);
    for (let i = s + 8; i + 4 <= last; i += 4) {
      const prog = (d[i] << 8) | d[i + 1];
      if (prog !== 0) {
        this.pmtPid = ((d[i + 2] & 0x1f) << 8) | d[i + 3];
        return;
      }
    }
  }

  parsePmt(d, p, end) {
    const s = p + 1 + d[p];
    if (s + 12 > end || d[s] !== 0x02) return;
    const len = ((d[s + 1] & 0x0f) << 8) | d[s + 2];
    const last = Math.min(s + 3 + len - 4, end);
    const pil = ((d[s + 10] & 0x0f) << 8) | d[s + 11];
    let video = -1;
    let audio = -1;
    let priv = -1;
    const types = [];
    for (let i = s + 12 + pil; i + 5 <= last; ) {
      const type = d[i];
      const epid = ((d[i + 1] & 0x1f) << 8) | d[i + 2];
      const esl = ((d[i + 3] & 0x0f) << 8) | d[i + 4];
      types.push(type.toString(16));
      if (VIDEO_TYPES.has(type) && video < 0) video = epid;
      else if (AUDIO_TYPES.has(type) && audio < 0) audio = epid;
      else if (type === 0x06 && priv < 0) priv = epid;
      i += 5 + esl;
    }
    this.videoPid = video;
    this.audioPid = audio >= 0 ? audio : priv;
    this.sig = types.join(',');
  }
}

// ------------------------------------------------------------------ clients

class Client {
  constructor(res, user, ip, ch) {
    this.res = res;
    this.user = user;
    this.ip = ip;
    this.channelId = ch.id;
    this.channelName = ch.name;
    this.since = Date.now();
    this.closed = false; // set once the server has let go of this viewer
    this.dead = false; // set as soon as nothing more should be written
    this.bytes = 0;
  }
  write(buf) {
    if (this.closed || this.dead) return;
    if (this.res.writableLength > CLIENT_MAX_BACKLOG) {
      this.kick(); // far behind: the TV reconnects and catches up from the burst
      return;
    }
    this.bytes += buf.length;
    this.res.write(buf);
  }
  kick() {
    if (this.dead) return;
    this.dead = true;
    try {
      this.res.destroy();
    } catch (e) {
      /* already gone */
    }
  }
  end() {
    try {
      this.res.end();
    } catch (e) {
      /* already gone */
    }
  }
}

// ------------------------------------------------------------------ sessions

class Session {
  constructor(hub, ch) {
    this.hub = hub;
    this.channelId = ch.id;
    this.name = ch.name;
    this.clients = new Set();
    this.gops = [];
    this.ringBytes = 0;
    this.keepSec = 10;
    this.pat = null;
    this.pmt = null;
    this.sig = '';
    this.lastTs = -1;
    this.proc = null;
    this.current = null;
    this.stopped = false;
    this.idleTimer = null;
    this.startedAt = Date.now();
    this.switches = 0;
    this.run().catch((e) => {
      log('Stream error on', this.name + ':', e);
      this.stop();
    });
  }

  addClient(c) {
    clearTimeout(this.idleTimer);
    this.idleTimer = null;
    this.clients.add(c);
    if (this.gops.length && this.gops[0].key) {
      if (this.pat) c.write(this.pat);
      if (this.pmt) c.write(this.pmt);
      for (const g of this.gops) for (const b of g.bufs) c.write(b);
    }
  }

  removeClient(c) {
    this.clients.delete(c);
    if (!this.clients.size && !this.idleTimer && !this.stopped) {
      this.idleTimer = setTimeout(() => this.stop(), GRACE_MS);
    }
  }

  deliver(out) {
    let pos = 0;
    for (const k of out.keys) {
      if (k.off > pos) this.appendRing(out.buf.subarray(pos, k.off));
      this.gops.push({ bufs: [], bytes: 0, ts: k.ts, key: true });
      pos = k.off;
    }
    if (pos < out.buf.length) this.appendRing(out.buf.subarray(pos));
    this.trimRing();
    for (const c of this.clients) c.write(out.buf);
  }

  appendRing(b) {
    if (!this.gops.length) this.gops.push({ bufs: [], bytes: 0, ts: -1, key: false });
    const g = this.gops[this.gops.length - 1];
    g.bufs.push(b);
    g.bytes += b.length;
    this.ringBytes += b.length;
  }

  trimRing() {
    while (this.gops.length > 1) {
      const first = this.gops[0];
      const second = this.gops[1];
      const enoughAfter = this.lastTs >= 0 && second.ts >= 0 && this.lastTs - second.ts >= this.keepSec * 90000;
      if (!first.key || this.ringBytes > RING_MAX_BYTES || enoughAfter) {
        this.ringBytes -= first.bytes;
        this.gops.shift();
      } else break;
    }
  }

  clearRing() {
    this.gops = [];
    this.ringBytes = 0;
  }

  channel() {
    return this.hub.store.channel(this.channelId);
  }

  async run() {
    let failedRounds = 0;
    let avoid = null;
    while (!this.stopped) {
      const ch = this.channel();
      if (!ch || !ch.enabled) break;
      const list = this.hub.sourceOrder(ch, avoid);
      if (!list.length) break;
      let progressed = false;
      for (let i = 0; i < list.length && !this.stopped; i++) {
        const src = list[i];
        const r = await this.runSource(src);
        if (this.stopped) return;
        if (r.layoutChanged) {
          // A different picture/sound layout: start clean so the player re-reads the stream.
          log(`${this.name}: stream layout changed, restarting viewers`);
          for (const c of this.clients) c.kick();
          this.clearRing();
          this.lastTs = -1;
          this.sig = '';
          i--; // same source again, now from a fresh clock
          continue;
        }
        if (r.bytes > 0) {
          // It played, then stopped: the other links get the next turn, this one goes last.
          progressed = true;
          failedRounds = 0;
          avoid = list.length > 1 ? src.url : null;
          log(`${this.name}: link stopped after ${Math.round(r.ms / 1000)}s (${r.error})${avoid ? ', switching link' : ', reconnecting'}`);
          break;
        }
        this.hub.health.report(src, false, r.error);
        log(`${this.name}: link failed (${r.error})`);
      }
      if (!progressed) {
        failedRounds++;
        if (failedRounds >= 3) break;
        await new Promise((res) => setTimeout(res, 1500 * failedRounds));
      }
    }
    if (!this.stopped) {
      log(`${this.name}: no working link, closing the stream`);
      this.stop();
    }
  }

  /**
   * How to open a source. For HLS the best quality is picked here (so ffmpeg downloads only one),
   * and the segment length decides how far back from live to start (about 18 s of picture) and how
   * much of it goes to the TVs at once (the rest stays with ffmpeg to smooth out late segments).
   */
  async plan(src) {
    const plan = { input: src.url, segmented: /\.(m3u8|mpd)(\?|$)/i.test(src.url) || src.td > 0, td: src.td || 0 };
    if (isHttpUrl(src.url) && /\.m3u8(\?|$)/i.test(src.url)) {
      const get = async (url) => {
        const res = await fetch(url, { headers: headersFor(src), redirect: 'follow', signal: AbortSignal.timeout(6000) });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return { text: (await res.text()).slice(0, 512 * 1024), url: res.url || url };
      };
      try {
        let page = await get(src.url);
        if (/#EXT-X-STREAM-INF/i.test(page.text)) {
          if (!hasSeparateAudio(page.text)) {
            const v = pickVariant(page.text, page.url);
            if (v) {
              plan.input = v.url;
              page = await get(v.url);
            }
          }
        }
        plan.td = targetDuration(page.text) || plan.td;
        plan.ended = /#EXT-X-ENDLIST/i.test(page.text); // the broadcast is over (it would replay old segments)
      } catch (e) {
        /* ffmpeg will try the address itself */
      }
    }
    const td = plan.td || 6;
    plan.startIndex = Math.max(3, Math.min(6, Math.ceil(18 / td)));
    plan.burst = Math.max(4, Math.min(12, Math.round(td * plan.startIndex * 0.6)));
    return plan;
  }

  args(src, plan, offset) {
    const a = ['-hide_banner', '-nostdin', '-loglevel', 'error'];
    if (isHttpUrl(plan.input)) {
      a.push('-user_agent', oneLine(src.ua) || DEFAULT_UA);
      if (oneLine(src.referer)) a.push('-referer', oneLine(src.referer));
      a.push('-rw_timeout', '15000000');
      if (!plan.segmented) a.push('-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_delay_max', '4');
    } else if (/^(rtmp|rtsp|srt)/i.test(plan.input)) {
      a.push('-rw_timeout', '15000000');
    }
    // Segment-based sources arrive in bursts; read them at play speed after a first burst so the
    // TVs get a steady stream (and an early stall is covered by what ffmpeg already holds).
    if (plan.segmented && this.hub.ff.readrate) {
      a.push('-readrate', '1');
      if (this.hub.ff.burst) a.push('-readrate_initial_burst', String(plan.burst));
    }
    if (/\.m3u8(\?|$)/i.test(plan.input)) a.push('-live_start_index', String(-plan.startIndex));
    a.push('-fflags', '+discardcorrupt', '-i', plan.input);
    a.push('-map', '0:v:0?', '-map', '0:a?', '-dn', '-sn', '-c', 'copy');
    if (offset != null) a.push('-output_ts_offset', offset.toFixed(3));
    a.push('-max_muxing_queue_size', '2048', '-f', 'mpegts', 'pipe:1');
    return a;
  }

  async runSource(src) {
    const t0 = Date.now();
    const plan = await this.plan(src);
    if (this.stopped) return { bytes: 0, ms: 0, error: 'stopped' };
    const ch = this.channel();
    if (plan.ended && ch && ch.sources.length > 1) return { bytes: 0, ms: Date.now() - t0, error: 'البث منتهي' };
    this.keepSec = plan.burst + 2;
    // Continue the clock after what the TVs already have (ffmpeg adds its own 1.4 s start).
    const offset = this.lastTs >= 0 ? Math.max(0, this.lastTs / 90000 + 0.08 - MPEGTS_START_S) : null;
    return new Promise((resolve) => {
      const p = spawn(this.hub.ff.bin, this.args(src, plan, offset), { stdio: ['ignore', 'pipe', 'pipe'] });
      this.proc = p;
      this.current = src;
      const parser = new TsParser();
      let bytes = 0;
      let last = 0;
      let reason = '';
      let err = '';
      let layoutChanged = false;
      const kind = plan.segmented ? 'seg' : 'ts';
      p.stdout.on('data', (chunk) => {
        if (layoutChanged) return;
        const out = parser.push(chunk);
        if (!out || !out.buf.length) return;
        if (bytes === 0) {
          if (offset != null && this.sig && parser.sig && parser.sig !== this.sig) {
            layoutChanged = true;
            p.kill('SIGKILL');
            return;
          }
          if (parser.sig) this.sig = parser.sig;
          if (this.switches++ > 0 || offset != null) log(`${this.name}: playing from ${shortUrl(src.url)}`);
          this.hub.health.report(src, true);
        }
        if (parser.pat) this.pat = parser.pat;
        if (parser.pmt) this.pmt = parser.pmt;
        bytes += out.buf.length;
        last = Date.now();
        if (parser.lastTs >= 0) this.lastTs = parser.lastTs;
        this.deliver(out);
      });
      p.stderr.on('data', (d) => {
        err = (err + d.toString()).slice(-1500);
      });
      const watchdog = setInterval(() => {
        const now = Date.now();
        if (bytes === 0 && now - t0 > START_MS[kind]) {
          reason = 'لم يبدأ البث';
          p.kill('SIGKILL');
        } else if (bytes > 0 && now - last > STALL_MS[kind]) {
          reason = 'توقف البث';
          p.kill('SIGKILL');
        }
      }, 1000);
      p.on('error', (e) => {
        reason = 'ffmpeg: ' + e.message;
      });
      p.on('close', (code) => {
        clearInterval(watchdog);
        if (this.proc === p) this.proc = null;
        const lastLine = err.trim().split('\n').pop() || '';
        resolve({ bytes, ms: Date.now() - t0, layoutChanged, error: reason || lastLine.slice(0, 120) || 'انتهى البث (' + code + ')' });
      });
    });
  }

  stop() {
    if (this.stopped) return;
    this.stopped = true;
    clearTimeout(this.idleTimer);
    if (this.proc) this.proc.kill('SIGKILL');
    for (const c of this.clients) c.end();
    this.clients.clear();
    this.clearRing();
    if (this.hub.sessions.get(this.channelId) === this) this.hub.sessions.delete(this.channelId);
  }
}

function shortUrl(u) {
  return String(u).replace(/^(\w+:\/\/[^/]+)(.{0,30}).*$/, '$1$2…');
}

// ------------------------------------------------------------------ hub

class Hub {
  constructor({ store, health, ff }) {
    this.store = store;
    this.health = health;
    this.ff = ff;
    this.sessions = new Map();
    this.clients = new Set();
  }

  /** Working links first (in the owner's order), then unchecked, then failing ones; `avoid` goes last. */
  sourceOrder(ch, avoidUrl) {
    const rank = { ok: 0, unknown: 1, warn: 2, dead: 3 };
    return ch.sources
      .map((s, i) => ({ s, i }))
      .filter((x) => isStreamUrl(x.s.url))
      .sort((a, b) => {
        if (avoidUrl && (a.s.url === avoidUrl) !== (b.s.url === avoidUrl)) return a.s.url === avoidUrl ? 1 : -1;
        return (rank[a.s.status] ?? 1) - (rank[b.s.status] ?? 1) || a.i - b.i;
      })
      .map((x) => x.s);
  }

  /** Makes room for a new stream of this account; false when its device limit is reached. */
  admit(user, ip) {
    const mine = [...this.clients].filter((c) => c.user.id === user.id && !c.dead);
    if (mine.length < Math.max(1, Number(user.maxConnections) || 1)) return true;
    // The same TV changing channel or reconnecting replaces its own older stream.
    const same = mine.filter((c) => c.ip === ip).sort((a, b) => a.since - b.since);
    if (same.length) {
      same[0].kick();
      return true;
    }
    return false;
  }

  serveTs(req, res, user, ch, ip) {
    if (!this.admit(user, ip)) {
      res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('Device limit reached');
    }
    if (!this.ff.ok) return this.serveTsWithoutFfmpeg(req, res, user, ch, ip);
    res.useChunkedEncodingByDefault = false;
    res.writeHead(200, {
      'Content-Type': 'video/mp2t',
      'Cache-Control': 'no-cache, no-store',
      Connection: 'close',
      'Access-Control-Allow-Origin': '*',
    });
    if (req.method === 'HEAD') return res.end();
    if (res.socket) res.socket.setNoDelay(true);
    const client = new Client(res, user, ip, ch);
    this.clients.add(client);
    let s = this.sessions.get(ch.id);
    if (!s || s.stopped) {
      s = new Session(this, ch);
      this.sessions.set(ch.id, s);
    }
    s.addClient(client);
    user.lastSeen = Date.now();
    const done = () => {
      if (client.closed) return;
      client.closed = true;
      this.clients.delete(client);
      s.removeClient(client);
    };
    res.on('close', done);
    res.on('error', done);
    req.on('close', done);
  }

  /** HLS request: sent straight to the source when it needs no special headers. */
  serveHls(req, res, ch) {
    const src = this.sourceOrder(ch)[0];
    if (src && /\.m3u8(\?|$)/i.test(src.url) && isHttpUrl(src.url) && !src.ua && !src.referer) {
      res.writeHead(302, { Location: src.url, 'Cache-Control': 'no-cache' });
      return res.end();
    }
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Use the .ts address for this channel');
  }

  /** Without ffmpeg: direct TS links are passed through; HLS links are redirected. */
  async serveTsWithoutFfmpeg(req, res, user, ch, ip) {
    for (const src of this.sourceOrder(ch)) {
      if (!isHttpUrl(src.url)) continue;
      if (/\.(m3u8|mpd)(\?|$)/i.test(src.url)) {
        res.writeHead(302, { Location: src.url });
        return res.end();
      }
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), 12000);
      try {
        const up = await fetch(src.url, { headers: headersFor(src), signal: ctrl.signal, redirect: 'follow' });
        clearTimeout(timer);
        if (!up.ok || !up.body) continue;
        res.writeHead(200, { 'Content-Type': 'video/mp2t', 'Cache-Control': 'no-cache' });
        const reader = up.body.getReader();
        req.on('close', () => ctrl.abort());
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          if (!res.write(Buffer.from(value))) await new Promise((r) => res.once('drain', r));
        }
        return res.end();
      } catch (e) {
        clearTimeout(timer);
        if (res.headersSent) return res.end();
      }
    }
    if (!res.headersSent) {
      res.writeHead(502, { 'Content-Type': 'text/plain' });
      res.end('No working link');
    }
  }

  /** Who is watching what, for the control panel. */
  viewers() {
    return [...this.clients].map((c) => ({
      user: c.user.name || c.user.username,
      username: c.user.username,
      channel: c.channelName,
      channelId: c.channelId,
      ip: c.ip,
      since: c.since,
      mb: Math.round(c.bytes / 1048576),
    }));
  }

  activeCount(userId) {
    let n = 0;
    for (const c of this.clients) if (c.user.id === userId) n++;
    return n;
  }

  stopAll() {
    for (const s of [...this.sessions.values()]) s.stop();
  }
}

module.exports = { Hub, TsParser, detectFfmpeg, detectFfprobe };
