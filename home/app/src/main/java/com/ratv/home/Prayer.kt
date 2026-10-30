package com.ratv.home

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

enum class Salah(val ar: String) {
    FAJR("الفجر"), SUNRISE("الشروق"), DHUHR("الظهر"), ASR("العصر"), MAGHRIB("المغرب"), ISHA("العشاء")
}

/**
 * Prayer times by the Umm al-Qura method (Saudi Arabia): fajr at 18.5° below the horizon,
 * asr when a shadow equals its object plus the noon shadow, isha 90 minutes after maghrib (120 in Ramadan).
 * The sun's position follows the PrayTimes.org formulas; accurate to about a minute.
 */
object Prayer {
    private const val FAJR_ANGLE = 18.5
    private const val SUN_ANGLE = 0.833 // the sun's radius and the air's refraction at sunrise and sunset

    /** Minutes after midnight (local time) for each prayer, on the given day. */
    fun times(year: Int, month: Int, day: Int, lat: Double, lng: Double, utcOffsetHours: Double, ramadan: Boolean = false): Map<Salah, Int> {
        val jd = julian(year, month, day) - lng / (15 * 24)
        fun midDay(t: Double) = fixHour(12 - sun(jd + t).second)
        fun angleTime(angle: Double, t: Double, before: Boolean): Double {
            val decl = sun(jd + t).first
            val noon = midDay(t)
            val x = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))
            val tt = dacos(x.coerceIn(-1.0, 1.0)) / 15
            return noon + if (before) -tt else tt
        }
        fun asr(t: Double): Double {
            val decl = sun(jd + t).first
            return angleTime(-dacot(1 + dtan(abs(lat - decl))), t, false)
        }
        val maghrib = angleTime(SUN_ANGLE, 18.0 / 24, false)
        val hours = linkedMapOf(
            Salah.FAJR to angleTime(FAJR_ANGLE, 5.0 / 24, true),
            Salah.SUNRISE to angleTime(SUN_ANGLE, 6.0 / 24, true),
            Salah.DHUHR to midDay(12.0 / 24),
            Salah.ASR to asr(13.0 / 24),
            Salah.MAGHRIB to maghrib,
            Salah.ISHA to maghrib + (if (ramadan) 120 else 90) / 60.0
        )
        return hours.mapValues { (_, h) -> ((h + utcOffsetHours - lng / 15) * 60).roundToInt().mod(24 * 60) }
    }

    /** The sun's declination (degrees) and the equation of time (hours) at a Julian date. */
    private fun sun(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = datan2(dcos(e) * dsin(l), dcos(l)) / 15
        return dasin(dsin(e) * dsin(l)) to q / 15 - fixHour(ra)
    }

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun dsin(d: Double) = sin(rad(d))
    private fun dcos(d: Double) = cos(rad(d))
    private fun dtan(d: Double) = tan(rad(d))
    private fun dasin(x: Double) = deg(asin(x))
    private fun dacos(x: Double) = deg(acos(x))
    private fun datan2(y: Double, x: Double) = deg(atan2(y, x))
    private fun dacot(x: Double) = deg(atan(1 / x))
    private fun fixAngle(a: Double) = a - 360 * floor(a / 360)
    private fun fixHour(a: Double) = a - 24 * floor(a / 24)
}
