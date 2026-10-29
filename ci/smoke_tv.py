#!/usr/bin/env python3
"""Drives RATV on an Android TV emulator with remote keys only (CI only)."""
import json
import re
import os
import sys
import time

os.environ.setdefault("SMOKE_OUT", "smoke-results/tv")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import smoke as S  # noqa: E402

NAV = ["الرئيسية", "مباشر", "المباريات", "أفلام", "مسلسلات", "حسابي"]


def key(name, n=1, pause=1.0):
    for _ in range(n):
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


# The top bar in the other interface languages (left to right).
NAV_LANG = {
    "en": ["Home", "Live", "Matches", "Movies", "Series", "My account"],
    "es": ["Inicio", "En vivo", "Partidos", "Películas", "Series", "Mi cuenta"],
}


def go_tab(label, nav=NAV, tries=30):
    """Moves up to the top bar with the remote and opens a section."""
    rtl = nav is NAV
    f = None
    for _ in range(tries):
        _, f = log_focus("nav-search")
        if f in nav:
            break
        # Up reaches the bar; from the search box, back towards the sections.
        # (by the start of its text: a settings row in Spanish describes itself with "Busca subtítulos…")
        searching = f and f.startswith(("ابحث", "Search", "Buscar", "Busca canales"))
        key(("DPAD_RIGHT" if rtl else "DPAD_LEFT") if searching else "DPAD_UP")
    _, f = log_focus("nav-start")
    if f not in nav:
        S.shot("stuck-before-" + label)
    expect(f in nav, "top bar not reached (focus: %s)" % f)
    step = nav.index(label) - nav.index(f)
    if step:
        forward = "DPAD_LEFT" if rtl else "DPAD_RIGHT"
        backward = "DPAD_RIGHT" if rtl else "DPAD_LEFT"
        key(forward if step > 0 else backward, abs(step))
    _, f = log_focus("nav-target")
    expect(f == label, "top bar item %s not focused (focus: %s)" % (label, f))
    key("DPAD_CENTER", pause=2.0)


# ------------------------------------------------------------------- steps

def t_login_screen():
    _, n = S.wait_for(timeout=40, text="أضف اشتراكك", save="login")
    expect(n is not None, "login screen not shown")
    time.sleep(1.5)
    _, f = log_focus("login-initial")
    S.shot("login")
    expect(f is not None and "server.example" in f, "server field not selected (focus: %s)" % f)
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


def type_in_selected(text):
    """OK on the selected field, type, then close the keyboard with Back."""
    key("DPAD_CENTER", pause=2.0)
    S.type_text(text)
    if ime_shown():
        key("BACK", pause=1.2)


def t_fill_with_remote():
    type_in_selected("http//10.0.2.2:8000")  # typo on purpose: the app fixes it
    key("DPAD_DOWN")
    _, f = log_focus("login-user")
    expect(f is not None and "username" in f, "down arrow did not reach the username field (focus: %s)" % f)
    type_in_selected("test")
    key("DPAD_DOWN")
    type_in_selected("test")
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
    # The controls hide 4 s after the last key; a slow screenshot can take that long,
    # so wake them right before Back (Left only moves the focus, or shows them again).
    key("DPAD_LEFT", pause=0.5)
    key("BACK", pause=1.5)
    S.shot("player-controls-hidden")
    key("BACK", pause=2.5)
    _, n = S.wait_for(timeout=15, text="الرئيسية", save="home-after-player")
    S.shot("home-after-player")
    expect(n is not None, "Back did not return from the player")


def focus_home_item(word, tag, presses=5):
    """From the top bar, down the home screen until the focus holds [word]."""
    go_tab("الرئيسية")
    time.sleep(2)
    f = None
    for _ in range(presses):
        _, f = log_focus(tag)
        if f and word in f:
            return f
        key("DPAD_DOWN", pause=0.8)
    _, f = log_focus(tag)
    return f


