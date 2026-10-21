#!/usr/bin/env python3
"""A tiny fake IPTV provider used only by the CI smoke test.

It answers the Xtream Codes API (player_api.php, xmltv.php), serves an M3U
playlist and plays generated test media, so the app can be exercised end to
end on an emulator without a real subscription.
"""
import io
import json
import os
import sys
import time
import zipfile
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse
from xml.sax.saxutils import escape

ROOT = os.path.dirname(os.path.abspath(__file__))
MEDIA = os.path.join(ROOT, "media")
PORT = 8000
BASE = "http://10.0.2.2:%d" % PORT  # the host machine as seen from the emulator
USER, PASS = "test", "test"
NOW = int(time.time())
TZ = timezone(timedelta(hours=3))

LIVE_CATS = [("1", "أخبار"), ("2", "رياضة"), ("3", "أطفال"), ("4", "وثائقي")]
# stream_id, num, name, category, epg id, logo file
CHANNELS = [
    (101, 1, "RATV News HD", "1", "news.ratv", "l1.png"),
    (102, 2, "Gulf 24 FHD", "1", "gulf24.ratv", None),
    (103, 3, "قناة الأخبار", "1", "akhbar.ratv", "l2.png"),
    (201, 4, "Sport 1 HD", "2", "sport1.ratv", "l3.png"),
    (202, 5, "Sport 2 4K", "2", "sport2.ratv", "missing.png"),
    (203, 6, "Racing SD", "2", "", None),
    (301, 7, "Kids Fun", "3", "kids.ratv", "l4.png"),
    (302, 8, "Cartoon Time HD", "3", "cartoon.ratv", None),
    (401, 9, "Nature Docs", "4", "nature.ratv", "l5.png"),
    (402, 10, "History FHD", "4", "history.ratv", None),
    (204, 11, "Sport 1 4K", "2", "sport1.ratv", "l3.png"),
    (205, 12, "Sport 1 EN", "2", "sport1.ratv", None),
    (206, 13, "Sky Sports Main", "2", "skymain.ratv", None),
]
# The same game, named in Arabic on one channel and in English on another.
MATCH = {
    "sport1.ratv": "الدوري الإسباني: ريال مدريد × برشلونة",
    "skymain.ratv": "LaLiga: Real Madrid v Barcelona",
}
VOD_CATS = [("10", "أفلام عربية"), ("11", "أكشن"), ("12", "دراما"), ("13", "4K أفلام فائقة الوضوح"), ("14", "XXX Adults")]
MOVIES = [
    (5001, "Desert Night (2023)", "10", "7.4"),
    (5002, "The Long Road 2021", "11", "6.8"),
    (5003, "ليالي الصحراء", "10", "8.2"),
    (5004, "Fast Track 4K", "11", "5.9"),
    (5005, "Silent Sea (2019)", "12", "7.0"),
    (5006, "Old Town", "12", ""),
    (5007, "Sky Riders 2022", "11", "6.1"),
    (5008, "4K-AR: Desert Night (2023)", "13", "7.4"),
    # Adult titles: one in an adult section, one only marked in its name.
    (5009, "Night Club", "14", "5.0"),
    (5010, "Night Party +18", "11", "4.0"),
]
SERIES_CATS = [("20", "مسلسلات خليجية"), ("21", "دراما"), ("22", "مسلسلات 4K")]
SERIES = [
    (7001, "Sands of Time", "20", "8.1", "2022-05-01"),
    (7002, "City Lights", "21", "7.2", "2020-09-10"),
    (7003, "بيت الجيران", "20", "", "2021-01-15"),
    (7004, "4K: Sands of Time", "22", "8.1", "2022-05-01"),
]

# Subtitles for "Silent Sea" (2019): English and Arabic files, as a Podnapisi-like service.
SUBTITLES = {
    "sub-en-1": ("en", "Silent.Sea.2019.1080p.WEB", ["Welcome to the silent sea", "The water is calm tonight", "We sail at dawn"]),
    "sub-ar-1": ("ar", "Silent.Sea.2019.AR", ["أهلاً بكم في البحر الصامت", "البحر هادئ الليلة", "نبحر عند الفجر"]),
}


