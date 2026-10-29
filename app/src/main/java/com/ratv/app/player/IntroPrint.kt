package com.ratv.app.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Prints of the sound for finding an episode's opening ("تخطي المقدمة"): one 32-bit print for every
 * 50 ms of the media's time, from the sound's spectrum (33 bands between 300 and 3000 Hz, each bit
 * telling whether the difference of two neighbouring bands grew or shrank since 50 ms before, as
 * audio fingerprints usually do). The same piece of sound gives nearly the same prints in two
 * episodes, whatever its loudness and the compression of each file; other sound differs in about
 * half the bits. Plain Kotlin (no Android): the tests run it on the computer.
 */
object IntroPrint {
    /** The sound is looked at 8000 times a second. */
    const val RATE = 8000
    /** One print every 50 ms. */
    const val FRAME_MS = 50
    const val HOP = RATE * FRAME_MS / 1000
    /** Each print looks at 256 ms of sound. */
    const val WINDOW = 2048
    private const val BANDS = 33
    private const val LOW_HZ = 300.0
    private const val HIGH_HZ = 3000.0

    /** The frame (print number) at a time of the media. */
    fun frameAt(ms: Long): Int = (ms / FRAME_MS).toInt()

    fun msOf(frame: Int): Long = frame.toLong() * FRAME_MS

    internal val hann = FloatArray(WINDOW) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / WINDOW)).toFloat() }

    /** The FFT bins of each band, log spaced. */
    internal val bandFrom = IntArray(BANDS)
    internal val bandTo = IntArray(BANDS)

    init {
        val binHz = RATE.toDouble() / WINDOW
        for (j in 0 until BANDS) {
            val f0 = LOW_HZ * exp(ln(HIGH_HZ / LOW_HZ) * j / BANDS)
            val f1 = LOW_HZ * exp(ln(HIGH_HZ / LOW_HZ) * (j + 1) / BANDS)
            bandFrom[j] = kotlin.math.ceil(f0 / binHz).toInt()
            bandTo[j] = maxOf(bandFrom[j] + 1, kotlin.math.ceil(f1 / binHz).toInt())
        }
    }
}

/** The prints of one episode or film: [bits] of each frame, [heard] where there is one (not silent, not missing). */
class Prints(val capacity: Int) {
    val bits = IntArray(capacity)
    val heard = BooleanArray(capacity)
    /** One past the last frame with a print. */
    @Volatile var end = 0

    val heardCount: Int get() = (0 until end).count { heard[it] }

    fun put(frame: Int, value: Int, ok: Boolean) {
        if (frame < 0 || frame >= capacity) return
        bits[frame] = value
        heard[frame] = ok
        if (frame + 1 > end) end = frame + 1
    }

    /** Frames [from, to) of these prints as prints of their own (an opening kept for its series). */
    fun slice(from: Int, to: Int): Prints {
        val f = from.coerceIn(0, end)
        val t = to.coerceIn(f, end)
        val out = Prints(t - f)
        System.arraycopy(bits, f, out.bits, 0, t - f)
        System.arraycopy(heard, f, out.heard, 0, t - f)
        out.end = t - f
        return out
    }

    companion object {
        fun of(bits: IntArray, heard: BooleanArray): Prints {
            val p = Prints(bits.size)
            System.arraycopy(bits, 0, p.bits, 0, bits.size)
            System.arraycopy(heard, 0, p.heard, 0, heard.size.coerceAtMost(bits.size))
            p.end = bits.size
            return p
        }
    }
}

/**
 * Turns decoded sound (any rate, any number of channels) into [prints], frame by frame on the
 * media's clock: frame n is the sound from n × 50 ms, wherever the sound came in from. A jump in
 * the sound's time (a seek) starts the filters over; the frames already made stay.
 */
class Printer(val prints: Prints) {
    // Low-pass at 3400 Hz before going down to 8000 samples a second (two biquads, Butterworth).
    private var rate = 0
    private val b0 = DoubleArray(2); private val b1 = DoubleArray(2); private val b2 = DoubleArray(2)
    private val a1 = DoubleArray(2); private val a2 = DoubleArray(2)
    private val z1 = DoubleArray(2); private val z2 = DoubleArray(2)

    // Going down to 8000 a second: the last input sample and its time, in input samples since [baseUs].
    private var baseUs = Long.MIN_VALUE
    private var inCount = 0L
    private var lastY = 0.0
    private var nextOut = 0L       // index of the next 8 kHz sample, on the media's clock (k × 125 µs)
    private var expectUs = Long.MIN_VALUE

