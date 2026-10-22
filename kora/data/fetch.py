#!/usr/bin/env python3
"""Builds the schedule of «جدول RA كورة»: football matches from yesterday to 31 days ahead.

The matches come from ESPN's public scoreboard, one request per league and day (leagues.json
lists the leagues, with their Arabic names). Team names in Arabic come from names_ar.json;
teams that still have no Arabic name are listed in report.json, to be added there.

    fetch.py OUT_DIR [PREVIOUS_FIXTURES_JSON]

Writes OUT_DIR/fixtures.json (read by the app) and OUT_DIR/report.json. When some requests of a
league fail, its matches from the previous fixtures.json are kept, so a bad hour at ESPN never
empties the schedule.
"""
import concurrent.futures
import datetime as dt
import json
import os
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.request
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
SCOREBOARD = "https://site.api.espn.com/apis/site/v2/sports/soccer/%s/scoreboard?dates=%s"
LEAGUE_LIST = "https://sports.core.api.espn.com/v2/sports/soccer/leagues?limit=1000"
UA = "Mozilla/5.0 (X11; Linux x86_64) RA-Kora-schedule"
DAYS_BACK = 1
DAYS_AHEAD = 31
WORKERS = 12
LOGO_PX = 160


class Invalid(Exception):
    """ESPN has no such league."""


BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36"