def srt(lines):
    out = []
    for i in range(200):
        start = i * 3
        out.append("%d\n%s --> %s\n%s\n" % (i + 1, ts(start), ts(start + 2.6), lines[i % len(lines)]))
    return "\n".join(out)


def ts(sec):
    ms = int(round(sec * 1000))
    return "%02d:%02d:%02d,%03d" % (ms // 3600000, ms // 60000 % 60, ms // 1000 % 60, ms % 1000)


def subtitle_search(q):
    keyword = (q.get("keywords") or "").strip().lower()
    lang = q.get("language")
    data = []
    if keyword in ("silent sea",):
        for sid, (l, release, _) in SUBTITLES.items():
            if l == lang:
                data.append({
                    "id": sid, "language": l, "flags": [], "url": "/subtitles/" + sid,
                    "releases": [release], "custom_releases": [],
                    "movie": {"title": "Silent Sea", "type": "movie", "year": 2019, "episode_info": {}},
                    "stats": {"downloads": 100 if l == "en" else 10},
                })
    return {"data": data, "page": 1, "all_pages": 1}


def subtitle_zip(sid):
    lang, release, lines = SUBTITLES[sid]
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr(release + ".srt", srt(lines).encode("utf-8"))
    return buf.getvalue()

TITLES = ["نشرة الأخبار", "Morning Show", "حوار مفتوح", "Live Match", "وثائقي الطبيعة", "Cartoon Hour", "Weekly Review", "مسلسل السهرة"]


def user_info(auth=1):
    return {
        "user_info": {
            "username": USER, "password": PASS, "message": "", "auth": auth,
            "status": "Active", "exp_date": str(NOW + 30 * 86400), "is_trial": "0",
            "active_cons": "0", "created_at": str(NOW - 90 * 86400), "max_connections": "2",
            "allowed_output_formats": ["m3u8", "ts"],
        },
        "server_info": {
            "url": "10.0.2.2", "port": str(PORT), "https_port": "", "server_protocol": "http",
            "rtmp_port": "", "timezone": "Asia/Riyadh", "timestamp_now": NOW,
            "time_now": datetime.fromtimestamp(NOW, TZ).strftime("%Y-%m-%d %H:%M:%S"),
        },
    }


def live_streams():
    out = []
    for sid, num, name, cat, epg, logo in CHANNELS:
        out.append({
            "num": num, "name": name, "stream_type": "live", "stream_id": sid,
            "stream_icon": (BASE + "/logo/" + logo) if logo else "", "epg_channel_id": epg or None,
            "added": str(NOW - 86400 * num), "category_id": cat, "custom_sid": "", "tv_archive": 0,
            "direct_source": "", "tv_archive_duration": 0,
        })
    return out


def vod_streams():
    out = []
    for i, (sid, name, cat, rating) in enumerate(MOVIES):
        out.append({
            "num": i + 1, "name": name, "stream_type": "movie", "stream_id": sid, "stream_icon": "",
            "rating": rating, "rating_5based": 0, "added": str(NOW - 3600 * (i + 1)), "category_id": cat,
            "container_extension": "mp4", "custom_sid": "", "direct_source": "",
        })
    return out


def series_list():
    out = []
    for i, (sid, name, cat, rating, released) in enumerate(SERIES):
        out.append({
            "num": i + 1, "name": name, "series_id": sid, "cover": "", "plot": "قصة تجريبية للمسلسل " + name,
            "cast": "سارة, خالد, نورة", "director": "أحمد علي", "genre": "Drama", "releaseDate": released,
            "last_modified": str(NOW - 7200 * (i + 1)), "rating": rating, "rating_5based": 0,
            "backdrop_path": [], "youtube_trailer": "", "episode_run_time": "45", "category_id": cat,
        })
    return out


def vod_info(vod_id):
    movie = next((m for m in MOVIES if str(m[0]) == str(vod_id)), MOVIES[0])
    return {
        "info": {
            "name": movie[1], "o_name": movie[1], "cover_big": "", "movie_image": "",
            "releasedate": "2023-02-01", "youtube_trailer": "", "director": "أحمد علي",
            "actors": "سارة, خالد, نورة", "cast": "سارة, خالد, نورة, Omar Saleh",
            "description": "وصف تجريبي.", "plot": "قصة تجريبية: رحلة عبر الصحراء في ليلة طويلة.",
            "country": "SA", "genre": "Drama, Adventure", "backdrop_path": [],
            "duration_secs": 120, "duration": "00:02:00", "rating": movie[3],
        },
        "movie_data": {
            "stream_id": movie[0], "name": movie[1], "added": str(NOW), "category_id": movie[2],
            "container_extension": "mp4", "custom_sid": "", "direct_source": "",
        },
    }


def series_info(series_id):
    item = next((s for s in SERIES if str(s[0]) == str(series_id)), SERIES[0])
    episodes = {}
    for season in (1, 2):
        lst = []
        for n in range(1, 5 if season == 1 else 3):
            eid = "%d%d%02d" % (item[0], season, n)
            lst.append({
                "id": eid, "episode_num": n, "title": "%s - S%02dE%02d" % (item[1], season, n),
                "container_extension": "mp4",
                "info": {"duration_secs": 120, "duration": "00:02:00", "plot": "حلقة تجريبية رقم %d" % n, "movie_image": ""},
                "custom_sid": "", "added": str(NOW), "season": season, "direct_source": "",
            })
        episodes[str(season)] = lst
    return {
        "seasons": [],
        "info": {
            "name": item[1], "cover": "", "plot": "قصة تجريبية للمسلسل " + item[1], "cast": "سارة, خالد, نورة",
            "director": "أحمد علي", "genre": "Drama", "releaseDate": item[4], "rating": item[3],
            "backdrop_path": [], "youtube_trailer": "", "episode_run_time": "2", "category_id": item[2],
        },
        "episodes": episodes,
    }


def fmt(ts):
    return datetime.fromtimestamp(ts, TZ).strftime("%Y%m%d%H%M%S") + " +0300"


MATCH_START = (NOW // 1800) * 1800 - 1800
MATCH_END = MATCH_START + 7200


def xmltv():
    lines = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="ratv-mock">']
    for sid, num, name, cat, epg, logo in CHANNELS:
        if epg:
            lines.append('<channel id="%s"><display-name>%s</display-name></channel>' % (epg, escape(name)))
    start = (NOW // 1800) * 1800 - 3 * 3600
    for sid, num, name, cat, epg, logo in CHANNELS:
        if not epg:
            continue
        t = start
        i = 0
        while t < NOW + 30 * 3600:
            dur = [1800, 3600, 2700, 5400][(i + num) % 4]
            title = TITLES[(i + num) % len(TITLES)]
            if epg in MATCH:
                # The game has the same start and end on every channel that shows it.
                if t == MATCH_START:
                    dur, title = MATCH_END - MATCH_START, MATCH[epg]
                elif t < MATCH_START < t + dur:
                    dur = MATCH_START - t
            lines.append(
                '<programme start="%s" stop="%s" channel="%s"><title lang="ar">%s</title>'
                '<desc lang="ar">%s</desc></programme>'
                % (fmt(t), fmt(t + dur), epg, escape(title), escape("وصف البرنامج " + title))
            )
            t += dur
            i += 1
    lines.append("</tv>")
    return "\n".join(lines)


def m3u():
    lines = ['#EXTM3U url-tvg="%s/xmltv.php"' % BASE]
    cats = dict(LIVE_CATS)
    for sid, num, name, cat, epg, logo in CHANNELS:
        lines.append(
            '#EXTINF:-1 tvg-id="%s" tvg-name="%s" tvg-logo="%s" group-title="%s",%s'
            % (epg, name, (BASE + "/logo/" + logo) if logo else "", cats[cat], name)
        )
        lines.append("%s/live/%s/%s/%d.ts" % (BASE, USER, PASS, sid))
    vcats = dict(VOD_CATS)
    for sid, name, cat, rating in MOVIES:
        lines.append('#EXTINF:-1 tvg-name="%s" tvg-logo="" group-title="%s",%s' % (name, vcats[cat], name))
        lines.append("%s/movie/%s/%s/%d.mp4" % (BASE, USER, PASS, sid))
    scats = dict(SERIES_CATS)
    for sid, name, cat, rating, released in SERIES:
        for season in (1, 2):
            for n in range(1, 4):
                title = "%s S%02d E%02d" % (name, season, n)
                lines.append('#EXTINF:-1 tvg-name="%s" group-title="%s",%s' % (title, scats[cat], title))
                lines.append("%s/series/%s/%s/%d%d%02d.mp4" % (BASE, USER, PASS, sid, season, n))
    return "\n".join(lines) + "\n"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt_, *args):
        sys.stderr.write("%s %s %s\n" % (time.strftime("%H:%M:%S"), self.command, self.path))

    def _send(self, body, ctype, code=200):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def send_json(self, obj):
        self._send(json.dumps(obj, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

    def send_file(self, path, ctype):
        if not os.path.exists(path):
            self.send_error(404)
            return
        size = os.path.getsize(path)
        start, end, code = 0, size - 1, 200
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            first, _, last = rng[6:].split(",")[0].strip().partition("-")
            if first:
                start = int(first)
                end = int(last) if last else size - 1
            else:
                start = max(0, size - int(last))
            end = min(end, size - 1)
            if start >= size or start > end:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            code = 206
        length = end - start + 1
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if code == 206:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        if self.command == "HEAD":
            return
        with open(path, "rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(65536, remaining))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return
                remaining -= len(chunk)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        p = u.path
        if p == "/__down":  # speed test download
            n = int(q.get("bytes", "0") or 0)
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(n))
            self.end_headers()
            chunk = b"\0" * 65536
            try:
                while n > 0:
                    k = min(n, len(chunk))
                    self.wfile.write(chunk[:k])
                    n -= k
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        if p == "/player_api.php":
            if q.get("username") != USER or q.get("password") != PASS:
                return self.send_json({"user_info": {"auth": 0}})
            action = q.get("action")
            if action is None:
                return self.send_json(user_info())
            if action == "get_live_categories":
                return self.send_json([{"category_id": c, "category_name": n, "parent_id": 0} for c, n in LIVE_CATS])
            if action == "get_live_streams":
                return self.send_json(live_streams())
            if action == "get_vod_categories":
                return self.send_json([{"category_id": c, "category_name": n, "parent_id": 0} for c, n in VOD_CATS])
            if action == "get_vod_streams":
                return self.send_json(vod_streams())
            if action == "get_series_categories":
                return self.send_json([{"category_id": c, "category_name": n, "parent_id": 0} for c, n in SERIES_CATS])
            if action == "get_series":
                return self.send_json(series_list())
            if action == "get_vod_info":
                return self.send_json(vod_info(q.get("vod_id")))
            if action == "get_series_info":
                return self.send_json(series_info(q.get("series_id")))
            return self.send_json([])
        if p == "/subtitles/search/advanced":
            return self.send_json(subtitle_search(q))
        if p.startswith("/subtitles/") and p.endswith("/download"):
            sid = p.split("/")[2]
            if sid not in SUBTITLES:
                return self.send_error(404)
            return self._send(subtitle_zip(sid), "application/zip")
        if p == "/xmltv.php":
            return self._send(xmltv().encode("utf-8"), "application/xml; charset=utf-8")
        if p in ("/playlist.m3u", "/get.php"):
            return self._send(m3u().encode("utf-8"), "audio/x-mpegurl; charset=utf-8")
        if p.startswith("/live/"):
            name = "live_multi.ts" if os.path.basename(p).startswith("201.") else "live.ts"
            return self.send_file(os.path.join(MEDIA, name), "video/mp2t")
        if p.startswith("/movie/") or p.startswith("/series/"):
            return self.send_file(os.path.join(MEDIA, "movie.mp4"), "video/mp4")
        if p.startswith("/logo/"):
            return self.send_file(os.path.join(MEDIA, os.path.basename(p)), "image/png")
        self.send_error(404)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print("mock IPTV server on port %d" % PORT, flush=True)
    server.serve_forever()