    // The last WINDOW samples at 8 kHz, and the index of the newest.
    private val ring = FloatArray(IntroPrint.WINDOW)
    private var ringEnd = -1L      // index (8 kHz) of the newest sample in the ring
    private var ringFill = 0
    private var prevFrame = -1
    private val prevDiff = DoubleArray(32)
    private var prevOk = false

    private val windowed = DoubleArray(IntroPrint.WINDOW)
    private val zr = DoubleArray(IntroPrint.WINDOW / 2)
    private val zi = DoubleArray(IntroPrint.WINDOW / 2)
    private val power = DoubleArray(IntroPrint.WINDOW / 2)
    private val band = DoubleArray(33)

    /** The sound's time jumped (or the stream started again): what follows does not join what came before. */
    fun reset() {
        baseUs = Long.MIN_VALUE
        expectUs = Long.MIN_VALUE
        ringFill = 0
        ringEnd = -1L
        prevFrame = -1
        prevOk = false
        z1.fill(0.0); z2.fill(0.0)
    }

    private fun design(sampleRate: Int) {
        rate = sampleRate
        // Butterworth 4th order as two biquads (Q 0.541 and 1.307), bilinear transform.
        val qs = doubleArrayOf(0.5411961, 1.3065630)
        val k = tan(PI * minOf(3400.0, sampleRate * 0.45) / sampleRate)
        for (s in 0..1) {
            val q = qs[s]
            val norm = 1.0 / (1.0 + k / q + k * k)
            b0[s] = k * k * norm
            b1[s] = 2.0 * b0[s]
            b2[s] = b0[s]
            a1[s] = 2.0 * (k * k - 1.0) * norm
            a2[s] = (1.0 - k / q + k * k) * norm
        }
        z1.fill(0.0); z2.fill(0.0)
    }

    /**
     * [mono] samples [0, count) at [sampleRate], the first at [startUs] of the media. Returns the
     * frames made (the caller looks for the opening when enough are new).
     */
    fun feed(mono: FloatArray, count: Int, sampleRate: Int, startUs: Long): Int {
        if (count <= 0 || sampleRate <= 0) return 0
        if (sampleRate != rate) {
            design(sampleRate)
            reset()
        }
        // Joins the sound before unless its time jumped by more than 40 ms.
        if (expectUs == Long.MIN_VALUE || abs(startUs - expectUs) > 40_000L) {
            reset()
            baseUs = startUs
            inCount = 0L
            // The first 8 kHz sample at or after the start, on the media's clock.
            nextOut = Math.floorDiv(startUs * IntroPrint.RATE + 999_999L, 1_000_000L)
            lastY = Double.NaN
        }
        var made = 0
        // Times in input samples since baseUs: the 8 kHz sample k is at k·step − offset of them.
        val step = sampleRate.toDouble() / IntroPrint.RATE
        val offset = baseUs * sampleRate / 1_000_000.0
        // The filters' state in locals (the loop runs 48 000 times a second of sound).
        val b00 = b0[0]; val b10 = b1[0]; val b20 = b2[0]; val a10 = a1[0]; val a20 = a2[0]
        val b01 = b0[1]; val b11 = b1[1]; val b21 = b2[1]; val a11 = a1[1]; val a21 = a2[1]
        var z10 = z1[0]; var z20 = z2[0]; var z11 = z1[1]; var z21 = z2[1]
        var y = lastY
        var n = inCount
        var nextAt = nextOut * step - offset
        for (i in 0 until count) {
            val x0 = mono[i].toDouble()
            val y0 = b00 * x0 + z10
            z10 = b10 * x0 - a10 * y0 + z20
            z20 = b20 * x0 - a20 * y0
            val x1 = b01 * y0 + z11
            z11 = b11 * y0 - a11 * x1 + z21
            z21 = b21 * y0 - a21 * x1
            val t = n.toDouble()
            n++
            if (y.isNaN()) {
                y = x1
                continue
            }
            // 8 kHz samples between the last input sample (t − 1) and this one, by straight-line interpolation.
            while (nextAt <= t) {
                if (nextAt >= t - 1.0) {
                    if (push(nextOut, (y + (x1 - y) * (nextAt - (t - 1.0))).toFloat())) made++
                }
                nextOut++
                nextAt = nextOut * step - offset
            }
            y = x1
        }
        z1[0] = z10; z2[0] = z20; z1[1] = z11; z2[1] = z21
        lastY = y
        inCount = n
        expectUs = baseUs + (inCount * 1_000_000L) / sampleRate
        return made
    }