def t_player_numbers():
    """Number keys open a channel by its number; the previous-channel key goes back to the one before.
    Changing channel is no stall of the line: it must not raise the steadiness wait."""
    S.shell("logcat -c")
    f = focus_home_item("شاهد الآن", "numbers-hero")
    expect(f and "شاهد الآن" in f, "watch-now not focused (focus: %s)" % f)
    key("DPAD_CENTER", pause=9)
    try:
        S.shell("input keyevent KEYCODE_5")
        time.sleep(0.6)
        S.shot("player-number-typed")
        key("DPAD_CENTER", pause=0.8)  # OK opens the typed number at once
        root = S.dump("player-number")
        S.shot("player-number")
        texts = S.all_texts(root)
        expect("Sport 2 4K" in texts, "the 5 key did not open channel 5 (Sport 2 4K): %s" % texts[:300])
        time.sleep(3.5)  # the banner goes
        S.shell("input keyevent KEYCODE_LAST_CHANNEL")
        time.sleep(0.8)
        root = S.dump("player-last-channel")
        S.shot("player-last-channel")
        back_texts = S.all_texts(root)
        names = [t for t in back_texts.split(" | ") if t.strip() and not t.strip().isdigit()]
        expect(names and "Sport 2 4K" not in back_texts, "the previous-channel key did not go back: %s" % back_texts[:300])
        playback = S.shell("logcat -d -s RATVPlayback:I")
        expect("steadiness level" not in playback, "changing channel was taken for a stall: " + playback[-300:])
    finally:
        # Out of the player whatever happened, so the next steps start from the home screen.
        for _ in range(3):
            key("BACK", pause=2)
            _, n = S.wait_for(timeout=4, text="الرئيسية")
            if n is not None:
                break


def t_update_notes():
    """The update window lists what the new version fixes (the notes in version.json), and the remote scrolls them."""
    try:
        with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "release-notes.json"), encoding="utf-8") as fh:
            notes = json.load(fh)["ar"]
    except (OSError, ValueError, KeyError):
        notes = ["إصلاح تجريبي للفحص"]
    f = focus_home_item("تحديث جديد متاح", "update-banner")
    expect(f and "تحديث جديد متاح" in f, "update banner not focused (focus: %s)" % f)
    key("DPAD_CENTER", pause=2.5)
    root = S.dump("update-notes")
    S.shot("update-notes")
    texts = S.all_texts(root)
    key("DPAD_DOWN", n=4, pause=0.8)
    S.shot("update-notes-scrolled")
    _, f2 = log_focus("update-notes-scrolled")
    key("BACK", pause=1.5)
    expect("وش الجديد في هذا التحديث" in texts and notes[0][:20] in texts, "the update window shows no notes: %s" % texts[:300])
    expect(f2 is not None and ("تحديث الآن" in f2 or "لاحقاً" in f2), "the update buttons lost the focus (focus: %s)" % f2)


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
    for _ in range(6):  # the first poster takes the focus once the list is ready
        _, f = log_focus("series")
        if f is not None and f not in NAV:
            break
        time.sleep(1)
    key("DPAD_CENTER", pause=4)
    _, f = log_focus("series-detail")
    S.shot("series-detail")
    expect(f is not None and "الحلقة" in f, "episode button not focused (focus: %s)" % f)
    key("BACK", pause=2)


def t_matches():
    go_tab("المباريات")
    _, n = S.wait_for(timeout=20, contains="ريال مدريد", save="matches")
    S.shot("matches")
    expect(n is not None, "the game of the test guide is missing from the schedule")
    root = S.dump()
    texts = " | ".join((x.get("text") or "") for x in S.nodes(root))
    expect("Sky Sports Main" in texts, "Sky Sports Main not listed as showing the game: " + texts[:300])


def t_match_link():
    """A match from the schedule app: with the remote, the best channel is selected and plays."""
    S.open_match_link(S.match_uri("Real Madrid", "Barcelona", "ريال مدريد", "برشلونة", "الدوري الإسباني"))
    _, n = S.wait_for(timeout=25, text="Sky Sports Main", save="match-link")
    S.shot("match-link")
    expect(n is not None, "the match link did not list the channels showing the game")
    _, f = log_focus("match-link")
    expect(f is not None and ("Sport" in f or "Sky" in f), "no channel selected for the remote (focus: %s)" % f)
    key("DPAD_CENTER", pause=7)
    S.shot("match-link-play")
    root = S.dump("match-link-play")
    expect(S.find(root, contains="وين تنعرض المباراة") is None, "the selected channel did not open")
    key("BACK", pause=2)
    key("BACK", pause=2)


