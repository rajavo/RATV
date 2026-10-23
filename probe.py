import gzip, json, os, re, time, urllib.request, datetime as dt
from zoneinfo import ZoneInfo
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36"
os.makedirs("out", exist_ok=True)
log = open("out/log.txt", "w", encoding="utf-8")
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "text/html,application/json,*/*", "Accept-Language": "ar,en;q=0.8", "Accept-Encoding": "gzip"})
    with urllib.request.urlopen(req, timeout=20) as r:
        body = r.read()
        if r.headers.get("Content-Encoding") == "gzip":
            body = gzip.decompress(body)
        return r.status, dict(r.headers), body
def save(name, body):
    with gzip.open("out/" + name + ".gz", "wb") as f:
        f.write(body)
def ctx(text, kws, width=260, limit=6):
    out = []
    for kw in kws:
        for m in list(re.finditer(kw, text))[:limit]:
            i = m.start()
            out.append(kw + " :: " + re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", text[max(0, i - width):i + width])))
    return out
cairo = dt.datetime.now(ZoneInfo("Africa/Cairo"))
day_url = "https://www.yallakora.com/match-center/?date=%d/%d/%d" % (cairo.month, cairo.day, cairo.year)
st, hd, body = get(day_url)
html = body.decode("utf-8", "replace")
save("yk_day.html", body)
cards = []
for block in re.split(r'<div class="\d+ matchCard matchesList">', html)[1:]:
    tour = re.search(r'<img src="[^"]*" alt="([^"]*)" enName="([^"]*)"', block)
    for card in block.split('liveScoreMatchId="')[1:]:
        mid = card[:card.find('"')]
        href = re.search(r'<a href="([^"#]+)', card)
        names = [n.strip() for n in re.findall(r"<p>\s*([^<]+?)\s*</p>", card)][:2]
        when = re.search(r'<span class="time">\s*([^<]*?)\s*</span>', card)
        cards.append({"id": mid, "href": href.group(1) if href else "", "names": names, "tour": tour.group(2) if tour else "", "tour_ar": tour.group(1) if tour else "", "time": when.group(1) if when else ""})
log.write("cards: %d\n" % len(cards))
json.dump(cards, open("out/cards.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
pref = ["ksa", "qatar", "epl", "la-liga", "uae", "serie", "bundes", "ligue", "egypt", "afc", "african", "uefa"]
cards.sort(key=lambda c: next((i for i, p in enumerate(pref) if p in c["tour"].lower()), 99))
found = {}
for c in cards[:28]:
    if not c["href"]:
        continue
    url = "https://www.yallakora.com" + c["href"]
    try:
        st, hd, body = get(url)
        page = body.decode("utf-8", "replace")
        if len(found) < 6:
            save("yk_match_%s.html" % c["id"], body)
        hits = ctx(page, ["القناة", "المعلق", "قناة", "beIN", "SSC", "Alkass", "الكأس", "AD Sports", "أبوظبي", "channel"], 200, 3)
        found[c["id"]] = {"card": c, "bytes": len(body), "hits": hits}
        log.write("== %s %s %s | %s\n" % (c["id"], c["tour"], " x ".join(c["names"]), url))
        for h in hits[:12]:
            log.write("   " + h[:500] + "\n")
    except Exception as e:
        log.write("!! %s %s\n" % (url, e))
    time.sleep(0.5)
json.dump(found, open("out/match_pages.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
for name, url in [("bein_tvguide_ar", "https://www.beinsports.com/ar-mena/tv-guide"),
                  ("bein_tvguide_en", "https://www.beinsports.com/en-mena/tv-guide"),
                  ("filgoal_day", "https://www.filgoal.com/matches/?date=%s" % cairo.strftime("%Y-%m-%d")),
                  ("kooora_day", "https://www.kooora.com/?region=-1&area=0")]:
    try:
        st, hd, body = get(url)
        save(name + ".html", body)
        page = body.decode("utf-8", "replace")
        log.write("== %s %s %d bytes\n" % (name, st, len(body)))
        for h in ctx(page, ["beIN SPORTS", "beIN Sports", "القناة", "SSC", "__NEXT_DATA__", "api/", "channel"], 200, 3)[:14]:
            log.write("   " + h[:500] + "\n")
    except Exception as e:
        log.write("!! %s %s\n" % (name, e))
log.close()