    /** One 8 kHz sample, number [k] on the media's clock. True when it completed a frame. */
    private fun push(k: Long, v: Float): Boolean {
        if (ringEnd >= 0 && k != ringEnd + 1) {
            ringFill = 0
            prevOk = false
        }
        ring[(k and (IntroPrint.WINDOW - 1).toLong()).toInt()] = v
        ringEnd = k
        if (ringFill < IntroPrint.WINDOW) ringFill++
        val first = k + 1 - IntroPrint.WINDOW
        if (ringFill < IntroPrint.WINDOW || first < 0 || first % IntroPrint.HOP != 0L) return false
        frame((first / IntroPrint.HOP).toInt(), first)
        return true
    }

    private fun frame(n: Int, first: Long) {
        var energy = 0.0
        val at = (first % IntroPrint.WINDOW).toInt()
        for (i in 0 until IntroPrint.WINDOW) {
            val s = ring[(at + i) and (IntroPrint.WINDOW - 1)].toDouble()
            energy += s * s
            windowed[i] = s * IntroPrint.hann[i]
        }
        Fft.power(windowed, zr, zi, power, IntroPrint.bandTo[32])
        for (j in 0 until 33) {
            var e = 0.0
            for (b in IntroPrint.bandFrom[j] until IntroPrint.bandTo[j]) e += power[b]
            band[j] = e
        }
        // Silence (under −50 dB) says nothing: such frames match nothing.
        val loud = sqrt(energy / IntroPrint.WINDOW) > 0.003
        var bits = 0
        val joined = prevOk && prevFrame == n - 1
        for (m in 0 until 32) {
            val d = band[m] - band[m + 1]
            if (joined && d - prevDiff[m] > 0) bits = bits or (1 shl m)
            prevDiff[m] = d
        }
        prints.put(n, bits, joined && loud)
        prevFrame = n
        prevOk = true
    }
}

/**
 * The spectrum of one window of real sound: a radix-2 FFT of half the window's size on the sound's
 * even and odd samples taken as one complex signal, then split (half the work of a full FFT).
 */
internal object Fft {
    private const val N = IntroPrint.WINDOW
    private const val n = N / 2
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2 * PI * it / n) }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }
    private val cosN = DoubleArray(n) { cos(2 * PI * it / N) }
    private val sinN = DoubleArray(n) { sin(2 * PI * it / N) }

    /** The power of bins [0, bins) (bins ≤ N/2) of the N real samples [x]; [zr] and [zi] are work arrays of N/2. */
    fun power(x: DoubleArray, zr: DoubleArray, zi: DoubleArray, out: DoubleArray, bins: Int) {
        for (k in 0 until n) {
            zr[k] = x[2 * k]
            zi[k] = x[2 * k + 1]
        }
        transform(zr, zi)
        for (k in 0 until minOf(bins, n)) {
            val m = if (k == 0) 0 else n - k
            // Even samples' spectrum (Z[k] + conj Z[n−k]) / 2 and odd ones' (Z[k] − conj Z[n−k]) / 2i.
            val er = (zr[k] + zr[m]) * 0.5
            val ei = (zi[k] - zi[m]) * 0.5
            val odr = (zi[k] + zi[m]) * 0.5
            val odi = -(zr[k] - zr[m]) * 0.5
            val c = cosN[k]
            val s = sinN[k]
            val xr = er + odr * c + odi * s
            val xi = ei - odr * s + odi * c
            out[k] = xr * xr + xi * xi
        }
    }

    private fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val tre = re[l] * cosT[k] + im[l] * sinT[k]
                    val tim = -re[l] * sinT[k] + im[l] * cosT[k]
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

/** Where a piece of sound of [a] is found again in [b]: frames [aFrom, aTo) of a are [bFrom, bTo) of b. */
data class Shared(val aFrom: Int, val aTo: Int, val bFrom: Int, val bTo: Int) {
    val frames: Int get() = aTo - aFrom
}

/**
 * Finding the same sound in two sets of prints: the shift between them is voted for by prints that
 * (nearly) agree, then each likely shift is walked along to find where the prints keep agreeing.
 */