def intro_log():
    out = S.shell("logcat -d -s RATVIntro:I RATVIntro:W")
    return [l.strip() for l in out.splitlines() if "RATVIntro" in l]


def wait_intro(pattern, timeout, tag):
    """The first line of the intro log matching [pattern], waiting for it up to [timeout] s."""
    end = time.time() + timeout
    while time.time() < end:
        for l in intro_log():
            if re.search(pattern, l):
                S.log("  " + l)
                return l
        time.sleep(1.5)
    for l in intro_log()[-12:]:
        S.log("  " + l)
    S.shot(tag)
    return None


def t_intro():
    """«تخطي المقدمة»: episodes 1-3 of Sands of Time share a 40 s opening (from 20 s, 15 s and 8 s). The
    first is printed as it loads; in the second the opening is learnt before it comes and OK skips it;
    in the third it is found again by itself, and TheIntroDB's recap of it is asked for; TheIntroDB was
    asked about the first film too (tmdb 550, when the films' step played it)."""
    S.shell("logcat -c")
    try:
        go_tab("مسلسلات")
        _, n = S.wait_for(timeout=20, contains="Sands of Time")
        expect(n is not None, "series missing")
        for _ in range(6):
            _, f = log_focus("intro-series")
            if f is not None and "Sands of Time" in f:
                break
            time.sleep(1)
        key("DPAD_CENTER", pause=4)
        _, f = log_focus("intro-detail")
        expect(f is not None and "الحلقة 1" in f, "episode 1 not focused (focus: %s)" % f)
        key("DPAD_CENTER", pause=3)
        # Episode 1: its sound is printed as the player loads it.
        expect(wait_intro(r"printing its sound", 25, "intro-ep1") is not None, "the first episode's sound is not printed")
        time.sleep(8)
        S.shot("intro-ep1")
        # To its end (the remote's right jumps 10 s): the next episode starts by itself.
        key("DPAD_RIGHT", n=10, pause=0.4)
        expect(wait_intro(r"kept \d+ s of prints", 40, "intro-ep1-kept") is not None, "the first episode's prints were not kept")
        l = wait_intro(r"opening learnt at", 40, "intro-learnt")
        expect(l is not None, "the opening was not learnt in the second episode")
        m = re.search(r"learnt at ([\d.]+)-([\d.]+) s", l)
        expect(m is not None and abs(float(m.group(1)) - 15) < 1.5 and abs(float(m.group(2)) - 55) < 1.5,
               "the opening is not where it is (15-55 s): " + l)
        _, b = S.wait_for(timeout=40, text="تخطي المقدمة", save="intro-button")
        S.shot("intro-button")
        expect(b is not None, "no «تخطي المقدمة» during the opening of the second episode")
        key("DPAD_CENTER", pause=2)
        l = wait_intro(r"skipped from \d+ to \d+ ms \(sound\)", 10, "intro-skip")
        expect(l is not None, "OK did not skip the opening")
        m = re.search(r"skipped from (\d+) to (\d+) ms", l)
        expect(m is not None and 14000 <= int(m.group(1)) <= 54000 and abs(int(m.group(2)) - 55000) < 1500,
               "the skip did not go to the end of the opening: " + l)
        S.shot("intro-skipped")
        # Episode 3: found again; TheIntroDB has its recap.
        S.shell("logcat -c")
        key("DPAD_RIGHT", n=5, pause=0.4)
        expect(wait_intro(r"TheIntroDB: 1:1000-7000", 30, "intro-db") is not None, "TheIntroDB's recap of the third episode was not used")
        l = wait_intro(r"opening found again at", 40, "intro-again")
        expect(l is not None, "the opening was not found again in the third episode")
        m = re.search(r"again at ([\d.]+)-([\d.]+) s", l)
        expect(m is not None and abs(float(m.group(1)) - 8) < 1.5 and abs(float(m.group(2)) - 48) < 1.5,
               "the opening is not where it is in the third episode (8-48 s): " + l)
        _, b = S.wait_for(timeout=40, text="تخطي المقدمة", save="intro-button3")
        S.shot("intro-button3")
        expect(b is not None, "no «تخطي المقدمة» in the third episode")
        key("DPAD_CENTER", pause=2)
        expect(wait_intro(r"skipped from \d+ to \d+ ms \(sound\)", 10, "intro-skip3") is not None, "OK did not skip the third episode's opening")
        asked = mock("/introdb/asked")
        S.log("TheIntroDB asked: %s" % asked)
        expect(any("tmdb_id=550" in q for q in asked), "TheIntroDB was not asked about the film (tmdb 550)")
        expect(any("tmdb_id=1399" in q and "episode=3" in q for q in asked), "TheIntroDB was not asked about the episode")
    finally:
        for _ in range(4):
            key("BACK", pause=1.5)
            _, n = S.wait_for(timeout=4, text="الرئيسية")
            if n is not None:
                break


