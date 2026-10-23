#!/usr/bin/env python3
"""Drives RATV on an Android TV emulator with remote keys only (CI only)."""
import json
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


def go_tab(label, nav=NAV, tries=15):
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
    for _ in range(24):
        _, f = log_focus("deep-" + tag)
        if f and "Language" in f:
            found = True
            break
        key("DPAD_DOWN", pause=0.6)
    expect(found, "%s: no language row in the settings" % tag)
    for i in range(30):
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
        ("settings", t_settings),
        ("settings-deep", t_settings_deep),
        ("enhance", t_enhance),
        ("languages", t_languages),
    ]:
        S.step(name, fn)
    S.finish()
