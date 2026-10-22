'use strict';
// RATV Server: a personal IPTV server (Xtream Codes compatible) with an Arabic control panel.

const http = require('http');
const fs = require('fs');
const path = require('path');
const { Store } = require('./lib/db');
const { Health } = require('./lib/health');
const { Hub, detectFfmpeg, detectFfprobe } = require('./lib/stream');
const { Epg } = require('./lib/epg');
const { Xtream } = require('./lib/xtream');
const { Admin } = require('./lib/admin');
const { refreshPackage } = require('./lib/catalog');
const { log, writeFileAtomic } = require('./lib/util');

const VERSION = require('./package.json').version;
const PORT = Number(process.env.PORT) || 8080;
const DATA = path.resolve(process.env.DATA_DIR || path.join(__dirname, 'data'));
const CACHE = path.join(DATA, 'cache');
const PUBLIC = path.join(__dirname, 'public');
const TRUST_PROXY = process.env.TRUST_PROXY === '1';

fs.mkdirSync(CACHE, { recursive: true });
fs.mkdirSync(path.join(DATA, 'epg'), { recursive: true });

const store = new Store(path.join(DATA, 'db.json')).load();
const ff = detectFfmpeg();
const health = new Health({ store, ffprobe: detectFfprobe() });
const hub = new Hub({ store, health, ff });
const epg = new Epg({ store, dataDir: DATA, cacheDir: CACHE });
const xtream = new Xtream({ store, hub, epg });

const app = {
  version: VERSION,
  startedAt: Date.now(),
  store,
  ff,
  health,
  hub,
  epg,
  xtream,
  packageRunning: false,
  guideTimer: null,

  refreshPackage() {
    if (this.packageRunning) return { ok: true, running: true };
    if (!store.settings.package.enabled) return { ok: false, error: 'الباقة المجانية مقفلة' };
    this.packageRunning = true;
    refreshPackage(store, CACHE)
      .then(() => {
        health.runDue().catch((e) => log('Health check failed:', e));
        this.updateGuide(true);
      })
      .catch((e) => log('Free package:', e.message))
      .finally(() => {
        this.packageRunning = false;
      });
    return { ok: true, started: true };
  },

  /** Rewrites the guide's channel list (soon, or at once) and re-reads the guide. */
  updateGuide(now) {
    clearTimeout(this.guideTimer);
    this.guideTimer = setTimeout(
      async () => {
        try {
          await epg.writeChannelsXml();
          await epg.reload({ force: true });
        } catch (e) {
          log('Guide:', e.message);
        }
      },
      now ? 200 : 60000
    );
  },
};
const admin = new Admin(app);

if (store.firstRun) {
  writeFileAtomic(path.join(DATA, 'first-run.json'), JSON.stringify(store.firstRun, null, 1), 0o600);
  log('First start. Control panel password:', store.firstRun.adminPassword || '(from ADMIN_PASSWORD)');
  if (store.firstRun.username) log(`Family account: ${store.firstRun.username} / ${store.firstRun.password}`);
}
log(`RATV Server ${VERSION} · ffmpeg ${ff.ok ? ff.version : 'NOT FOUND (install ffmpeg)'} · data in ${DATA}`);

// ------------------------------------------------------------------ http

function clientIp(req) {
  if (TRUST_PROXY && req.headers['x-forwarded-for']) return String(req.headers['x-forwarded-for']).split(',')[0].trim();
  return String(req.socket.remoteAddress || '').replace(/^::ffff:/, '');
}

function readForm(req) {
  return new Promise((resolve) => {
    let s = '';
    req.on('data', (d) => {
      s += d;
      if (s.length > 10000) req.destroy();
    });
    req.on('end', () => resolve(Object.fromEntries(new URLSearchParams(s))));
    req.on('error', () => resolve({}));
  });
}

function sendFile(res, file, type) {
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404);
      return res.end();
    }
    res.writeHead(200, { 'Content-Type': type, 'Content-Length': data.length, 'Cache-Control': 'no-cache' });
    res.end(data);
  });
}

