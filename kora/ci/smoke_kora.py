#!/usr/bin/env python3
"""Checks جدول RA كورة on an Android TV emulator (CI only).

The schedule and the live scores come from ci/mock_server.py (started by the workflow), with
matches placed around the emulator's own clock: one being played, two later today, one
tomorrow, one yesterday. Everything is done with the remote's keys, as a viewer would.
"""
import datetime as dt
import gzip
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

OUT = os.path.abspath(os.environ.get("KORA_OUT", "kora-results"))
WWW = os.path.abspath(os.environ.get("KORA_WWW", "/tmp/kora-www"))
os.makedirs(OUT, exist_ok=True)
os.makedirs(WWW, exist_ok=True)
PKG = "com.ratv.kora"
HOST = "http://10.0.2.2:8000"
results = []
count = [0]


def log(msg):
    print(msg, flush=True)
    with open(os.path.join(OUT, "steps.log"), "a", encoding="utf-8") as f:
        f.write(time.strftime("%H:%M:%S ") + msg + "\n")


def adb(*args, timeout=60):
    try:
        r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
        return r.stdout.decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        return ""


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def shot(name):
    count[0] += 1
    try:
        data = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=30).stdout
        with open(os.path.join(OUT, "%02d-%s.png" % (count[0], name)), "wb") as f:
            f.write(data)
    except subprocess.TimeoutExpired:
        pass


def dump(save=None):
    for _ in range(3):
        sh("rm -f /sdcard/ui.xml")
        sh("uiautomator dump /sdcard/ui.xml", timeout=45)
        xml = adb("exec-out", "cat", "/sdcard/ui.xml", timeout=20)
        i = xml.find("<hierarchy")
        if i >= 0:
            try:
                root = ET.fromstring(xml[i:])
            except ET.ParseError:
                root = None
            if root is not None:
                if save:
                    with open(os.path.join(OUT, save + ".xml"), "w", encoding="utf-8") as f:
                        f.write(xml[i:])
                return root
        time.sleep(1.5)
    return None


def texts(root):
    if root is None:
        return ""
    return " | ".join(t for t in ((n.get("text") or n.get("content-desc") or "") for n in root.iter("node")) if t)


def focused(root):
    if root is None:
        return None
    for n in root.iter("node"):
        if n.get("focused") == "true":
            return texts(n) or "(no text)"
    return None


def key(name, pause=1.0):
    sh("input keyevent KEYCODE_" + name)
    time.sleep(pause)


def wait_text(want, timeout=25, save=None, gone=False):
    end = time.time() + timeout
    while time.time() < end:
        root = dump()
        if (want in texts(root)) != gone:
            if save:
                dump(save)
            return root
        time.sleep(1)
    if save:
        dump(save + "-timeout")
    return None


def expect(cond, msg):
    if not cond:
        raise RuntimeError(msg)


def step(name, fn):
    log("== " + name)
    ok, err = True, ""
    try:
        fn()
    except Exception as e:  # keep going, so the later checks still run
        ok, err = False, repr(e)
        log("!! " + err)
        shot("fail-" + name)
    results.append({"step": name, "ok": ok, "error": err})


def move_until(direction, want, tries=12):
    """Moves the selection with the remote until the selected item shows [want]."""
    f = None
    for _ in range(tries):
        f = focused(dump())
        if f and want in f:
            return f
        key(direction, 0.7)
    f = focused(dump())
    expect(f and want in f, "could not reach '%s' with %s (selected: %s)" % (want, direction, f))
    return f


# ------------------------------------------------------------------ test data