def get(url, tries=4, ua=UA):
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": ua, "Accept": "*/*"})
            with urllib.request.urlopen(req, timeout=25) as r:
                return r.status, dict(r.headers), r.read()
        except urllib.error.HTTPError as e:
            if e.code in (400, 404):
                raise Invalid("HTTP %d" % e.code)
            last = e
        except Exception as e:  # timeouts and dropped connections: try again
            last = e
        time.sleep(1.5 * (i + 1))
    raise last


def get_json(url):
    return json.loads(get(url)[2].decode("utf-8"))


# ------------------------------------------------------------------ names

SPECIAL = str.maketrans({"ø": "o", "Ø": "o", "æ": "ae", "Æ": "ae", "ß": "ss", "ł": "l", "Ł": "l",
                         "đ": "d", "Đ": "d", "ı": "i", "œ": "oe", "þ": "th", "ð": "d"})
CLUB_WORDS = {"fc", "cf", "sc", "afc", "ac", "as", "ssc", "cd", "ud", "rcd", "sd", "ca", "sv", "fk", "sk", "nk",
              "bk", "if", "club", "de", "1", "the", "sfc", "cfc", "fsv", "tsg", "vfl", "vfb", "rc", "sl", "kv", "kaa",
              "krc", "rsc", "ssd", "aris", "us", "acf", "calcio"}


def norm(s):
    s = unicodedata.normalize("NFKD", (s or "").translate(SPECIAL))
    s = "".join(c for c in s if not unicodedata.combining(c)).lower().replace("&", " and ")
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def core(s):
    n = norm(s)
    w = [x for x in n.split() if x not in CLUB_WORDS]
    return " ".join(w) or n


with open(os.path.join(HERE, "names_ar.json"), encoding="utf-8") as f:
    _names = json.load(f)
BY_ID = {str(k): v for k, v in _names.get("by_id", {}).items()}
BY_NAME = {norm(k): v for k, v in _names.get("by_name", {}).items()}
BY_CORE = {}
for _k, _v in _names.get("by_name", {}).items():
    BY_CORE.setdefault(core(_k), _v)


def arabic(team):
    tid = str(team.get("id") or "")
    if tid in BY_ID:
        return BY_ID[tid]
    keys = [team.get("displayName"), team.get("shortDisplayName"), team.get("name"), team.get("location")]
    for k in keys:
        if k and norm(k) in BY_NAME:
            return BY_NAME[norm(k)]
    for k in keys:
        if k and core(k) in BY_CORE:
            return BY_CORE[core(k)]
    return None


LOGOS_SMALL = [False]


def logo(url):
    """A small copy of an ESPN logo (the originals are 500 px)."""
    if not url:
        return ""
    if LOGOS_SMALL[0] and url.startswith("https://a.espncdn.com/i/"):
        return "https://a.espncdn.com/combiner/i?img=%s&w=%d&h=%d" % (url[len("https://a.espncdn.com"):], LOGO_PX, LOGO_PX)
    return url


# ------------------------------------------------------------------ matches

def ts(s):
    if not s:
        return None
    try:
        return int(dt.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp())
    except ValueError:
        return None


def score(side):
    s = side.get("score")
    if isinstance(s, dict):
        s = s.get("displayValue", s.get("value"))
    try:
        return int(float(s))
    except (TypeError, ValueError):
        return None


def team_of(side, teams, league_id):
    t = side.get("team") or {}
    tid = str(t.get("id") or "")
    if not tid:
        return None
    entry = teams.get(tid)
    if entry is None:
        url = t.get("logo") or ((t.get("logos") or [{}])[0].get("href"))
        entry = {
            "en": t.get("displayName") or t.get("name") or "?",
            "s": t.get("shortDisplayName") or "",
            "ar": arabic(t),
            "logo": url or "",
            "leagues": set(),
        }
        teams[tid] = entry
    entry["leagues"].add(league_id)
    return tid


def parse(ev, league_id, teams):
    comp = (ev.get("competitions") or [{}])[0]
    sides = comp.get("competitors") or []
    home = next((c for c in sides if c.get("homeAway") == "home"), None)
    away = next((c for c in sides if c.get("homeAway") == "away"), None)
    if (home is None or away is None) and len(sides) == 2:
        home, away = sides
    if home is None or away is None:
        return None
    when = ts(comp.get("date") or ev.get("date"))
    h, a = team_of(home, teams, league_id), team_of(away, teams, league_id)
    if when is None or not h or not a:
        return None
    status = comp.get("status") or ev.get("status") or {}
    kind = status.get("type") or {}
    m = {
        "id": str(ev.get("id")),
        "lg": league_id,
        "t": when,
        "h": h,
        "a": a,
        "st": kind.get("state") or "pre",
        "sn": (kind.get("name") or "").replace("STATUS_", ""),
    }
    if comp.get("timeValid") is False:
        m["tbd"] = 1
    if m["st"] != "pre":
        hs, as_ = score(home), score(away)
        if hs is not None and as_ is not None:
            m["hs"], m["as"] = hs, as_
        hp, ap = home.get("shootoutScore"), away.get("shootoutScore")
        if hp is not None and ap is not None:
            m["hp"], m["ap"] = hp, ap
    if m["st"] == "in" and status.get("displayClock"):
        m["clk"] = status["displayClock"]
    venue = comp.get("venue") or {}
    if venue.get("fullName"):
        m["v"] = venue["fullName"]
    city = (venue.get("address") or {}).get("city")
    if city:
        m["c"] = city
    note = next((n.get("headline") for n in (comp.get("notes") or []) if n.get("headline")), None)
    if note:
        m["n"] = note
    return m


def fetch_day(league_id, day):
    try:
        data = get_json(SCOREBOARD % (league_id, day.strftime("%Y%m%d")))
    except Invalid as e:
        return "invalid", str(e)
    except Exception as e:
        return "error", "%s: %s" % (type(e).__name__, e)
    if not data.get("leagues"):
        return "invalid", "no league in the answer"
    return "ok", data


# ------------------------------------------------------------------ Yallakora, for the Arab leagues ESPN lacks

YK_URL = "https://www.yallakora.com/match-center/?date=%d/%d/%d"
YK_ZONE = ZoneInfo("Africa/Cairo")  # the times on the page are Cairo's


def yk_league(en, ar, yk_leagues):
    en, ar = (en or "").strip().lower(), (ar or "").strip()
    for lg in yk_leagues:
        if ar in lg.get("yk_ar", []) or any(w in en for w in lg.get("yk_en", [])):
            return lg["id"]
    return None


def yk_clean(s):
    return re.sub(r"\s+", " ", (s or "").replace("ـ", "")).strip()


def yk_day(day):
    try:
        return "ok", get(YK_URL % (day.month, day.day, day.year), ua=BROWSER)[2].decode("utf-8", "replace")
    except Exception as e:
        return "error", "%s: %s" % (type(e).__name__, e)


def yk_parse(html, day, teams, yk_leagues, yk_logos):
    """Matches of one day's page, of the leagues listed with yk_en / yk_ar in leagues.json."""
    out = []
    blocks = re.split(r'<div class="(\d+) matchCard matchesList">', html)
    for k in range(1, len(blocks) - 1, 2):
        block = blocks[k + 1]
        tour = re.search(r'<img src="([^"]*)" alt="([^"]*)" enName="([^"]*)"', block)
        lg = yk_league(tour.group(3), tour.group(2), yk_leagues) if tour else None
        if not lg:
            continue
        yk_logos.setdefault(lg, tour.group(1).replace("\\", "/"))
        for card in block.split('liveScoreMatchId="')[1:]:
            mid = card[:card.find('"')]
            kind = re.search(r'class="item ([^"]*?) ?liItem"', card[:300])
            kind = kind.group(1).strip() if kind else ""
            status = re.search(r'matchStatus">\s*<span>\s*([^<]*?)\s*</span>', card)
            status = yk_clean(status.group(1)) if status else ""
            names = [yk_clean(n) for n in re.findall(r"<p>\s*([^<]+?)\s*</p>", card)]
            logos = re.findall(r'<img src="([^"]+)"', card)
            when = re.search(r'<span class="time">\s*(\d{1,2}):(\d{2})\s*</span>', card)
            scores = re.findall(r'<span class="score">\s*([^<]*?)\s*</span>', card)
            round_ = re.search(r'<div class="date">\s*([^<]*?)\s*</div>', card)
            if not mid.isdigit() or len(names) < 2:
                continue
            if when:
                local = dt.datetime(day.year, day.month, day.day, int(when.group(1)), int(when.group(2)), tzinfo=YK_ZONE)
                t, tbd = int(local.timestamp()), False
            else:
                t, tbd = int(dt.datetime(day.year, day.month, day.day, 12, tzinfo=YK_ZONE).timestamp()), True
            ids = []
            for side in range(2):
                tid = "y:" + names[side]
                if tid not in teams:
                    url = logos[side].replace("\\", "/") if side < len(logos) else ""
                    teams[tid] = {"en": "", "s": "", "ar": names[side], "logo": url, "leagues": set(), "yk": True}
                teams[tid]["leagues"].add(lg)
                ids.append(tid)
            if "finish" in kind or "end" in kind or "انته" in status:
                state, name = "post", "FULL_TIME"
            elif "now" in kind or "live" in kind or "جار" in status or "مباشر" in status or "الشوط" in status:
                state, name = "in", "IN_PROGRESS"
            else:
                state, name = "pre", "SCHEDULED"
            if "تأجل" in status or "مؤجل" in status:
                state, name = "post", "POSTPONED"
            elif "ألغي" in status or "الغاء" in status or "إلغاء" in status:
                state, name = "post", "CANCELED"
            m = {"id": "y" + mid, "lg": lg, "t": t, "h": ids[0], "a": ids[1], "st": state, "sn": name}
            if tbd:
                m["tbd"] = 1
            if state != "pre" and len(scores) >= 2 and scores[0].isdigit() and scores[1].isdigit():
                m["hs"], m["as"] = int(scores[0]), int(scores[1])
            if round_ and yk_clean(round_.group(1)):
                m["n"] = yk_clean(round_.group(1))
            out.append(m)
    return out


# ------------------------------------------------------------------ checks for the report

def discover_leagues():
    try:
        items = get_json(LEAGUE_LIST).get("items") or []
        slugs = sorted({m.group(1) for it in items for m in [re.search(r"/leagues/([^/?]+)", it.get("$ref", ""))] if m})
        return slugs
    except Exception as e:
        return ["error: %s" % e]


def probe_logo(sample):
    if not sample or not sample.startswith("https://a.espncdn.com/i/"):
        return {"sample": sample, "small": False}
    small = "https://a.espncdn.com/combiner/i?img=%s&w=%d&h=%d" % (sample[len("https://a.espncdn.com"):], LOGO_PX, LOGO_PX)
    out = {"sample": sample, "small_url": small}
    try:
        st, hd, body = get(sample)
        out["original"] = [st, hd.get("Content-Type"), len(body)]
        st2, hd2, body2 = get(small)
        out["combiner"] = [st2, hd2.get("Content-Type"), len(body2)]
        out["small"] = st2 == 200 and (hd2.get("Content-Type") or "").startswith("image/") and 0 < len(body2) < len(body)
    except Exception as e:
        out["error"] = str(e)
        out["small"] = False
    return out


# ------------------------------------------------------------------ main

def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    previous = None
    if len(sys.argv) > 2 and os.path.exists(sys.argv[2]):
        try:
            with open(sys.argv[2], encoding="utf-8") as f:
                previous = json.load(f)
        except Exception as e:
            print("previous fixtures unreadable: %s" % e)
    with open(os.path.join(HERE, "leagues.json"), encoding="utf-8") as f:
        all_leagues = json.load(f)
    order = {lg["id"]: i for i, lg in enumerate(all_leagues)}
    leagues = [lg for lg in all_leagues if lg.get("source", "espn") == "espn"]
    yk_leagues = [lg for lg in all_leagues if lg.get("source") == "yallakora"]
    started = time.time()
    today = dt.datetime.now(dt.timezone.utc).date()
    days = [today + dt.timedelta(days=d) for d in range(-DAYS_BACK, DAYS_AHEAD + 1)]

    results = {}
    yk_pages = {}
    with concurrent.futures.ThreadPoolExecutor(WORKERS) as pool:
        yk_jobs = {pool.submit(yk_day, d): d for d in days} if yk_leagues else {}
        # First today's request of every league: a league ESPN does not know is not asked again.
        first = {pool.submit(fetch_day, lg["id"], today): lg["id"] for lg in leagues}
        for f in concurrent.futures.as_completed(first):
            results[(first[f], today)] = f.result()
        valid = [lg["id"] for lg in leagues if results[(lg["id"], today)][0] != "invalid"]
        rest = {pool.submit(fetch_day, lg_id, d): (lg_id, d) for lg_id in valid for d in days if d != today}
        for f in concurrent.futures.as_completed(rest):
            results[rest[f]] = f.result()
        for f in concurrent.futures.as_completed(yk_jobs):
            yk_pages[yk_jobs[f]] = f.result()

    events = {}  # (league, event id) -> event
    seen_day = {}  # event id -> days whose request returned it
    meta = {}
    errors = []
    failed_leagues = set()
    for (lg_id, day), (state, payload) in sorted(results.items(), key=lambda kv: (kv[0][0], kv[0][1])):
        if state == "error":
            failed_leagues.add(lg_id)
            errors.append("%s %s %s" % (lg_id, day, payload))
            continue
        if state != "ok":
            continue
        meta.setdefault(lg_id, payload["leagues"][0])
        for ev in payload.get("events") or []:
            eid = str(ev.get("id"))
            events[(lg_id, eid)] = ev
            seen_day.setdefault(eid, set()).add(day)

    # Small logos, if ESPN's resizer answers.
    sample = next((url for ev in events.values()
                   for side in ((ev.get("competitions") or [{}])[0].get("competitors") or [])
                   for url in [(side.get("team") or {}).get("logo")]
                   if url and url.startswith("https://a.espncdn.com/i/")), None)
    logo_probe = probe_logo(sample)
    LOGOS_SMALL[0] = bool(logo_probe.get("small"))

    teams = {}
    matches = {}
    for (lg_id, eid), ev in events.items():
        m = parse(ev, lg_id, teams)
        if m:
            matches[(lg_id, m["id"])] = m

    # The Arab leagues ESPN lacks, from Yallakora's day pages.
    yk_errors = []
    yk_count = 0
    yk_logos = {}
    for day, (state, html) in sorted(yk_pages.items()):
        if state != "ok":
            yk_errors.append("%s %s" % (day, html))
            continue
        try:
            for m in yk_parse(html, day, teams, yk_leagues, yk_logos):
                matches[(m["lg"], m["id"])] = m
                yk_count += 1
        except Exception as e:
            yk_errors.append("%s parse: %r" % (day, e))
    if yk_leagues and (yk_errors or yk_count == 0):
        failed_leagues.update(lg["id"] for lg in yk_leagues)
    errors.extend("yallakora " + e for e in yk_errors)

    # Requests that failed: keep that league's coming matches from the last good schedule.
    kept = 0
    if previous and failed_leagues:
        now = int(time.time())
        old_teams = previous.get("teams") or {}
        for m in previous.get("matches") or []:
            key = (m.get("lg"), str(m.get("id")))
            if m.get("lg") in failed_leagues and key not in matches and m.get("t", 0) >= now - 3 * 3600:
                matches[key] = m
                kept += 1
                for tid in (m.get("h"), m.get("a")):
                    if tid not in teams and tid in old_teams:
                        o = old_teams[tid]
                        teams[tid] = {"en": o.get("en", "?"), "s": o.get("s", ""), "ar": o.get("ar"),
                                      "logo": o.get("logo", ""), "leagues": {m["lg"]}, "kept": True}

    match_list = sorted(matches.values(), key=lambda m: (m["t"], order.get(m["lg"], 99), m["id"]))
    used = {m["lg"] for m in match_list}
    league_out = []
    for lg in all_leagues:
        if lg["id"] not in used:
            continue
        info = meta.get(lg["id"]) or {}
        lg_logo = ((info.get("logos") or [{}])[0]).get("href", "") or yk_logos.get(lg["id"], "")
        prev_lg = next((p for p in (previous or {}).get("leagues", []) if p.get("id") == lg["id"]), {})
        league_out.append({
            "id": lg["id"],
            "ar": lg["ar"],
            "short": lg["short"],
            "en": info.get("name") or lg.get("en") or prev_lg.get("en", ""),
            "logo": logo(lg_logo) if lg_logo else prev_lg.get("logo", ""),
        })
    team_out = {}
    for tid, t in teams.items():
        o = {"en": t["en"], "logo": t["logo"] if t.get("kept") else logo(t["logo"])}
        if t.get("ar"):
            o["ar"] = t["ar"]
        if t.get("s") and t["s"] != t["en"]:
            o["s"] = t["s"]
        team_out[tid] = o

    fixtures = {
        "v": 1,
        "updated": int(time.time()),
        "from": days[0].isoformat(),
        "to": days[-1].isoformat(),
        "leagues": league_out,
        "teams": team_out,
        "matches": match_list,
    }
    with open(os.path.join(out_dir, "fixtures.json"), "w", encoding="utf-8") as f:
        json.dump(fixtures, f, ensure_ascii=False, separators=(",", ":"))

    # ------------------------------------------------ report
    et = ZoneInfo("America/New_York")
    same_utc = same_et = total = 0
    for (lg_id, eid), ev in events.items():
        when = ts(ev.get("date"))
        got = seen_day.get(eid) or set()
        if when is None or len(got) != 1:
            continue
        d = next(iter(got))
        total += 1
        same_utc += dt.datetime.fromtimestamp(when, dt.timezone.utc).date() == d
        same_et += dt.datetime.fromtimestamp(when, et).date() == d
    per_league = []
    for lg in leagues:
        states = [results.get((lg["id"], d), ("skipped", ""))[0] for d in days]
        status = "invalid" if results[(lg["id"], today)][0] == "invalid" else (
            "ok" if all(s == "ok" for s in states) else "partial")
        per_league.append({
            "id": lg["id"], "ar": lg["ar"], "status": status,
            "espn": (meta.get(lg["id"]) or {}).get("name"),
            "matches": sum(1 for m in match_list if m["lg"] == lg["id"]),
            "failed_days": sum(1 for s in states if s == "error"),
            "why": results[(lg["id"], today)][1] if status == "invalid" else "",
        })
    for lg in yk_leagues:
        per_league.append({
            "id": lg["id"], "ar": lg["ar"], "status": "partial" if yk_errors else "ok", "espn": "(yallakora)",
            "matches": sum(1 for m in match_list if m["lg"] == lg["id"]), "failed_days": len(yk_errors), "why": "",
        })
    report = {
        "updated": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "seconds": round(time.time() - started, 1),
        "requests": len(results),
        "errors": errors[:40],
        "error_count": len(errors),
        "kept_from_previous": kept,
        "matches": len(match_list),
        "teams_total": len(team_out),
        "teams_without_arabic": sorted(
            ({"id": tid, "en": t["en"], "s": t.get("s", ""), "leagues": sorted(t["leagues"])}
             for tid, t in teams.items() if not t.get("ar")),
            key=lambda x: (x["leagues"], x["en"])),
        "leagues": per_league,
        "day_grouping": {"events": total, "same_utc_date": same_utc, "same_new_york_date": same_et},
        "logo_probe": logo_probe,
        "yallakora": {"pages": len(yk_pages), "matches": yk_count, "errors": yk_errors[:10]},
        "espn_leagues": discover_leagues(),
        "teams": sorted(({"id": tid, "en": t["en"], "ar": t.get("ar"), "leagues": sorted(t["leagues"])}
                         for tid, t in teams.items()), key=lambda x: (x["leagues"], x["en"])),
    }
    with open(os.path.join(out_dir, "report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)

    print("matches: %d, teams: %d (no Arabic name: %d), requests: %d, errors: %d, kept: %d, %.0f s" % (
        len(match_list), len(team_out), len(report["teams_without_arabic"]), len(results), len(errors), kept,
        time.time() - started))
    for lg in per_league:
        print("  %-22s %-8s %3d matches %s" % (lg["id"], lg["status"], lg["matches"], lg["why"]))
    if not match_list:
        print("no matches at all: not publishing")
        sys.exit(1)


if __name__ == "__main__":
    main()
