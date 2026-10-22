'use strict';
// M3U playlists: reading them, and tidying channel names and categories.

const { oneLine } = require('./util');

/**
 * Parses an M3U/M3U8 playlist.
 * Returns { epgUrl, entries: [{ name, url, tvgId, tvgName, logo, group, ua, referer, drm }] }.
 */
function parseM3u(text) {
  const lines = String(text || '').replace(/^﻿/, '').split(/\r?\n/);
  const out = [];
  let epgUrl = '';
  let cur = null;
  for (let raw of lines) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith('#EXTM3U')) {
      const a = attrs(line);
      epgUrl = a['url-tvg'] || a['x-tvg-url'] || epgUrl;
      continue;
    }
    if (line.startsWith('#EXTINF')) {
      const a = attrs(line);
      cur = {
        name: titleOf(line) || a['tvg-name'] || '',
        tvgId: a['tvg-id'] || '',
        tvgName: a['tvg-name'] || '',
        logo: a['tvg-logo'] || '',
        group: a['group-title'] || '',
        ua: a['http-user-agent'] || a['user-agent'] || '',
        referer: a['http-referrer'] || a['http-referer'] || a['referer'] || '',
        drm: false,
      };
      continue;
    }
    if (!cur) {
      if (!line.startsWith('#')) out.push(entryFromBareUrl(line));
      continue;
    }
    if (line.startsWith('#EXTVLCOPT:')) {
      const opt = line.slice(11);
      const eq = opt.indexOf('=');
      if (eq > 0) {
        const k = opt.slice(0, eq).trim().toLowerCase();
        const v = opt.slice(eq + 1).trim();
        if (k === 'http-user-agent') cur.ua = v;
        if (k === 'http-referrer' || k === 'http-referer') cur.referer = v;
      }
      continue;
    }
    if (line.startsWith('#EXTGRP:')) {
      if (!cur.group) cur.group = line.slice(8).trim();
      continue;
    }
    if (line.startsWith('#KODIPROP:')) {
      if (/license_(key|type)|drm/i.test(line)) cur.drm = true;
      continue;
    }
    if (line.startsWith('#')) continue;
    // The address. Kodi style "url|User-Agent=..&Referer=.." carries headers after a pipe.
    let url = line;
    const pipe = url.indexOf('|');
    if (pipe > 0) {
      const extra = url.slice(pipe + 1);
      url = url.slice(0, pipe);
      for (const part of extra.split('&')) {
        const eq = part.indexOf('=');
        if (eq < 0) continue;
        const k = part.slice(0, eq).trim().toLowerCase();
        let v = part.slice(eq + 1).trim();
        try {
          v = decodeURIComponent(v);
        } catch (e) {
          /* keep as is */
        }
        if (k === 'user-agent') cur.ua = v;
        if (k === 'referer' || k === 'referrer') cur.referer = v;
      }
    }
    cur.url = url.trim();
    cur.ua = oneLine(cur.ua);
    cur.referer = oneLine(cur.referer);
    out.push(cur);
    cur = null;
  }
  return { epgUrl, entries: out };
}

function entryFromBareUrl(url) {
  return { name: url.split('/').pop() || url, url, tvgId: '', tvgName: '', logo: '', group: '', ua: '', referer: '', drm: false };
}

/** key="value" attributes of an #EXTINF / #EXTM3U line. */
function attrs(line) {
  const out = {};
  const head = line.slice(0, titleStart(line));
  const re = /([a-zA-Z0-9_-]+)="([^"]*)"/g;
  let m;
  while ((m = re.exec(head))) out[m[1].toLowerCase()] = m[2].trim();
  return out;
}

/** Index where the title starts: after the first comma that is outside quotes. */
function titleStart(line) {
  let q = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '"') q = !q;
    else if (c === ',' && !q) return i + 1;
  }
  return line.length;
}

function titleOf(line) {
  return line.slice(titleStart(line)).trim();
}

// ------------------------------------------------------------------ names

const QUALITY = [
  [/\(\s*(2160|4k)p?\s*\)/i, '4K'],
  [/\(\s*1080[pi]?\s*\)/i, 'FHD'],
  [/\(\s*720p?\s*\)/i, 'HD'],
  [/\(\s*(576|540|480|404|360|288|240)[pi]?\s*\)/i, 'SD'],
];

/**
 * "Al Saudiya (1080p) [Geo-blocked]" → { base: "Al Saudiya", quality: "FHD", geo: true, part: false }.
 * The quality becomes a short tag the RATV app shows as a badge.
 */
