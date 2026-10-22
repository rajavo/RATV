#!/usr/bin/env python3
"""Plays one channel for several minutes while the fake server misbehaves at random (CI only)."""
import json
import os
import sys
import time

os.environ.setdefault("SMOKE_OUT", "soak-results/phone")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import smoke as S  # noqa: E402

MINUTES = 10
KEYS = {
    "reconnects": "RATVLive: reconnected",
    "joined": "joined the new connection",
    "not_joined": "could not join",
    "cut_stuck": "no data for",
    "stalls": "RATVPlayback: stall",
    "went_back": "position went back",
    "jumped_ahead": "position jumped ahead",
}


def counts():
    lines = S.adb("logcat", "-d", "-s", "RATVLive:I", "RATVPlayback:I", timeout=30).splitlines()
    return {k: sum(1 for l in lines if v in l) for k, v in KEYS.items()}


def memory():
    """Java heap, native heap, graphics and total, in MB, from the app's memory summary."""
    out = S.shell("dumpsys meminfo com.ratv.app")
    vals = {}
    for line in out.splitlines():
        parts = line.split()
        for name, key in (("Java Heap:", "java"), ("Native Heap:", "native"), ("Graphics:", "graphics"), ("TOTAL PSS:", "total")):
            label = name.split()
            if parts[:len(label)] == label and len(parts) > len(label):
                try:
                    vals[key] = round(int(parts[len(label)]) / 1024)
                except ValueError:
                    pass
    return json.dumps(vals)


def soak():
    S.tap_where(text="الكل")
    time.sleep(1)
    n = None
    for _ in range(8):
        _, n = S.wait_for(timeout=4, contains="Chaos Live")
        if n is not None:
            break
        S.shell("input swipe 540 1700 540 900 600")
        time.sleep(1.5)
    if n is None:
        raise RuntimeError("Chaos Live missing")
    S.adb("logcat", "-c")
    S.tap_node(n)
    S.log("memory at start: " + memory())
    end = time.time() + MINUTES * 60
    minute = 0
    while time.time() < end:
        time.sleep(60)
        minute += 1
        S.log("minute %d: %s memory %s" % (minute, json.dumps(counts()), memory()))
    w, h = S.shot("soak-end")
    S.open_from_controls("معلومات البث", w, h)
    time.sleep(2)
    root = S.dump("soak-stats")
    S.shot("soak-stats")
    S.log("stats: " + S.all_texts(root)[:600])
    c = counts()
    c["memory_end"] = memory()
    S.log("memory at end: " + c["memory_end"])
    with open(os.path.join(S.OUT, "soak.json"), "w", encoding="utf-8") as f:
        json.dump(c, f, ensure_ascii=False, indent=1)
    if c["went_back"] > 0:
        raise RuntimeError("the picture jumped back %d times" % c["went_back"])
    if c["stalls"] > 6:
        raise RuntimeError("%d stalls in %d minutes" % (c["stalls"], MINUTES))


if __name__ == "__main__":
    for name, fn in [
        ("launch", S.s_launch),
        ("login-screen", S.s_login_screen),
        ("wrong-password", S.s_wrong_password),
        ("xtream-login", S.s_xtream_login),
        ("live", S.s_live),
        ("soak", soak),
    ]:
        S.step(name, fn)
    S.finish()
