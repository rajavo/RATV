#!/usr/bin/env python3
"""Checks جدول RA كورة on a phone emulator with taps (CI only).

Same test server and schedule as smoke_kora.py; here every action is a tap on the screen.
"""
import os
import re
import sys
import time

os.environ.setdefault("KORA_OUT", "kora-results/phone")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import smoke_kora as K  # noqa: E402


def node_with(root, text):
    """The node whose own text contains [text], the most deeply nested one."""
    if root is None:
        return None
    found = None
    for n in root.iter("node"):
        if text in (n.get("text") or "") or text in (n.get("content-desc") or ""):
            found = n
    return found


def tap_text(text, timeout=15):
    end = time.time() + timeout
    while time.time() < end:
        n = node_with(K.dump(), text)
        if n is not None:
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            K.sh("input tap %d %d" % ((x1 + x2) // 2, (y1 + y2) // 2))
            time.sleep(1.5)
            return
        time.sleep(1)
    raise RuntimeError("nothing to tap with '%s'" % text)


def p_setup():
    K.s_setup()


def p_today():
    root = K.wait_text("67'", timeout=30, save="phone-today")
    K.shot("phone-today")
    K.expect(root is not None, "the live match did not show: " + K.texts(K.dump())[:300])
    t = K.texts(root)
    for want in ("الهلال", "النصر", "آرسنال", "الأهلي", "اليوم"):
        K.expect(want in t, "'%s' is missing on the phone" % want)


def p_tomorrow():
    tap_text("بكرة")
    root = K.wait_text("ريال مدريد", timeout=10, save="phone-tomorrow")
    K.shot("phone-tomorrow")
    K.expect(root is not None, "tapping tomorrow did not show its match")
    tap_text("اليوم")
    K.expect(K.wait_text("الهلال", timeout=10) is not None, "could not come back to today")


def p_details():
    tap_text("الهلال")
    root = K.wait_text("شاهد على RA TV", timeout=10, save="phone-details")
    K.shot("phone-details")
    K.expect(root is not None, "the details did not open")
    tap_text("شاهد على RA TV")
    root = K.wait_text("RA TV مو مثبت", timeout=10, save="phone-not-installed")
    K.shot("phone-not-installed")
    K.expect(root is not None, "no message that RA TV is missing")
    K.key("BACK", 1.2)
    K.key("BACK", 1.5)


if __name__ == "__main__":
    for name, fn in [
        ("phone-setup", p_setup),
        ("phone-today", p_today),
        ("phone-tomorrow", p_tomorrow),
        ("phone-details", p_details),
    ]:
        K.step(name, fn)
    K.finish()
