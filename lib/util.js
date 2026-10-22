'use strict';
// Small helpers shared by every part of the server (no outside packages).

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { Readable } = require('stream');
const { pipeline } = require('stream/promises');

const DEFAULT_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36';

/** One line of text: no line breaks (they would break ffmpeg headers or M3U lines). */
function oneLine(v) {
  return (v == null ? '' : String(v)).replace(/[\r\n\t]+/g, ' ').trim();
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

/** Stream addresses the server is willing to open (never something ffmpeg would read as an option). */
function isStreamUrl(u) {
  return /^(https?|rtmps?|rtsp|udp|rtp|srt):\/\/[^\s]+$/i.test(oneLine(u));
}

function isHttpUrl(u) {
  return /^https?:\/\/[^\s]+$/i.test(oneLine(u));
}

function hostOf(u) {
  const m = /^[a-z0-9+.-]+:\/\/([^/:?#]+)/i.exec(u || '');
  return m ? m[1].toLowerCase() : '';
}

function headersFor(src) {
  const h = { 'user-agent': oneLine(src && src.ua) || DEFAULT_UA, accept: '*/*' };
  const ref = oneLine(src && src.referer);
  if (ref) h.referer = ref;
  return h;
}

/** Reads a web stream into a Buffer, stopping (and cancelling the download) after maxBytes. */
async function readLimited(body, maxBytes) {
  if (!body) return Buffer.alloc(0);
  const reader = body.getReader();
  const parts = [];
  let size = 0;
  try {
    while (size < maxBytes) {
      const { done, value } = await reader.read();
      if (done) break;
      parts.push(Buffer.from(value));
      size += value.length;
    }
  } finally {
    reader.cancel().catch(() => {});
  }
  const buf = Buffer.concat(parts);
  return buf.length > maxBytes ? buf.subarray(0, maxBytes) : buf;
}

function gunzipIfNeeded(buf) {
  if (buf.length > 2 && buf[0] === 0x1f && buf[1] === 0x8b) {
    try {
      return zlib.gunzipSync(buf);
    } catch (e) {
      return buf;
    }
  }
  return buf;
}

/** GET a text resource (playlists, guides). Follows redirects; un-gzips .gz files. */
async function fetchText(url, { headers, timeout = 30000, maxBytes = 60 * 1024 * 1024 } = {}) {
  const res = await fetch(url, {
    headers: headers || { 'user-agent': DEFAULT_UA },
    redirect: 'follow',
    signal: AbortSignal.timeout(timeout),
  });
  if (!res.ok) {
    res.body && res.body.cancel().catch(() => {});
    throw new Error('HTTP ' + res.status);
  }
  const buf = gunzipIfNeeded(await readLimited(res.body, maxBytes));
  return { text: buf.toString('utf8'), finalUrl: res.url || url };
}

/** Downloads a (possibly large) file to disk atomically. */
async function downloadFile(url, dest, { timeout = 180000 } = {}) {
  const res = await fetch(url, {
    headers: { 'user-agent': DEFAULT_UA },
    redirect: 'follow',
    signal: AbortSignal.timeout(timeout),
  });
  if (!res.ok || !res.body) throw new Error('HTTP ' + res.status);
  const tmp = dest + '.part';
  await pipeline(Readable.fromWeb(res.body), fs.createWriteStream(tmp));
  fs.renameSync(tmp, dest);
  return dest;
}

/** Writes a file atomically (temp file + rename), so a crash never leaves half a file. */
function writeFileAtomic(file, data, mode) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = file + '.' + process.pid + '.tmp';
  fs.writeFileSync(tmp, data, mode ? { mode } : undefined);
  fs.renameSync(tmp, file);
}

function fileAgeMs(file) {
  try {
    return Date.now() - fs.statSync(file).mtimeMs;
  } catch (e) {
    return Infinity;
  }
}

const PW_ALPHABET = 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789';

function randomPassword(len = 10) {
  const bytes = crypto.randomBytes(len);
  let s = '';
  for (const b of bytes) s += PW_ALPHABET[b % PW_ALPHABET.length];
  return s;
}

/** A short number that is easy to type with a TV remote. */
function randomPin(len = 6) {
  const bytes = crypto.randomBytes(len);
  let s = '';
  for (const b of bytes) s += String(b % 10);
  return (s[0] === '0' ? '7' : s[0]) + s.slice(1);
}

function hashPassword(password, salt) {
  salt = salt || crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(String(password), salt, 32).toString('hex');
  return { salt, hash };
}

function checkPassword(password, salt, hash) {
  if (!salt || !hash) return false;
  const a = Buffer.from(crypto.scryptSync(String(password), salt, 32).toString('hex'));
  const b = Buffer.from(String(hash));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function safeEqual(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

function xmlEscape(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function xmlUnescape(s) {
  return String(s || '')
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/&#x([0-9a-f]+);/gi, (m, h) => safeCodePoint(parseInt(h, 16), m))
    .replace(/&#(\d+);/g, (m, d) => safeCodePoint(parseInt(d, 10), m))
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&');
}

function safeCodePoint(n, fallback) {
  try {
    return String.fromCodePoint(n);
  } catch (e) {
    return fallback;
  }
}

/** "20261022180000 +0300" → epoch ms (0 when unreadable). */
function parseXmltvTime(v) {
  const m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{4})?/.exec(String(v || '').trim());
  if (!m) return 0;
  let t = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
  if (m[7]) {
    const sign = m[7][0] === '-' ? -1 : 1;
    const mins = +m[7].slice(1, 3) * 60 + +m[7].slice(3, 5);
    t -= sign * mins * 60000;
  }
  return t;
}

function xmltvTime(ms) {
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return (
    d.getUTCFullYear() +
    p(d.getUTCMonth() + 1) +
    p(d.getUTCDate()) +
    p(d.getUTCHours()) +
    p(d.getUTCMinutes()) +
    p(d.getUTCSeconds()) +
    ' +0000'
  );
}

/** "YYYY-MM-DD HH:MM:SS" in the given time zone (Xtream style). */
function localDateTime(ms, timeZone) {
  try {
    const parts = new Intl.DateTimeFormat('en-GB', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hourCycle: 'h23',
    }).formatToParts(new Date(ms));
    const g = (t) => (parts.find((x) => x.type === t) || {}).value;
    return `${g('year')}-${g('month')}-${g('day')} ${g('hour')}:${g('minute')}:${g('second')}`;
  } catch (e) {
    return new Date(ms).toISOString().replace('T', ' ').slice(0, 19);
  }
}

/** Runs async jobs with a global limit and a per-host limit. */
class Limiter {
  constructor(max, perHost) {
    this.max = max;
    this.perHost = perHost;
    this.active = 0;
    this.hosts = new Map();
    this.queue = [];
  }
  run(host, fn) {
    return new Promise((resolve, reject) => {
      this.queue.push({ host, fn, resolve, reject });
      this._next();
    });
  }
  _next() {
    if (this.active >= this.max) return;
    const i = this.queue.findIndex((j) => (this.hosts.get(j.host) || 0) < this.perHost);
    if (i < 0) return;
    const job = this.queue.splice(i, 1)[0];
    this.active++;
    this.hosts.set(job.host, (this.hosts.get(job.host) || 0) + 1);
    Promise.resolve()
      .then(job.fn)
      .then(job.resolve, job.reject)
      .finally(() => {
        this.active--;
        this.hosts.set(job.host, this.hosts.get(job.host) - 1);
        this._next();
      });
    this._next();
  }
}

/** Server log kept in memory so the control panel can show the last lines. */
const LOG = [];
function log(...args) {
  const line =
    new Date().toISOString().replace('T', ' ').slice(0, 19) +
    ' ' +
    args.map((a) => (a instanceof Error ? a.message : typeof a === 'string' ? a : JSON.stringify(a))).join(' ');
  LOG.push(line);
  if (LOG.length > 300) LOG.splice(0, LOG.length - 300);
  console.log(line);
}

module.exports = {
  DEFAULT_UA,
  oneLine,
  sleep,
  isStreamUrl,
  isHttpUrl,
  hostOf,
  headersFor,
  readLimited,
  gunzipIfNeeded,
  fetchText,
  downloadFile,
  writeFileAtomic,
  fileAgeMs,
  randomPassword,
  randomPin,
  hashPassword,
  checkPassword,
  safeEqual,
  xmlEscape,
  xmlUnescape,
  parseXmltvTime,
  xmltvTime,
  localDateTime,
  Limiter,
  LOG,
  log,
};
