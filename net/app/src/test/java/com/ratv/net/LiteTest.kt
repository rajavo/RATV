package com.ratv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteTest {
    @Test
    fun theTvNeedsTheseSoTheyAreNeverListed() {
        for (pkg in listOf(
            "com.android.vending",
            "com.android.tv.settings",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.katniss",
            "com.tcl.tv",
            "com.tcl.tv.something",
            "com.tcl.settings",
            "com.tcl.ui_mediaCenter",
            "com.android.tv.inputs",
            "com.google.android.tungsten.setupwraith"
        )) assertTrue(pkg, Lite.keep(pkg))
    }

    @Test
    fun extraAppsCanBeListed() {
        for (pkg in listOf(
            "com.google.android.videos",
            "com.google.android.youtube.tvmusic",
            "com.netflix.ninja",
            "com.tcl.waterfall.overseas",
            "tv.pluto.android"
        )) assertFalse(pkg, Lite.keep(pkg))
    }

    @Test
    fun comfortJoinsTheAccessibilityServicesAlreadyOn() {
        val me = "com.ratv.net/com.ratv.net.FocusAccessibilityService"
        assertEquals(me, Lite.withService("null", me))
        assertEquals(me, Lite.withService("", me))
        assertEquals("a/.B:$me", Lite.withService("a/.B", me))
        assertEquals("a/.B:$me", Lite.withService("a/.B:$me", me))
    }

    @Test
    fun comfortIsWarmAndStrongerStepByStep() {
        assertEquals(0, Comfort.color(0))
        var last = 0
        for (level in 1..3) {
            val alpha = Comfort.color(level) ushr 24
            assertTrue(alpha > last)
            last = alpha
        }
        assertEquals(0x401C00, Comfort.color(2) and 0xFFFFFF)
    }
}