def set_zone():
    """A time zone where it is about noon now, so 'today' has room before and after."""
    h = dt.datetime.now(dt.timezone.utc).hour
    off = 12 - h
    zone = "Etc/UTC" if off == 0 else ("Etc/GMT-%d" % off if off > 0 else "Etc/GMT+%d" % -off)
    sh("service call alarm 3 s16 %s" % zone)
    time.sleep(1)
    if sh("getprop persist.sys.timezone").strip() != zone:
        adb("root")
        time.sleep(3)
        sh("setprop persist.sys.timezone %s" % zone)
        time.sleep(1)
    z = sh("date +%z").strip()
    m = re.match(r"([+-])(\d\d)(\d\d)", z)
    minutes = (1 if not m or m.group(1) == "+" else -1) * ((int(m.group(2)) * 60 + int(m.group(3))) if m else 0)
    log("time zone %s (wanted %s), device time %s" % (z, zone, sh("date").strip()))
    return minutes


def write_schedule(offset_min):
    now = int(time.time())
    local = dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=offset_min)
    midnight = local.replace(hour=0, minute=0, second=0, microsecond=0) - dt.timedelta(minutes=offset_min)
    today0 = int(midnight.replace(tzinfo=dt.timezone.utc).timestamp())
    logo = lambda n: HOST + "/logo/%s.png" % n
    teams = {
        "929": {"en": "Al Hilal", "ar": "الهلال", "logo": logo("929")},
        "817": {"en": "Al Nassr", "ar": "النصر", "logo": logo("817")},
        "359": {"en": "Arsenal", "ar": "آرسنال", "logo": logo("359")},
        "363": {"en": "Chelsea", "ar": "تشيلسي", "logo": ""},
        "86": {"en": "Real Madrid", "ar": "ريال مدريد", "logo": logo("86")},
        "83": {"en": "Barcelona", "ar": "برشلونة", "logo": logo("83")},
        "364": {"en": "Liverpool", "ar": "ليفربول", "logo": ""},
        "382": {"en": "Manchester City", "ar": "مانشستر سيتي", "logo": ""},
        "y:الأهلي": {"en": "", "ar": "الأهلي", "logo": ""},
        "y:الزمالك": {"en": "", "ar": "الزمالك", "logo": ""},
    }
    leagues = [
        {"id": "ksa.1", "ar": "دوري روشن السعودي", "short": "روشن", "en": "Saudi Pro League", "logo": logo("ksa")},
        {"id": "uefa.champions", "ar": "دوري أبطال أوروبا", "short": "أبطال أوروبا", "en": "UEFA Champions League", "logo": ""},
        {"id": "eng.1", "ar": "الدوري الإنجليزي الممتاز", "short": "الإنجليزي", "en": "English Premier League", "logo": logo("eng")},
        {"id": "esp.1", "ar": "الدوري الإسباني", "short": "الإسباني", "en": "Spanish LALIGA", "logo": ""},
        {"id": "egy.1", "ar": "الدوري المصري الممتاز", "short": "المصري", "en": "Egyptian Premier League", "logo": ""},
    ]
    matches = [
        {"id": "9000", "lg": "eng.1", "t": today0 - 86400 + 20 * 3600, "h": "364", "a": "382", "st": "post", "sn": "FULL_TIME", "hs": 3, "as": 1},
        {"id": "9001", "lg": "ksa.1", "t": now - 50 * 60, "h": "929", "a": "817", "st": "in", "sn": "FIRST_HALF", "hs": 1, "as": 0,
         "clk": "5'", "v": "Kingdom Arena", "c": "Riyadh"},
        {"id": "9002", "lg": "eng.1", "t": now + 2 * 3600, "h": "359", "a": "363", "st": "pre", "sn": "SCHEDULED"},
        {"id": "y9003", "lg": "egy.1", "t": now + 3 * 3600, "h": "y:الأهلي", "a": "y:الزمالك", "st": "pre", "sn": "SCHEDULED",
         "n": "الأسبوع الثامن"},
        {"id": "9004", "lg": "esp.1", "t": today0 + 86400 + 21 * 3600, "h": "86", "a": "83", "st": "pre", "sn": "SCHEDULED"},
        {"id": "9005", "lg": "uefa.champions", "t": today0 + 5 * 86400 + 22 * 3600, "h": "364", "a": "86", "st": "pre",
         "sn": "SCHEDULED"},
    ]
    with open(os.path.join(WWW, "fixtures.json"), "w", encoding="utf-8") as f:
        json.dump({"v": 1, "updated": now - 600, "leagues": leagues, "teams": teams, "matches": matches}, f, ensure_ascii=False)
    live = {"events": [{"id": "9001", "competitions": [{
        "status": {"displayClock": "67'", "type": {"state": "in", "name": "STATUS_SECOND_HALF"}},
        "competitors": [{"homeAway": "home", "score": "2"}, {"homeAway": "away", "score": "1"}]}]}]}
    with open(os.path.join(WWW, "espn_ksa.1.json"), "w", encoding="utf-8") as f:
        json.dump(live, f)
    log("schedule written: local midnight %s UTC" % dt.datetime.fromtimestamp(today0, dt.timezone.utc))


