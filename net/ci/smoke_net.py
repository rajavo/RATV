#!/usr/bin/env python3
"""Checks RA Net on an Android TV emulator (CI only).

The probe app keeps calling a public address and logs ok or fail:
with focus on it must get through while it is on the screen, and be blocked once it is in
the background. The adb shell stands for an app that is never on the screen.
"""
import gzip
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

OUT = os.path.abspath(os.environ.get("NET_OUT", "net-results"))
os.makedirs(OUT, exist_ok=True)
PKG = "com.ratv.net"
PROBE = "com.ratv.net.probe"
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


def wait_text(want, timeout=25, save=None):
    end = time.time() + timeout
    while time.time() < end:
        root = dump()
        if want in texts(root):
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


def vpn_up():
    return re.search(r"\btun\d+", sh("ip link")) is not None


def nc(host, port, request, want):
    """Can the adb shell (an app that is never on the screen) reach this address? None: cannot tell."""
    out = sh("printf '%s' | toybox nc -w 4 %s %d 2>&1" % (request, host, port), timeout=20)
    if "not found" in out or "Unknown command" in out:
        log("no nc on this image: " + out[:120])
        return None
    return want in out


def shell_net():
    """The internet, from the shell."""
    return nc("1.1.1.1", 80, r"GET / HTTP/1.0\r\nHost: 1.1.1.1\r\n\r\n", "HTTP/1.")


def shell_lan():
    """The home network (the computer running the emulator), from the shell."""
    return nc("10.0.2.2", 8000, r"GET /probe.txt HTTP/1.0\r\n\r\n", "200 OK")


def probe_log():
    lines = adb("logcat", "-d", "-s", "RANetProbe:I", timeout=30).splitlines()
    return ["ok" if " ok " in l else "fail" for l in lines if " ok " in l or " fail " in l]


def probe_after(mark, seconds):
    time.sleep(seconds)
    return probe_log()[mark:]


def start_probe():
    sh("am start -W -n %s/.ProbeActivity" % PROBE)


def open_ranet():
    sh("am start -W -n %s/.MainActivity" % PKG)
    time.sleep(2.5)


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


# ------------------------------------------------------------------- steps

def s_setup():
    for _ in range(6):  # let the freshly booted emulator settle
        time.sleep(5)
        dump()
    key("HOME", 3)
    sh("setprop debug.ranet.grace 0")  # no minute of grace for the app that left the screen
    sh("appops set %s ACTIVATE_VPN allow" % PKG)  # the VPN request, accepted in advance
    sh("appops set %s GET_USAGE_STATS allow" % PKG)
    adb("logcat", "-c")
    log(sh("am start -W -n %s/.MainActivity" % PKG).strip())
    root = wait_text("RA Net", save="start")
    time.sleep(2)
    root = dump("start")
    shot("start")
    t = texts(root)
    log("start: " + t[:500])
    expect("التركيز طافي" in t, "focus should start off")
    expect("خطوة وحدة قبل التشغيل" not in t, "usage access is granted but the setup step still shows")
    expect(shell_net() is not False, "no internet before focus is on")
    expect(shell_lan() is not False, "the home network is not reachable before focus is on")


def s_turn_on():
    root = dump()
    f = focused(root)
    log("focus: %s" % f)
    expect(f is not None and "التركيز" in f, "the on/off card is not selected (focus: %s)" % f)
    key("DPAD_CENTER", 3)
    root = wait_text("التركيز شغّال", save="on")
    shot("on")
    expect(root is not None, "focus did not turn on")
    expect(vpn_up(), "no VPN interface")
    time.sleep(2)
    expect(shell_net() is not True, "an app off the screen (adb shell) still reached the internet")
    expect(shell_lan() is not False, "the home network was blocked too (it must stay open)")


def s_probe_on_screen():
    mark = len(probe_log())
    start_probe()
    r = probe_after(mark, 8)
    shot("probe-on-screen")
    log("probe on screen: %s" % r)
    expect(len(r) >= 3 and r[-3:] == ["ok"] * 3, "the app on the screen has no internet: %s" % r)


def s_probe_background():
    mark = len(probe_log())
    key("HOME", 1)
    r = probe_after(mark, 11)
    shot("probe-background")
    log("probe in background: %s" % r)
    expect(len(r) >= 3 and r[-3:] == ["fail"] * 3, "the app in the background still has internet: %s" % r)


def s_probe_back():
    mark = len(probe_log())
    start_probe()
    r = probe_after(mark, 8)
    log("probe back on screen: %s" % r)
    expect(len(r) >= 3 and r[-2:] == ["ok"] * 2, "the app did not get its internet back: %s" % r)
    pid = sh("pidof %s" % PKG).strip()
    threads = sh("ps -T -p %s" % pid) if pid else ""
    log("VPN reader threads: %d" % threads.count("ranet-tun"))


def s_blocked_list():
    open_ranet()
    seen = ""
    for i in range(14):  # down through the screen with the remote, reading as it scrolls
        root = dump("blocked" if i == 0 else None)
        if i == 0:
            shot("blocked")
        seen += " | " + texts(root)
        if "استهلاك النت الحين" in seen and re.search(r"RANet Probe \| \d+ محاولة", seen):
            break
        key("DPAD_DOWN", 0.6)
    shot("blocked-scrolled")
    log("blocked screen: " + seen[:1200])
    expect("استهلاك النت الحين" in seen, "no live usage section")
    expect("حاولت تستخدم النت وانحجبت" in seen, "no list of blocked apps")
    expect(re.search(r"RANet Probe \| \d+ محاولة", seen), "the probe is missing from the blocked apps")