def t_settings():
    go_tab("حسابي")
    time.sleep(1.5)
    _, f = log_focus("settings")
    S.shot("settings")
    expect(f is not None and "إضافة مستخدم" in f, "settings not focused (focus: %s)" % f)


def restart_app():
    S.shell("am force-stop %s" % S.PKG)
    S.shell("am start -W -n %s/.MainActivity" % S.PKG)


def deep_settings_and_back(nav, tag):
    """Down the settings to the language row, then up to the top bar again (both ways with the remote)."""
    go_tab(nav[-1], nav)
    time.sleep(2)
    found = False
    for _ in range(44):
        _, f = log_focus("deep-" + tag)
        if f and "Language" in f:
            found = True
            break
        key("DPAD_DOWN", pause=0.6)
    expect(found, "%s: no language row in the settings" % tag)
    for i in range(40):
        _, f = log_focus("up-" + tag)
        if f in nav:
            break
        if i in (6, 12):
            S.shot("up-%s-%d" % (tag, i))
        key("DPAD_UP", pause=0.6)
    expect(f in nav, "%s: stuck going up the settings (focus: %s)" % (tag, f))


def t_settings_deep():
    """Arabic: to the bottom of the settings and back up to the top bar with the remote."""
    deep_settings_and_back(NAV, "ar")


def controls_texts(tag):
    """OK shows the player's controls; returns what they say."""
    key("DPAD_CENTER", pause=1.5)
    root = S.dump(tag)
    return S.all_texts(root)


def enhance_log():
    out = S.shell("logcat -d -s RATVEnhance:I")
    lines = [l for l in out.splitlines() if "RATVEnhance" in l]
    for l in lines[-8:]:
        S.log("  " + l.strip())
    return "\n".join(lines)


def press_enhance_pill(tag):
    """With the player showing: brings its controls up, goes to the enhance button and presses it.
    Returns what the button said before."""
    time.sleep(5)  # the controls go by themselves; OK brings them back with the remote on play/pause
    key("DPAD_CENTER", pause=1.2)
    key("DPAD_DOWN", pause=0.8)
    for direction in ["DPAD_LEFT"] * 6 + ["DPAD_RIGHT"] * 12:
        _, f = log_focus(tag)
        if f and "تحسين الصورة" in f:
            key("DPAD_CENTER", pause=1)
            return f
        key(direction, pause=0.6)
    S.shot(tag)
    raise RuntimeError("no enhance button in the player's controls (%s)" % tag)


