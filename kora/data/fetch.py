#!/usr/bin/env python3
"""Builds the schedule of «جدول RA كورة»: football matches from yesterday to 31 days ahead.

The matches come from ESPN's public scoreboard, one request per league and day (leagues.json
lists the leagues, with their Arabic names). Team names in Arabic come from names_ar.json;
teams that still have no Arabic name are listed in report.json, to be added there.

    fetch.py OUT_DIR [PREVIOUS_FIXTURES_JSON]

Writes OUT_DIR/fixtures.json (read by the app), OUT_DIR/players_ar.json (players' names in Arabic,
grown run after run from the previous one beside PREVIOUS_FIXTURES_JSON) and OUT_DIR/report.json. When some requests of a
league fail, its matches from the previous fixtures.json are kept, so a bad hour at ESPN never
empties the schedule.
"""
import concurrent.futures
import datetime as dt
import difflib
import json
import os
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
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


def yk_cards(html, day):
    """Every match card of one day's page, whatever the competition."""
    blocks = re.split(r'<div class="(\d+) matchCard matchesList">', html)
    for k in range(1, len(blocks) - 1, 2):
        block = blocks[k + 1]
        tour = re.search(r'<img src="([^"]*)" alt="([^"]*)" enName="([^"]*)"', block)
        if not tour:
            continue
        for card in block.split('liveScoreMatchId="')[1:]:
            mid = card[:card.find('"')]
            kind = re.search(r'class="item ([^"]*?) ?liItem"', card[:300])
            status = re.search(r'matchStatus">\s*<span>\s*([^<]*?)\s*</span>', card)
            names = [yk_clean(n) for n in re.findall(r"<p>\s*([^<]+?)\s*</p>", card)]
            logos = [u.replace("\\", "/") for u in re.findall(r'<img src="([^"]+)"', card)]
            when = re.search(r'<span class="time">\s*(\d{1,2}):(\d{2})\s*</span>', card)
            round_ = re.search(r'<div class="date">\s*([^<]*?)\s*</div>', card)
            if not mid.isdigit() or len(names) < 2:
                continue
            if when:
                local = dt.datetime(day.year, day.month, day.day, int(when.group(1)), int(when.group(2)), tzinfo=YK_ZONE)
                t, tbd = int(local.timestamp()), False
            else:
                t, tbd = int(dt.datetime(day.year, day.month, day.day, 12, tzinfo=YK_ZONE).timestamp()), True
            yield {
                "tour_en": tour.group(3), "tour_ar": tour.group(2), "tour_logo": tour.group(1).replace("\\", "/"),
                "mid": mid, "kind": kind.group(1).strip() if kind else "", "status": yk_clean(status.group(1)) if status else "",
                "names": names[:2], "logos": (logos + ["", ""])[:2], "t": t, "tbd": tbd,
                "scores": re.findall(r'<span class="score">\s*([^<]*?)\s*</span>', card),
                "round": yk_clean(round_.group(1)) if round_ else "",
            }


def yk_match(c, lg, teams):
    """A schedule entry from one card of a league ESPN lacks."""
    ids = []
    for side in range(2):
        tid = "y:" + c["names"][side]
        if tid not in teams:
            teams[tid] = {"en": "", "s": "", "ar": c["names"][side], "logo": c["logos"][side], "leagues": set(), "yk": True}
        teams[tid]["leagues"].add(lg)
        ids.append(tid)
    kind, status = c["kind"], c["status"]
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
    m = {"id": "y" + c["mid"], "lg": lg, "t": c["t"], "h": ids[0], "a": ids[1], "st": state, "sn": name}
    if c["tbd"]:
        m["tbd"] = 1
    sc = c["scores"]
    if state != "pre" and len(sc) >= 2 and sc[0].isdigit() and sc[1].isdigit():
        m["hs"], m["as"] = int(sc[0]), int(sc[1])
    if c["round"]:
        m["n"] = c["round"]
    return m


# Yallakora competitions that ESPN also has, for learning Arabic names of new teams.
YK_TO_ESPN = {
    "epl": "eng.1", "la-liga": "esp.1", "serie-a": "ita.1", "bundesliga": "ger.1", "ligue1": "fra.1",
    "primeira-liga": "por.1", "turkish super lig": "tur.1", "europa-league": "uefa.europa",
    "uefa europa conference league": "uefa.europa.conf", "african-champions-league": "caf.champions",
    "african-confederation-cup": "caf.confed", "ksa-league": "ksa.1", "uefa": "uefa.champions",
    "carabao cup": "eng.league_cup", "can-qualifiers": "caf.nations_qual",
}


