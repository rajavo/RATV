import gzip, json, os, re, urllib.request, urllib.parse
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36"
os.makedirs("out", exist_ok=True)
log = open("out/log.txt", "w", encoding="utf-8")
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "text/html,application/json,*/*", "Accept-Language": "ar", "Accept-Encoding": "gzip"})
    with urllib.request.urlopen(req, timeout=25) as r:
        body = r.read()
        if r.headers.get("Content-Encoding") == "gzip":
            body = gzip.decompress(body)
        return r.status, r.geturl(), body
base = "https://www.kooora.com"
q = urllib.parse.quote
try:
    st, u, js = get(base + "/_next/static/chunks/pages/%5Bsports-category%5D/live-scores-657e22780d024f8a.js")
    js = js.decode("utf-8", "replace")
    open("out/live-scores.js", "w").write(js)
    for kw in ["date", "dayType", "tomorrow", "yesterday", "push(", "href"]:
        for m in list(re.finditer(re.escape(kw), js))[:8]:
            i = m.start()
            log.write("JS %s :: %s\n" % (kw, js[max(0, i - 160):i + 160].replace("\n", " ")))
except Exception as e:
    log.write("js error %s\n" % e)
cands = [
    "/%s/%s" % (q("كرة-القدم"), q("مباريات-اليوم")),
    "/%s/%s" % (q("كرة-القدم"), q("مباريات-الغد")),
    "/%s/%s" % (q("كرة-القدم"), q("مباريات-غدا")),
    "/%s/%s?date=2026-10-24" % (q("كرة-القدم"), q("مباريات-اليوم")),
    "/%s/%s/2026-10-24" % (q("كرة-القدم"), q("مباريات")),
    "/%s/%s?date=2026-10-24" % (q("كرة-القدم"), q("مباريات")),
    "/football/live-scores?date=2026-10-24",
    "/%s/live-scores?date=2026-10-24" % q("كرة-القدم"),
]
for c in cands:
    try:
        st, u, body = get(base + c)
        h = body.decode("utf-8", "replace")
        m = re.search(r'<script id="__NEXT_DATA__" type="application/json"[^>]*>(.*?)</script>', h, re.S)
        info = ""
        if m:
            d = json.loads(m.group(1))
            pp = d.get("props", {}).get("pageProps", {})
            data = pp.get("data") or []
            n = sum(len(b.get("matches") or []) for b in data) if isinstance(data, list) else -1
            first = next((mt.get("startDate") for b in data for mt in (b.get("matches") or [])), None) if isinstance(data, list) else None
            info = "page=%s date=%s dayType=%s matches=%s first=%s" % (d.get("page"), pp.get("paramDateString"), pp.get("dayType"), n, first)
        log.write("URL %s -> %s %s %d bytes %s\n" % (urllib.parse.unquote(c), st, urllib.parse.unquote(u), len(body), info))
    except Exception as e:
        log.write("URL %s -> error %s\n" % (urllib.parse.unquote(c), e))
log.close()
