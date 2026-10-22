'use strict';
// The free Arabic channels list for RATV without any server (GitHub Actions runs this every day):
//
//   node tools/free-list.js <outDir> [guideUrl]
//
// Writes tv.m3u (the list RATV opens, pointing to the programme guide), channels.xml (for the
// iptv-org guide collector) and status.json. Pay TV, adult channels and anything served from the
// same hosts as pay TV are left out, the same way as the server's free package.
//
// Local test: FREE_SOURCES="a.m3u,b.m3u" API_DIR=<folder with channels.json, blocklist.json,
// guides.json> SKIP_CHECK=1 node tools/free-list.js out

const fs = require('fs');
const path = require('path');
const { fetchText, downloadFile, hostOf, isHttpUrl, Limiter, log } = require('../lib/util');
const { parseM3u, splitName, arabicNameOf, matchKey, genreOf, genreName, genreOrder, countryRank } = require('../lib/m3u');
const { exclusionReason, leaderRank, baseId } = require('../lib/catalog');
const { Health } = require('../lib/health');
const { channelsXml } = require('../lib/epg');
const { DEFAULT_PACKAGE_SOURCES } = require('../lib/db');

const API = 'https://iptv-org.github.io/api/';
// Checked from GitHub's servers, a link only counts as gone when its address no longer exists;
// a refusal or a timeout may just mean the channel only plays inside its own region.
const GONE = /^HTTP (404|410)$|العنوان غير موجود|السيرفر رفض الاتصال/;

const q = (s) => String(s == null ? '' : s).replace(/"/g, "'").replace(/[\r\n]+/g, ' ');

async function readSource(src) {
  if (/^https?:\/\//i.test(src)) return (await fetchText(src, { timeout: 120000 })).text;
  return fs.readFileSync(src, 'utf8');
}

async function readApi(name, cacheDir) {
  if (process.env.API_DIR) return JSON.parse(fs.readFileSync(path.join(process.env.API_DIR, name), 'utf8'));
  const file = path.join(cacheDir, name);
  await downloadFile(API + name, file, { timeout: 300000 });
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

async function main() {
  const out = path.resolve(process.argv[2] || 'out');
  const guideUrl = process.argv[3] || '';
  const cache = path.join(out, '.cache');
  fs.mkdirSync(cache, { recursive: true });
  const sources = (process.env.FREE_SOURCES ? process.env.FREE_SOURCES.split(',') : DEFAULT_PACKAGE_SOURCES)
    .map((s) => s.trim())
    .filter(Boolean);

  const entries = [];
  for (const s of sources) entries.push(...parseM3u(await readSource(s)).entries);
  if (!entries.length) throw new Error('The public lists are empty');

  const [channels, blocklist] = await Promise.all([readApi('channels.json', cache), readApi('blocklist.json', cache)]);
  const meta = new Map(channels.map((c) => [c.id, { name: c.name, alt_names: c.alt_names, owners: c.owners || [], nsfw: !!c.is_nsfw }]));
  const blocked = new Set(blocklist.map((b) => b.channel));

  const excluded = { paid: 0, adult: 0, blocked: 0, drm: 0, url: 0, host: 0, headers: 0, format: 0 };
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
    if (e.ua || e.referer) {
      excluded.headers++; // RATV opens list links without extra headers
      continue;
    }
    if (!isHttpUrl(e.url) || /\.mpd(\?|$)/i.test(e.url)) {
      excluded.format++;
      continue;
    }
    const key = e.tvgId || 'name:' + matchKey(e.name);
    if (items.has(key)) continue;
    const { base, quality } = splitName(e.name);
    const ar = arabicNameOf(meta.get(baseId(e.tvgId)));
    items.set(key, {
      key,
      tvgId: e.tvgId,
      name: ((ar || base || 'قناة') + (quality ? ' ' + quality : '')).trim(),
      logo: /^https?:\/\//i.test(e.logo) ? e.logo : '',
      genre: genreOf(e.group, base),
      url: e.url,
    });
  }

  let removed = 0;
  if (process.env.SKIP_CHECK !== '1') {
    const health = new Health({ store: { settings: {} }, ffprobe: null });
    const limiter = new Limiter(16, 4);
    await Promise.all(
      [...items.values()].map((it) =>
        limiter.run(hostOf(it.url), async () => {
          const r = await health.check({ url: it.url });
          if (!r.ok && GONE.test(r.error || '')) {
            items.delete(it.key);
            removed++;
          }
        })
      )
    );
  }

  const list = [...items.values()].sort(
    (a, b) =>
      genreOrder(a.genre) - genreOrder(b.genre) ||
      leaderRank({ epgId: a.tvgId }) - leaderRank({ epgId: b.tvgId }) ||
      countryRank(a.tvgId) - countryRank(b.tvgId) ||
      a.name.localeCompare(b.name, 'ar')
  );
  const lines = [guideUrl ? `#EXTM3U url-tvg="${guideUrl}" x-tvg-url="${guideUrl}"` : '#EXTM3U'];
  for (const it of list) {
    lines.push(`#EXTINF:-1 tvg-id="${q(it.tvgId)}" tvg-logo="${q(it.logo)}" group-title="${q(genreName(it.genre))}",${q(it.name)}`);
    lines.push(it.url);
  }
  fs.writeFileSync(path.join(out, 'tv.m3u'), lines.join('\n') + '\n');

  let guideChannels = 0;
  try {
    const guides = await readApi('guides.json', cache);
    const res = channelsXml(guides, new Set(list.map((it) => it.tvgId).filter(Boolean)));
    fs.writeFileSync(path.join(out, 'channels.xml'), res.text);
    guideChannels = res.mapped;
  } catch (e) {
    log('Guide channel list failed:', e.message);
  }

  const status = { builtAt: new Date().toISOString(), channels: list.length, removedDead: removed, excluded, guideChannels };
  fs.writeFileSync(path.join(out, 'status.json'), JSON.stringify(status, null, 1) + '\n');
  fs.rmSync(cache, { recursive: true, force: true });
  log(`Free list: ${list.length} channels, ${removed} dead links dropped, guide for ${guideChannels}; left out:`, excluded);
}

main().catch((e) => {
  console.error(e && e.stack ? e.stack : e);
  process.exit(1);
});
