#!/usr/bin/env python3
"""Checks RA Store on an Android TV emulator (CI only): it lists the RA apps from the releases,
and installs one through the TV's installer."""
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

OUT = os.path.abspath(os.environ.get("STORE_OUT", "store-results"))
os.makedirs(OUT, exist_ok=True)
PKG = "com.ratv.store"
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


def bounds(n):
    m = re.findall(r"\d+", n.get("bounds") or "")
    return tuple(map(int, m)) if len(m) == 4 else None


def tap_node(n):
    b = bounds(n)
    if b:
        sh("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
        time.sleep(1.5)
        return True
    return False


def s_list():
    sh("appops set %s REQUEST_INSTALL_PACKAGES allow" % PKG)
    sh("am start -W -n %s/.MainActivity" % PKG)
    root = wait_text("RA Home", timeout=40)
    shot("list")
    t = texts(root)
    log("store: " + t[:1500])
    expect(root is not None, "the RA apps did not show")
    for name in ("RATV", "RA Net", "RA Home", "كورة"):
        expect(name in t, "missing from the list: " + name)
    expect("اسمح بالتثبيت" not in t, "asks to allow installs although it is allowed")
    expect(focused(root) is not None, "nothing is selected for the remote")


def s_install():
    """Installs RA Home from the list, through the TV's installer."""
    root = dump()
    name = next((n for n in root.iter("node") if (n.get("text") or "") == "RA Home"), None) if root is not None else None
    expect(name is not None, "no RA Home row")
    y = (bounds(name)[1] + bounds(name)[3]) // 2
    button = None
    for n in root.iter("node"):
        b = bounds(n)
        if (n.get("text") or "") == "ثبّت" and b and b[1] - 60 <= y <= b[3] + 60:
            button = n
    expect(button is not None, "no install button on the RA Home row")
    tap_node(button)
    for _ in range(40):
        time.sleep(2)
        r = dump()
        hit = None
        if r is not None:
            for n in r.iter("node"):
                if re.fullmatch(r"(?i)install|تثبيت|INSTALL", (n.get("text") or "").strip()) and n.get("clickable") == "true":
                    hit = n
        if hit is not None:
            shot("installer")
            tap_node(hit)
            break
    for _ in range(40):
        if "com.ratv.home" in sh("pm list packages com.ratv.home"):
            break
        time.sleep(2)
    expect("com.ratv.home" in sh("pm list packages com.ratv.home"), "RA Home was not installed")
    sh("am start -W -n %s/.MainActivity" % PKG)
    root = wait_text("مثبّت ومحدّث", timeout=30)
    shot("installed")
    expect(root is not None, "the list does not show RA Home as installed")


def finish():
    crash = adb("logcat", "-d", "-b", "crash", timeout=30)
    with open(os.path.join(OUT, "crash.txt"), "w", encoding="utf-8") as f:
        f.write(crash)
    crashed = PKG in crash
    with open(os.path.join(OUT, "summary.json"), "w", encoding="utf-8") as f:
        json.dump({"results": results, "rastore_crashed": crashed}, f, ensure_ascii=False, indent=1)
    for r in results:
        print("::%s title=%s::%s" % ("notice" if r["ok"] else "error", r["step"], r["error"] or "ok"), flush=True)
    raise SystemExit(1 if crashed or any(not r["ok"] for r in results) else 0)


if __name__ == "__main__":
    for name, fn in [("list", s_list), ("install", s_install)]:
        step(name, fn)
    finish()
