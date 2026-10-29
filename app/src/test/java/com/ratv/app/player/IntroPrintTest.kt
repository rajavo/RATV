package com.ratv.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * The opening of a series found by its sound: three made-up episodes share a 40 s theme (a melody
 * with a beat) at different places, louder or softer, at different sample rates, with their own
 * talk around it and their own faint noise (as each file is encoded on its own).
 */
class IntroPrintTest {
    private val themeSec = 40.0

    /** Noise that is a function of time (the same sound whatever the sample rate). */
    private fun noiseAt(t: Double): Double {
        var h = (t * 8000).toLong() * -7046029254386353131L
        h = h xor (h ushr 29)
        h *= -4658895280553007687L
        h = h xor (h ushr 32)
        return ((h ushr 11).toDouble() / (1L shl 53)) * 2 - 1
    }

    /** The theme: notes every 0.3 s with a harmonic, a bass line and a beat of noise. */
    private fun theme(t: Double): Double {
        val step = (t * 3.33).toInt()
        val note = 330.0 * 2.0.pow(((step % 8) * 2 + (t * 1.11).toInt() % 3) / 12.0)
        val decay = 1 - 0.6 * ((t * 3.33) % 1.0)
        val bass = 110.0 * 2.0.pow(((t * 0.55).toInt() % 4) * 5 / 12.0)
        return 0.25 * sin(2 * PI * note * t) * decay + 0.1 * sin(4 * PI * note * t) * decay +
            0.18 * sin(2 * PI * bass * t) + 0.12 * noiseAt(t) * exp(-30 * (t % 0.5))
    }

    /** Talk: noise in the voice's band, rising and falling with the syllables. */
    private class Talk(seed: Long) {
        private val r = java.util.Random(seed)
        private var lp = 0.0
        private var hp = 0.0
        private val rate = 3.0 + (seed % 5) * 0.4
        fun at(t: Double): Double {
            val w = r.nextDouble() * 2 - 1
            lp += 0.35 * (w - lp)
            hp = lp - 0.92 * hp
            val env = 0.5 + 0.4 * sin(2 * PI * rate * t) + 0.2 * sin(2 * PI * 0.7 * t)
            return 0.5 * hp * env.coerceAtLeast(0.05)
        }
    }

    /** An episode: [pre] seconds of talk, the theme (at [gain]), then talk, [total] seconds at [rate]. */
    private fun episode(pre: Double, seed: Long, rate: Int, gain: Double = 1.0, total: Double = 120.0, withTheme: Boolean = true): Prints {
        val prints = Prints(IntroPrint.frameAt((total * 1000).toLong()) + 1)
        val printer = Printer(prints)
        val before = Talk(seed)
        val after = Talk(seed + 100)
        val hiss = java.util.Random(seed + 7)
        val chunk = 1024
        val buf = FloatArray(chunk)
        val n = (total * rate).toInt()
        var i = 0
        while (i < n) {
            val m = minOf(chunk, n - i)
            for (k in 0 until m) {
                val t = (i + k).toDouble() / rate
                val inTheme = withTheme && t >= pre && t < pre + themeSec
                val v = when {
                    inTheme -> gain * theme(t - pre)
                    t < pre -> before.at(t)
                    else -> after.at(t)
                }
                buf[k] = (v + 0.002 * (hiss.nextDouble() * 2 - 1)).toFloat()
            }
            printer.feed(buf, m, rate, i * 1_000_000L / rate)
            i += m
        }
        return prints
    }

    private fun sec(frame: Int) = IntroPrint.msOf(frame) / 1000.0

    @Test
    fun theOpeningSharedByTwoEpisodesIsFound() {
        val ep1 = episode(pre = 20.0, seed = 1, rate = 48_000)
        val ep2 = episode(pre = 35.037, seed = 2, rate = 44_100, gain = 0.8)
        val s = IntroMatch.shared(ep1, ep2, minFrames = 400, maxFrames = 6000)
        assertNotNull(s)
        s!!
        assertTrue("ep1 starts at ${sec(s.aFrom)}", abs(sec(s.aFrom) - 20.0) < 0.7)
        assertTrue("ep1 ends at ${sec(s.aTo)}", abs(sec(s.aTo) - 60.0) < 0.7)
        assertTrue("ep2 starts at ${sec(s.bFrom)}", abs(sec(s.bFrom) - 35.04) < 0.7)
        assertTrue("ep2 ends at ${sec(s.bTo)}", abs(sec(s.bTo) - 75.04) < 0.7)
    }

    @Test
    fun anotherSeriesSharesNothing() {
        val ep1 = episode(pre = 20.0, seed = 1, rate = 48_000)
        val other = episode(pre = 10.0, seed = 9, rate = 48_000, withTheme = false)
        assertNull(IntroMatch.shared(ep1, other, minFrames = 400, maxFrames = 6000))
        // Nor do two episodes' talk.
        val ep3 = episode(pre = 60.0, seed = 3, rate = 32_000, withTheme = false)
        assertNull(IntroMatch.shared(other, ep3, minFrames = 200, maxFrames = 6000))
    }

    @Test
    fun theOpeningIsFoundAgainBeforeItEnds() {
        val ep1 = episode(pre = 20.0, seed = 1, rate = 48_000)
        val intro = ep1.slice(IntroPrint.frameAt(20_000), IntroPrint.frameAt(60_000))
        val ep3 = episode(pre = 9.981, seed = 3, rate = 48_000, gain = 1.2)
        // Only its first 25 s are printed yet: the opening has begun, not ended.
        val partial = ep3.slice(0, IntroPrint.frameAt(25_000))
        val s = IntroMatch.shared(intro, partial, minFrames = 120, maxFrames = 6000)
        assertNotNull(s)
        s!!
        assertTrue("found at ${sec(s.bFrom)}", abs(sec(s.bFrom) - sec(s.aFrom) - 9.98) < 0.7)
        assertEquals("runs to the edge of what is printed", partial.end.toDouble(), s.bTo.toDouble(), 3.0)
    }

    @Test
    fun aJumpInTheSoundKeepsTheMediaClock() {
        // The same sound fed in two pieces with a seek between: its frames stay where they belong.
        val whole = episode(pre = 20.0, seed = 1, rate = 48_000)
        val prints = Prints(whole.capacity)
        val printer = Printer(prints)
        val rate = 48_000
        val buf = FloatArray(1024)
        for ((from, to) in listOf(30.0 to 40.0, 22.0 to 30.0)) {
            printer.reset()
            var t0 = from
            while (t0 < to) {
                for (k in buf.indices) buf[k] = theme(t0 + k.toDouble() / rate - 20.0).toFloat()
                printer.feed(buf, buf.size, rate, (t0 * 1_000_000).toLong())
                t0 += buf.size.toDouble() / rate
            }
        }
        var same = 0
        var both = 0
        for (f in IntroPrint.frameAt(23_000) until IntroPrint.frameAt(39_000)) {
            if (!prints.heard[f] || !whole.heard[f]) continue
            both++
            if (Integer.bitCount(prints.bits[f] xor whole.bits[f]) <= 8) same++
        }
        assertTrue("only $both frames printed", both > 250)
        assertTrue("$same of $both frames agree", same > both * 0.8)
    }
}