function splitName(name) {
  let s = String(name || '');
  let quality = '';
  for (const [re, tag] of QUALITY) {
    if (re.test(s)) {
      quality = tag;
      s = s.replace(re, ' ');
      break;
    }
  }
  const geo = /\[\s*geo-?blocked\s*\]/i.test(s);
  const part = /\[\s*not\s*24\/7\s*\]/i.test(s);
  s = s.replace(/\[[^\]]*\]/g, ' ').replace(/\s+/g, ' ').trim();
  return { base: s, quality, geo, part };
}

const ARABIC = /[؀-ۿ]/;

/** Picks the Arabic name of a channel from the iptv-org database, if it has one. */
function arabicNameOf(meta) {
  if (!meta) return '';
  for (const n of [meta.name].concat(meta.alt_names || [])) {
    if (n && ARABIC.test(n) && n.length <= 40) return n.replace(/ـ/g, '').replace(/\s+/g, ' ').trim(); // no tatweel stretching
  }
  return '';
}

/** Normalised name used to spot the same channel in two playlists. */
function matchKey(name) {
  return String(name || '')
    .toLowerCase()
    .replace(/\([^)]*\)|\[[^\]]*\]/g, ' ')
    .replace(/\b(4k|uhd|fhd|hd|sd|hq|hevc|h\.?26[45]|1080p?|720p?|576p?|480p?|backup|vip|raw|live)\b/g, ' ')
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim();
}

// ------------------------------------------------------------- categories

/** Package genres → Arabic category names, in the order they are listed. */
const GENRES = [
  ['general', 'قنوات عامة'],
  ['news', 'أخبار'],
  ['sports', 'رياضة'],
  ['kids', 'أطفال'],
  ['religious', 'دينية'],
  ['movies', 'أفلام'],
  ['series', 'مسلسلات'],
  ['documentary', 'وثائقية'],
  ['music', 'أغاني وموسيقى'],
  ['entertainment', 'منوعات'],
  ['culture', 'ثقافة وتعليم'],
  ['business', 'اقتصاد'],
  ['cooking', 'طبخ'],
  ['radio', 'إذاعات'],
  ['other', 'أخرى'],
];

const GENRE_ALIASES = {
  animation: 'kids',
  family: 'entertainment',
  comedy: 'entertainment',
  lifestyle: 'entertainment',
  travel: 'entertainment',
  outdoor: 'entertainment',
  auto: 'entertainment',
  classic: 'movies',
  education: 'culture',
  science: 'culture',
  legislative: 'news',
  weather: 'news',
  shop: 'other',
  relax: 'other',
  undefined: 'other',
  general: 'general',
};

// When a channel has several genres, the more specific one wins.
const GENRE_PRIORITY = ['kids', 'sports', 'news', 'religious', 'movies', 'series', 'documentary', 'music', 'cooking', 'business', 'culture', 'entertainment', 'general', 'other'];

function genreOf(group, name) {
  if (/\b(radio|fm)\b|إذاعة|راديو/i.test(name || '')) return 'radio';
  const parts = String(group || '')
    .split(/[;,|/]/)
    .map((g) => g.trim().toLowerCase())
    .filter(Boolean)
    .map((g) => GENRE_ALIASES[g] || (GENRES.some(([k]) => k === g) ? g : 'other'));
  if (!parts.length) return 'other';
  let best = 'other';
  for (const p of parts) if (GENRE_PRIORITY.indexOf(p) < GENRE_PRIORITY.indexOf(best)) best = p;
  return best;
}

function genreName(key) {
  const g = GENRES.find(([k]) => k === key);
  return g ? g[1] : 'أخرى';
}

function genreOrder(key) {
  const i = GENRES.findIndex(([k]) => k === key);
  return 100 + (i < 0 ? 99 : i) * 10;
}

/** Saudi first, then the rest of the Gulf, then everyone else. */
function countryRank(tvgId) {
  const m = /\.([a-z]{2})(@|$)/i.exec(tvgId || '');
  const cc = m ? m[1].toLowerCase() : '';
  if (cc === 'sa') return 0;
  if (['ae', 'kw', 'qa', 'bh', 'om'].includes(cc)) return 1;
  return 2;
}

module.exports = {
  parseM3u,
  splitName,
  arabicNameOf,
  matchKey,
  genreOf,
  genreName,
  genreOrder,
  countryRank,
  GENRES,
};
