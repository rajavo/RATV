'use strict';
// Channels and categories: ordering, what the apps see, importing playlists and the free package.

const fs = require('fs');
const path = require('path');
const { fetchText, downloadFile, fileAgeMs, isStreamUrl, hostOf, oneLine, log } = require('./util');
const { parseM3u, splitName, arabicNameOf, matchKey, genreOf, genreName, genreOrder, countryRank } = require('./m3u');

const API_BASE = 'https://iptv-org.github.io/api/';

// Pay TV is never part of the free package, whatever a public list contains.
const PAID_OWNER = /(bein media|orbit showtime|^osn$|\bosn\b|saudi sports company|thmanyah|starz ?play|shahid vip)/i;
const PAID_NAME =
  /\b(bein|osn|ssc|thmanyah|starz(play)?|showtime|orbit|shahid\s*vip|dazn|sky\s*sports?|espn|eurosport|premium|tod)\b|ثمانية|بي\s*[إا]ن/i;
const ADULT =
  /(x{3,}|porn|\badults?\b|erotic|playboy|brazzers|hustler|penthouse|\bsexy?\b|hentai|onlyfans|18\s*\+|\+\s*18|للكبار|اباحي|إباحي|سكس)/i;

// ------------------------------------------------------------- sources

function newSource(u, pkg) {
  return {
    url: oneLine(u.url),
    ua: oneLine(u.ua),
    referer: oneLine(u.referer),
    pkg: !!pkg,
    status: 'unknown',
    fails: 0,
    checkedAt: 0,
    ms: 0,
    error: '',
  };
}

/** ok: at least one link plays · warn: failed once · dead: every link is down · unknown: not checked yet. */
function channelState(ch) {
  const st = (ch.sources || []).map((s) => s.status || 'unknown');
  if (!st.length) return 'dead';
  if (st.includes('ok')) return 'ok';
  if (st.includes('warn')) return 'warn';
  if (st.includes('unknown')) return 'unknown';
  return 'dead';
}

// ------------------------------------------------------------- ordering

// Well-known channels lead their category (by guide id, without the feed).
const LEADERS = [
  'AlSaudiya.sa', 'SBC.sa', 'AlEkhbariya.sa', 'AlArabiya.ae', 'AlHadath.sa', 'AsharqNews.sa', 'AlJazeera.qa',
  'SkyNewsArabia.ae', 'MBC1.ae', 'SaudiQuranTV.sa', 'AlQuranAlKareemTV.sa', 'AlSunnahAlNabawiyahTV.sa',
  'KTVSport.kw', 'KTVSportPlus.kw', 'OmanSportsTV.om', 'SharjahSports.ae', 'BahrainSports1.bh',
];
const LEADER_RANK = new Map(LEADERS.map((id, i) => [id.toLowerCase(), i]));
function leaderRank(ch) {
  const id = String(ch.epgId || ch.pkgKey || '').split('@')[0].toLowerCase();
  return LEADER_RANK.has(id) ? LEADER_RANK.get(id) : 999;
}

function orderedChannels(store) {
  const cats = new Map(store.categories.map((c) => [c.id, c]));
  const key = (ch) => {
    const c = cats.get(ch.categoryId);
    return c ? c.order * 100000 + c.id : 99999 * 100000;
  };
  return store.channels.slice().sort((a, b) => {
    const ka = key(a);
    const kb = key(b);
    if (ka !== kb) return ka - kb;
    if (!!b.pinned !== !!a.pinned) return b.pinned ? 1 : -1;
    const la = leaderRank(a);
    const lb = leaderRank(b);
    if (la !== lb) return la - lb;
    const ra = countryRank(a.epgId || a.pkgKey);
    const rb = countryRank(b.epgId || b.pkgKey);
    if (ra !== rb) return ra - rb;
    return String(a.name).localeCompare(String(b.name), 'ar');
  });
}

function isVisible(store, ch) {
  if (!ch.enabled || !ch.sources || !ch.sources.length) return false;
  if (ch.origin === 'pkg' && !store.settings.package.enabled) return false;
  const cat = store.category(ch.categoryId);
  if (cat && cat.hidden) return false;
  if (store.settings.hideDead && channelState(ch) === 'dead') return false;
  return true;
}

/** What the TV apps get: visible channels in order, each with its fixed channel number. */
function liveLineup(store) {
  const all = orderedChannels(store);
  const out = [];
  all.forEach((ch, i) => {
    if (isVisible(store, ch)) out.push({ ch, num: i + 1 });
  });
  return out;
}

function otherCategory(store) {
  return store.categoryByKey('pkg:other', genreName('other'), genreOrder('other'));
}

/** Categories in order, each with how many visible channels it has. */
function lineupCategories(store, lineup) {
  const counts = new Map();
  for (const { ch } of lineup) counts.set(ch.categoryId, (counts.get(ch.categoryId) || 0) + 1);
  return store.categories
    .filter((c) => !c.hidden && counts.get(c.id))
    .sort((a, b) => a.order - b.order || a.id - b.id);
}

