package com.ratv.app.player

import android.content.Context
import android.content.SharedPreferences
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
 * after a channel stalled (a shaky line at home, or a server that sends unevenly), the next
 * recovery waits 15 s longer, up to a minute more (a store of 70 s, which rides out what made it
 * stall many times over), and channels open a little later (up to 10 s); after 44 minutes without
 * a stall it steps back down (an unsteady server often is so for the evening, not for minutes). "fast" never waits longer, "max" always stores a minute and a half more.
 *
 * What the device learned about its line is kept across restarts (still stepping down with the
 * calm time that passed), so a TV on a fluctuating line starts with the bigger store at once.
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

    private var saved: SharedPreferences? = null

    private const val STEP_MS = 15_000L
    private const val MAX_AUTO_LEVEL = 4
    private const val MAX_LEVEL = 6
    private const val CALM_MS = 44L * 60L * 1000L

    /** At most this much longer before a channel opens (the bigger store is gathered after a stall). */
    const val START_EXTRA_MAX_MS = 10_000L

    /** What a live channel stores after a stall before it plays again, before [extraMs]. */
    const val AFTER_STALL_MS = 10_000

    /** What a live channel gathers after a stall now (ms), for the player to say so. */
    val afterStallTargetMs: Long get() = AFTER_STALL_MS + extraMs

    /** Picks up what this device learned before (once per run of the app). */
    @Synchronized
    fun attach(context: Context) {
        if (saved != null) return
        val sp = context.applicationContext.getSharedPreferences("steadiness", Context.MODE_PRIVATE)
        saved = sp
        val stored = sp.getInt("level", 0).coerceIn(0, MAX_AUTO_LEVEL)
        val since = sp.getLong("since", 0L)
        val calmFor = (System.currentTimeMillis() - since).coerceAtLeast(0L)
        val steps = if (since > 0L) (calmFor / CALM_MS).toInt() else stored
        level = (stored - steps).coerceAtLeast(0)
        // The calm time already spent at this level counts.
        calmSince = SystemClock.elapsedRealtime() - (calmFor % CALM_MS)
        if (level > 0) Log.i("RATVPlayback", "steadiness level $level (kept from before)")
    }

    private fun save() {
        saved?.edit()?.putInt("level", level)?.putLong("since", System.currentTimeMillis())?.apply()
    }

    /** How much longer than usual a live channel waits after a stall now (ms); a start waits a quarter of it, at most [START_EXTRA_MAX_MS]. */
    val extraMs: Long
        get() = when (mode) {
            "fast" -> 0L
            "max" -> MAX_LEVEL * STEP_MS
            else -> {
                calmDown()
                level * STEP_MS
            }
        }

    /** True when the line has stalled again and again lately (or "max" is chosen). */
    val unsteadyLine: Boolean
        get() = when (mode) {
            "fast" -> false
            "max" -> true
            else -> {
                calmDown()
                level >= 2
            }
        }

    /** A live channel ran out of picture. */
    fun onStall() {
        calmSince = SystemClock.elapsedRealtime()
        if (mode == "auto" && level < MAX_AUTO_LEVEL) {
            level++
            Log.i("RATVPlayback", "steadiness level $level")
        }
        save()
    }

    private fun calmDown() {
        if (level == 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - calmSince > CALM_MS) {
            level--
            calmSince = now
            save()
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
        var needUs = (if (parameters.rebuffering) afterStallMs + extra else startMs + minOf(extra / 4, Steadiness.START_EXTRA_MAX_MS)) * 1000L
        // An HLS channel cannot store more than its distance from the live edge: wait for half of it at most.
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) needUs = minOf(needUs, parameters.targetLiveOffsetUs / 2)
        val buffered = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        return buffered >= needUs || allocator.totalBytesAllocated >= targetBytes
    }
}