# ------------------------------------------------------------------ steps

def s_setup():
    for _ in range(6):  # let the freshly booted emulator settle
        time.sleep(5)
        dump()
    key("HOME", 3)
    offset = set_zone()
    write_schedule(offset)
    sh("setprop debug.kora.data %s/fixtures.json" % HOST)
    sh("setprop debug.kora.espn %s" % HOST)
    sh("setprop debug.kora.update %s/update" % HOST)
    adb("logcat", "-c")
    log(sh("am start -W -n %s/.MainActivity" % PKG).strip())
    root = wait_text("جدول RA كورة", save="start")
    shot("start")
    expect(root is not None, "the app did not open")


def s_today():
    root = wait_text("الهلال", timeout=30)
    expect(root is not None, "no matches on the screen: " + texts(dump())[:400])
    root = wait_text("67'", timeout=25, save="today")
    shot("today")
    t = texts(root if root is not None else dump())
    log("today: " + t[:900])
    for want in ("اليوم", "النصر", "آرسنال", "تشيلسي", "الأهلي", "الزمالك", "دوري روشن السعودي", "مباشر الحين"):
        expect(want in t, "'%s' is missing from today's schedule" % want)
    expect("67'" in t, "the live minute (67') from the live scores did not arrive")
    expect("ريال مدريد" not in t, "tomorrow's match shows today")
    f = focused(root)
    log("selected at start: %s" % f)
    expect(f and "اليوم" in f, "the remote does not start on today (selected: %s)" % f)


def s_tomorrow():
    key("DPAD_LEFT", 1.5)  # right to left: tomorrow is on the left
    root = wait_text("ريال مدريد", timeout=15, save="tomorrow")
    shot("tomorrow")
    f = focused(dump())
    log("selected: %s" % f)
    expect(root is not None, "tomorrow's match did not show")
    expect(f and "بكرة" in f, "tomorrow is not the selected day (%s)" % f)
    expect("الهلال" not in texts(root), "today's matches still show on tomorrow")


def s_yesterday():
    key("DPAD_RIGHT", 1.2)
    key("DPAD_RIGHT", 1.5)
    root = wait_text("ليفربول", timeout=15, save="yesterday")
    shot("yesterday")
    expect(root is not None, "yesterday's match did not show")
    t = texts(root)
    expect("أمس" in (focused(root) or ""), "yesterday is not selected")
    expect("انتهت" in t and "3" in t, "yesterday's result is missing: " + t[:300])
    key("DPAD_LEFT", 1.5)
    expect(wait_text("الهلال", timeout=15) is not None, "could not come back to today")


def s_filter():
    key("DPAD_DOWN", 1.0)
    f = focused(dump())
    log("after down: %s" % f)
    move_until("DPAD_LEFT", "الإنجليزي")
    key("DPAD_CENTER", 1.5)
    root = wait_text("الهلال", timeout=10, gone=True, save="filtered")
    shot("filtered")
    expect(root is not None, "the competition filter did not hide the other matches")
    expect("آرسنال" in texts(root), "the English match is missing after choosing the English league")
    move_until("DPAD_RIGHT", "الكل")
    key("DPAD_CENTER", 1.5)
    expect(wait_text("الهلال", timeout=10) is not None, "«الكل» did not bring every match back")