// ------------------------------------------------------------- deleting

function deleteChannel(store, ch) {
  const i = store.channels.indexOf(ch);
  if (i >= 0) store.channels.splice(i, 1);
  if (ch.pkgKey) {
    const removed = store.settings.package.removed || (store.settings.package.removed = []);
    if (!removed.includes(ch.pkgKey)) removed.push(ch.pkgKey);
  }
}

// ------------------------------------------------------------- importing

/**
 * Adds the entries of a playlist the owner provides.
 * merge: a channel that already exists (same guide id or same name) gets the link as a backup.
 */
function importEntries(store, entries, { mode = 'groups', categoryId = null, merge = true } = {}) {
  const res = { added: 0, merged: 0, skipped: 0 };
  const byEpg = new Map();
  const byName = new Map();
  if (merge) {
    for (const ch of store.channels) {
      if (ch.epgId) byEpg.set(ch.epgId.toLowerCase(), ch);
      byName.set(matchKey(ch.name), ch);
    }
  }
  const fixedCat = categoryId ? store.category(categoryId) : null;
  for (const e of entries) {
    if (!e.url || !isStreamUrl(e.url) || e.drm) {
      res.skipped++;
      continue;
    }
    const { base, quality } = splitName(e.name);
    const name = (base + (quality ? ' ' + quality : '')).trim() || 'قناة';
    let target = null;
    if (merge) target = (e.tvgId && byEpg.get(e.tvgId.toLowerCase())) || byName.get(matchKey(name)) || null;
    if (target) {
      if (target.sources.some((s) => s.url === e.url)) {
        res.skipped++;
      } else {
        target.sources.push(newSource(e, false));
        res.merged++;
      }
      continue;
    }
    let cat = fixedCat;
    if (!cat) {
      const g = oneLine(e.group).slice(0, 40);
      cat = g ? store.categoryByKey('imp:' + g.toLowerCase(), g, 300) : otherCategory(store);
    }
    const ch = {
      id: store.nextId('channel'),
      name,
      logo: /^https?:\/\//i.test(e.logo) ? e.logo : '',
      categoryId: cat.id,
      epgId: e.tvgId || '',
      enabled: true,
      pinned: false,
      origin: 'import',
      flags: {},
      sources: [newSource(e, false)],
      createdAt: Date.now(),
    };
    store.channels.push(ch);
    if (merge) {
      if (ch.epgId) byEpg.set(ch.epgId.toLowerCase(), ch);
      byName.set(matchKey(ch.name), ch);
    }
    res.added++;
  }
  store.save();
  return res;
}

// ------------------------------------------------------------- free package

async function cachedJson(cacheDir, name, maxAgeMs) {
  const file = path.join(cacheDir, name);
  if (fileAgeMs(file) > maxAgeMs) {
    try {
      await downloadFile(API_BASE + name, file);
    } catch (e) {
      log('Could not refresh', name + ':', e.message, fs.existsSync(file) ? '(using the saved copy)' : '');
    }
  }
  if (!fs.existsSync(file)) return null;
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    return null;
  }
}

/** Names, owners and adult flags of the channels in the lists (from the public channel database). */
async function loadMeta(cacheDir, wantedIds) {
  const [channels, blocklist] = await Promise.all([
    cachedJson(cacheDir, 'channels.json', 3 * 86400000),
    cachedJson(cacheDir, 'blocklist.json', 86400000),
  ]);
  const meta = new Map();
  if (Array.isArray(channels)) {
    for (const c of channels) {
      if (c && wantedIds.has(c.id)) {
        meta.set(c.id, { name: c.name, alt_names: c.alt_names, owners: c.owners || [], nsfw: !!c.is_nsfw });
      }
    }
  }
  const blocked = new Set(Array.isArray(blocklist) ? blocklist.map((b) => b.channel) : []);
  return { meta, blocked, haveDb: Array.isArray(channels) };
}

function baseId(tvgId) {
  return String(tvgId || '').split('@')[0];
}

/** Why an entry is left out of the free package (null when it is fine). */
function exclusionReason(e, m, blocked) {
  const id = baseId(e.tvgId);
  if (id && blocked.has(id)) return 'blocked';
  if (m && m.nsfw) return 'adult';
  if (ADULT.test(e.name) || ADULT.test(e.group)) return 'adult';
  if (m && m.owners.some((o) => PAID_OWNER.test(o))) return 'paid';
  if (PAID_NAME.test(e.name) || (m && PAID_NAME.test(m.name || ''))) return 'paid';
  if (e.drm) return 'drm';
  if (!isStreamUrl(e.url)) return 'url';
  return null;
}

