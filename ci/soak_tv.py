#!/usr/bin/env python3
"""Plays the chaos channel on the TV emulator for SOAK_MINUTES while the fake server misbehaves at
random: cuts, short pauses, frozen connections, chunks, slow spells, bursts of 0-6 s (CI only).
Counts the stalls (the picture stopping to wait for the stream) and what the app did about them."""
import json
import os
import sys
import time

os.environ.setdefault("SMOKE_OUT", "soak-results/tv")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import smoke as S  # noqa: E402
import smoke_tv as T  # noqa: E402

MINUTES = int(os.environ.get("SOAK_MINUTES", "20"))
CHAOS_NUMBER = 18
KEYS = {
    "reconnects": "RATVLive: reconnected",
    "joined": "joined the new connection",
    "not_joined": "could not join",
    "cut_stuck": "no data for",
    "stalls": "RATVPlayback: stall",
    "went_back": "position went back",
    "jumped_ahead": "position jumped ahead",
    "restarts": "starting the channel again",
    "steadiness": "steadiness level",
    "hls": "trying HLS",
}
LIVE_LOG = os.path.join(S.OUT, "logcat-live.txt")


def lines_since(start):
    """The app's log lines written to the live log since line [start] (the logcat buffer may roll over)."""
    try:
        with open(LIVE_LOG, encoding="utf-8", errors="replace") as f:
            return f.readlines()[start:]
    except OSError:
        return []


def line_count():
    try:
        with open(LIVE_LOG, encoding="utf-8", errors="replace") as f:
            return sum(1 for _ in f)
    except OSError:
        return 0


def counts(start):
    got = lines_since(start)
    return {k: sum(1 for l in got if v in l) for k, v in KEYS.items()}


def reserves(start):
    """The reserve (ms) at each stall, as the app logs it."""
    out = []
    for l in lines_since(start):
        if "RATVPlayback: stall" in l and "reserve" in l:
            try:
                out.append(int(l.split("reserve")[1].split("ms")[0].strip()))
            except (IndexError, ValueError):
                pass
    return out


def t_soak():
    f = T.focus_home_item("شاهد الآن", "soak-hero")
    T.expect(f and "شاهد الآن" in f, "watch-now not focused (focus: %s)" % f)
    T.key("DPAD_CENTER", pause=6)
    start = line_count()
    T.open_number(CHAOS_NUMBER, wait=3)
    S.shot("soak-start")
    S.log("chaos channel on screen: %s" % ("Chaos Live" in S.all_texts(S.dump("soak-start"))))
    end = time.time() + MINUTES * 60
    minute = 0
    while time.time() < end:
        time.sleep(60)
        minute += 1
        S.log("minute %d: %s" % (minute, json.dumps(counts(start))))
        if minute % 5 == 0:
            S.shot("soak-%02d" % minute)
    c = counts(start)
    c["minutes"] = MINUTES
    c["reserve_at_stalls_ms"] = reserves(start)
    S.log("soak: " + json.dumps(c, ensure_ascii=False))
    with open(os.path.join(S.OUT, "soak.json"), "w", encoding="utf-8") as fh:
        json.dump(c, fh, ensure_ascii=False, indent=1)
    S.shot("soak-end")


if __name__ == "__main__":
    for name, fn in [
        ("launch", S.s_launch),
        ("login-screen", T.t_login_screen),
        ("fill-with-remote", T.t_fill_with_remote),
        ("home-focus", T.t_home_focus),
        ("soak", t_soak),
    ]:
        S.step(name, fn)
    S.finish()