def s_accessibility():
    """The second way to know the app on the screen, for TVs without usage access."""
    sh("settings put secure enabled_accessibility_services %s/%s.FocusAccessibilityService" % (PKG, PKG))
    sh("settings put secure accessibility_enabled 1")
    time.sleep(4)
    sh("appops set %s GET_USAGE_STATS ignore" % PKG)
    time.sleep(2)
    mark = len(probe_log())
    start_probe()
    r = probe_after(mark, 8)
    log("accessibility, probe on screen: %s" % r)
    expect(vpn_up(), "focus stopped when usage access was taken away")
    expect(len(r) >= 3 and r[-2:] == ["ok"] * 2, "accessibility only: the app on the screen has no internet: %s" % r)
    mark = len(probe_log())
    key("HOME", 1)
    r = probe_after(mark, 11)
    log("accessibility, probe in background: %s" % r)
    expect(len(r) >= 3 and r[-3:] == ["fail"] * 3, "accessibility only: the background app still has internet: %s" % r)


def s_turn_off():
    open_ranet()
    f = None
    for _ in range(20):
        f = focused(dump())
        if f and "التركيز" in f:
            break
        key("DPAD_UP", 0.5)
    log("focus before turning off: %s" % f)
    shot("before-off")
    key("DPAD_CENTER", 3)
    root = wait_text("التركيز طافي", save="off")
    shot("off")
    expect(root is not None, "focus did not turn off")
    time.sleep(2)
    expect(not vpn_up(), "the VPN interface is still there")
    expect(shell_net() is not False, "the internet did not come back for every app")


def tap_text(root, pattern):
    """Taps the first node whose text matches; True when one was found."""
    if root is None:
        return False
    for n in root.iter("node"):
        if re.search(pattern, n.get("text") or "", re.I):
            m = re.findall(r"\d+", n.get("bounds") or "")
            if len(m) == 4:
                x1, y1, x2, y2 = map(int, m)
                sh("input tap %d %d" % ((x1 + x2) // 2, (y1 + y2) // 2))
                time.sleep(1)
                return True
    return False


def s_lite():
    """The «خفّف الشاشة» page: it opens, lists the factory apps, and its button answers without a crash."""
    open_ranet()
    f = None
    for _ in range(12):
        f = focused(dump())
        if f and "خفّف الشاشة" in f:
            break
        key("DPAD_DOWN", 0.5)
    log("focus on the lite card: %s" % f)
    expect(f is not None and "خفّف الشاشة" in f, "the «خفّف الشاشة» card is not reachable with the remote")
    key("DPAD_CENTER", 2)
    root = wait_text("احذف تطبيقات المصنع", save="lite")
    shot("lite")
    expect(root is not None, "the lite page did not open")
    log("lite page: " + texts(root)[:1200])
    f = focused(root)
    log("first focus on the lite page: %s" % f)
    key("DPAD_CENTER", 2)
    outcome = None
    end = time.time() + 40
    while time.time() < end and outcome is None:
        root = dump()
        t = texts(root)
        if tap_text(root, r"always allow|السماح دائم"):
            log("debugging prompt: allowed")
            tap_text(dump(), r"^(allow|ok|السماح|موافق)$")
            continue
        for want in ("خفّت الشاشة", "تصحيح الأخطاء طافي", "ما وصلت الموافقة", "صار خطأ"):
            if want in t and "لحظة" not in t:
                outcome = want
        time.sleep(1)
    shot("lite-after")
    log("lite outcome: %s, animator scale: %s" % (outcome, sh("settings get global animator_duration_scale").strip()))
    expect(outcome is not None, "the lighten button gave no answer")
    expect(outcome != "صار خطأ", "the lighten button failed: " + texts(dump("lite-error"))[:400])
    if outcome == "خفّت الشاشة":
        expect(sh("settings get global animator_duration_scale").strip() == "0.5", "animations were not made quicker")
        sh("settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; "
           "settings put global animator_duration_scale 1.0")
    key("BACK", 2)
    expect(wait_text("التركيز", timeout=10) is not None, "back did not return to the main page")


def finish():
    full = adb("logcat", "-d", "-v", "threadtime", timeout=90)
    with gzip.open(os.path.join(OUT, "logcat.txt.gz"), "wt", encoding="utf-8") as f:
        f.write(full)
    crash = adb("logcat", "-d", "-b", "crash", timeout=30)
    with open(os.path.join(OUT, "crash.txt"), "w", encoding="utf-8") as f:
        f.write(crash)
    crashed = PKG in crash
    with open(os.path.join(OUT, "summary.json"), "w", encoding="utf-8") as f:
        json.dump({"results": results, "ranet_crashed": crashed}, f, ensure_ascii=False, indent=1)
    for r in results:
        print("::%s title=%s::%s" % ("notice" if r["ok"] else "error", r["step"], r["error"] or "ok"), flush=True)
    failed = [r for r in results if not r["ok"]]
    raise SystemExit(1 if failed or crashed else 0)


if __name__ == "__main__":
    for name, fn in [
        ("setup", s_setup),
        ("turn-on", s_turn_on),
        ("probe-on-screen", s_probe_on_screen),
        ("probe-background", s_probe_background),
        ("probe-back", s_probe_back),
        ("blocked-list", s_blocked_list),
        ("accessibility", s_accessibility),
        ("turn-off", s_turn_off),
        ("lite", s_lite),
    ]:
        step(name, fn)
    finish()