def t_enhance():
    """Smart picture enhancement: turned on in the settings, then on two weak SD channels (one known
    by its name, one by the size of its picture); off from the player's own button (the same SD
    picture both ways, to compare); the light strength of weaker chips; and, on this emulator that
    draws in software (far too slow for it), stepping down by itself while the channel goes on."""
    S.shell("logcat -c")
    S.shell("setprop debug.ratv.enhancekeep 1")  # keep it on for the pictures, whatever the speed
    try:
        go_tab("حسابي")
        time.sleep(2)
        found = None
        for _ in range(30):
            _, f = log_focus("enhance-setting")
            if f and "تحسين الصورة الذكي" in f:
                found = f
                break
            key("DPAD_DOWN", pause=0.6)
        expect(found, "no picture enhancement row in the settings")
        key("DPAD_CENTER", pause=1.5)
        S.shot("enhance-setting")
        go_tab("الرئيسية", tries=40)
        f = focus_home_item("شاهد الآن", "enhance-hero")
        expect(f and "شاهد الآن" in f, "watch-now not focused (focus: %s)" % f)
        key("DPAD_CENTER", pause=8)
        # Kids Fun (7): its name says nothing; its 720x576 picture is seen and enhanced before it shows.
        # (The digits reach the player with or without its controls up.)
        S.shell("input keyevent KEYCODE_7")
        time.sleep(12)
        S.shot("enhance-kids")
        texts = controls_texts("enhance-kids")
        S.shot("enhance-kids-controls")
        expect("Kids Fun" in texts, "the 7 key did not open Kids Fun: %s" % texts[:300])
        expect("تحسين الصورة: يعمل" in texts, "Kids Fun (SD) is not enhanced: %s" % texts[:300])
        # Racing SD (6): enhanced from the start, by its name.
        time.sleep(5)  # the controls go by themselves
        S.shell("input keyevent KEYCODE_6")
        time.sleep(10)
        S.shot("enhance-racing")
        texts = controls_texts("enhance-racing")
        S.shot("enhance-racing-controls")
        expect("Racing SD" in texts, "the 6 key did not open Racing SD: %s" % texts[:300])
        expect("تحسين الصورة: يعمل" in texts, "Racing SD is not enhanced: %s" % texts[:300])
        log = enhance_log()
        expect("(enhanced)" in log, "the full enhancement never drew a picture")
        expect("came out black" not in log, "the enhanced picture came out black: " + log[-400:])
        # Off from the player's button: the same picture without the enhancement.
        pill = press_enhance_pill("enhance-pill")
        expect("يعمل" in pill, "the button did not say the enhancement was on: %s" % pill)
        time.sleep(11)
        S.shot("enhance-off")
        texts = controls_texts("enhance-off")
        expect("تحسين الصورة: يعمل" not in texts and "تحسين الصورة" in texts, "the button did not turn the enhancement off: %s" % texts[:300])
        expect("Racing SD" in texts, "the channel did not stay on after the switch: %s" % texts[:300])
        expect("trying HLS" not in S.shell("logcat -d -s RATVPlayback:I"), "stalls from the enhancement were taken for a weak line (switched to HLS)")
        # The light strength, as weaker chips get it (Mali 400 series and the like).
        S.shell("setprop debug.ratv.enhancegpu weak")
        press_enhance_pill("enhance-pill-light")
        time.sleep(11)
        S.shot("enhance-light")
        texts = controls_texts("enhance-light")
        S.shot("enhance-light-controls")
        log = enhance_log()
        expect("(light)" in log, "the light enhancement never drew a picture")
        expect("تحسين الصورة: يعمل" in texts, "the light enhancement is not on: %s" % texts[:300])
        expect("came out black" not in log, "the light picture came out black: " + log[-400:])
        # The safety: without the test's switches the app must find this emulator too slow for the full
        # strength, step down by itself (to light, or off if light is too slow as well), and play on.
        S.shell("setprop debug.ratv.enhancegpu ''")
        S.shell("setprop debug.ratv.enhancekeep ''")
        press_enhance_pill("enhance-pill-off2")   # off
        time.sleep(6)
        press_enhance_pill("enhance-pill-on2")    # on again: from the full strength
        time.sleep(28)
        S.shot("enhance-auto")
        log = enhance_log()
        texts = controls_texts("enhance-auto")
        S.shot("enhance-auto-controls")
        expect("full enhancement does not work out" in log, "the full enhancement did not step down on a device too slow for it")
        expect("Racing SD" in texts, "the channel did not go on playing: %s" % texts[:300])
        S.log("after stepping down: " + ("still on (light)" if "تحسين الصورة: يعمل" in texts else "off"))
    finally:
        S.shell("setprop debug.ratv.enhancekeep ''")
        S.shell("setprop debug.ratv.enhancegpu ''")
        for _ in range(4):
            key("BACK", pause=1.5)
            _, n = S.wait_for(timeout=4, text="الرئيسية")
            if n is not None:
                break


def mock(path):
    import urllib.request
    return json.loads(urllib.request.urlopen("http://127.0.0.1:8000" + path, timeout=5).read())


def board_node(root):
    """The scoreboard over the picture (its description tells the teams, the score and the clock)."""
    for n in S.nodes(root):
        if (n.get("content-desc") or "").startswith("لوحة النتيجة:"):
            return n
    return None