const LIVE = /^\/live\/([^/]+)\/([^/]+)\/(\d+)\.(ts|m3u8)$/i;
const BARE = /^\/([^/]+)\/([^/]+)\/(\d+)(\.ts)?$/;

async function route(req, res) {
  const url = new URL(req.url, 'http://local');
  const p = url.pathname;
  const ip = clientIp(req);

  if (p.startsWith('/api/')) return admin.handle(req, res, p, ip);
  if (p === '/admin' || p === '/admin/') return sendFile(res, path.join(PUBLIC, 'admin.html'), 'text/html; charset=utf-8');
  if (p === '/icon.svg' || p === '/favicon.ico') return sendFile(res, path.join(PUBLIC, 'icon.svg'), 'image/svg+xml');
  if (p === '/') {
    res.writeHead(302, { Location: '/admin' });
    return res.end();
  }
  if (p === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    return res.end('ok');
  }

  let query = Object.fromEntries(url.searchParams);
  if (p === '/player_api.php' || p === '/panel_api.php') {
    if (req.method === 'POST') query = Object.assign(await readForm(req), query);
    return xtream.playerApi(req, res, query, ip);
  }
  if (p === '/get.php') return xtream.m3u(req, res, query, ip);
  if (p === '/xmltv.php') return xtream.xmltv(req, res, query, ip);

  const m = LIVE.exec(p) || (!/^\/(movie|series|timeshift)\//.test(p) && BARE.exec(p));
  if (m) {
    const dec = (s) => {
      try {
        return decodeURIComponent(s);
      } catch (e) {
        return s;
      }
    };
    const user = xtream.auth(dec(m[1]), dec(m[2]), ip);
    if (!user) {
      res.writeHead(401, { 'Content-Type': 'text/plain' });
      return res.end('Wrong user name or password');
    }
    const ch = store.channel(m[3]);
    if (!ch || !ch.enabled) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      return res.end('Channel not found');
    }
    if (String(m[4] || '').toLowerCase() === 'm3u8') return hub.serveHls(req, res, ch);
    return hub.serveTs(req, res, user, ch, ip);
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('Not found');
}

const server = http.createServer((req, res) => {
  route(req, res).catch((e) => {
    log('Request failed:', req.url.replace(/\/live\/[^/]+\/[^/]+\//, '/live/…/'), e);
    if (!res.headersSent) {
      res.writeHead(500, { 'Content-Type': 'text/plain' });
      res.end('Server error');
    } else res.end();
  });
});
server.keepAliveTimeout = 65000;
server.on('error', (e) => {
  log('Cannot listen on port', PORT + ':', e.message);
  process.exit(1);
});
server.listen(PORT, () => log(`Listening on port ${PORT}. Control panel: /admin`));

// ------------------------------------------------------------------ background work

health.start();
epg.start();

const pkgDue = () => {
  const pkg = store.settings.package;
  return pkg.enabled && (Date.now() - (pkg.lastRefresh || 0) > 24 * 3600000 || !store.channels.some((c) => c.origin === 'pkg'));
};
setTimeout(() => {
  if (pkgDue()) app.refreshPackage();
  else if (!fs.existsSync(epg.channelsFile)) app.updateGuide(true);
}, 3000);
setInterval(() => {
  if (pkgDue()) app.refreshPackage();
}, 15 * 60000);

function shutdown() {
  log('Stopping…');
  hub.stopAll();
  try {
    store.saveNow();
  } catch (e) {
    /* nothing more to do */
  }
  process.exit(0);
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
process.on('unhandledRejection', (e) => log('Unexpected error:', e && e.stack ? e.stack : e));
process.on('uncaughtException', (e) => {
  // Start over cleanly (Docker restarts the server in a second; the TVs reconnect on their own).
  log('Unexpected error, restarting:', e && e.stack ? e.stack : e);
  try {
    store.saveNow();
  } catch (x) {
    /* nothing more to do */
  }
  process.exit(1);
});