async function refreshPackage(store, cacheDir) {
  const pkg = store.settings.package;
  const started = Date.now();
  const entries = [];
  const errors = [];
  for (const url of pkg.sources) {
    try {
      const { text } = await fetchText(url, { timeout: 90000 });
      const { entries: list } = parseM3u(text);
      entries.push(...list);
    } catch (e) {
      errors.push(url + ' → ' + e.message);
    }
  }
  if (!entries.length) {
    pkg.lastResult = { ok: false, at: Date.now(), errors };
    store.save();
    throw new Error('تعذر تحميل الباقة المجانية: ' + (errors[0] || 'القائمة فارغة'));
  }

  const ids = new Set(entries.map((e) => baseId(e.tvgId)).filter(Boolean));
  const { meta, blocked, haveDb } = await loadMeta(cacheDir, ids);

  // Leave out pay TV, adult channels and anything else served from the same hosts as pay TV.
  const excluded = { paid: 0, adult: 0, blocked: 0, drm: 0, url: 0, host: 0 };
  const tainted = new Set();
  const keep = [];
  for (const e of entries) {
    const why = exclusionReason(e, meta.get(baseId(e.tvgId)), blocked);
    if (why) {
      excluded[why]++;
      if (why === 'paid' || why === 'blocked') tainted.add(hostOf(e.url));
    } else keep.push(e);
  }
  const items = new Map();
  for (const e of keep) {
    if (tainted.has(hostOf(e.url))) {
      excluded.host++;
      continue;
    }
    const key = e.tvgId || 'name:' + matchKey(e.name);
    const link = { url: e.url, ua: e.ua, referer: e.referer };
    const prev = items.get(key);
    if (prev) {
      if (!prev.urls.some((u) => u.url === e.url)) prev.urls.push(link); // a second list gives a backup link
      continue;
    }
    const { base, quality, geo, part } = splitName(e.name);
    const ar = store.settings.arabicNames ? arabicNameOf(meta.get(baseId(e.tvgId))) : '';
    items.set(key, {
      key,
      tvgId: e.tvgId,
      name: ((ar || base || 'قناة') + (quality ? ' ' + quality : '')).trim(),
      logo: /^https?:\/\//i.test(e.logo) ? e.logo : '',
      genre: genreOf(e.group, base),
      urls: [link],
      flags: { geo, part },
    });
  }

  const existing = new Map(store.channels.filter((c) => c.origin === 'pkg').map((c) => [c.pkgKey, c]));
  const removed = new Set(pkg.removed || []);
  const result = { ok: true, at: Date.now(), added: 0, updated: 0, gone: 0, total: 0, excluded, errors, haveDb };
  for (const item of items.values()) {
    if (removed.has(item.key)) continue;
    result.total++;
    const cat = store.categoryByKey('pkg:' + item.genre, genreName(item.genre), genreOrder(item.genre));
    const ch = existing.get(item.key);
    if (!ch) {
      store.channels.push({
        id: store.nextId('channel'),
        name: item.name,
        logo: item.logo,
        categoryId: cat.id,
        epgId: item.tvgId,
        enabled: true,
        pinned: false,
        origin: 'pkg',
        pkgKey: item.key,
        flags: item.flags,
        sources: item.urls.map((u) => newSource(u, true)),
        createdAt: Date.now(),
      });
      result.added++;
      continue;
    }
    existing.delete(item.key);
    const old = new Map(ch.sources.filter((s) => s.pkg).map((s) => [s.url, s]));
    const pkgSources = item.urls.map((u) => {
      const s = old.get(u.url);
      if (!s) return newSource(u, true);
      s.ua = oneLine(u.ua);
      s.referer = oneLine(u.referer);
      return s;
    });
    // Package links take the place the old package links had; the owner's own links stay where they are.
    const list = [];
    let placed = false;
    for (const s of ch.sources) {
      if (s.pkg) {
        if (!placed) list.push(...pkgSources);
        placed = true;
      } else list.push(s);
    }
    if (!placed) list.unshift(...pkgSources);
    ch.sources = list;
    if (!ch.edited) {
      ch.name = item.name;
      ch.logo = item.logo || ch.logo;
      ch.categoryId = cat.id;
      ch.epgId = item.tvgId;
    }
    ch.flags = item.flags;
    result.updated++;
  }
  // Channels the lists no longer have.
  for (const ch of existing.values()) {
    ch.sources = ch.sources.filter((s) => !s.pkg);
    if (!ch.sources.length) {
      const i = store.channels.indexOf(ch);
      if (i >= 0) store.channels.splice(i, 1);
    } else {
      ch.origin = 'manual';
      delete ch.pkgKey;
    }
    result.gone++;
  }
  pkg.lastRefresh = Date.now();
  pkg.lastResult = result;
  store.save();
  log(
    `Free package: ${result.total} channels (${result.added} new, ${result.gone} gone), left out:`,
    excluded,
    'in',
    Math.round((Date.now() - started) / 1000) + 's'
  );
  return result;
}

module.exports = {
  leaderRank,
  baseId,
  newSource,
  channelState,
  orderedChannels,
  isVisible,
  liveLineup,
  lineupCategories,
  otherCategory,
  deleteChannel,
  importEntries,
  refreshPackage,
  exclusionReason,
  parseM3u,
};