def yk_espn_league(en):
    en = (en or "").strip().lower()
    if en in YK_TO_ESPN:
        return YK_TO_ESPN[en]
    if "champions" in en and "afric" not in en:
        return "afc.champions" if ("asia" in en or "afc" in en) else "uefa.champions"
    return None


def ar_words(s):
    s = re.sub("[\u064B-\u065F\u0670\u0640]", "", s or "")
    for a, b in (("أ", "ا"), ("إ", "ا"), ("آ", "ا"), ("ة", "ه"), ("ى", "ي")):
        s = s.replace(a, b)
    return {w[2:] if w.startswith("ال") and len(w) > 3 else w for w in re.split(r"[\s\-.]+", s) if w}


def same_team(a, b):
    wa, wb = ar_words(a), ar_words(b)
    return bool(wa) and bool(wb) and (wa <= wb or wb <= wa)


def learn_names(matches, teams, cards):
    """
    Arabic names for ESPN teams that have none yet (a new club in a cup draw, say), from the same
    match on Yallakora: same competition, same kick-off, and the other team already known.
    """
    by_minute = {}
    for c in cards:
        lg = yk_espn_league(c["tour_en"])
        if lg and not c["tbd"]:
            by_minute.setdefault((lg, c["t"] // 60), []).append(c)
    learned = {}
    for m in matches.values():
        if m["id"].startswith("y"):
            continue
        h, a = teams.get(m["h"]), teams.get(m["a"])
        if not h or not a or bool(h.get("ar")) == bool(a.get("ar")):
            continue
        near = [c for d in range(-10, 11) for c in by_minute.get((m["lg"], m["t"] // 60 + d), [])]
        for c in near:
            yh, ya = c["names"]
            if h.get("ar") and same_team(h["ar"], yh) and not same_team(h["ar"], ya):
                learned[m["a"]] = ya
            elif a.get("ar") and same_team(a["ar"], ya) and not same_team(a["ar"], yh):
                learned[m["h"]] = yh
    for tid, name in learned.items():
        teams[tid]["ar"] = name
    return learned


# ------------------------------------------------------------------ Kooora: the channels that show each match

# Today's matches with the TV channels showing them ("beIN Sports Mena 1", "ALKASS Five", "Thmanyah"…),
# for RA TV's own guide of the sports channels: a channel whose provider guide says nothing still
# shows the match it carries, and its scoreboard. The page is today's by the clock of the machine
# asking (New York's on GitHub), which covers the matches of the Gulf's day.
KOOORA_URL = "https://www.kooora.com/%D9%83%D8%B1%D8%A9-%D8%A7%D9%84%D9%82%D8%AF%D9%85/%D9%85%D8%A8%D8%A7%D8%B1%D9%8A%D8%A7%D8%AA-%D8%A7%D9%84%D9%8A%D9%88%D9%85"


KOOORA_SAMPLES = []  # raw matches, in report.json, to see what Kooora gives


def kooora_day():
    try:
        html = get(KOOORA_URL, ua=BROWSER)[2].decode("utf-8", "replace")
    except Exception as e:
        return "error", "%s: %s" % (type(e).__name__, e)
    m = re.search(r'<script id="__NEXT_DATA__" type="application/json"[^>]*>(.*?)</script>', html, re.S)
    if not m:
        return "error", "no match data in the page"
    try:
        data = (json.loads(m.group(1)).get("props") or {}).get("pageProps", {}).get("data") or []
    except ValueError as e:
        return "error", "unreadable match data: %s" % e
    out = []
    for block in data:
        comp = (block.get("competition") or {}).get("name") or ""
        for mt in block.get("matches") or []:
            tv = [c.get("name").strip() for c in (mt.get("tvChannels") or []) if (c.get("name") or "").strip()]
            t = ts(mt.get("startDate"))
            a = ((mt.get("teamA") or {}).get("name") or "").strip()
            b = ((mt.get("teamB") or {}).get("name") or "").strip()
            if tv and t and a and b and len(KOOORA_SAMPLES) < 3:
                KOOORA_SAMPLES.append(mt)
            if tv and t and a and b:
                out.append({"t": t, "a": a, "b": b, "comp": comp, "tv": tv,
                            "venue": ((mt.get("venue") or {}).get("name") or "").strip(),
                            "la": ((mt.get("teamA") or {}).get("image") or {}).get("url") or "",
                            "lb": ((mt.get("teamB") or {}).get("image") or {}).get("url") or ""})
    return "ok", out


# The Arab world's sports channels: the matches of other competitions on them still go in RA TV's
# guide (without a scoreboard, as nothing gives their live score).
ARAB_CHANNEL = re.compile(r"bein|alkass|al kass|ssc|thmanyah|ad sport|abu dhabi|dubai sport|sharjah|ktv|oman sport|"
                          r"bahrain sport|iraqiya|on time sport|الشارقة|الكأس|ثمانية|أبوظبي|دبي|الرابعة", re.I)


def name_key(s):
    """A team name folded for comparing spellings (نوتنجهام فوريست / نوتنغهام فورست)."""
    s = re.sub("[\u064B-\u065F\u0670\u0640]", "", (s or "").lower())
    for a, b in (("أ", "ا"), ("إ", "ا"), ("آ", "ا"), ("ة", "ه"), ("ى", "ي"), ("غ", "ج"), ("ڤ", "ف"), ("ؤ", "و"), ("ئ", "ي")):
        s = s.replace(a, b)
    words = [w[2:] if w.startswith("ال") and len(w) > 3 else w for w in re.split(r"[\s\-.']+", s) if w]
    return "".join(words)


def name_sim(a, b):
    ka, kb = name_key(a), name_key(b)
    if not ka or not kb:
        return 0.0
    if len(ka) >= 3 and len(kb) >= 3 and (ka in kb or kb in ka):
        return 0.95
    return difflib.SequenceMatcher(None, ka, kb).ratio()


def attach_tv(matches, teams, kooora):
    """Puts Kooora's channels on the same matches of the schedule: same kick-off (10 minutes), both teams alike."""
    by_minute = {}
    for m in matches.values():
        by_minute.setdefault(m["t"] // 60, []).append(m)
    attached, unmatched = 0, []
    for k in kooora:
        best, score = None, 0.0
        for d in range(-10, 11):
            for m in by_minute.get(k["t"] // 60 + d, []):
                h, a = teams.get(m["h"]) or {}, teams.get(m["a"]) or {}
                hn = [h.get("ar") or "", h.get("en") or ""]
                an = [a.get("ar") or "", a.get("en") or ""]
                # One team as good as the same name, the other at least alike (ملقا / مالاجا), either way round.
                for x, y in (
                    (max(name_sim(k["a"], n) for n in hn), max(name_sim(k["b"], n) for n in an)),
                    (max(name_sim(k["b"], n) for n in hn), max(name_sim(k["a"], n) for n in an)),
                ):
                    if max(x, y) < 0.85 or min(x, y) < 0.35:
                        continue
                    s = x + y - abs(d) * 0.01
                    if s > score:
                        best, score = m, s
        if best is not None:
            best["tv"] = list(dict.fromkeys((best.get("tv") or []) + k["tv"]))
            if k.get("venue"):
                best["venue_ar"] = k["venue"]  # Kooora writes the stadium in Arabic
            attached += 1
        else:
            unmatched.append(k)
    return attached, unmatched


def guide_extras(unmatched, previous, now):
    """Matches outside the schedule shown on the Arab world's channels, for the guide only."""
    out = [{"t": k["t"], "a": k["a"], "b": k["b"], "c": k["comp"], "tv": k["tv"], "la": k["la"], "lb": k["lb"]}
           for k in unmatched if any(ARAB_CHANNEL.search(n) for n in k["tv"])]
    seen = {(g["t"], g["a"]) for g in out}
    for g in (previous or {}).get("guide") or []:
        if g.get("t", 0) >= now - 3 * 3600 and (g.get("t"), g.get("a")) not in seen:
            out.append(g)
    out.sort(key=lambda g: g["t"])
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

# ------------------------------------------------------------------ absences

FM_DAY = "https://www.fotmob.com/api/data/matches?date=%s&timezone=UTC"
FM_MATCH = "https://www.fotmob.com/api/data/matchDetails?matchId=%s"
ABSENT_DAYS = 4
ABSENT_MAX = 320
REASONS = {"injury": "إصابة", "suspension": "إيقاف", "international": "مع المنتخب", "national": "مع المنتخب"}


def fm_reason(u):
    """FotMob's unavailability in Arabic, and whether the player may still play."""
    kind = (u.get("type") or "").lower()
    back = (u.get("expectedReturn") or "").lower()
    reason = next((ar for en, ar in REASONS.items() if en in kind), "غياب")
    return reason, "doubt" in kind or "doubt" in back


def absences(match_list, teams, previous):
    """Injured and suspended players of the coming matches, from FotMob (ESPN has none): m["abs"] = {"h": [...], "a": [...]}.

    FotMob's matches are found by kick-off (10 minutes) and both teams' English names; a match whose
    answer fails keeps what the last schedule had."""
    now = int(time.time())
    due = [m for m in match_list if m.get("st") != "post" and now - 3 * 3600 <= m["t"] <= now + ABSENT_DAYS * 86400]
    state = {"due": len(due), "days_ok": 0, "fotmob_matches": 0, "matched": 0, "fetched": 0, "with_absences": 0, "errors": []}
    fm = []
    today = dt.datetime.now(dt.timezone.utc).date()
    for d in range(-1, ABSENT_DAYS + 1):
        day = (today + dt.timedelta(days=d)).strftime("%Y%m%d")
        try:
            data = json.loads(get(FM_DAY % day, tries=2, ua=BROWSER)[2].decode("utf-8"))
            state["days_ok"] += 1
        except Exception as e:
            state["errors"].append("day %s: %s" % (day, str(e)[:120]))
            continue
        for lg in data.get("leagues") or []:
            for x in lg.get("matches") or []:
                if x.get("timeTS") and x.get("id"):
                    fm.append({"id": x["id"], "t": int(x["timeTS"]) // 1000,
                               "h": [(x.get("home") or {}).get(k) or "" for k in ("longName", "name")],
                               "a": [(x.get("away") or {}).get(k) or "" for k in ("longName", "name")]})
    state["fotmob_matches"] = len(fm)
    by_minute = {}
    for x in fm:
        by_minute.setdefault(x["t"] // 60, []).append(x)

    def names(tid):
        t = teams.get(tid) or {}
        return [n for n in (t.get("en"), t.get("s"), core(t.get("en") or "")) if n]

    pairs = []
    for m in due:
        hn, an = names(m["h"]), names(m["a"])
        if not hn or not an:
            continue
        best, score = None, 0.0
        for d in range(-10, 11):
            for x in by_minute.get(m["t"] // 60 + d, []):
                h = max(name_sim(core(a), core(b)) for a in hn for b in x["h"] if b)
                a = max(name_sim(core(p), core(q)) for p in an for q in x["a"] if q)
                if max(h, a) < 0.85 or min(h, a) < 0.45:
                    continue
                sc = h + a - abs(d) * 0.01
                if sc > score:
                    best, score = x, sc
        if best is not None:
            pairs.append((m, best["id"]))
    state["matched"] = len(pairs)

    def one(pair):
        m, fid = pair
        d = json.loads(get(FM_MATCH % fid, tries=2, ua=BROWSER)[2].decode("utf-8"))
        lu = (d.get("content") or {}).get("lineup") or {}
        out = {}
        for side, key in (("h", "homeTeam"), ("a", "awayTeam")):
            lst = []
            for p in (lu.get(key) or {}).get("unavailable") or []:
                name = p.get("name") or " ".join(x for x in (p.get("firstName"), p.get("lastName")) if x)
                if not name:
                    continue
                reason, doubtful = fm_reason(p.get("unavailability") or {})
                e = {"n": name, "r": reason}
                if doubtful:
                    e["d"] = 1
                lst.append(e)
            if lst:
                out[side] = lst
        return m, out

    old = {str(m.get("id")): m.get("abs") for m in (previous or {}).get("matches") or [] if m.get("abs")}
    done = set()
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        for f in concurrent.futures.as_completed([ex.submit(one, p) for p in pairs[:ABSENT_MAX]]):
            try:
                m, out = f.result()
            except Exception as e:
                if len(state["errors"]) < 15:
                    state["errors"].append(str(e)[:120])
                continue
            done.add(m["id"])
            state["fetched"] += 1
            if out:
                m["abs"] = out
                state["with_absences"] += 1
    kept = 0
    for m in due:
        if m["id"] not in done and not m.get("abs") and m["id"] in old:
            m["abs"] = old[m["id"]]
            kept += 1
    state["kept_from_previous"] = kept
    state["sample"] = [{"id": m["id"], "abs": m["abs"]} for m in due if m.get("abs")][:2]
    return state

# ------------------------------------------------------------------ players' names in Arabic

ROSTER = "https://site.api.espn.com/apis/site/v2/sports/soccer/%s/teams/%s/roster"
WD_SEARCH = "https://www.wikidata.org/w/api.php?action=wbsearchentities&search=%s&language=en&uselang=en&type=item&limit=6&format=json"
WD_GET = "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=%s&props=labels%%7Caliases%%7Cclaims&languages=ar%%7Carz%%7Cen&format=json"
WD_UA = "RA-Kora-schedule/1.0 (https://github.com/rajavo/RATV)"
FOOTBALLER = "Q937857"
NEW_NAMES_MAX = 2500
NEW_NAMES_SECONDS = 600
RETRY_DAYS = 14
# Bumped when the way names are matched changes, so the names found before are looked up again.
NAMES_VERSION = 2


WD_SPARQL = "https://query.wikidata.org/sparql"


def wd_get(url, data=None):
    """A Wikidata request that waits when asked to slow down (429) instead of failing."""
    for i in range(5):
        try:
            req = urllib.request.Request(url, data=data, headers={"User-Agent": WD_UA, "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=70) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code != 429 and e.code < 500:
                raise
            wait = e.headers.get("Retry-After") if e.headers else None
            time.sleep(min(30, int(wait)) if wait and wait.isdigit() else 3 * (i + 1))
    raise IOError("Wikidata kept answering 429")


def wikidata_bulk(names):
    """Arabic names of footballers whose English name or alias is exactly one of [names]: 200 names a request."""
    out = {}
    names = list(names)
    for i in range(0, len(names), 200):
        part = names[i:i + 200]
        values = " ".join('"%s"@en' % n.replace("\\", "\\\\").replace('"', '\\"') for n in part)
        q = ('SELECT ?name ?ar WHERE { VALUES ?name { %s } ?item rdfs:label|skos:altLabel ?name ; wdt:P106 wd:%s . '
             '?item rdfs:label ?ar . FILTER(LANG(?ar) = "ar") }' % (values, FOOTBALLER))
        d = wd_get(WD_SPARQL, urllib.parse.urlencode({"query": q, "format": "json"}).encode())
        for b in (d.get("results") or {}).get("bindings") or []:
            n = (b.get("name") or {}).get("value")
            ar = re.sub(r"\s*\([^)]*\)", "", (b.get("ar") or {}).get("value") or "").strip()
            if n and ar and n not in out and re.search("[\u0600-\u06FF]", ar):
                out[n] = ar
    return out


def wikidata_arabic(name):
    """The Arabic name of the footballer called [name] in English, from Wikidata; None when it has none."""
    q = urllib.request.quote(name)
    found = wd_get(WD_SEARCH % q).get("search") or []
    ids = [x["id"] for x in found if x.get("id")]
    if not ids:
        return None
    ents = wd_get(WD_GET % "%7C".join(ids)).get("entities") or {}
    want = norm(name)
    for qid in ids:  # in the search's order: the best known first
        e = ents.get(qid) or {}
        jobs = [((c.get("mainsnak") or {}).get("datavalue") or {}).get("value") or {} for c in (e.get("claims") or {}).get("P106") or []]
        if not any(isinstance(j, dict) and j.get("id") == FOOTBALLER for j in jobs):
            continue
        # The same name in English (or a spelling of it), not only a nickname that happens to match.
        english = [(e.get("labels") or {}).get("en", {}).get("value") or ""] + [a.get("value") or "" for a in (e.get("aliases") or {}).get("en") or []]
        if max(difflib.SequenceMatcher(None, want, norm(x)).ratio() for x in english) < 0.85:
            continue
        labels = e.get("labels") or {}
        for lang in ("ar", "arz"):
            v = re.sub(r"\s*\([^)]*\)", "", (labels.get(lang) or {}).get("value") or "").strip()
            if v and re.search("[\u0600-\u06FF]", v):
                return v
        return None
    return None


def player_names(match_list, previous_players):
    """Arabic names of the players of the coming matches (squads from ESPN, absent players from FotMob).

    Names found before are kept; names Wikidata has no Arabic for are tried again after RETRY_DAYS."""
    started = time.time()
    if (previous_players or {}).get("v") != NAMES_VERSION:
        previous_players = None  # found by an older rule: look them up again
    names = dict((previous_players or {}).get("names") or {})
    miss = dict((previous_players or {}).get("miss") or {})
    now = int(time.time())
    due = [m for m in match_list if m.get("st") != "post" and now - 3 * 3600 <= m["t"] <= now + ABSENT_DAYS * 86400]
    first_at = {}
    for m in due:
        if not str(m["id"]).startswith("y"):
            for tid in (m["h"], m["a"]):
                first_at[(m["lg"], tid)] = min(first_at.get((m["lg"], tid), m["t"]), m["t"])
    squads = sorted(first_at)
    wanted = set()
    order = {}  # name → when that player plays first: the nearest matches are looked up first
    state = {"squads": len(squads), "squad_errors": 0}

    def squad(key):
        d = get_json(ROSTER % key)
        return [a.get("displayName") for a in d.get("athletes") or [] if a.get("displayName")]

    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as ex:
        jobs = {ex.submit(squad, k): k for k in squads}
        for f in concurrent.futures.as_completed(jobs):
            try:
                for n in f.result():
                    wanted.add(n)
                    order[n] = min(order.get(n, first_at[jobs[f]]), first_at[jobs[f]])
            except Exception:
                state["squad_errors"] += 1
    for m in due:
        for side in (m.get("abs") or {}).values():
            for a in side:
                if a.get("n"):
                    wanted.add(a["n"])
                    order[a["n"]] = 0  # absent players are few: first
    today = dt.date.today()

    def stale(n):
        try:
            return (today - dt.date.fromisoformat(miss[n])).days >= RETRY_DAYS
        except Exception:
            return True

    new = sorted((n for n in wanted if n not in names and (n not in miss or stale(n))), key=lambda n: (order.get(n, 0), n))
    state.update({"players": len(wanted), "known": sum(1 for n in wanted if n in names), "to_look_up": len(new)})
    # Exact names in bulk first; the others one by one with Wikidata's search (spellings, aliases).
    try:
        bulk = wikidata_bulk(new)
    except Exception as e:
        bulk = {}
        state["bulk_error"] = str(e)[:200]
    names.update(bulk)
    for n in bulk:
        miss.pop(n, None)
    state["found_in_bulk"] = len(bulk)
    new = [n for n in new if n not in bulk]
    looked, found, errors = 0, 0, []
    # Wikidata asks bots to go gently: few at a time (it answers 429 to more).
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as ex:
        futures = {}
        for n in new[:NEW_NAMES_MAX]:  # names the bulk did not find
            futures[ex.submit(lambda n=n: None if time.time() - started > NEW_NAMES_SECONDS else (n, wikidata_arabic(n)))] = n
        for f in concurrent.futures.as_completed(futures):
            try:
                r = f.result()
            except Exception as e:
                if len(errors) < 10:
                    errors.append("%s: %s" % (futures[f], str(e)[:100]))
                continue
            if r is None:
                continue  # out of time: next run
            looked += 1
            n, ar = r
            if ar:
                names[n] = ar
                miss.pop(n, None)
                found += 1
            else:
                miss[n] = today.isoformat()
    # The absent players in Arabic right in the schedule.
    for m in match_list:
        for side in (m.get("abs") or {}).values():
            for a in side:
                if a.get("n") in names:
                    a["n"] = names[a["n"]]
    state.update({"looked_up": looked, "found": found, "errors": errors, "names_total": len(names),
                  "coming_in_arabic": sum(1 for n in wanted if n in names),
                  "sample": dict(sorted((n, names[n]) for n in wanted if n in names)[:15]),
                  "seconds": round(time.time() - started, 1)})
    return {"v": NAMES_VERSION, "names": names, "miss": miss}, state

# ------------------------------------------------------------------ stadiums and cities in Arabic

WD_SEARCH_AR = "https://www.wikidata.org/w/api.php?action=wbsearchentities&search=%s&language=en&uselang=en&type=item&limit=5&format=json"
WD_GET_PLACE = "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=%s&props=labels%%7Caliases%%7Cdescriptions&languages=ar%%7Cen&format=json"
PLACES_VERSION = 2
VENUE_WORDS = re.compile(r"stadium|arena|ground|park|field|venue|stade|estadio|stadio|bowl|dome|sports", re.I)
CITY_WORDS = re.compile(r"\b(city|town|capital|municipality|commune|village|metropolis|borough|human settlement)\b", re.I)
NOT_A_PLACE = re.compile(r"airport|station|stadium|club|team|football|church|university|river", re.I)


def wikidata_place(name, kind):
    """The Arabic name of a stadium ([kind] "v") or a city ("c") called [name] in English; None when Wikidata has none."""
    found = wd_get(WD_SEARCH_AR % urllib.request.quote(name)).get("search") or []
    ids = [x["id"] for x in found if x.get("id")]
    if not ids:
        return None
    ents = wd_get(WD_GET_PLACE % "%7C".join(ids)).get("entities") or {}
    want = norm(name)
    for qid in ids:
        e = ents.get(qid) or {}
        desc = ((e.get("descriptions") or {}).get("en") or {}).get("value") or ""
        if not (VENUE_WORDS if kind == "v" else CITY_WORDS).search(desc) or (kind == "c" and NOT_A_PLACE.search(desc)):
            continue
        english = [((e.get("labels") or {}).get("en") or {}).get("value") or ""] + [a.get("value") or "" for a in (e.get("aliases") or {}).get("en") or []]
        if max(difflib.SequenceMatcher(None, want, norm(x)).ratio() for x in english) < 0.85:
            continue
        v = re.sub(r"\s*\([^)]*\)", "", ((e.get("labels") or {}).get("ar") or {}).get("value") or "").strip()
        return v if re.search("[\u0600-\u06FF]", v) else None
    return None


def arabic_places(match_list, previous_places):
    """Stadiums and cities of the schedule in Arabic (m["v"], m["c"]), from Wikidata; kept run after run in places_ar.json."""
    started = time.time()
    if (previous_places or {}).get("v") != PLACES_VERSION:
        previous_places = None
    names = dict((previous_places or {}).get("names") or {})
    miss = dict((previous_places or {}).get("miss") or {})
    today = dt.date.today()

    def stale(k):
        try:
            return (today - dt.date.fromisoformat(miss[k])).days >= RETRY_DAYS
        except Exception:
            return True

    # Kooora's own Arabic stadium names: kept for the next matches there too.
    from_kooora = 0
    for m in match_list:
        ar = m.pop("venue_ar", None)
        if ar and m.get("v") and not re.search("[\u0600-\u06FF]", m["v"]):
            names["v:" + m["v"]] = ar
            miss.pop("v:" + m["v"], None)
            from_kooora += 1
    wanted = []
    for m in sorted(match_list, key=lambda m: m["t"]):
        for kind in ("v", "c"):
            n = m.get(kind)
            if n and not re.search("[\u0600-\u06FF]", n):
                k = kind + ":" + n
                if k not in names and (k not in miss or stale(k)) and k not in wanted:
                    wanted.append(k)
    looked, found, errors = 0, 0, []
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as ex:
        futures = {ex.submit(lambda k=k: None if time.time() - started > 240 else (k, wikidata_place(k[2:], k[0]))): k for k in wanted[:800]}
        for f in concurrent.futures.as_completed(futures):
            try:
                r = f.result()
            except Exception as e:
                if len(errors) < 8:
                    errors.append("%s: %s" % (futures[f], str(e)[:100]))
                continue
            if r is None:
                continue
            looked += 1
            k, ar = r
            if ar:
                names[k] = ar
                miss.pop(k, None)
                found += 1
            else:
                miss[k] = today.isoformat()
    shown = 0
    for m in match_list:
        for kind in ("v", "c"):
            ar = names.get(kind + ":" + (m.get(kind) or ""))
            if ar:
                m[kind] = ar
                shown += 1
    state = {"from_kooora": from_kooora, "to_look_up": len(wanted), "looked_up": looked, "found": found, "errors": errors, "known": len(names),
             "places_in_arabic": shown, "seconds": round(time.time() - started, 1),
             "sample": dict(list(sorted(names.items()))[:12] + [kv for kv in sorted(names.items()) if kv[0].startswith("v:")][:25])}
    return {"v": PLACES_VERSION, "names": names, "miss": miss}, state

def commentator_probe():
    """Where commentators' names can be read (Kooora and Yalla Kora have none): snippets around «معلق» on other sites."""
    out = {}

    def around(html, n=4):
        spots = [m.start() for m in re.finditer(r"المعلق|معلق|التعليق", html)][:n]
        return [re.sub(r"<[^>]+>", " ", re.sub(r"\s+", " ", html[max(0, i - 300):i + 300])) for i in spots]

    today = dt.datetime.now(dt.timezone.utc).date()
    for site, day_url, link_re, base in (
        ("filgoal", "https://www.filgoal.com/matches/?date=%s" % today.isoformat(), r'href="(/matches/\d+[^"]*)"', "https://www.filgoal.com"),
        ("btolat", "https://www.btolat.com/matches-center", r'href="([^"]*/match/\d+[^"]*)"', "https://www.btolat.com"),
        ("btolat2", "https://www.btolat.com/", r'href="([^"]*/match/\d+[^"]*)"', "https://www.btolat.com"),
    ):
        try:
            code, _, body = get(day_url, tries=1, ua=BROWSER)
            html = body.decode("utf-8", "replace")
            links = list(dict.fromkeys(re.findall(link_re, html)))[:3]
            out[site] = {"code": code, "size": len(html), "around": around(html, 3), "links": links}
            for l in links[:2]:
                url = l if l.startswith("http") else base + l
                try:
                    page = get(url, tries=1, ua=BROWSER)[2].decode("utf-8", "replace")
                    out[url] = {"size": len(page), "around": around(page)}
                except Exception as e:
                    out[url] = {"error": str(e)[:150]}
        except Exception as e:
            out[site] = {"error": str(e)[:150]}
    return out

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
        kooora_job = pool.submit(kooora_day)
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
    yk_all = []
    for day, (state, html) in sorted(yk_pages.items()):
        if state != "ok":
            yk_errors.append("%s %s" % (day, html))
            continue
        try:
            for c in yk_cards(html, day):
                yk_all.append(c)
                lg = yk_league(c["tour_en"], c["tour_ar"], yk_leagues)
                if not lg:
                    continue
                yk_logos.setdefault(lg, c["tour_logo"])
                m = yk_match(c, lg, teams)
                matches[(m["lg"], m["id"])] = m
                yk_count += 1
        except Exception as e:
            yk_errors.append("%s parse: %r" % (day, e))
    learned = learn_names(matches, teams, yk_all)

    # The channels of today's matches, from Kooora; the ones found before stay for the others.
    k_state, k_payload = kooora_job.result()
    tv_attached, tv_unmatched = (attach_tv(matches, teams, k_payload) if k_state == "ok" else (0, []))
    extras = guide_extras(tv_unmatched, previous, int(time.time()))
    if k_state != "ok":
        errors.append("kooora " + k_payload)
    kept_tv = 0
    if previous:
        old_tv = {str(m.get("id")): m.get("tv") for m in previous.get("matches") or [] if m.get("tv")}
        for m in matches.values():
            if not m.get("tv") and m["id"] in old_tv:
                m["tv"] = old_tv[m["id"]]
                kept_tv += 1
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
    try:
        absent_state = absences(match_list, teams, previous)
    except Exception as e:  # never lose the schedule over the absences
        absent_state = {"error": repr(e)[:300]}
    previous_players = None
    if len(sys.argv) > 2:
        pp = os.path.join(os.path.dirname(sys.argv[2]), "players_ar.json")
        if os.path.exists(pp):
            try:
                with open(pp, encoding="utf-8") as f:
                    previous_players = json.load(f)
            except Exception as e:
                print("previous players unreadable: %s" % e)
    try:
        players, players_state = player_names(match_list, previous_players)
    except Exception as e:
        players, players_state = previous_players or {"v": 1, "names": {}, "miss": {}}, {"error": repr(e)[:300]}
    with open(os.path.join(out_dir, "players_ar.json"), "w", encoding="utf-8") as f:
        json.dump(players, f, ensure_ascii=False, separators=(",", ":"))
    previous_places = None
    if len(sys.argv) > 2:
        pp = os.path.join(os.path.dirname(sys.argv[2]), "places_ar.json")
        if os.path.exists(pp):
            try:
                with open(pp, encoding="utf-8") as f:
                    previous_places = json.load(f)
            except Exception as e:
                print("previous places unreadable: %s" % e)
    try:
        places, places_state = arabic_places(match_list, previous_places)
    except Exception as e:
        places, places_state = previous_places or {"v": PLACES_VERSION, "names": {}, "miss": {}}, {"error": repr(e)[:300]}
    with open(os.path.join(out_dir, "places_ar.json"), "w", encoding="utf-8") as f:
        json.dump(places, f, ensure_ascii=False, separators=(",", ":"))
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
        "guide": extras,
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
        "yallakora": {"pages": len(yk_pages), "matches": yk_count, "errors": yk_errors[:10],
                      "competitions": sorted({"%s | %s" % (c["tour_en"], c["tour_ar"]) for c in yk_all})},
        "learned_names": {tid: {"en": teams[tid]["en"], "ar": ar} for tid, ar in learned.items()},
        "tv": {"kooora": k_state, "kooora_matches": len(k_payload) if k_state == "ok" else 0, "attached": tv_attached,
               "kept_from_previous": kept_tv, "matches_with_tv": sum(1 for m in match_list if m.get("tv")),
               "guide_only": len(extras),
               "unmatched": ["%s %s × %s (%s) %s" % (dt.datetime.fromtimestamp(k["t"], dt.timezone.utc).strftime("%H:%M"), k["a"], k["b"],
                                                    k["comp"], ", ".join(k["tv"][:3])) for k in tv_unmatched[:80]]},
        "espn_leagues": discover_leagues(),
        "absences": absent_state,
        "players_ar": players_state,
        "places_ar": places_state,
        "commentator_probe": commentator_probe(),
        "teams": sorted(({"id": tid, "en": t["en"], "ar": t.get("ar"), "leagues": sorted(t["leagues"])}
                         for tid, t in teams.items()), key=lambda x: (x["leagues"], x["en"])),
    }
    with open(os.path.join(out_dir, "report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)

    print("matches: %d, teams: %d (no Arabic name: %d), requests: %d, errors: %d, kept: %d, %.0f s" % (
        len(match_list), len(team_out), len(report["teams_without_arabic"]), len(results), len(errors), kept,
        time.time() - started))
    print("channels: Kooora %s, %d matches with channels there, %d put on the schedule, %d kept from before, %d for the guide only" % (
        k_state, report["tv"]["kooora_matches"], tv_attached, kept_tv, len(extras)))
    for lg in per_league:
        print("  %-22s %-8s %3d matches %s" % (lg["id"], lg["status"], lg["matches"], lg["why"]))
    if not match_list:
        print("no matches at all: not publishing")
        sys.exit(1)


if __name__ == "__main__":
    main()
