#!/usr/bin/env python3
"""Drives RATV on an Android TV emulator with remote keys only (CI only)."""
import os
import sys
import time

os.environ.setdefault("SMOKE_OUT", "smoke-results/tv")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import smoke as S  # noqa: E402

NAV = ["الرئيسية", "مباشر", "أفلام", "مسلسلات", "حسابي"]


# Keys go through the emulator's hardware keyboard so Android sees a real D-pad,
# like a TV remote (adb "input" events come from a virtual device instead).
EMU_KEYS = {"DPAD_UP": 103, "DPAD_DOWN": 108, "DPAD_LEFT": 105, "DPAD_RIGHT": 106, "DPAD_CENTER": 28, "BACK": 158}
_emu_ok = [None]


def key(name, n=1, pause=1.0):
    for _ in range(n):
        code = EMU_KEYS.get(name)
        sent = False
        if code is not None and _emu_ok[0] is not False:
            out = S.adb("emu", "event", "send", "EV_KEY:%d:1" % code, "EV_SYN:0:0", "EV_KEY:%d:0" % code, "EV_SYN:0:0", timeout=20)
            if _emu_ok[0] is None:
                S.log("emu event output: %r" % out.strip()[:200])
                _emu_ok[0] = "KO" not in out and "unknown" not in out.lower()
            sent = _emu_ok[0] is True
        if not sent:
            S.shell("input keyevent KEYCODE_%s" % name)
        time.sleep(pause)


def focused_node(root):
    for n in S.nodes(root):
        if n.get("focused") == "true":
            return n
    return None


def focused_text(root):
    n = focused_node(root)
    if n is None:
        return None
    texts = []
    for m in n.iter("node"):
        t = (m.get("text") or "") or (m.get("content-desc") or "")
        if t:
            texts.append(t)
    return " | ".join(texts) if texts else "(%s, no text)" % n.get("class")


def log_focus(tag):
    root = S.dump(tag)
    f = focused_text(root)
    S.log("focus[%s]: %s" % (tag, f))
    return root, f


def ime_shown():
    return "mInputShown=true" in S.shell("dumpsys input_method")


def expect(cond, message):
    if not cond:
        raise RuntimeError(message)


def go_tab(label):
    """Moves to the side bar with the remote and opens a section."""
    for _ in range(5):
        _, f = log_focus("nav-search")
        if f in NAV:
            break
        key("DPAD_RIGHT")
    _, f = log_focus("nav-start")
    expect(f in NAV, "side bar not reached (focus: %s)" % f)
    step = NAV.index(label) - NAV.index(f)
    key("DPAD_DOWN" if step > 0 else "DPAD_UP", abs(step))
    _, f = log_focus("nav-target")
    expect(f == label, "side bar item %s not focused (focus: %s)" % (label, f))
    key("DPAD_CENTER", pause=2.0)


# ------------------------------------------------------------------- steps

def t_login_screen():
    _, n = S.wait_for(timeout=40, text="أضف اشتراكك", save="login")
    expect(n is not None, "login screen not shown")
    time.sleep(1.5)
    root, f = log_focus("login-initial")
    S.shot("login")
    node = focused_node(root)
    expect(node is not None and node.get("class") == "android.widget.EditText", "first field not focused (focus: %s)" % f)
    expect(not ime_shown(), "keyboard opened without pressing OK")


def t_keyboard_on_ok():
    key("DPAD_CENTER", pause=2.5)
    shown = ime_shown()
    S.shot("login-keyboard")
    expect(shown, "OK did not open the keyboard")
    key("BACK", pause=1.5)
    _, n = S.wait_for(timeout=10, text="أضف اشتراكك")
    expect(n is not None, "Back closed the login screen instead of the keyboard")
    expect(not ime_shown(), "keyboard still open after Back")


