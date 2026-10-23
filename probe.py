import json, time, urllib.request, gzip, os, re, datetime as dt
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36"
os.makedirs("out", exist_ok=True)
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*", "Accept-Encoding": "gzip"})
    with urllib.request.urlopen(req, timeout=25) as r:
        body = r.read()
        if r.headers.get("Content-Encoding") == "gzip":
            body = gzip.decompress(body)
        return r.status, dict(r.headers), body
day = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d")
log = open("out/log.jsonl", "w")
leagues = ["ksa.1", "caf.champions", "qat.1", "tur.1"]
t0 = time.time()
n = 0
while time.time() - t0 < 330:
    for lg in leagues if n % 4 == 0 else ["ksa.1"]:
        try:
            st, hd, body = get("https://site.api.espn.com/apis/site/v2/sports/soccer/%s/scoreboard" % lg)
            d = json.loads(body)
            if n == 0:
                open("out/scoreboard_%s.json" % lg, "wb").write(body)
            for ev in d.get("events") or []:
                comp = (ev.get("competitions") or [{}])[0]
                s = comp.get("status") or ev.get("status") or {}
                if (s.get("type") or {}).get("state") == "in" or n == 0:
                    log.write(json.dumps({"t": round(time.time(), 1), "lg": lg, "id": ev.get("id"), "name": ev.get("shortName"),
                        "status": s, "evstatus": ev.get("status"),
                        "scores": [(c.get("homeAway"), c.get("score")) for c in comp.get("competitors") or []],
                        "age": hd.get("Age"), "cache": hd.get("Cache-Control"), "bytes": len(body)}, ensure_ascii=False) + "\n")
        except Exception as e:
            log.write(json.dumps({"t": time.time(), "lg": lg, "error": str(e)}) + "\n")
    if n % 4 == 0:
        try:
            st, hd, body = get("https://site.api.espn.com/apis/site/v2/sports/soccer/ksa.1/summary?event=401900444")
            open("out/summary_%d.json" % n, "wb").write(body)
        except Exception as e:
            log.write(json.dumps({"t": time.time(), "summary_error": str(e)}) + "\n")
        try:
            now = dt.datetime.now(dt.timezone(dt.timedelta(hours=2)))
            st, hd, body = get("https://www.yallakora.com/match-center/?date=%d/%d/%d" % (now.month, now.day, now.year))
            open("out/yk_%d.html" % n, "wb").write(body)
            log.write(json.dumps({"t": round(time.time(), 1), "yk_bytes": len(body), "yk_headers": {k: v for k, v in hd.items() if k.lower() in ("cache-control", "age", "date", "last-modified", "x-cache", "cf-cache-status", "expires")}}) + "\n")
        except Exception as e:
            log.write(json.dumps({"t": time.time(), "yk_error": str(e)}) + "\n")
    log.flush()
    n += 1
    time.sleep(15)
