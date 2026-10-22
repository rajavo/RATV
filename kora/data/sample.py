#!/usr/bin/env python3
"""Saves pages of other schedule sources, to study their layout while building the parsers (CI only)."""
import datetime as dt
import os
import sys
import urllib.request

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
browser = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36"
today = dt.datetime.now(dt.timezone.utc).date()
for n in (0, 1, 3, 9):
    d = today + dt.timedelta(days=n)
    url = "https://www.yallakora.com/match-center/?date=%d/%d/%d" % (d.month, d.day, d.year)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": browser, "Accept": "text/html"})
        with urllib.request.urlopen(req, timeout=30) as r:
            body = r.read()
            headers = dict(r.headers)
        with open(os.path.join(out, "yallakora-%s.html" % d.isoformat()), "wb") as f:
            f.write(body)
        print(url, len(body), headers.get("Content-Type"))
    except Exception as e:
        print(url, "failed:", e)