def t_fill_with_remote():
    S.type_text("http://10.0.2.2:8000")
    key("DPAD_DOWN")
    S.type_text("test")
    key("DPAD_DOWN")
    S.type_text("test")
    key("DPAD_DOWN")
    _, f = log_focus("login-name")
    key("DPAD_DOWN")
    _, f = log_focus("login-submit")
    S.shot("login-filled")
    expect(f is not None and "دخول" in f, "submit button not reached with the down arrow (focus: %s)" % f)
    key("DPAD_CENTER", pause=2.0)
    _, n = S.wait_for(timeout=90, text="الرئيسية", save="home")
    expect(n is not None, "home not reached")
    time.sleep(6)
    S.shot("home")


def t_home_focus():
    _, f = log_focus("home-focus")
    S.shot("home-focus")
    expect(f is not None and "RATV News HD" in f, "hero not focused (focus: %s)" % f)


def t_player_remote():
    key("DPAD_CENTER", pause=10)
    S.shot("player")
    key("DPAD_DOWN", pause=2)
    S.shot("player-zap")
    key("DPAD_CENTER", pause=1.5)
    S.shot("player-controls")
    key("DPAD_LEFT", pause=1)
    S.shot("player-controls-moved")
    key("BACK", pause=1.5)
    S.shot("player-controls-hidden")
    key("BACK", pause=2.5)
    _, n = S.wait_for(timeout=15, text="الرئيسية", save="home-after-player")
    S.shot("home-after-player")
    expect(n is not None, "Back did not return from the player")


def t_movies():
    go_tab("أفلام")
    _, n = S.wait_for(timeout=20, contains="Desert Night")
    time.sleep(1.5)
    _, f = log_focus("movies")
    S.shot("movies")
    expect(n is not None, "movies missing")
    expect(f is not None and ("Sky Riders" in f or "Desert Night" in f or "Old Town" in f or "Silent Sea" in f or "Fast Track" in f), "no poster focused (focus: %s)" % f)
    key("DPAD_CENTER", pause=3)
    _, f = log_focus("movie-detail")
    S.shot("movie-detail")
    expect(f is not None and ("تشغيل" in f or "متابعة" in f), "play button not focused (focus: %s)" % f)
    key("DPAD_CENTER", pause=10)
    key("DPAD_RIGHT", n=2, pause=1)
    S.shot("player-movie-seek")
    key("BACK", pause=3)
    S.shot("movie-detail-after")
    key("BACK", pause=2)


def t_live():
    go_tab("مباشر")
    _, n = S.wait_for(timeout=20, contains="RATV News HD")
    time.sleep(1.5)
    _, f = log_focus("live")
    S.shot("live")
    expect(f is not None and ("Gulf 24 FHD" in f or "RATV News HD" in f), "channel row not focused (focus: %s)" % f)
    key("DPAD_DOWN", n=2)
    _, f = log_focus("live-moved")
    S.shot("live-moved")


def t_series():
    go_tab("مسلسلات")
    _, n = S.wait_for(timeout=20, contains="Sands of Time")
    expect(n is not None, "series missing")
    time.sleep(1)
    key("DPAD_CENTER", pause=4)
    _, f = log_focus("series-detail")
    S.shot("series-detail")
    expect(f is not None and "الحلقة" in f, "episode button not focused (focus: %s)" % f)
    key("BACK", pause=2)


def t_settings():
    go_tab("حسابي")
    time.sleep(1.5)
    _, f = log_focus("settings")
    S.shot("settings")
    expect(f is not None and "تبديل القائمة" in f, "settings not focused (focus: %s)" % f)


if __name__ == "__main__":
    for name, fn in [
        ("launch", S.s_launch),
        ("login-screen", t_login_screen),
        ("keyboard-on-ok", t_keyboard_on_ok),
        ("fill-with-remote", t_fill_with_remote),
        ("home-focus", t_home_focus),
        ("player-remote", t_player_remote),
        ("movies", t_movies),
        ("live", t_live),
        ("series", t_series),
        ("settings", t_settings),
    ]:
        S.step(name, fn)
    S.finish()