def s_details():
    f = None
    for _ in range(6):
        key("DPAD_DOWN", 0.8)
        f = focused(dump())
        if f and "الهلال" in f:
            break
    log("selected row: %s" % f)
    expect(f and "الهلال" in f and "النصر" in f, "could not select the Al Hilal match (%s)" % f)
    key("DPAD_CENTER", 1.5)
    root = wait_text("شاهد على RA TV", timeout=10, save="details")
    shot("details")
    expect(root is not None, "the match details did not open")
    t = texts(root)
    expect("Kingdom Arena" in t, "the stadium is missing from the details")
    expect("67'" in t, "the live minute is missing from the details")
    f = focused(root)
    expect(f and "شاهد على RA TV" in f, "the watch button is not selected (%s)" % f)
    key("DPAD_CENTER", 2)
    root = wait_text("RA TV مو مثبت", timeout=10, save="not-installed")
    shot("not-installed")
    expect(root is not None, "no message that RA TV is missing")
    lines = adb("logcat", "-d", "-s", "RAKora:I", timeout=30)
    link = [l for l in lines.splitlines() if "watch NOT_INSTALLED" in l]
    log("link: %s" % (link[-1] if link else "(none)"))
    expect(link and "ratv://match" in link[-1] and "homeAr=" in link[-1], "the RA TV link was not built")
    key("BACK", 1.2)
    key("BACK", 1.5)
    root = wait_text("شاهد على RA TV", timeout=8, gone=True)
    expect(root is not None, "the dialogs did not close with Back")
    f = focused(root)
    log("selected after closing: %s" % f)
    expect(f and "الهلال" in f, "the selection did not come back to the match (%s)" % f)


def s_offline():
    """The schedule kept on the device opens even when it cannot be downloaded."""
    os.rename(os.path.join(WWW, "fixtures.json"), os.path.join(WWW, "fixtures.off"))
    sh("am force-stop %s" % PKG)
    time.sleep(1)
    sh("am start -W -n %s/.MainActivity" % PKG)
    root = wait_text("الهلال", timeout=20, save="offline")
    shot("offline")
    os.rename(os.path.join(WWW, "fixtures.off"), os.path.join(WWW, "fixtures.json"))
    expect(root is not None, "without the server the saved schedule did not show")


def finish():
    full = adb("logcat", "-d", "-v", "threadtime", timeout=90)
    with gzip.open(os.path.join(OUT, "logcat.txt.gz"), "wt", encoding="utf-8") as f:
        f.write(full)
    with open(os.path.join(OUT, "kora-log.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(l for l in full.splitlines() if "RAKora" in l or "FATAL" in l or ("AndroidRuntime" in l and PKG in l)))
    crash = adb("logcat", "-d", "-b", "crash", timeout=30)
    with open(os.path.join(OUT, "crash.txt"), "w", encoding="utf-8") as f:
        f.write(crash)
    crashed = PKG in crash
    with open(os.path.join(OUT, "summary.json"), "w", encoding="utf-8") as f:
        json.dump({"results": results, "kora_crashed": crashed}, f, ensure_ascii=False, indent=1)
    for r in results:
        print("::%s title=%s::%s" % ("notice" if r["ok"] else "error", r["step"], r["error"] or "ok"), flush=True)
    failed = [r for r in results if not r["ok"]]
    raise SystemExit(1 if failed or crashed else 0)


if __name__ == "__main__":
    for name, fn in [
        ("setup", s_setup),
        ("today", s_today),
        ("tomorrow", s_tomorrow),
        ("yesterday", s_yesterday),
        ("filter", s_filter),
        ("details", s_details),
        ("offline", s_offline),
    ]:
        step(name, fn)
    finish()
