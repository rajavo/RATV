'use strict';
// Quick checks of the parts that do not need the network:  node test/run.js

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const { parseM3u, splitName, genreOf, matchKey } = require('../lib/m3u');
const { exclusionReason } = require('../lib/catalog');
const { pickVariant, segmentsOf, targetDuration } = require('../lib/health');
const { TsParser } = require('../lib/stream');
const { readXmltv } = require('../lib/epg');
const { parseXmltvTime, xmltvTime } = require('../lib/util');

let passed = 0;
async function test(name, fn) {
  try {
    await fn();
    passed++;
    console.log('ok   ' + name);
  } catch (e) {
    console.log('FAIL ' + name + '\n     ' + (e && e.message));
    process.exitCode = 1;
  }
}

(async () => {
  await test('M3U: attributes, commas in titles, VLC options, Kodi headers, DRM', () => {
    const { entries, epgUrl } = parseM3u(
      [
        '#EXTM3U url-tvg="http://g/epg.xml"',
        '#EXTINF:-1 tvg-id="A.sa@SD" tvg-logo="http://l/a.png" group-title="News;General",Al, Channel (1080p)',
        '#EXTVLCOPT:http-user-agent=Agent/1',
        '#EXTVLCOPT:http-referrer=http://ref/',
        'http://s/a.m3u8',
        '#EXTINF:-1,B',
        'http://s/b.ts|User-Agent=Kodi%2F1&Referer=http://r2/',
        '#EXTINF:-1,C',
        '#KODIPROP:inputstream.adaptive.license_key=x',
        'http://s/c.mpd',
      ].join('\n')
    );
    assert.strictEqual(epgUrl, 'http://g/epg.xml');
    assert.strictEqual(entries.length, 3);
    assert.strictEqual(entries[0].name, 'Al, Channel (1080p)');
    assert.strictEqual(entries[0].tvgId, 'A.sa@SD');
    assert.strictEqual(entries[0].ua, 'Agent/1');
    assert.strictEqual(entries[0].referer, 'http://ref/');
    assert.strictEqual(entries[1].url, 'http://s/b.ts');
    assert.strictEqual(entries[1].ua, 'Kodi/1');
    assert.strictEqual(entries[2].drm, true);
  });

  await test('names: quality tag and flags', () => {
    assert.deepStrictEqual(splitName('Al Saudiya (1080p) [Geo-blocked]'), { base: 'Al Saudiya', quality: 'FHD', geo: true, part: false });
    assert.strictEqual(splitName('X (720p) [Not 24/7]').part, true);
    assert.strictEqual(genreOf('Kids;Religious', 'Ajyal'), 'kids');
    assert.strictEqual(genreOf('Music', 'Rotana FM KSA'), 'radio');
    assert.strictEqual(matchKey('MBC 1 FHD'), matchKey('MBC 1 (720p)'));
  });

  await test('free package: pay TV and adult channels are left out', () => {
    const blocked = new Set(['Blocked.xx']);
    const e = (name, tvgId, url = 'http://s/x.m3u8') => ({ name, tvgId, url, group: '', drm: false });
    assert.strictEqual(exclusionReason(e('beIN Sports 1 (1080p)', 'beINSports1.qa@MENA'), null, blocked), 'paid');
    assert.strictEqual(exclusionReason(e('Sports 1', 'X.qa'), { owners: ['beIN Media Group'], name: 'X' }, blocked), 'paid');
    assert.strictEqual(exclusionReason(e('SSC 1 HD', 'SSC1.sa'), null, blocked), 'paid');
    assert.strictEqual(exclusionReason(e('Something', 'Blocked.xx@SD'), null, blocked), 'blocked');
    assert.strictEqual(exclusionReason(e('Al Saudiya (1080p)', 'AlSaudiya.sa@SD'), { owners: ['Saudi Broadcasting Authority'], name: 'Al Saudiya' }, blocked), null);
    assert.strictEqual(exclusionReason(e('KTV Sport', 'KTVSport.kw@SD'), null, blocked), null);
    assert.strictEqual(exclusionReason(e('Al Saudiya Today', 'X.sa'), null, blocked), null); // "Today" is not TOD
    assert.strictEqual(exclusionReason(e('Bad', 'Y', '-i /etc/passwd'), null, blocked), 'url');
  });

  await test('HLS: best quality up to 1080p, segments, target duration', () => {
    const master = '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=3840x2160\nuhd.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\nfhd.m3u8\n';
    assert.strictEqual(pickVariant(master, 'http://h/live/index.m3u8').url, 'http://h/live/fhd.m3u8');
    const media = '#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n#EXTINF:6,\nhttp://cdn/seg2.ts\n';
    assert.deepStrictEqual(segmentsOf(media, 'http://h/a/b.m3u8'), ['http://h/a/seg1.ts', 'http://cdn/seg2.ts']);
    assert.strictEqual(targetDuration(media), 6);
  });

  await test('XMLTV times', () => {
    const t = parseXmltvTime('20261022200000 +0300');
    assert.strictEqual(new Date(t).toISOString(), '2026-10-22T17:00:00.000Z');
    assert.strictEqual(xmltvTime(t), '20261022170000 +0000');
  });

  await test('XMLTV reader keeps wanted channels inside the window', async () => {
    const file = path.join(os.tmpdir(), 'ratv-test-guide.xml');
    const now = Date.now();
    const p = (id, startMs, title) =>
      `<programme start="${xmltvTime(startMs)}" stop="${xmltvTime(startMs + 3600000)}" channel="${id}"><title lang="ar">${title}</title><desc>d &amp; e</desc></programme>`;
    fs.writeFileSync(file, `<?xml version="1.0"?><tv>${p('A.sa@SD', now, 'أ')}${p('B.xx', now, 'b')}${p('A.sa@SD', now - 5 * 86400000, 'old')}</tv>`);
    const index = new Map();
    const n = await readXmltv(file, new Set(['a.sa@sd']), now - 86400000, now + 86400000, index);
    fs.rmSync(file);
    assert.strictEqual(n, 1);
    assert.strictEqual(index.get('a.sa@sd')[0].title, 'أ');
    assert.strictEqual(index.get('a.sa@sd')[0].desc, 'd & e');
  });

  const ff = spawnSync('ffmpeg', ['-version']);
  if (!ff.error && ff.status === 0) {
    await test('TS parser: whole packets, key frames, clock', () => {
      const r = spawnSync(
        'ffmpeg',
        ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=25', '-f', 'lavfi', '-i', 'sine', '-t', '4',
          '-c:v', 'libx264', '-preset', 'ultrafast', '-g', '25', '-c:a', 'aac', '-f', 'mpegts', 'pipe:1'],
        { maxBuffer: 64 * 1024 * 1024 }
      );
      assert.ok(r.stdout && r.stdout.length > 10000, 'ffmpeg made no stream');
      const parser = new TsParser();
      let bytes = 0;
      let keys = 0;
      // Feed it in odd-sized pieces, as a pipe would.
      for (let i = 0; i < r.stdout.length; i += 7001) {
        const out = parser.push(r.stdout.subarray(i, i + 7001));
        if (out) {
          assert.strictEqual(out.buf.length % 188, 0);
          bytes += out.buf.length;
          keys += out.keys.length;
        }
      }
      assert.ok(parser.videoPid > 0 && parser.audioPid > 0, 'PIDs not found');
      assert.ok(keys >= 4 && keys <= 5, 'expected 4 key frames, got ' + keys);
      assert.ok(Math.abs(parser.lastTs / 90000 - 5.36) < 0.2, 'clock ' + parser.lastTs / 90000);
      assert.ok(bytes > r.stdout.length - 188 * 2);
    });
  } else console.log('skip TS parser (ffmpeg not installed)');

  console.log(`\n${passed} passed${process.exitCode ? ', some failed' : ''}`);
})();
