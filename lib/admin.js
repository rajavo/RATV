'use strict';
// JSON API behind the control panel (public/admin.html).

const crypto = require('crypto');
const { hashPassword, checkPassword, isStreamUrl, isHttpUrl, oneLine, LOG, log, fetchText } = require('./util');
const { channelState, isVisible, newSource, deleteChannel, importEntries, otherCategory, orderedChannels, parseM3u } = require('./catalog');
const { Guard } = require('./xtream');

const COOKIE = 'ratv_admin';
const SESSION_DAYS = 30;

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function send(res, status, body) {
  const data = Buffer.from(JSON.stringify(body));
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': data.length, 'Cache-Control': 'no-store' });
  res.end(data);
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const parts = [];
    let size = 0;
    req.on('data', (d) => {
      size += d.length;
      if (size > limit) {
        reject(new HttpError(413, 'المحتوى كبير جداً'));
        req.destroy();
      } else parts.push(d);
    });
    req.on('end', () => {
      if (!size) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(parts).toString('utf8')));
      } catch (e) {
        reject(new HttpError(400, 'طلب غير مفهوم'));
      }
    });
    req.on('error', reject);
  });
}

function cookies(req) {
  const out = {};
  for (const part of String(req.headers.cookie || '').split(';')) {
    const i = part.indexOf('=');
    if (i > 0) out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim());
  }
  return out;
}

const text = (v, max) => oneLine(v).slice(0, max);
const bool = (v) => v === true || v === 'true' || v === 1 || v === '1';

class Admin {
  constructor(app) {
    this.app = app;
    this.store = app.store;
    this.guard = new Guard(8, 10 * 60000, 15 * 60000);
  }

  // ------------------------------------------------------------- sessions

  sign(payload) {
    const body = Buffer.from(JSON.stringify(payload)).toString('base64url');
    const sig = crypto.createHmac('sha256', this.store.settings.sessionSecret).update(body).digest('base64url');
    return body + '.' + sig;
  }

