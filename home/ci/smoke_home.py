#!/usr/bin/env python3
"""Checks RA Home on an Android TV emulator (CI only): it opens, shows the prayer times and the apps,
the remote moves between the tiles, and the city can be picked."""
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

OUT = os.path.abspath(os.environ.get("HOME_OUT", "home-results"))
os.makedirs(OUT, exist_ok=True)
PKG = "com.ratv.home"
results = []
count = [0]


def log(msg):
    print(msg, flush=True)
    with open(os.path.join(OUT, "steps.log"), "a", encoding="utf-8") as f:
        f.write(time.strftime("%H:%M:%S ") + msg + "\n")


def adb(*args, timeout=60):
    try:
        return subprocess.run(["adb", *args], capture_output=True, timeout=timeout).stdout.decode("utf-8", "replace")
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


def dump():
    for _ in range(3):
        sh("rm -f /sdcard/ui.xml")
        sh("uiautomator dump /sdcard/ui.xml", timeout=45)
        xml = adb("exec-out", "cat", "/sdcard/ui.xml", timeout=20)
        i = xml.find("<hierarchy")
        if i >= 0:
            try:
                return ET.fromstring(xml[i:])
            except ET.ParseError:
                pass
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


def wait_text(want, timeout=25):
    end = time.time() + timeout
    while time.time() < end:
        root = dump()
        if want in texts(root):
            return root
        time.sleep(1)
    return None


def expect(cond, msg):
    if not cond:
        raise RuntimeError(msg)


def step(name, fn):
    log("== " + name)
    ok, err = True, ""
    try:
        fn()
    except Exception as e:
        ok, err = False, repr(e)
        log("!! " + err)
        shot("fail-" + name)
    results.append({"step": name, "ok": ok, "error": err})


def s_open():
    sh("settings put global time_zone Asia/Riyadh; setprop persist.sys.timezone Asia/Riyadh")
    sh("am start -W -n %s/.MainActivity" % PKG)
    root = wait_text("الصلاة القادمة")
    shot("home")
    expect(root is not None, "the prayer times did not show")
    t = texts(root)
    log("home: " + t[:1500])
    for s in ("الفجر", "الظهر", "العصر", "المغرب", "العشاء"):
        expect(s in t, "missing prayer: " + s)
    expect(re.search(r"\d{1,2}:\d\d [صم]", t), "no prayer time on the screen")
    expect("هـ" in t, "no hijri date")
    expect("قال رسول الله ﷺ" in t, "no hadith")
    first = re.search(r"«([^»]+)»", t)
    sh("input keyevent KEYCODE_HOME")  # away and back: a new hadith
    time.sleep(2)
    sh("am start -W -n %s/.MainActivity" % PKG)
    root = wait_text("قال رسول الله")
    second = re.search(r"«([^»]+)»", texts(root))
    log("hadiths: %s / %s" % (first and first.group(1), second and second.group(1)))
    expect(first and second and first.group(1) != second.group(1), "the hadith did not change")


def s_remote():
    root = dump()
    first = focused(root)
    log("first focus: %s" % first)
    expect(first is not None, "nothing is selected for the remote")
    key("DPAD_LEFT", 1)
    second = focused(dump())
    log("after one step: %s" % second)
    shot("moved")
    expect(second is not None and second != first, "the remote did not move between the tiles")


def s_city():
    for _ in range(40):
        f = focused(dump())
        if f and "المدينة" in f:
            break
        key("DPAD_DOWN", 0.4)
        f2 = focused(dump())
        if f2 and "المدينة" in f2:
            f = f2
            break
        key("DPAD_LEFT", 0.4)
    log("focus on the city tile: %s" % f)
    expect(f and "المدينة" in f, "the city tile is not reachable with the remote")
    key("DPAD_CENTER", 2)
    root = wait_text("مدينة مواقيت الصلاة")
    shot("cities")
    expect(root is not None, "the city list did not open")
    key("DPAD_DOWN", 0.8)  # الرياض
    key("DPAD_DOWN", 0.8)  # جدة
    key("DPAD_CENTER", 2)
    root = wait_text("جدة")
    shot("jeddah")
    expect(root is not None and "مدينة مواقيت الصلاة" not in texts(root), "picking a city did not close the list")
    log("after picking: " + texts(root)[:300])


def tile_x(root, label):
    """The middle of the app tile's name on the screen (x), or None."""
    if root is None:
        return None
    for n in root.iter("node"):
        if (n.get("text") or "") == label:
            m = re.findall(r"\d+", n.get("bounds") or "")
            if len(m) == 4:
                return (int(m[0]) + int(m[2])) // 2
    return None


def s_move():
    """Long OK picks an app up, the arrows move it, OK puts it down, and the order stays after a restart."""
    sh("am force-stop %s" % PKG)
    sh("am start -W -n %s/.MainActivity" % PKG)
    time.sleep(3)
    root = dump()
    label = focused(root)
    x0 = tile_x(root, label)
    log("first app: %s at x=%s" % (label, x0))
    if x0 is None:
        with open(os.path.join(OUT, "move-start.xml"), "w", encoding="utf-8") as f:
            f.write(ET.tostring(root, encoding="unicode") if root is not None else "")
    expect(label and x0, "no first app selected")
    sh("input keyevent --longpress KEYCODE_DPAD_CENTER")
    time.sleep(1.5)
    shot("picked-up")
    expect("حرّك" in texts(dump()), "a long OK did not pick the app up")
    key("DPAD_LEFT", 1)
    key("DPAD_CENTER", 1.5)
    root = dump()
    shot("moved-app")
    x1 = tile_x(root, label)
    log("after moving: x=%s" % x1)
    expect(x1 is not None and x1 < x0, "the app did not move one place to the left")
    expect(sh("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'").find(PKG) >= 0,
           "putting the app down opened it instead")
    sh("am force-stop %s" % PKG)
    sh("am start -W -n %s/.MainActivity" % PKG)
    time.sleep(3)
    x2 = tile_x(dump(), label)
    log("after a restart: x=%s" % x2)
    expect(x2 is not None and abs(x2 - x1) < 20, "the new order was not kept")


def finish():
    crash = adb("logcat", "-d", "-b", "crash", timeout=30)
    with open(os.path.join(OUT, "crash.txt"), "w", encoding="utf-8") as f:
        f.write(crash)
    crashed = PKG in crash
    with open(os.path.join(OUT, "summary.json"), "w", encoding="utf-8") as f:
        json.dump({"results": results, "rahome_crashed": crashed}, f, ensure_ascii=False, indent=1)
    for r in results:
        print("::%s title=%s::%s" % ("notice" if r["ok"] else "error", r["step"], r["error"] or "ok"), flush=True)
    raise SystemExit(1 if crashed or any(not r["ok"] for r in results) else 0)


if __name__ == "__main__":
    for name, fn in [("open", s_open), ("remote", s_remote), ("city", s_city), ("move", s_move)]:
        step(name, fn)
    finish()