object IntroMatch {
    /** Agreeing: under 35 % of the bits differ, over a second. */
    private const val MAX_BITS = 0.35 * 32
    private const val SMOOTH = 20
    private const val CANDIDATES = 24

    /**
     * The longest stretch of [a] heard again in [b], of [minFrames] to [maxFrames] frames; null when
     * there is none. Prints that are not [Prints.heard] agree with nothing.
     */
    fun shared(a: Prints, b: Prints, minFrames: Int, maxFrames: Int): Shared? {
        val aLen = a.end
        val bLen = b.end
        if (aLen < minFrames || bLen < minFrames) return null
        val shifts = likelyShifts(a, b)
        var best: Shared? = null
        for (s in shifts) {
            val found = walk(a, b, s, minFrames, maxFrames) ?: continue
            if (best == null || found.frames > best.frames) best = found
        }
        return best
    }

    /** Shifts (b frame − a frame) voted for by halves of prints equal or one bit apart. */
    private fun likelyShifts(a: Prints, b: Prints): IntArray {
        val aLen = a.end
        val bLen = b.end
        // Where each 16-bit half of a's prints is (the high halves after the low ones).
        val keys = 1 shl 17
        val start = IntArray(keys + 1)
        for (j in 0 until aLen) {
            if (!a.heard[j]) continue
            val v = a.bits[j]
            start[(v and 0xFFFF) + 1]++
            start[((v ushr 16) or 0x10000) + 1]++
        }
        for (k in 0 until keys) start[k + 1] += start[k]
        val at = start.copyOf()
        val where = IntArray(start[keys])
        for (j in 0 until aLen) {
            if (!a.heard[j]) continue
            val v = a.bits[j]
            where[at[v and 0xFFFF]++] = j
            where[at[(v ushr 16) or 0x10000]++] = j
        }
        val votes = IntArray(aLen + bLen)
        for (i in 0 until bLen) {
            if (!b.heard[i]) continue
            val v = b.bits[i]
            for (half in 0..1) {
                val h = if (half == 0) v and 0xFFFF else v ushr 16
                val tag = half shl 16
                for (flip in -1 until 16) {
                    val key = (if (flip < 0) h else h xor (1 shl flip)) or tag
                    for (w in start[key] until start[key + 1]) votes[i - where[w] + aLen]++
                }
            }
        }
        // The most voted shifts, each with its neighbours (the two files' frames may fall half a frame apart).
        val order = (votes.indices).sortedByDescending { votes[it] }.take(CANDIDATES)
        val out = LinkedHashSet<Int>()
        for (k in order) {
            if (votes[k] < 3) continue
            for (d in -1..1) out.add(k - aLen + d)
        }
        return out.toIntArray()
    }

    /** Along shift [s], the longest run where the prints agree over each second. */
    private fun walk(a: Prints, b: Prints, s: Int, minFrames: Int, maxFrames: Int): Shared? {
        val j0 = maxOf(0, -s)
        val j1 = minOf(a.end, b.end - s)
        val n = j1 - j0
        if (n < minFrames) return null
        val d = IntArray(n)
        for (k in 0 until n) {
            val j = j0 + k
            val i = j + s
            d[k] = if (a.heard[j] && b.heard[i]) Integer.bitCount(a.bits[j] xor b.bits[i]) else 16
        }
        // The average over a second centred on each frame.
        val good = BooleanArray(n)
        var sum = 0
        val half = SMOOTH / 2
        for (k in 0 until minOf(n, half)) sum += d[k]
        for (k in 0 until n) {
            val add = k + half
            if (add < n) sum += d[add]
            val drop = k - half - 1
            if (drop >= 0) sum -= d[drop]
            val count = minOf(n - 1, k + half) - maxOf(0, k - half) + 1
            good[k] = sum.toDouble() / count < MAX_BITS
        }
        var best: Shared? = null
        var k = 0
        while (k < n) {
            if (!good[k]) {
                k++
                continue
            }
            var e = k
            while (e < n && good[e]) e++
            // The smoothing spreads a run by up to half a second at each end: take back the frames that disagree.
            var from = k
            var to = e
            while (from < to && d[from] > 13) from++
            while (to > from && d[to - 1] > 13) to--
            val len = to - from
            if (len in minFrames..maxFrames && (best == null || len > best.frames)) {
                best = Shared(j0 + from, j0 + to, j0 + from + s, j0 + to + s)
            }
            k = e
        }
        return best
    }
}
