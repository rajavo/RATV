#!/usr/bin/env python3
"""Drives the RATV app on an emulator against the mock provider and records
screenshots, UI dumps and logs into smoke-results/ (CI only)."""
import gzip
import json
import os
import re
import struct
import subprocess
import time
import xml.etree.ElementTree as ET

OUT = os.path.abspath(os.environ.get("SMOKE_OUT", "smoke-results"))
os.makedirs(OUT, exist_ok=True)
PKG = "com.ratv.app"
results = []
pids = set()
counter = [0]


def log(msg):
    print(msg, flush=True)
    with open(os.path.join(OUT, "steps.log"), "a", encoding="utf-8") as f:
        f.write(time.strftime("%H:%M:%S ") + msg + "\n")


def adb(*args, timeout=60, binary=False):
    try:
        r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return b"" if binary else ""
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def shell(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def shot(name):
    counter[0] += 1
    data = adb("exec-out", "screencap", "-p", binary=True, timeout=30)
    path = os.path.join(OUT, "%02d-%s.png" % (counter[0], name))
    with open(path, "wb") as f:
        f.write(data)
    w, h = (struct.unpack(">II", data[16:24]) if data[:8] == b"\x89PNG\r\n\x1a\n" else (1080, 2400))
    log("screenshot %s %dx%d" % (os.path.basename(path), w, h))
    return w, h


SYSTEM_DIALOGS = ("isn't responding", "keeps stopping", "has stopped", "Viewing full screen")
notes = []


def dismiss_system_dialog(root):
    """Closes Android's "app isn't responding" dialogs (slow emulator) and notes them."""
    if root is None:
        return False
    texts = " | ".join((n.get("text") or "") for n in root.iter("node"))
    if not any(m in texts for m in SYSTEM_DIALOGS):
        return False
    log("system dialog: " + texts[:200])
    notes.append(texts[:200])
    for label in ("Wait", "Got it", "Close app", "Close", "OK"):
        for n in root.iter("node"):
            if (n.get("text") or "") == label:
                x1, y1, x2, y2 = bounds(n)
                shell("input tap %d %d" % ((x1 + x2) // 2, (y1 + y2) // 2))
                time.sleep(2)
                return True
    return False


def dump(save=None, tries=3, depth=0):
    for _ in range(tries):
        shell("rm -f /sdcard/ui.xml")
        shell("uiautomator dump /sdcard/ui.xml", timeout=45)
        xml = adb("exec-out", "cat", "/sdcard/ui.xml", timeout=20)
        i = xml.find("<hierarchy")
        if i >= 0:
            try:
                root = ET.fromstring(xml[i:])
            except ET.ParseError:
                root = None
            if root is not None:
                if depth < 4 and dismiss_system_dialog(root):
                    return dump(save, tries, depth + 1)
                if save:
                    with open(os.path.join(OUT, save + ".xml"), "w", encoding="utf-8") as f:
                        f.write(xml[i:])
                return root
        time.sleep(1.5)
    return None


def nodes(root):
    return list(root.iter("node")) if root is not None else []


def bounds(n):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else (0, 0, 0, 0)


def center(n):
    x1, y1, x2, y2 = bounds(n)
    return (x1 + x2) // 2, (y1 + y2) // 2


def matches(n, text=None, contains=None, cls=None):
    t = n.get("text") or ""
    d = n.get("content-desc") or ""
    if cls and n.get("class") != cls:
        return False
    if text is not None and text not in (t, d):
        return False
    if contains is not None and contains not in t and contains not in d:
        return False
    x1, y1, x2, y2 = bounds(n)
    return x2 > x1 and y2 > y1


def find(root, bottom=False, **kw):
    found = [n for n in nodes(root) if matches(n, **kw)]
    if not found:
        return None
    return max(found, key=lambda n: bounds(n)[1]) if bottom else found[0]


def wait_for(timeout=30, save=None, **kw):  # kw: text/contains/cls/bottom
    end = time.time() + timeout
    root = None
    while time.time() < end:
        root = dump()
        n = find(root, **kw)
        if n is not None:
            if save:
                dump(save)
            return root, n
        time.sleep(1.5)
    if save:
        dump(save + "-timeout")
    return root, None


def tap(x, y):
    shell("input tap %d %d" % (x, y))
    time.sleep(0.8)


def tap_node(n):
    x, y = center(n)
    tap(x, y)


def tap_where(timeout=20, **kw):
    _, n = wait_for(timeout=timeout, **kw)
    if n is None:
        raise RuntimeError("not found: %s" % kw)
    tap_node(n)
    return n


def type_text(s):
    shell("input text '%s'" % s.replace(" ", "%s"))
    time.sleep(0.6)


def back():
    shell("input keyevent 4")
    time.sleep(1.8)


def app_pid():
    return shell("pidof %s" % PKG).strip()


def fill_fields(values):
    for idx, value in values:
        root = dump()
        edits = [n for n in nodes(root) if n.get("class") == "android.widget.EditText"]
        if len(edits) <= idx:
            raise RuntimeError("only %d text fields" % len(edits))
        tap_node(edits[idx])
        type_text(value)


def step(name, fn):
    log("== " + name)
    ok, err = True, ""
    try:
        fn()
    except Exception as e:  # keep going so later screens are still checked
        ok, err = False, repr(e)
        log("!! " + err)
        try:
            shot("fail-" + name)
        except Exception:
            pass
    pid = app_pid()
    if pid:
        pids.update(pid.split())
    else:
        ok = False
        err = (err + " " if err else "") + "APP NOT RUNNING"
        log("!! app is not running")
        shell("am start -W -n %s/.MainActivity" % PKG)
        time.sleep(4)
    results.append({"step": name, "ok": ok, "error": err})


# ------------------------------------------------------------------- steps

def all_texts(root):
    return " | ".join((x.get("text") or "") for x in nodes(root))


def row_chip(root, row_text, chip_text):
    """The button with chip_text on the same row as the text row_text."""
    row = find(root, text=row_text)
    if row is None:
        return None
    _, ry = center(row)
    chips = [x for x in nodes(root) if (x.get("text") or "") == chip_text]
    if not chips:
        return None
    best = min(chips, key=lambda c: abs(center(c)[1] - ry))
    return best if abs(center(best)[1] - ry) < 120 else None


def open_from_controls(label, w, h):
    """Shows the player controls and taps the button with this label before they hide again."""
    tap(w // 2, h // 3)
    root = dump()
    pill = find(root, text=label) or find(root, contains=label)
    if pill is None:
        tap(w // 2, h // 3)
        root = dump()
        pill = find(root, text=label) or find(root, contains=label)
    if pill is None:
        raise RuntimeError("button missing: " + label)
    px, py = center(pill)
    time.sleep(5)  # the controls hide again by themselves
    shell("input tap %d %d" % (w // 2, h // 3))
    time.sleep(0.7)
    tap(px, py)
    time.sleep(1.5)


def leave_player():
    for _ in range(3):
        back()
        _, n = wait_for(timeout=4, text="البث المباشر")
        if n is not None:
            break


def s_launch():
    # Let the freshly booted emulator settle; its launcher is slow at first.
    shell("settings put global hide_error_dialogs 1")
    shell("settings put secure immersive_mode_confirmations confirmed")
    for _ in range(6):
        time.sleep(5)
        dump()
    shell("input keyevent 3")
    time.sleep(3)
    dump()
    adb("logcat", "-b", "all", "-c")
    # Subtitles come from the mock provider instead of the real online library.
    shell("setprop debug.ratv.subs http://10.0.2.2:8000")
    shell("setprop debug.ratv.speed http://10.0.2.2:8000")
    shell("setprop debug.ratv.update http://10.0.2.2:8000/update")
    log("debug.ratv.subs=" + shell("getprop debug.ratv.subs").strip())
    log(shell("am start -W -n %s/.MainActivity" % PKG).strip())
    time.sleep(1.5)
    shot("splash")


def s_login_screen():
    _, n = wait_for(timeout=40, text="أضف اشتراكك", save="login")
    if n is None:
        raise RuntimeError("login screen not shown")
    shot("login")


def s_wrong_password():
    # The server is typed without http:// on purpose: the app adds it.
    fill_fields([(0, "10.0.2.2:8000"), (1, "test"), (2, "wrong")])
    tap_where(text="دخول")
    root, n = wait_for(timeout=40, contains="غير صحيحة", save="login-error")
    shot("login-error")
    if n is None:
        raise RuntimeError("no error for a wrong password")
    if find(root, text="http://10.0.2.2:8000") is None:
        raise RuntimeError("server link was not completed with http://")


def s_xtream_login():
    root = dump()
    edits = [n for n in nodes(root) if n.get("class") == "android.widget.EditText"]
    tap_node(edits[2])
    shell("input keyevent 123")  # move to end
    for _ in range(8):
        shell("input keyevent 67")  # delete "wrong"
    type_text("test")
    shot("login-filled")
    tap_where(text="دخول")
    _, n = wait_for(timeout=90, text="الرئيسية", bottom=True, save="home")
    if n is None:
        raise RuntimeError("home not reached")
    time.sleep(6)
    dump("home")
    shot("home")


def s_live():
    tap_where(text="مباشر", bottom=True)
    _, n = wait_for(timeout=20, contains="RATV News HD", save="live")
    shot("live")
    if n is None:
        raise RuntimeError("channel list missing")


def s_live_category():
    tap_where(text="رياضة")
    time.sleep(1.5)
    dump("live-sport")
    shot("live-sport")
    tap_where(text="الكل")


def s_favorite():
    _, n = wait_for(timeout=10, text="إضافة إلى المفضلة")
    if n is None:
        raise RuntimeError("star button missing")
    tap_node(n)
    time.sleep(1)
    tap_where(text="المفضلة")
    time.sleep(1.5)
    shot("live-favorites")
    tap_where(text="الكل")


def s_play_live():
    _, n = wait_for(timeout=10, contains="Gulf 24 FHD")
    if n is None:
        raise RuntimeError("channel row missing")
    tap_node(n)
    time.sleep(12)
    w, h = shot("player-live")
    tap(w // 2, h // 3)
    time.sleep(1)
    root = dump("player-live-controls")
    shot("player-live-controls")
    if find(root, contains="الاحتياطي ضد انقطاع النت") is None:
        raise RuntimeError("reserve not shown with the controls")
    # Stream information: what the stream is and what playback went through.
    open_from_controls("معلومات البث", w, h)
    time.sleep(3)
    root = dump("stats")
    shot("stats")
    texts = all_texts(root)
    log("stats: " + texts[:500])
    if "إطارات ساقطة" not in texts or "التوقفات" not in texts:
        raise RuntimeError("stream info panel missing: " + texts[:200])
    back()
    time.sleep(2)
    shot("after-live-player")


def s_offline():
    """The internet drops for a while: the channel keeps playing from the reserve."""
    _, n = wait_for(timeout=10, contains="Gulf 24 FHD")
    if n is None:
        raise RuntimeError("channel row missing")
    tap_node(n)
    try:
        time.sleep(15)
        shell("svc wifi disable; svc data disable")
        time.sleep(4)
        a = adb("exec-out", "screencap", "-p", binary=True, timeout=30)
        time.sleep(6)
        b = adb("exec-out", "screencap", "-p", binary=True, timeout=30)
        root = dump("offline")
        shot("offline")
        texts = all_texts(root)
        log("offline: " + texts[:300])
        if "تعذّر" in texts or "انقطع البث" in texts:
            raise RuntimeError("playback stopped while offline: " + texts[:200])
        if a == b:
            raise RuntimeError("picture froze while offline")
    finally:
        shell("svc wifi enable; svc data enable")
    time.sleep(10)
    shot("back-online")
    back()
    time.sleep(2)


def s_commentator():
    """Sport 1 HD has two commentators and two other versions (4K and EN)."""
    tap_where(text="رياضة")
    time.sleep(1.5)
    _, n = wait_for(timeout=10, contains="Sport 1 HD")
    if n is None:
        raise RuntimeError("Sport 1 HD missing")
    tap_node(n)
    try:
        time.sleep(12)
        w, h = shot("player-sport1")
        tap(w // 2, h // 3)
        root = dump("player-sport1-controls")
        shot("player-sport1-controls")
        if find(root, text="4K متاح") is None:
            raise RuntimeError("4K shortcut missing")
        pill = find(root, text="المعلق والجودة")
        if pill is None:
            raise RuntimeError("commentator button missing")
        px, py = center(pill)
        time.sleep(5)  # the controls hide again by themselves
        shell("input tap %d %d" % (w // 2, h // 3))
        time.sleep(0.7)
        tap(px, py)  # open the panel while the controls are on screen
        time.sleep(1.5)
        shot("sources-panel")
        texts = ""
        root = None
        for _ in range(4):
            root = dump("sources-panel")
            texts = " | ".join((x.get("text") or "") for x in nodes(root))
            if "نسخ أخرى من القناة" in texts:
                break
            time.sleep(1)
        log("sources: " + texts[:400])
        for need in ("المعلق في هذا البث", "نسخ أخرى من القناة", "Sport 1 4K", "تنقل نفس المباراة الآن", "Sky Sports Main"):
            if need not in texts:
                raise RuntimeError("missing in panel: " + need)
        second = [x for x in nodes(root) if (x.get("text") or "") in ("الإنجليزية", "الإنكليزية", "English")]
        if not second:
            raise RuntimeError("second commentator not listed")
        tap_node(second[0])
        time.sleep(1.5)
        shot("sources-second-commentator")
        root = dump()
        chip = row_chip(root, "Sport 1 4K", "انتقال")
        if chip is None:
            raise RuntimeError("switch button for the 4K version missing")
        tap_node(chip)
        time.sleep(10)
        shot("player-sport1-4k")
    finally:
        # Leave the player (closing the panel first if it is still open).
        for _ in range(3):
            back()
            _, n = wait_for(timeout=4, text="البث المباشر")
            if n is not None:
                break
        tap_where(text="الكل")


def s_commentary_mix():
    """Sky Sports Main shows the same game: its commentary plays over Sport 1 HD's picture."""
    tap_where(text="رياضة")
    time.sleep(1.5)
    _, n = wait_for(timeout=10, contains="Sport 1 HD")
    if n is None:
        raise RuntimeError("Sport 1 HD missing")
    tap_node(n)
    try:
        time.sleep(12)
        w, h = shot("mix-player")
        open_from_controls("المعلق والجودة", w, h)
        root = None
        for _ in range(5):
            root = dump("mix-panel")
            if find(root, text="Sky Sports Main") is not None:
                break
            time.sleep(1.5)
        shot("mix-panel")
        texts = all_texts(root)
        log("mix panel: " + texts[:500])
        for need in ("تنقل نفس المباراة الآن", "Sky Sports Main"):
            if need not in texts:
                raise RuntimeError("missing in panel: " + need)
        chip = row_chip(root, "Sky Sports Main", "سماع معلقها")
        if chip is None:
            raise RuntimeError("listen button missing")
        tap_node(chip)
        time.sleep(3)
        _, n = wait_for(timeout=10, text="تسمع الآن معلق", save="mix-on")
        shot("mix-on")
        if n is None:
            raise RuntimeError("commentary from the other channel did not start")
        tap_where(text="+½")
        time.sleep(1.5)
        _, n = wait_for(timeout=8, contains="الصوت متأخر 0.5", save="mix-shifted")
        shot("mix-shifted")
        if n is None:
            raise RuntimeError("sync buttons did not move the sound")
        back()  # close the panel and keep listening for a moment
        time.sleep(6)
        root = dump("mix-playing")
        shot("mix-playing")
        if find(root, contains="معلق Sky Sports Main") is None:
            raise RuntimeError("no sign on screen that another commentary is playing")
    finally:
        leave_player()
        tap_where(text="الكل")


ENGLISH_LINES = ("Welcome to the silent sea", "The water is calm tonight", "We sail at dawn")
UI_TEXTS = ("الترجمة", "رجوع", "مباشر")


def subtitle_on_screen(root):
    for x in nodes(root):
        t = (x.get("text") or "").strip()
        if len(t) >= 4 and t not in UI_TEXTS:
            return t
    return None


def s_subtitles():
    """Finds subtitles for Silent Sea and shows the English one, machine-translated into Arabic."""
    tap_where(text="أفلام", bottom=True)
    _, n = wait_for(timeout=30, contains="Silent Sea", save="movies-subs")
    if n is None:
        raise RuntimeError("Silent Sea missing")
    tap_node(n)
    _, n = wait_for(timeout=20, text="تشغيل")
    if n is None:
        raise RuntimeError("play button missing")
    tap_node(n)
    time.sleep(15)
    w, h = shot("subs-player")
    open_from_controls("الترجمة", w, h)
    root = None
    for _ in range(8):
        root = dump("subs-panel")
        if find(root, contains="Silent.Sea.2019") is not None:
            break
        time.sleep(2)
    shot("subs-panel")
    log("subtitles panel: " + all_texts(root)[:500])
    en = find(root, text="Silent.Sea.2019.1080p.WEB")
    if en is None:
        raise RuntimeError("subtitle search found nothing")
    if find(root, text="Silent.Sea.2019.AR") is None:
        raise RuntimeError("Arabic subtitle not listed")
    tap_node(en)
    time.sleep(3)
    shot("subs-chosen")
    back()  # close the panel
    seen = None
    for _ in range(10):
        time.sleep(3)
        root = dump()
        seen = subtitle_on_screen(root)
        if seen:
            break
    shot("subs-on-screen")
    log("subtitle on screen: %r" % seen)
    if not seen:
        raise RuntimeError("no subtitle line over the film")


def s_subtitles_translated():
    """The English lines turn into Arabic once the on-device translation model is ready."""
    try:
        arabic = None
        for _ in range(40):
            root = dump()
            t = subtitle_on_screen(root)
            if t and any(0x0600 <= ord(c) <= 0x06FF for c in t):
                arabic = t
                break
            time.sleep(4)
        shot("subs-translated")
        log("translated line: %r" % arabic)
        if not arabic:
            raise RuntimeError("subtitles were not translated into Arabic")
    finally:
        back()
        time.sleep(2)
        back()
        time.sleep(1.5)


def s_guide():
    tap_where(text="دليل البرامج")
    time.sleep(4)
    dump("guide")
    shot("guide")
    back()


def s_movies():
    tap_where(text="أفلام", bottom=True)
    _, n = wait_for(timeout=30, contains="Desert Night", save="movies")
    shot("movies")
    if n is None:
        raise RuntimeError("movies missing")
    tap_node(n)
    _, n = wait_for(timeout=20, text="تشغيل", save="movie-detail")
    time.sleep(2)
    shot("movie-detail")
    if n is None:
        raise RuntimeError("play button missing")
    offered_4k = find(dump(), text="4K متاح") is not None
    log("4K copy offered: %s" % offered_4k)
    tap_node(n)
    time.sleep(25)
    w, h = shot("player-movie")
    tap(w // 2, h // 3)
    time.sleep(1)
    shot("player-movie-controls")
    back()
    _, n = wait_for(timeout=15, contains="متابعة من", save="movie-detail-resume")
    shot("movie-detail-resume")
    back()
    if n is None:
        raise RuntimeError("resume point not saved")
    if not offered_4k:
        raise RuntimeError("the 4K copy of Desert Night was not offered")


def s_series():
    tap_where(text="مسلسلات", bottom=True)
    _, n = wait_for(timeout=30, contains="Sands of Time", save="series")
    shot("series")
    if n is None:
        raise RuntimeError("series missing")
    tap_node(n)
    _, n = wait_for(timeout=25, contains="الحلقة 1", save="series-detail")
    time.sleep(1.5)
    shot("series-detail")
    if n is None:
        raise RuntimeError("episodes missing")
    _, s2 = wait_for(timeout=5, text="الموسم 2")
    if s2 is not None:
        tap_node(s2)
        time.sleep(1.5)
        shot("series-season2")
        tap_where(text="الموسم 1")
    _, n = wait_for(timeout=10, contains="الحلقة 2")
    tap_node(n)
    time.sleep(14)
    shot("player-episode")
    back()
    time.sleep(2)
    dump("series-detail-after")
    shot("series-detail-after")
    back()


def s_home_after():
    tap_where(text="الرئيسية", bottom=True)
    _, n = wait_for(timeout=15, text="تابع المشاهدة", save="home-after")
    shot("home-after")
    if n is None:
        raise RuntimeError("continue watching missing")


def s_search():
    tap_where(text="بحث")
    time.sleep(1.5)
    type_text("Night")
    time.sleep(2.5)
    root = dump("search")
    shot("search")
    back()
    texts = all_texts(root)
    # The parental filter is still off here, so the adult test titles are found.
    if "Desert Night" not in texts or "Night Club" not in texts:
        raise RuntimeError("search results incomplete: " + texts[:300])


def scroll_to(text, tries=6):
    """Finds a row on a long screen: looks down the page first, then back up."""
    for swipe in ("540 1700 540 900", "540 900 540 1700"):
        for _ in range(tries):
            _, n = wait_for(timeout=3, contains=text)
            if n is not None:
                return n
            shell("input swipe %s 900" % swipe)
            time.sleep(2.0)  # let the list stop before reading where rows are
    return None


def s_adult_hidden():
    """With the filter on, adult titles are gone everywhere, search included."""
    tap_where(text="الرئيسية", bottom=True)
    time.sleep(1.5)
    tap_where(text="بحث")
    time.sleep(1.5)
    type_text("Night")
    time.sleep(2.5)
    root = dump("search-adult")
    shot("search-adult")
    back()
    texts = all_texts(root)
    if "Desert Night" not in texts:
        raise RuntimeError("search did not run: " + texts[:300])
    if "Night Club" in texts or "Night Party" in texts:
        raise RuntimeError("adult titles still shown: " + texts[:300])


def s_speed_test():
    tap_where(text="حسابي", bottom=True)
    time.sleep(1.5)
    for _ in range(5):  # back to the top of the settings
        shell("input swipe 540 700 540 1900 200")
    time.sleep(2.0)
    if scroll_to("قياس سرعة النت") is None:
        raise RuntimeError("speed test row missing")
    tap_where(contains="قياس سرعة النت")
    texts = ""
    for _ in range(25):
        time.sleep(2)
        texts = all_texts(dump())
        if "يكفي" in texts or "ضعيف" in texts or "تعذّر" in texts:
            break
    dump("speed-test")
    shot("speed-test")
    log("speed: " + texts[:300])
    tap_where(text="إغلاق")
    if "يكفي" not in texts and "ضعيف" not in texts:
        raise RuntimeError("speed test gave no result: " + texts[:300])


def play_test_channel(name, seconds, tag):
    """Opens a test channel from the full list, plays it, and returns the stall count it shows."""
    tap_where(text="الكل")
    time.sleep(1)
    n = None
    for _ in range(6):
        _, n = wait_for(timeout=4, contains=name)
        if n is not None:
            break
        shell("input swipe 540 1700 540 900 600")
        time.sleep(1.5)
    if n is None:
        raise RuntimeError("channel missing: " + name)
    tap_node(n)
    try:
        time.sleep(seconds)
        w, h = shot(tag + "-playing")
        open_from_controls("معلومات البث", w, h)
        time.sleep(2)
        root = dump(tag + "-stats")
        shot(tag + "-stats")
        texts = all_texts(root)
        log("%s: %s" % (tag, texts[:500]))
        parts = [t.strip() for t in texts.split("|")]
        for i, t in enumerate(parts):
            if t == "التوقفات":
                stalls = next((int(x) for x in parts[i + 1:i + 4] if x.isdigit()), None)
                if stalls is not None:
                    return stalls
        raise RuntimeError("stall count not shown: " + texts[:200])
    finally:
        back()
        time.sleep(2)


def server_connections(sid):
    try:
        import urllib.request
        return json.loads(urllib.request.urlopen("http://127.0.0.1:8000/__stats", timeout=5).read()).get(str(sid), 0)
    except Exception as e:
        log("stats: %r" % e)
        return -1


def s_unstable():
    """The server cuts the channel every 7 s: the picture must go on without stalls."""
    stalls = play_test_channel("Unstable Live", 40, "unstable")
    if stalls > 1:
        raise RuntimeError("the channel stalled %d times while the server kept cutting it" % stalls)


def s_frozen():
    """Every 12 s the line stops delivering without closing: RATV must reconnect before the store runs out."""
    stalls = play_test_channel("Frozen Live", 50, "frozen")
    conns = server_connections(108)
    log("frozen: stalls %d, connections %d" % (stalls, conns))
    if conns < 3:
        raise RuntimeError("RATV did not reconnect after the line froze (%d connections)" % conns)
    if stalls > 1:
        raise RuntimeError("the channel stalled %d times when the line froze" % stalls)


def s_chunky():
    """A healthy server that sends in 2 s chunks: no stalls, and no needless reconnects."""
    stalls = play_test_channel("Chunky Live", 40, "chunky")
    conns = server_connections(109)
    log("chunky: stalls %d, connections %d" % (stalls, conns))
    if stalls > 0:
        raise RuntimeError("the chunked channel stalled %d times" % stalls)
    if conns > 2:
        raise RuntimeError("a healthy chunked stream was cut %d times" % (conns - 1))


def live_log(*needles):
    lines = adb("logcat", "-d", "-s", "RATVLive:I", "RATVPlayback:I", timeout=30).splitlines()
    return {n: sum(1 for l in lines if n in l) for n in needles}


def s_overlap():
    """Each reconnect repeats the last 4 s: RATV must join the streams without jumping back."""
    keys = ("joined the new connection", "position went back", "could not join")
    before = live_log(*keys)
    stalls = play_test_channel("Overlap Live", 40, "overlap")
    after = live_log(*keys)
    delta = {k: after[k] - before[k] for k in keys}
    log("overlap: stalls %d, log %s" % (stalls, delta))
    if stalls > 1:
        raise RuntimeError("the channel stalled %d times" % stalls)
    if delta["joined the new connection"] < 2:
        raise RuntimeError("new connections were not joined to the old ones: %s" % delta)
    if delta["position went back"] > 0:
        raise RuntimeError("the picture jumped back after a reconnect: %s" % delta)


def s_weak():
    """A weak server stops for 7 s every 12 s: after at most one stall RATV stores more and plays on."""
    before = live_log("steadiness level", "RATVPlayback: stall")
    stalls = play_test_channel("Weak Server", 90, "weak")
    after = live_log("steadiness level", "RATVPlayback: stall")
    log("weak: stalls %d, levels %d, connections %d" % (
        stalls, after["steadiness level"] - before["steadiness level"], server_connections(112)))
    if stalls > 2:
        raise RuntimeError("the weak server's channel stalled %d times in 90 s" % stalls)


def s_busy():
    """A busy server refuses the first two connections: RATV tries again and plays."""
    stalls = play_test_channel("Busy Server", 25, "busy")
    with open(os.path.join(OUT, "busy-stats.xml"), encoding="utf-8") as f:
        texts = f.read()
    log("busy: stalls %d, refused %d times" % (stalls, server_connections(113)))
    if "1280×720" not in texts and "1280&#215;720" not in texts:
        raise RuntimeError("the busy server's channel did not play")


def s_shaky():
    """Too slow as TS: after it stalls, RATV switches the channel to HLS and plays on."""
    shell("setprop debug.ratv.hlsafter 1")
    try:
        stalls = play_test_channel("Shaky TS", 70, "shaky")
    finally:
        shell("setprop debug.ratv.hlsafter ''")
    with open(os.path.join(OUT, "shaky-stats.xml"), encoding="utf-8") as f:
        texts = f.read()
    try:
        import urllib.request
        stats = json.loads(urllib.request.urlopen("http://127.0.0.1:8000/__stats", timeout=5).read())
    except Exception:
        stats = {}
    log("shaky: stalls after the switch %d, playlist requests %s, TS connections %s" % (
        stalls, stats.get("hls114"), stats.get("114")))
    if not stats.get("hls114"):
        raise RuntimeError("the channel was not tried as HLS")
    if 'text="HLS"' not in texts:
        raise RuntimeError("the channel is not playing as HLS at the end")
    if stalls > 1:
        raise RuntimeError("the channel stalled %d times after switching to HLS" % stalls)


def s_version_switch():
    """A channel that keeps stalling moves by itself to its lighter version (Stutter TV HD to SD)."""
    shell("setprop debug.ratv.hlsafter 99")  # straight to the other version, not HLS first
    keys = ("switching to another version", "stalled after the load ended", "starting the channel again")
    start = live_log(*keys)
    before = start["switching to another version"]
    tap_where(text="الكل")
    time.sleep(1)
    n = None
    for _ in range(6):
        _, n = wait_for(timeout=4, contains="Stutter TV HD")
        if n is not None:
            break
        shell("input swipe 540 1700 540 900 600")
        time.sleep(1.5)
    if n is None:
        raise RuntimeError("channel missing: Stutter TV HD")
    tap_node(n)
    try:
        switched = False
        notice = False
        deadline = time.time() + 150  # up to 2.5 minutes, however slow the emulator is
        while time.time() < deadline:
            time.sleep(2.5)
            if not notice:
                root = dump()
                if "انتقلنا لـ" in all_texts(root):
                    notice = True
                    shot("version-notice")
                    dump("version-notice")
            if live_log("switching to another version")["switching to another version"] > before:
                switched = True
                if notice:
                    break
        lines = [l for l in adb("logcat", "-d", "-s", "RATVPlayback:I", timeout=30).splitlines() if "another version" in l]
        log("version switch: %s" % (lines[-1] if lines else "(none)"))
        end = live_log(*keys)
        log("version switch: %s" % {k: end[k] - start[k] for k in keys})
        if not switched:
            raise RuntimeError("the stalling channel did not move to its other version")
        if not lines or "Stutter TV SD" not in lines[-1]:
            raise RuntimeError("it moved to the wrong version: %s" % (lines[-1] if lines else ""))
        if not notice:
            raise RuntimeError("no notice told the viewer about the switch")
        time.sleep(8)
        shot("version-after")
    finally:
        shell("setprop debug.ratv.hlsafter ''")
        back()
        time.sleep(2)


def s_palette():
    """«ألوان التطبيق»: another colour repaints the app at once, and orange comes back."""
    if scroll_to("ألوان التطبيق") is None:
        raise RuntimeError("the colours row is missing from the settings")
    tap_where(contains="ألوان التطبيق")
    _, n = wait_for(timeout=10, text="أزرق ليلي", save="palette-dialog")
    shot("palette-dialog")
    if n is None:
        raise RuntimeError("the colour choices did not open")
    tap_node(n)
    time.sleep(1)
    tap_where(text="تم")
    time.sleep(1.5)
    shot("palette-blue-settings")
    tap_where(text="الرئيسية", bottom=True)
    time.sleep(2.5)
    root = dump("palette-blue-home")
    shot("palette-blue-home")
    tap_where(text="حسابي", bottom=True)
    time.sleep(2)
    scroll_to("ألوان التطبيق")
    tap_where(contains="ألوان التطبيق")
    tap_where(timeout=10, text="برتقالي RA")
    time.sleep(1)
    tap_where(text="تم")
    time.sleep(1)
    shown = find(dump(), contains="برتقالي RA") is not None
    for _ in range(6):  # back to the top of the settings, where the next steps start
        shell("input swipe 540 900 540 1700 300")
        time.sleep(0.8)
    if not shown:
        raise RuntimeError("the colours row does not show the chosen colour")


def s_matches():
    """The schedule lists the test game once, with every channel showing it; a channel plays."""
    tap_where(text="جدول المباريات")
    _, n = wait_for(timeout=20, contains="ريال مدريد", save="matches")
    shot("matches")
    if n is None:
        raise RuntimeError("the game is missing from the schedule")
    root = dump()
    texts = all_texts(root)
    log("matches: " + texts[:400])
    for need in ("جدول المباريات", "Sky Sports Main", "Sport 1 HD", "قناة ناقلة"):
        if need not in texts:
            raise RuntimeError("schedule is missing " + need)
    tap_where(text="Sky Sports Main")
    time.sleep(8)
    shot("matches-play")
    back()
    time.sleep(2)
    back()
    time.sleep(2)


def match_uri(home, away, home_ar, away_ar, league="", minutes_from_now=-15):
    """The link the schedule app (جدول RA كورة) opens RATV with."""
    from urllib.parse import quote
    start = int((time.time() + minutes_from_now * 60) * 1000)
    return "ratv://match?home=%s&away=%s&homeAr=%s&awayAr=%s&start=%d&league=%s" % (
        quote(home), quote(away), quote(home_ar), quote(away_ar), start, quote(league))


def open_match_link(uri):
    # Single quotes keep the "&" of the link away from the device's shell.
    log(shell("am start -W -a android.intent.action.VIEW -p com.ratv.app -d '%s'" % uri).strip()[:300])


def s_match_link():
    """A match from the schedule app: RATV lists the channels of the test guide showing it, one plays."""
    open_match_link(match_uri("Real Madrid", "Barcelona", "ريال مدريد", "برشلونة", "الدوري الإسباني"))
    _, n = wait_for(timeout=25, contains="وين تنعرض المباراة", save="match-link")
    shot("match-link")
    if n is None:
        raise RuntimeError("the match link did not open the channels screen")
    _, n = wait_for(timeout=20, text="Sky Sports Main", save="match-link-found")
    root = dump()
    texts = all_texts(root)
    log("match link: " + texts[:500])
    for need in ("ريال مدريد × برشلونة", "Sky Sports Main", "Sport 1 HD", "اختر وحدة"):
        if need not in texts:
            raise RuntimeError("the channels screen is missing " + need)
    if "News" in texts or "Kids Fun" in texts:
        raise RuntimeError("channels that do not show the match are listed: " + texts[:300])
    tap_where(text="Sky Sports Main")
    time.sleep(8)
    shot("match-link-play")
    root = dump("match-link-play")
    if find(root, contains="وين تنعرض المباراة") is not None:
        raise RuntimeError("the channel did not open from the match link")
    back()
    time.sleep(2)


def s_match_link_missing():
    """A match the guide does not have: RATV says so and offers the sports channels."""
    open_match_link(match_uri("Al Hilal", "Al Nassr", "الهلال", "النصر", "دوري روشن السعودي", minutes_from_now=3 * 24 * 60 + 60))
    _, n = wait_for(timeout=25, contains="ما لقينا المباراة", save="match-link-missing")
    shot("match-link-missing")
    if n is None:
        raise RuntimeError("no message for a match missing from the guide")
    texts = all_texts(dump())
    for need in ("الهلال × النصر", "بعد 3 أيام", "قنوات الرياضة في اشتراكك", "Sport 1 HD"):
        if need not in texts:
            raise RuntimeError("the missing-match screen lacks " + need + ": " + texts[:400])
    back()
    time.sleep(2)


def s_kora_button():
    """«جدول 30 يوم» on the matches screen: the schedule app is not installed, so RATV offers it."""
    # The trophy button on the live channels opens the matches screen (as in s_matches).
    tap_where(text="مباشر", bottom=True)
    time.sleep(2)
    tap_where(text="جدول المباريات")
    time.sleep(2)
    tap_where(text="جدول 30 يوم")
    _, n = wait_for(timeout=10, text="ثبّت التطبيق", save="kora-offer")
    shot("kora-offer")
    if n is None:
        raise RuntimeError("no offer to install the schedule app")
    tap_where(text="لاحقاً")
    time.sleep(1)
    tap_where(text="الرئيسية", bottom=True)
    time.sleep(2)


def s_update():
    """Account > Update the app: finds the newer version, downloads it, opens Android's installer."""
    tap_where(text="حسابي", bottom=True)
    time.sleep(2)
    if scroll_to("تحديث التطبيق") is None:
        raise RuntimeError("update row missing")
    tap_where(contains="تحديث التطبيق")
    _, n = wait_for(timeout=20, text="تحديث جديد متاح", save="update-dialog")
    shot("update-dialog")
    if n is None:
        raise RuntimeError("no update offered")
    tap_where(text="تحديث الآن")
    focus = ""
    for _ in range(30):
        time.sleep(2)
        focus = shell("dumpsys window | grep -E 'mCurrentFocus'")
        if "packageinstaller" in focus.lower() or "settings" in focus.lower():
            break
    shot("update-installer")
    log("after update: " + focus.strip()[:200])
    back()
    time.sleep(2)
    _, n = wait_for(timeout=10, text="لاحقاً")
    if n is not None:
        tap_node(n)
    if "packageinstaller" not in focus.lower() and "settings" not in focus.lower():
        raise RuntimeError("installer did not open: " + focus.strip()[:200])


def s_clear_history():
    if scroll_to("حذف سجل المشاهدة") is None:
        raise RuntimeError("clear history row missing")
    tap_where(contains="حذف سجل المشاهدة")
    time.sleep(1)
    shot("clear-history-dialog")
    tap_where(text="حذف")
    time.sleep(1.5)
    tap_where(text="الرئيسية", bottom=True)
    time.sleep(2.5)
    root = dump("home-history-cleared")
    shot("home-history-cleared")
    if find(root, text="تابع المشاهدة") is not None:
        raise RuntimeError("continue watching still shown after clearing the history")


def s_users():
    """Both users are kept; switching back brings the first one with its favourites."""
    tap_where(text="حسابي", bottom=True)
    time.sleep(2)
    root = dump("users")
    shot("users")
    texts = all_texts(root)
    for need in ("اشتراكي", "قائمتي", "الحالي"):
        if need not in texts:
            raise RuntimeError("users list is missing " + need + ": " + texts[:300])
    tap_where(text="اشتراكي")
    _, n = wait_for(timeout=60, text="الرئيسية", bottom=True, save="home-user1")
    if n is None:
        raise RuntimeError("home not reached after switching user")
    time.sleep(3)
    tap_where(text="مباشر", bottom=True)
    time.sleep(2)
    tap_where(text="المفضلة")
    time.sleep(2)
    root = dump("user1-favorites")
    shot("user1-favorites")
    texts = all_texts(root)
    names = ("Sport 1 HD", "Sport 2 4K", "Racing SD", "Sport 1 4K", "Sport 1 EN", "Sky Sports Main", "RATV News HD", "Gulf 24 FHD")
    if not any(c in texts for c in names):
        raise RuntimeError("favourites of the first user were lost: " + texts[:300])
    tap_where(text="الكل")


def s_settings():
    tap_where(text="حسابي", bottom=True)
    time.sleep(2)
    dump("settings")
    shot("settings")


def s_parental():
    # Settings got longer: scroll until the parental row shows.
    for _ in range(4):
        _, n = wait_for(timeout=3, contains="حجب محتوى البالغين")
        if n is not None:
            break
        shell("input swipe 540 1700 540 900 400")
        time.sleep(1.2)
    tap_where(contains="حجب محتوى البالغين")
    time.sleep(1.5)
    fill_fields([(0, "1234")])
    shot("pin-dialog")
    tap_where(text="تأكيد")
    time.sleep(1)
    shot("settings-parental-on")


def s_switch_to_m3u():
    tap_where(text="حسابي", bottom=True)
    time.sleep(1.5)
    tap_where(text="إضافة مستخدم")
    time.sleep(1.5)
    _, n = wait_for(timeout=20, text="أضف اشتراكك")
    if n is None:
        raise RuntimeError("login screen not shown after switching")
    tap_where(text="رابط M3U")
    # A link the app cannot use gets a clear message.
    fill_fields([(0, "10.0.2.2:port/playlist.m3u")])
    tap_where(text="إضافة القائمة")
    _, n = wait_for(timeout=15, contains="رابط القائمة غير صحيح", save="login-m3u-bad")
    shot("login-m3u-bad")
    if n is None:
        raise RuntimeError("no message for a broken M3U link")
    # The right link typed without http:// works.
    root = dump()
    edits = [x for x in nodes(root) if x.get("class") == "android.widget.EditText"]
    tap_node(edits[0])
    shell("input keyevent 123")  # move to end
    shell("input keyevent " + " ".join(["67"] * 45))
    time.sleep(0.5)
    type_text("10.0.2.2:8000/playlist.m3u")
    shot("login-m3u")
    tap_where(text="إضافة القائمة")
    _, n = wait_for(timeout=90, text="الرئيسية", save="home-m3u")
    if n is None:
        raise RuntimeError("home not reached with M3U")
    time.sleep(6)
    shot("home-m3u")


def s_m3u_content():
    tap_where(text="مباشر", bottom=True)
    _, n = wait_for(timeout=20, contains="Sport 1 HD", save="live-m3u")
    shot("live-m3u")
    if n is None:
        raise RuntimeError("M3U channels missing")
    tap_node(n)
    time.sleep(12)
    shot("player-m3u")
    back()
    tap_where(text="مسلسلات", bottom=True)
    _, n = wait_for(timeout=20, contains="City Lights", save="series-m3u")
    shot("series-m3u")
    if n is None:
        raise RuntimeError("M3U series missing")
    tap_node(n)
    time.sleep(3)
    dump("series-detail-m3u")
    shot("series-detail-m3u")
    back()


def s_relaunch():
    shell("am force-stop %s" % PKG)
    time.sleep(1)
    shell("am start -W -n %s/.MainActivity" % PKG)
    _, n = wait_for(timeout=40, text="الرئيسية", save="relaunch")
    shot("relaunch")
    if n is None:
        raise RuntimeError("saved playlist not restored after restart")


def finish():
    logcat = adb("logcat", "-d", "-v", "threadtime", timeout=90)
    with gzip.open(os.path.join(OUT, "logcat-full.txt.gz"), "wt", encoding="utf-8") as f:
        f.write(logcat)
    keep = []
    for line in logcat.splitlines():
        parts = line.split()
        if len(parts) > 3 and parts[2] in pids:
            keep.append(line)
        elif "AndroidRuntime" in line or "FATAL" in line or ("ActivityManager" in line and PKG in line):
            keep.append(line)
    with open(os.path.join(OUT, "logcat-app.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(keep))
    crash = adb("logcat", "-d", "-b", "crash", timeout=30)
    with open(os.path.join(OUT, "crash.txt"), "w", encoding="utf-8") as f:
        f.write(crash)
    summary = {
        "results": results,
        "system_dialogs": notes,
        "anr": [l for l in logcat.splitlines() if "ANR in" in l][:20],
        "pids": sorted(pids),
        "crash_buffer_lines": len(crash.splitlines()),
        "fatal": [l for l in keep if "FATAL" in l or "AndroidRuntime" in l][:100],
    }
    with open(os.path.join(OUT, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=1)
    for r in results:
        print("::%s title=%s::%s" % ("notice" if r["ok"] else "error", r["step"], r["error"] or "ok"), flush=True)


if __name__ == "__main__":
    for name, fn in [
        ("launch", s_launch),
        ("login-screen", s_login_screen),
        ("wrong-password", s_wrong_password),
        ("xtream-login", s_xtream_login),
        ("live", s_live),
        ("live-category", s_live_category),
        ("favorite", s_favorite),
        ("play-live", s_play_live),
        ("offline", s_offline),
        ("unstable", s_unstable),
        ("frozen", s_frozen),
        ("chunky", s_chunky),
        ("overlap", s_overlap),
        ("weak", s_weak),
        ("busy", s_busy),
        ("shaky", s_shaky),
        ("version-switch", s_version_switch),
        ("matches", s_matches),
        ("commentator", s_commentator),
        ("commentary-mix", s_commentary_mix),
        ("guide", s_guide),
        ("movies", s_movies),
        ("subtitles", s_subtitles),
        ("subtitles-translated", s_subtitles_translated),
        ("series", s_series),
        ("home-after", s_home_after),
        ("search", s_search),
        ("settings", s_settings),
        ("palette", s_palette),
        ("parental", s_parental),
        ("adult-hidden", s_adult_hidden),
        ("speed-test", s_speed_test),
        ("clear-history", s_clear_history),
        ("update", s_update),
        ("relaunch", s_relaunch),
        ("switch-to-m3u", s_switch_to_m3u),
        ("m3u-content", s_m3u_content),
        ("users", s_users),
        ("match-link", s_match_link),
        ("match-link-missing", s_match_link_missing),
        ("kora-button", s_kora_button),
    ]:
        step(name, fn)
    finish()
