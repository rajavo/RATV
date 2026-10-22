'use strict';
// The whole server state lives in one JSON file (data/db.json), saved atomically.

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { writeFileAtomic, hashPassword, randomPassword, randomPin, log } = require('./util');

const DEFAULT_PACKAGE_SOURCES = [
  'https://iptv-org.github.io/iptv/languages/ara.m3u',
  'https://iptv-org.github.io/iptv/countries/sa.m3u',
];

function defaults() {
  return {
    version: 1,
    settings: {
      serverName: 'RATV Server',
      publicUrl: '',
      timezone: process.env.TZ || 'Asia/Riyadh',
      hideDead: true,
      arabicNames: true,
      healthEveryMin: 180,
      admin: null,
      sessionSecret: crypto.randomBytes(32).toString('hex'),
      package: {
        enabled: true,
        sources: DEFAULT_PACKAGE_SOURCES.slice(),
        removed: [],
        lastRefresh: 0,
        lastResult: null,
      },
      epg: { enabled: true, extraUrls: [] },
    },
    categories: [],
    channels: [],
    users: [],
    seq: { channel: 0, category: 0, user: 0 },
  };
}

class Store {
  constructor(file) {
    this.file = file;
    this.data = null;
    this.timer = null;
    this.firstRun = null; // credentials created on the very first start
  }

  load() {
    let data = null;
    if (fs.existsSync(this.file)) {
      try {
        data = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      } catch (e) {
        const bad = this.file + '.broken-' + Date.now();
        fs.copyFileSync(this.file, bad);
        log('db.json could not be read; a copy was kept at', bad);
      }
    }
    const base = defaults();
    if (!data) data = base;
    // Fill in anything missing (newer versions add settings).
    data.settings = Object.assign({}, base.settings, data.settings || {});
    data.settings.package = Object.assign({}, base.settings.package, data.settings.package || {});
    data.settings.epg = Object.assign({}, base.settings.epg, data.settings.epg || {});
    for (const k of ['categories', 'channels', 'users']) if (!Array.isArray(data[k])) data[k] = [];
    data.seq = Object.assign({}, base.seq, data.seq || {});
    this.data = data;

    const s = data.settings;
    const firstRun = {};
    const envPass = (process.env.ADMIN_PASSWORD || '').trim();
    if (!s.admin) {
      const pass = envPass || randomPassword(10);
      s.admin = hashPassword(pass);
      firstRun.adminPassword = pass;
    }
    // reset-password.sh leaves the new password in this file; it is used once and removed.
    const resetFile = path.join(path.dirname(this.file), 'reset-password.txt');
    if (fs.existsSync(resetFile)) {
      const pass = fs.readFileSync(resetFile, 'utf8').trim();
      fs.rmSync(resetFile, { force: true });
      if (pass.length >= 6) {
        s.admin = hashPassword(pass);
        s.sessionSecret = crypto.randomBytes(32).toString('hex');
        log('Control panel password was reset.');
      }
    }
    if (data.users.length === 0) {
      const user = {
        id: this.nextId('user'),
        name: 'البيت',
        username: 'home',
        password: randomPin(6),
        maxConnections: 4,
        enabled: true,
        createdAt: Date.now(),
      };
      data.users.push(user);
      firstRun.username = user.username;
      firstRun.password = user.password;
    }
    if (Object.keys(firstRun).length) this.firstRun = firstRun;
    this.saveNow();
    return this;
  }

  nextId(kind) {
    this.data.seq[kind] = (this.data.seq[kind] || 0) + 1;
    return this.data.seq[kind];
  }

  /** Saves shortly after the last change (many changes in a row are written once). */
  save() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.saveNow(), 400);
  }

  saveNow() {
    clearTimeout(this.timer);
    this.timer = null;
    writeFileAtomic(this.file, JSON.stringify(this.data, null, 1), 0o600);
  }

  get settings() {
    return this.data.settings;
  }
  get channels() {
    return this.data.channels;
  }
  get categories() {
    return this.data.categories;
  }
  get users() {
    return this.data.users;
  }

  channel(id) {
    id = Number(id);
    return this.data.channels.find((c) => c.id === id) || null;
  }
  category(id) {
    id = Number(id);
    return this.data.categories.find((c) => c.id === id) || null;
  }
  user(id) {
    id = Number(id);
    return this.data.users.find((u) => u.id === id) || null;
  }

  /** Finds (or creates) a category by its fixed key, e.g. "pkg:news". */
  categoryByKey(key, name, order) {
    let c = this.data.categories.find((x) => x.key === key);
    if (!c) {
      c = { id: this.nextId('category'), key, name, order: order == null ? 500 : order, hidden: false };
      this.data.categories.push(c);
    }
    return c;
  }
}

module.exports = { Store, DEFAULT_PACKAGE_SOURCES };