  loggedIn(req) {
    const tok = cookies(req)[COOKIE];
    if (!tok || tok.indexOf('.') < 0) return false;
    const [body, sig] = tok.split('.');
    const good = crypto.createHmac('sha256', this.store.settings.sessionSecret).update(body).digest('base64url');
    if (sig.length !== good.length || !crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(good))) return false;
    try {
      return JSON.parse(Buffer.from(body, 'base64url').toString()).exp > Date.now();
    } catch (e) {
      return false;
    }
  }

  setCookie(res) {
    const tok = this.sign({ exp: Date.now() + SESSION_DAYS * 86400000 });
    res.setHeader('Set-Cookie', `${COOKIE}=${tok}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${SESSION_DAYS * 86400}`);
  }

  // ------------------------------------------------------------- routing

  async handle(req, res, pathname, ip) {
    try {
      const method = req.method;
      if (pathname === '/api/login' && method === 'POST') return await this.login(req, res, ip);
      if (!this.loggedIn(req)) throw new HttpError(401, 'سجّل دخولك أولاً');
      // Changes must come from the panel itself (a page elsewhere cannot add this header).
      if (method !== 'GET' && req.headers['x-ratv'] !== '1') throw new HttpError(403, 'طلب مرفوض');
      const parts = pathname.split('/').filter(Boolean).slice(1); // after "api"
      const [a, b, c] = parts;
      const body = method === 'POST' || method === 'PUT' ? await readBody(req, a === 'import' ? 30 * 1024 * 1024 : 2 * 1024 * 1024) : {};

      if (a === 'logout' && method === 'POST') {
        res.setHeader('Set-Cookie', `${COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0`);
        return send(res, 200, { ok: true });
      }
      if (a === 'state' && method === 'GET') return send(res, 200, this.state(req));
      if (a === 'channels') {
        if (!b && method === 'GET') return send(res, 200, this.channelsList());
        if (!b && method === 'POST') return send(res, 200, this.saveChannel(null, body));
        if (b === 'bulk' && method === 'POST') return send(res, 200, this.bulk(body));
        if (b && !c && method === 'PUT') return send(res, 200, this.saveChannel(b, body));
        if (b && !c && method === 'DELETE') return send(res, 200, this.removeChannel(b));
        if (b && c === 'check' && method === 'POST') return send(res, 200, await this.checkChannel(b));
      }
      if (a === 'categories') {
        if (b === 'order' && method === 'POST') return send(res, 200, this.orderCategories(body));
        if (!b && method === 'POST') return send(res, 200, this.saveCategory(null, body));
        if (b && method === 'PUT') return send(res, 200, this.saveCategory(b, body));
        if (b && method === 'DELETE') return send(res, 200, this.removeCategory(b));
      }
      if (a === 'users') {
        if (!b && method === 'POST') return send(res, 200, this.saveUser(null, body));
        if (b && method === 'PUT') return send(res, 200, this.saveUser(b, body));
        if (b && method === 'DELETE') return send(res, 200, this.removeUser(b));
      }
      if (a === 'import' && method === 'POST') return send(res, 200, await this.importPlaylist(body));
      if (a === 'package') {
        if (b === 'refresh' && method === 'POST') return send(res, 200, this.app.refreshPackage());
        if (b === 'restore' && method === 'POST') {
          this.store.settings.package.removed = [];
          this.store.save();
          return send(res, 200, this.app.refreshPackage());
        }
        if (!b && method === 'PUT') return send(res, 200, this.savePackage(body));
      }
      if (a === 'health' && b === 'run' && method === 'POST') {
        this.app.health.runDue({ force: bool(body.force) }).catch((e) => log('Health check failed:', e));
        return send(res, 200, { ok: true });
      }
      if (a === 'epg' && b === 'refresh' && method === 'POST') {
        this.app.updateGuide(true);
        return send(res, 200, { ok: true });
      }
      if (a === 'settings' && method === 'PUT') return send(res, 200, this.saveSettings(body));
      if (a === 'password' && method === 'POST') return send(res, 200, this.changePassword(res, body));
      if (a === 'logs' && method === 'GET') return send(res, 200, { lines: LOG.slice(-200) });
      throw new HttpError(404, 'غير موجود');
    } catch (e) {
      if (!(e instanceof HttpError)) log('Panel error:', e);
      if (!res.headersSent) send(res, e.status || 500, { error: e.status ? e.message : 'صار خطأ في السيرفر' });
    }
  }

  async login(req, res, ip) {
    if (this.guard.blocked(ip)) throw new HttpError(429, 'محاولات كثيرة، جرّب بعد ربع ساعة');
    const body = await readBody(req, 10000);
    const admin = this.store.settings.admin || {};
    if (!checkPassword(String(body.password || ''), admin.salt, admin.hash)) {
      this.guard.fail(ip);
      throw new HttpError(401, 'كلمة المرور غير صحيحة');
    }
    this.guard.ok(ip);
    this.setCookie(res);
    send(res, 200, { ok: true });
  }

  // ------------------------------------------------------------- views

  state(req) {
    const s = this.store.settings;
    const counts = { channels: 0, visible: 0, ok: 0, warn: 0, dead: 0, unknown: 0, disabled: 0 };
    for (const ch of this.store.channels) {
      counts.channels++;
      if (!ch.enabled) {
        counts.disabled++;
        continue;
      }
      counts[channelState(ch)]++;
      if (isVisible(this.store, ch)) counts.visible++;
    }
    const hub = this.app.hub;
    return {
      server: {
        name: s.serverName,
        version: this.app.version,
        startedAt: this.app.startedAt,
        ffmpeg: this.app.ff.ok ? this.app.ff.version : null,
        url: this.app.xtream.baseUrl(req),
        publicUrl: s.publicUrl,
      },
      counts,
      viewers: hub.viewers(),
      users: this.store.users.map((u) => ({
        id: u.id,
        name: u.name,
        username: u.username,
        password: u.password,
        maxConnections: u.maxConnections,
        enabled: u.enabled,
        active: hub.activeCount(u.id),
        lastSeen: u.lastSeen || 0,
      })),
      categories: this.categoriesList(),
      package: {
        enabled: s.package.enabled,
        sources: s.package.sources,
        lastRefresh: s.package.lastRefresh,
        lastResult: s.package.lastResult,
        removed: (s.package.removed || []).length,
        running: this.app.packageRunning,
      },
      health: { running: this.app.health.running, progress: this.app.health.progress, lastRun: this.app.health.lastRun },
      epg: { enabled: s.epg.enabled, extraUrls: s.epg.extraUrls, status: this.app.epg.status, collector: this.app.epg.guideMtime > 0 },
      settings: {
        serverName: s.serverName,
        publicUrl: s.publicUrl,
        hideDead: s.hideDead,
        arabicNames: s.arabicNames,
        healthEveryMin: s.healthEveryMin,
      },
    };
  }

  categoriesList() {
    const counts = new Map();
    for (const ch of this.store.channels) counts.set(ch.categoryId, (counts.get(ch.categoryId) || 0) + 1);
    return this.store.categories
      .slice()
      .sort((a, b) => a.order - b.order || a.id - b.id)
      .map((c) => ({ id: c.id, name: c.name, hidden: !!c.hidden, order: c.order, count: counts.get(c.id) || 0, pkg: String(c.key || '').startsWith('pkg:') }));
  }

  channelsList() {
    const order = new Map(orderedChannels(this.store).map((ch, i) => [ch.id, i + 1]));
    return {
      categories: this.categoriesList(),
      channels: this.store.channels
        .map((ch) => ({
          id: ch.id,
          num: order.get(ch.id),
          name: ch.name,
          logo: ch.logo,
          categoryId: ch.categoryId,
          epgId: ch.epgId,
          enabled: ch.enabled,
          pinned: !!ch.pinned,
          origin: ch.origin,
          flags: ch.flags || {},
          state: channelState(ch),
          visible: isVisible(this.store, ch),
          hasGuide: this.app.epg.programmes(ch.epgId).length > 0,
          sources: ch.sources.map((s) => ({
            url: s.url,
            ua: s.ua,
            referer: s.referer,
            pkg: !!s.pkg,
            status: s.status,
            ms: s.ms,
            error: s.error,
            checkedAt: s.checkedAt,
          })),
        }))
        .sort((x, y) => x.num - y.num),
    };
  }

  // ------------------------------------------------------------- channels

  saveChannel(id, body) {
    const store = this.store;
    let ch = id ? store.channel(id) : null;
    if (id && !ch) throw new HttpError(404, 'القناة غير موجودة');
    const name = text(body.name, 80);
    if (!name) throw new HttpError(400, 'اكتب اسم القناة');
    const cat = store.category(body.categoryId) || otherCategory(store);
    const incoming = Array.isArray(body.sources) ? body.sources : [];
    const sources = [];
    const old = new Map(ch ? ch.sources.map((s) => [s.url, s]) : []);
    for (const s of incoming) {
      const url = text(s.url, 2000);
      if (!url) continue;
      if (!isStreamUrl(url)) throw new HttpError(400, 'رابط غير صالح: ' + url.slice(0, 60));
      const prev = old.get(url);
      if (prev) {
        // Package links keep their own headers; the owner's links take what was typed.
        if (!prev.pkg) {
          prev.ua = text(s.ua, 300);
          prev.referer = text(s.referer, 500);
        }
        sources.push(prev);
      } else sources.push(newSource({ url, ua: text(s.ua, 300), referer: text(s.referer, 500) }, false));
    }
    if (!sources.length) throw new HttpError(400, 'أضف رابط واحد على الأقل');
    const logo = text(body.logo, 1000);
    const epgId = text(body.epgId, 120);
    if (!ch) {
      ch = {
        id: store.nextId('channel'),
        origin: 'manual',
        flags: {},
        createdAt: Date.now(),
        enabled: true,
        pinned: false,
        sources: [],
      };
      store.channels.push(ch);
    } else if (ch.pkgKey && (ch.name !== name || ch.categoryId !== cat.id || ch.logo !== logo || ch.epgId !== epgId)) {
      ch.edited = true; // daily package updates keep these changes
    }
    const epgChanged = ch.epgId !== epgId;
    Object.assign(ch, {
      name,
      logo: isHttpUrl(logo) ? logo : '',
      categoryId: cat.id,
      epgId,
      enabled: body.enabled === undefined ? ch.enabled : bool(body.enabled),
      pinned: body.pinned === undefined ? ch.pinned : bool(body.pinned),
      sources,
    });
    store.save();
    if (sources.some((s) => !s.checkedAt)) this.app.health.runDue({ channelIds: [ch.id] }).catch(() => {});
    if (epgChanged) this.app.updateGuide();
    return { ok: true, id: ch.id };
  }

  removeChannel(id) {
    const ch = this.store.channel(id);
    if (!ch) throw new HttpError(404, 'القناة غير موجودة');
    deleteChannel(this.store, ch);
    this.store.save();
    return { ok: true };
  }

  bulk(body) {
    const ids = (Array.isArray(body.ids) ? body.ids : []).map(Number);
    const list = this.store.channels.filter((ch) => ids.includes(ch.id));
    const action = String(body.action || '');
    const cat = action === 'move' ? this.store.category(body.categoryId) : null;
    if (action === 'move' && !cat) throw new HttpError(400, 'اختر تصنيف');
    for (const ch of list) {
      if (action === 'enable') ch.enabled = true;
      else if (action === 'disable') ch.enabled = false;
      else if (action === 'delete') deleteChannel(this.store, ch);
      else if (action === 'move') {
        ch.categoryId = cat.id;
        if (ch.pkgKey) ch.edited = true;
      }
    }
    this.store.save();
    return { ok: true, count: list.length };
  }

  async checkChannel(id) {
    const ch = this.store.channel(id);
    if (!ch) throw new HttpError(404, 'القناة غير موجودة');
    const health = this.app.health;
    for (const s of ch.sources) health.apply(s, await health.check(s));
    this.store.save();
    return { ok: true, state: channelState(ch), sources: ch.sources.map((s) => ({ url: s.url, status: s.status, ms: s.ms, error: s.error })) };
  }

  // ------------------------------------------------------------- categories

  saveCategory(id, body) {
    const store = this.store;
    let c = id ? store.category(id) : null;
    if (id && !c) throw new HttpError(404, 'التصنيف غير موجود');
    const name = text(body.name, 40);
    if (!c) {
      if (!name) throw new HttpError(400, 'اكتب اسم التصنيف');
      const maxOrder = store.categories.reduce((m, x) => Math.max(m, x.order), 0);
      c = { id: store.nextId('category'), key: 'own:' + Date.now(), name, order: maxOrder + 10, hidden: false };
      store.categories.push(c);
    } else {
      if (name) c.name = name;
      if (body.hidden !== undefined) c.hidden = bool(body.hidden);
    }
    store.save();
    return { ok: true, id: c.id };
  }

  removeCategory(id) {
    const store = this.store;
    const c = store.category(id);
    if (!c) throw new HttpError(404, 'التصنيف غير موجود');
    const other = otherCategory(store);
    if (other.id === c.id) throw new HttpError(400, 'هذا التصنيف تنتقل له القنوات، ما ينحذف');
    for (const ch of store.channels) if (ch.categoryId === c.id) ch.categoryId = other.id;
    store.categories.splice(store.categories.indexOf(c), 1);
    store.save();
    return { ok: true };
  }

  orderCategories(body) {
    const ids = (Array.isArray(body.ids) ? body.ids : []).map(Number);
    ids.forEach((id, i) => {
      const c = this.store.category(id);
      if (c) c.order = (i + 1) * 10;
    });
    this.store.save();
    return { ok: true };
  }

  // ------------------------------------------------------------- users

  saveUser(id, body) {
    const store = this.store;
    let u = id ? store.user(id) : null;
    if (id && !u) throw new HttpError(404, 'الحساب غير موجود');
    const username = text(body.username, 32);
    const password = text(body.password, 32);
    if (!/^[A-Za-z0-9_.-]{2,32}$/.test(username)) throw new HttpError(400, 'اسم المستخدم: حروف إنجليزية أو أرقام، من 2 إلى 32');
    if (!/^\S{4,32}$/.test(password)) throw new HttpError(400, 'كلمة المرور: 4 خانات على الأقل وبدون مسافات');
    if (store.users.some((x) => x.username === username && x !== u)) throw new HttpError(400, 'اسم المستخدم مستخدم في حساب ثاني');
    const fields = {
      name: text(body.name, 40) || username,
      username,
      password,
      maxConnections: Math.max(1, Math.min(20, parseInt(body.maxConnections, 10) || 1)),
      enabled: body.enabled === undefined ? true : bool(body.enabled),
    };
    if (!u) {
      u = { id: store.nextId('user'), createdAt: Date.now() };
      store.users.push(u);
    }
    Object.assign(u, fields);
    if (!u.enabled) for (const c of this.app.hub.clients) if (c.user.id === u.id) c.kick();
    store.save();
    return { ok: true, id: u.id };
  }

  removeUser(id) {
    const u = this.store.user(id);
    if (!u) throw new HttpError(404, 'الحساب غير موجود');
    for (const c of this.app.hub.clients) if (c.user.id === u.id) c.kick();
    this.store.users.splice(this.store.users.indexOf(u), 1);
    this.store.save();
    return { ok: true };
  }

  // ------------------------------------------------------------- import & settings

  async importPlaylist(body) {
    let content = String(body.text || '');
    const url = text(body.url, 2000);
    if (!content && url) {
      if (!isHttpUrl(url)) throw new HttpError(400, 'الرابط لازم يبدأ بـ http');
      try {
        content = (await fetchText(url, { timeout: 60000 })).text;
      } catch (e) {
        throw new HttpError(400, 'ما قدرت أحمل القائمة: ' + e.message);
      }
    }
    if (!/#EXTINF|https?:\/\//i.test(content)) throw new HttpError(400, 'ما لقيت قنوات في القائمة');
    const { entries, epgUrl } = parseM3u(content);
    const res = importEntries(this.store, entries, {
      mode: body.mode === 'one' ? 'one' : 'groups',
      categoryId: body.mode === 'one' ? Number(body.categoryId) || null : null,
      merge: body.merge === undefined ? true : bool(body.merge),
    });
    const epg = this.store.settings.epg;
    if (epgUrl && isHttpUrl(epgUrl) && !epg.extraUrls.includes(epgUrl)) {
      epg.extraUrls.push(epgUrl);
      res.epgAdded = true;
      this.store.save();
    }
    this.app.health.runDue().catch(() => {});
    this.app.updateGuide();
    log(`Playlist imported: ${res.added} new channels, ${res.merged} backup links`);
    return { ok: true, ...res };
  }

  savePackage(body) {
    const pkg = this.store.settings.package;
    if (body.enabled !== undefined) pkg.enabled = bool(body.enabled);
    if (Array.isArray(body.sources)) {
      const list = body.sources.map((u) => text(u, 1000)).filter(Boolean);
      for (const u of list) if (!isHttpUrl(u)) throw new HttpError(400, 'رابط غير صالح: ' + u.slice(0, 60));
      if (!list.length) throw new HttpError(400, 'أضف رابط واحد على الأقل');
      pkg.sources = list;
    }
    // Turned off, the package channels are hidden from the apps (they come back when it is turned on).
    this.store.save();
    if (pkg.enabled && Array.isArray(body.sources)) this.app.refreshPackage();
    return { ok: true };
  }

  saveSettings(body) {
    const s = this.store.settings;
    if (body.serverName !== undefined) s.serverName = text(body.serverName, 40) || 'RATV Server';
    if (body.publicUrl !== undefined) {
      const u = text(body.publicUrl, 200).replace(/\/+$/, '');
      if (u && !isHttpUrl(u)) throw new HttpError(400, 'الرابط لازم يبدأ بـ http:// أو https://');
      s.publicUrl = u;
    }
    if (body.hideDead !== undefined) s.hideDead = bool(body.hideDead);
    if (body.arabicNames !== undefined && bool(body.arabicNames) !== s.arabicNames) {
      s.arabicNames = bool(body.arabicNames);
      this.app.refreshPackage();
    }
    if (body.healthEveryMin !== undefined) s.healthEveryMin = Math.max(30, Math.min(1440, parseInt(body.healthEveryMin, 10) || 180));
    if (body.epgEnabled !== undefined) s.epg.enabled = bool(body.epgEnabled);
    if (Array.isArray(body.epgExtraUrls)) {
      const list = body.epgExtraUrls.map((u) => text(u, 1000)).filter(Boolean);
      for (const u of list) if (!isHttpUrl(u)) throw new HttpError(400, 'رابط دليل غير صالح: ' + u.slice(0, 60));
      s.epg.extraUrls = list;
    }
    this.store.save();
    if (body.epgEnabled !== undefined || body.epgExtraUrls !== undefined) this.app.updateGuide(true);
    return { ok: true };
  }

  changePassword(res, body) {
    const admin = this.store.settings.admin || {};
    if (!checkPassword(String(body.current || ''), admin.salt, admin.hash)) throw new HttpError(400, 'كلمة المرور الحالية غير صحيحة');
    const next = String(body.next || '');
    if (next.length < 6) throw new HttpError(400, 'كلمة المرور الجديدة: 6 خانات على الأقل');
    this.store.settings.admin = hashPassword(next);
    this.store.settings.sessionSecret = crypto.randomBytes(32).toString('hex'); // signs out other devices
    this.store.saveNow();
    this.setCookie(res);
    return { ok: true };
  }
}

module.exports = { Admin };