def wait_board(tag, timeout=45, contains=None, gone=False):
    end = time.time() + timeout
    n = None
    while time.time() < end:
        root = S.dump()
        n = board_node(root)
        if gone and n is None:
            return None
        if not gone and n is not None and (contains is None or contains in n.get("content-desc")):
            S.dump(tag)
            return n
        time.sleep(1.5)
    S.dump(tag + "-timeout")
    return n


def press_pill(word, tag):
    """With the player showing: brings its controls up, goes to the button holding [word] and presses it."""
    time.sleep(5)  # the controls go by themselves; OK brings them back with the remote on play/pause
    key("DPAD_CENTER", pause=1.2)
    key("DPAD_DOWN", pause=0.8)
    for direction in ["DPAD_LEFT"] * 6 + ["DPAD_RIGHT"] * 12:
        _, f = log_focus(tag)
        if f and word in f:
            key("DPAD_CENTER", pause=1.5)
            return f
        key(direction, pause=0.6)
    S.shot(tag)
    raise RuntimeError("no «%s» button in the player's controls (%s)" % (word, tag))


def focus_row(word, tag):
    """Up, then down a list until the focus holds [word]."""
    for direction in ["DPAD_UP"] * 6 + ["DPAD_DOWN"] * 12:
        _, f = log_focus(tag)
        if f and word in f:
            return f
        key(direction, pause=0.7)
    return None


def t_scoreboard():
    """The scoreboard on a sports channel showing a match: found from the guide, with the live score
    and clock of (mock) ESPN; a goal lights it up; the controls move it down; a match picked by hand
    (a Yallakora one); and the board hidden on the channel."""
    S.shell("logcat -c")
    try:
        f = focus_home_item("شاهد الآن", "board-hero")
        expect(f and "شاهد الآن" in f, "watch-now not focused (focus: %s)" % f)
        key("DPAD_CENTER", pause=6)
        S.shell("input keyevent KEYCODE_4")  # Sport 1 HD: its guide says الدوري الإسباني: ريال مدريد × برشلونة
        n = wait_board("board", timeout=60)
        S.shot("board")
        logs = S.shell("logcat -d -s RATVScore:I RATVScores:I RATVScores:W")
        for line in logs.splitlines()[-6:]:
            S.log("  " + line.strip())
        expect(n is not None, "no scoreboard on Sport 1 HD")
        d = n.get("content-desc")
        S.log("board: " + d)
        expect("ريال مدريد" in d and "برشلونة" in d, "the board does not show the game of the guide: " + d)
        expect("2 - 1" in d, "the board does not show the live score 2-1: " + d)
        m = re.search(r"(\d\d):(\d\d)", d)
        expect(m is not None, "no game clock on the board: " + d)
        expect(20 <= int(m.group(1)) <= 45, "the game clock is far off: " + d)
        top_alone = S.bounds(n)[1]
        # A goal for the home side: the board tells it once it is on the screen (as late as the picture).
        mock("/espn/goal?id=900001&side=home")
        _, g = S.wait_for(timeout=110, text="هدف!", save="board-goal")
        S.shot("board-goal")
        expect(g is not None, "the goal did not light up the board")
        n = wait_board("board-goal2", timeout=10, contains="3 - 1")
        expect(n is not None, "the board did not count the goal")
        # On the channel's own score: the top left corner of the picture (a notice may hold it lower for a moment).
        corner = None
        for _ in range(8):
            n = wait_board("board-corner", timeout=6)
            corner = S.bounds(n) if n is not None else None
            if corner and corner[1] < 1080 * 0.06:
                break
            time.sleep(1.5)
        S.shot("board-corner")
        S.log("board alone at %s" % (corner,))
        expect(corner is not None and corner[0] < 1920 * 0.06 and corner[1] < 1080 * 0.06 and corner[2] < 1920 * 0.40,
               "the board is not on the channel's score at the top left: %s" % (corner,))
        top_alone = corner[1]
        # The controls up: the board moves down, under their top bar (they go by themselves after 4 s,
        # so OK again if they went while the screen was read).
        texts, root = "", None
        for _ in range(3):
            key("DPAD_CENTER", pause=0.8)
            S.shot("board-controls")
            root = S.dump("board-controls")
            texts = S.all_texts(root)
            if "لوحة النتيجة" in texts:
                break
        expect("لوحة النتيجة" in texts, "no scoreboard button in the controls: " + texts[:300])
        n = board_node(root)
        expect(n is not None and S.bounds(n)[1] > top_alone + 40 and S.bounds(n)[1] >= 140,
               "the board did not move down under the controls: %s" % (S.bounds(n) if n is not None else None,))
        # Picked by hand: a Yallakora match.
        press_pill("لوحة النتيجة", "board-pill")
        _, p = S.wait_for(timeout=8, contains="بدون لوحة", save="board-pick")
        S.shot("board-pick")
        expect(p is not None, "the match picker did not open")
        texts = S.all_texts(S.dump("board-pick2"))
        expect("الهلال × النصر" in texts and "الشمال × نادي قطر" in texts, "the matches being played are not all listed: " + texts[:400])
        expect("برشلونة × ريال مدريد" not in texts, "a match over long ago is offered: " + texts[:400])
        expect(focus_row("الشمال", "board-pick-yk"), "could not reach the Yallakora match with the remote")
        key("DPAD_CENTER", pause=2)
        n = wait_board("board-yk", timeout=40, contains="الشمال")
        S.shot("board-yk")
        expect(n is not None, "the picked match is not on the board")
        d = n.get("content-desc")
        S.log("board: " + d)
        expect("1 - 0" in d and "نادي قطر" in d, "the Yallakora match is not shown right: " + d)
        # Hidden on this channel.
        press_pill("لوحة النتيجة", "board-pill2")
        _, p = S.wait_for(timeout=8, contains="بدون لوحة")
        expect(p is not None, "the match picker did not open again")
        expect(focus_row("بدون لوحة", "board-hide"), "could not reach «no scoreboard» with the remote")
        key("DPAD_CENTER", pause=2)
        expect(wait_board("board-hidden", timeout=10, gone=True) is None, "the board did not go when hidden")
        S.shot("board-hidden")
    finally:
        for _ in range(4):
            key("BACK", pause=1.5)
            _, n = S.wait_for(timeout=4, text="الرئيسية")
            if n is not None:
                break


