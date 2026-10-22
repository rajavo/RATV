package com.ratv.app.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * How much picture a live channel stores before it plays, shared by every player of the app.
 *
 * A live channel comes in as fast as it is shown, so the only way to a bigger store is to wait for
 * it before playing. Waiting costs time when a channel opens, so it is done only where it pays:
 * after a channel stalled (a shaky line at home, or a server that sends unevenly), the next start
 * and the next recovery wait 5 s longer, up to 10 s more; after 10 minutes without a stall it steps
 * back down. "fast" never waits longer, "max" always does.
 */
object Steadiness {
    /** "auto" (the default), "fast" or "max", from the settings. */
    @Volatile
    var mode: String = "auto"

    /** The main player's store right now (ms), for the watch on stuck connections. */
    @Volatile
    var reserveMs: Long = 0L

    @Volatile
    private var level = 0

    @Volatile
    private var calmSince = 0L

    private const val STEP_MS = 5_000L
    private const val MAX_LEVEL = 2
    private const val CALM_MS = 10L * 60L * 1000L

    /** How much longer than usual a live channel waits before it plays now (ms). */
    val extraMs: Long
        get() = when (mode) {
            "fast" -> 0L
            "max" -> MAX_LEVEL * STEP_MS
            else -> {
                calmDown()
                level * STEP_MS
            }
        }

    /** A live channel ran out of picture. */
    fun onStall() {
        calmSince = SystemClock.elapsedRealtime()
        if (mode == "auto" && level < MAX_LEVEL) {
            level++
            Log.i("RATVPlayback", "steadiness level $level")
        }
    }

    private fun calmDown() {
        if (level == 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - calmSince > CALM_MS) {
            level--
            calmSince = now
        }
    }
}

/** The usual load control, with the live start and recovery waits made longer by [Steadiness]. */
@UnstableApi
class SteadyLoadControl(
    minBufferMs: Int,
    maxBufferMs: Int,
    private val startMs: Int,
    private val afterStallMs: Int,
    private val targetBytes: Int
) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    minBufferMs,
    maxBufferMs,
    startMs,
    afterStallMs,
    targetBytes,
    false,
    0,
    false
) {
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val extra = Steadiness.extraMs
        if (extra <= 0L) return super.shouldStartPlayback(parameters)
        var needUs = (if (parameters.rebuffering) afterStallMs + extra else startMs + extra / 2) * 1000L
        // An HLS channel cannot store more than its distance from the live edge: wait for half of it at most.
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) needUs = minOf(needUs, parameters.targetLiveOffsetUs / 2)
        val buffered = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        return buffered >= needUs || allocator.totalBytesAllocated >= targetBytes
    }
}
