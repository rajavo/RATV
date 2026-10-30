package com.ratv.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PrayerTest {
    private fun hm(s: String) = s.substringBefore(':').toInt() * 60 + s.substringAfter(':').toInt()

    private fun near(want: Map<Salah, String>, got: Map<Salah, Int>) {
        for ((s, t) in want) {
            val g = got.getValue(s)
            assertTrue("$s: want $t, got ${g / 60}:${g % 60}", abs(g - hm(t)) <= 2)
        }
    }

    @Test
    fun riyadhInAutumn() = near(
        mapOf(
            Salah.FAJR to "04:40", Salah.SUNRISE to "05:59", Salah.DHUHR to "11:37",
            Salah.ASR to "14:50", Salah.MAGHRIB to "17:15", Salah.ISHA to "18:45"
        ),
        Prayer.times(2026, 10, 30, 24.7136, 46.6753, 3.0)
    )

    @Test
    fun makkahInSummer() = near(
        mapOf(
            Salah.FAJR to "04:11", Salah.SUNRISE to "05:39", Salah.DHUHR to "12:22",
            Salah.ASR to "15:42", Salah.MAGHRIB to "19:06", Salah.ISHA to "20:36"
        ),
        Prayer.times(2026, 6, 21, 21.4225, 39.8262, 3.0)
    )

    @Test
    fun ishaIsTwoHoursAfterMaghribInRamadan() {
        val t = Prayer.times(2026, 3, 1, 24.7136, 46.6753, 3.0, ramadan = true)
        assertEquals(120, t.getValue(Salah.ISHA) - t.getValue(Salah.MAGHRIB))
    }

    @Test
    fun theDayGoesInOrder() {
        val t = Prayer.times(2026, 1, 15, 25.2048, 55.2708, 4.0).values.toList()
        assertEquals(t.sorted(), t)
    }
}