def open_number(n, wait=8):
    """Types a channel number on the remote while a channel plays (it opens 1.5 s after the last digit)."""
    for d in str(n):
        S.shell("input keyevent KEYCODE_%s" % d)
        time.sleep(0.3)
    time.sleep(wait)


def t_sports_guide():
    """RA TV's own guide of the sports channels: a channel with no guide (named "AR| beIN SPORTS 3 HD")
    and one whose guide says only "beIN SPORTS 1" show the match the schedule puts on them (Kooora's
    "beIN Sports Mena 3"/"Mena 1"), in the controls and on the scoreboard; a match of another
    competition on beIN 2 is in the guide too, without a board."""
    try:
        f = focus_home_item("شاهد الآن", "guide-hero")
        expect(f and "شاهد الآن" in f, "watch-now not focused (focus: %s)" % f)
        key("DPAD_CENTER", pause=6)
        # 24: AR| beIN SPORTS 3 HD, no guide of its own.
        open_number(24)
        n = wait_board("guide-bein3", timeout=45, contains="ليفربول")
        S.shot("guide-bein3")
        logs = S.shell("logcat -d -s RATVGuide:I RATVScore:I")
        for line in logs.splitlines()[-5:]:
            S.log("  " + line.strip())
        expect(n is not None, "no scoreboard on a beIN channel with no guide of its own")
        expect("1 - 0" in n.get("content-desc"), "the board does not show the match's score: " + n.get("content-desc"))
        texts = ""
        for _ in range(3):
            key("DPAD_CENTER", pause=0.8)
            texts = S.all_texts(S.dump("guide-bein3-controls"))
            if "ليفربول × أرسنال" in texts:
                break
        S.shot("guide-bein3-controls")
        expect("الدوري الإنجليزي الممتاز: ليفربول × أرسنال" in texts, "the controls do not say the match on now: " + texts[:400])
        time.sleep(5)  # the controls go
        # 25: beIN SPORTS 1 FHD, whose guide only says "beIN SPORTS 1".
        open_number(25)
        n = wait_board("guide-bein1", timeout=45, contains="إشبيلية")
        S.shot("guide-bein1")
        expect(n is not None, "the vague guide of beIN 1 did not give way to the match the schedule puts there")
        # 26: BEIN SPORT 2, a match of a competition outside the schedule: in the guide, no board.
        time.sleep(3)
        open_number(26)
        texts = ""
        for _ in range(3):
            key("DPAD_CENTER", pause=0.8)
            texts = S.all_texts(S.dump("guide-bein2-controls"))
            if "بولتون" in texts:
                break
        S.shot("guide-bein2-controls")
        expect("بولتون واندررز × ميدلزبره" in texts, "the guide of beIN 2 does not say its match: " + texts[:400])
        expect(board_node(S.dump()) is None, "a board on a match nothing gives the score of")
        # 27: ثمانية 3, and the sources say only "Thmanyah": the brand's only match now is on all its channels.
        time.sleep(5)
        open_number(27)
        n = wait_board("guide-thmanyah3", timeout=45, contains="الاتحاد")
        S.shot("guide-thmanyah3")
        expect(n is not None and "2 - 0" in n.get("content-desc"), "no board for the brand's only match on ثمانية 3")
        # 28: SSC 2, and two matches named only "SSC" at once: the player asks, and OK opens the match list.
        time.sleep(3)
        open_number(28)
        _, q = S.wait_for(timeout=20, contains="أكثر من مباراة الحين", save="guide-ask")
        S.shot("guide-ask")
        expect(q is not None, "no question when two matches of the brand are on at once")
        expect(board_node(S.dump()) is None, "a board for one of two matches the channel may show")
        key("DPAD_CENTER", pause=2)
        _, p = S.wait_for(timeout=8, contains="بدون لوحة", save="guide-ask-pick")
        S.shot("guide-ask-pick")
        expect(p is not None, "OK did not open the match list after the question")
        key("BACK", pause=1.5)
    finally:
        for _ in range(4):
            key("BACK", pause=1.5)
            _, n = S.wait_for(timeout=4, text="الرئيسية")
            if n is not None:
                break


