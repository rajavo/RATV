#!/usr/bin/env python3
"""Test server for جدول RA كورة on the emulator (CI only).

Serves, from the folder given as first argument:
  /fixtures.json                                   the schedule written by smoke_kora.py
  /apis/site/v2/sports/soccer/<league>/scoreboard  espn_<league>.json there (live scores), else no events
  /update/version.json                             an old version, so no update is offered
"""
import json
import os
import struct
import sys
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

ROOT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8000


def make_png(size=48, rgb=(255, 159, 28)):
    """A plain square logo, so pictures load without the internet."""
    raw = b"".join(b"\x00" + bytes(rgb) * size for _ in range(size))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


PNG = make_png()


class Handler(BaseHTTPRequestHandler):
    def send(self, code, body, kind="application/json"):
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/fixtures.json":
            f = os.path.join(ROOT, "fixtures.json")
            if not os.path.exists(f):
                return self.send(404, '{"error":"no schedule yet"}')
            with open(f, "rb") as fh:
                return self.send(200, fh.read())
        if path.startswith("/apis/site/v2/sports/soccer/") and path.endswith("/scoreboard"):
            league = path.split("/")[-2]
            f = os.path.join(ROOT, "espn_%s.json" % league)
            if os.path.exists(f):
                with open(f, "rb") as fh:
                    return self.send(200, fh.read())
            return self.send(200, json.dumps({"events": []}))
        if path == "/update/version.json":
            return self.send(200, json.dumps({"versionCode": 0, "versionName": "1.0.0", "apk": "RAKora.apk"}))
        if path.startswith("/logo/"):
            return self.send(200, PNG, "image/png")
        self.send(404, '{"error":"not found"}')

    def log_message(self, fmt, *args):
        sys.stdout.write("%s %s\n" % (self.log_date_time_string(), fmt % args))
        sys.stdout.flush()


if __name__ == "__main__":
    os.makedirs(ROOT, exist_ok=True)
    print("serving %s on port %d" % (ROOT, PORT), flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