def t_languages():
    """The interface in English and in Spanish (chosen for the test with debug.ratv.lang), then Arabic again."""
    try:
        for lang in ("en", "es"):
            nav = NAV_LANG[lang]
            S.shell("setprop debug.ratv.lang " + lang)
            restart_app()
            _, n = S.wait_for(timeout=45, text=nav[0], save="home-" + lang)
            time.sleep(2)
            S.shot("home-" + lang)
            expect(n is not None, "%s: the top bar shows no %s" % (lang, nav[0]))
            go_tab(nav[-1], nav)
            time.sleep(2)
            S.shot("settings-" + lang)
            # Down the settings to the language row, then open its list.
            found = False
            for _ in range(24):
                _, f = log_focus("settings-" + lang)
                if f and "Language" in f:
                    found = True
                    break
                key("DPAD_DOWN", pause=0.6)
            expect(found, "%s: no language row in the settings" % lang)
            key("DPAD_CENTER", pause=2)
            root = S.dump("language-" + lang)
            S.shot("language-" + lang)
            texts = S.all_texts(root)
            expect("English" in texts and "Español" in texts and "العربية" in texts, "%s: the language list is incomplete" % lang)
            key("BACK", pause=1.5)
            go_tab(nav[1], nav, tries=40)  # from far down the settings
            time.sleep(3)
            S.shot("live-" + lang)
    finally:
        S.shell("setprop debug.ratv.lang ''")
        restart_app()
        S.wait_for(timeout=45, text="الرئيسية")


if __name__ == "__main__":
    for name, fn in [
        ("launch", S.s_launch),
        ("login-screen", t_login_screen),
        ("keyboard-on-ok", t_keyboard_on_ok),
        ("fill-with-remote", t_fill_with_remote),
        ("home-focus", t_home_focus),
        ("player-remote", t_player_remote),
        ("player-numbers", t_player_numbers),
        ("update-notes", t_update_notes),
        ("movies", t_movies),
        ("live", t_live),
        ("series", t_series),
        ("matches", t_matches),
        ("match-link", t_match_link),
        ("scoreboard", t_scoreboard),
        ("sports-guide", t_sports_guide),
        ("intro", t_intro),
        ("settings", t_settings),
        ("settings-deep", t_settings_deep),
        ("enhance", t_enhance),
        ("languages", t_languages),
    ]:
        S.step(name, fn)
    S.finish()
