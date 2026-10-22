package com.ratv.app.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps a live TS channel flowing when the connection breaks or gets stuck.
 *
 * A live channel is one endless download. When the server drops it, or the connection stops
 * delivering without being closed, the stock player keeps showing what it has stored and only
 * connects again once that store is empty, so every network hiccup becomes a visible stall.
 *
 * This source reconnects at once when the stream breaks or ends, while the picture keeps playing
 * from the store. It also watches a connection that stops delivering: once data has flowed, a
 * wait much longer than this server's usual pauses (3 s for a steady stream, up to 8 s for
 * servers that send in chunks) cuts the connection and opens a new one, before the store runs
 * out. A channel that takes a while to start is given its time: the watch begins with the data.
 *
 * A new connection starts with a burst of the last seconds the server holds, part of which the
 * player already has. Passed on as it is, the picture would jump back or freeze for those seconds,
 * so the new stream is joined where the old one ended: everything up to the last picture already
 * delivered is skipped (by decode time), and it resumes with the very next picture. Only when
 * pictures were lost in between (the server jumped ahead) does it wait, at most 1.5 s, for a key
 * frame, so the picture is not smeared until the next one.
 *
 * If the stream's clock itself jumps (the server restarted the channel's source, a common thing
 * with IPTV), the player would take the new times for minutes of stored picture and freeze for
 * good. Such a jump ends the load at that point, and the player starts the channel again.
 *
 * Only live streams (no known length, not HLS) are handled this way.
 */
@UnstableApi
class LiveReconnectingDataSource(context: Context, client: OkHttpClient, userAgent: String) : DataSource {

    /** The request of the current connection, so a stuck one can be cut from the watch thread. */
    @Volatile
    private var call: Call? = null

    private val upstream: DataSource.Factory = DefaultDataSource.Factory(
        context,
        OkHttpDataSource.Factory(object : Call.Factory {
            override fun newCall(request: Request): Call = client.newCall(request).also { call = it }
        }).setUserAgent(userAgent)
    )
    private val listeners = ArrayList<TransferListener>()
    private var current: DataSource? = null
    private var spec: DataSpec? = null

    @Volatile
    private var live = false

    @Volatile
    private var closed = false

    /** When the read now waiting for data began (0: not waiting). */
    @Volatile
    private var waitingSince = 0L

    /** Data has flowed on the current connection. */
    @Volatile
    private var receiving = false
    private var openedAt = 0L

    // Longest wait for data seen lately (this 30 s and the previous 30 s): the server's usual pauses.
    @Volatile
    private var windowMax = 0L

    @Volatile
    private var previousMax = 0L
    private var windowStart = 0L

    // Joining a new connection to the old one (MPEG-TS packets of 188 bytes).
    private val delivered = ByteArray(TS_PACKET)
    private var deliveredFill = 0
    private var lastVideoDts = -1L
    private var keyFramesMarked = false
    private var splicing = false
    private var spliceStartedAt = 0L
    private var spliceDropped = 0L
    private val incoming = ByteArray(TS_PACKET)
    private var incomingFill = 0
    private val spliceBuffer = ByteArray(64 * 1024)
    private var pending: ByteArray? = null
    private var pendingOffset = 0
    private var foundDts = 0L
    private var foundKey = false
    private var foundPid = -1
    private var videoPid = -1 // the one picture stream followed

    /** The stream's clock jumped: the next read ends the load, so the player starts the channel again. */
    private var timelineJumped = false

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
    }

    private fun newSource(): DataSource = upstream.createDataSource().also { s -> listeners.forEach { s.addTransferListener(it) } }

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        spec = dataSpec
        pending = null
        splicing = false
        deliveredFill = 0
        incomingFill = 0
        lastVideoDts = -1L
        keyFramesMarked = false
        timelineJumped = false
        videoPid = -1
        val source = newSource()
        val length = source.open(dataSpec)
        current = source
        val scheme = dataSpec.uri.scheme?.lowercase()
        val path = dataSpec.uri.path.orEmpty().lowercase()
        live = length == C.LENGTH_UNSET.toLong() && dataSpec.position == 0L &&
            (scheme == "http" || scheme == "https") && !path.endsWith(".m3u8") && !path.endsWith(".m3u")
        if (live) {
            openedAt = SystemClock.elapsedRealtime()
            windowStart = openedAt
            windowMax = 0L
            previousMax = 0L
            receiving = false
            WATCHED.add(this)
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (timelineJumped) throw IOException(CLOCK_JUMPED)
        pending?.let { p ->
            val n = minOf(length, p.size - pendingOffset)
            System.arraycopy(p, pendingOffset, buffer, offset, n)
            pendingOffset += n
            if (pendingOffset >= p.size) pending = null
            return deliver(buffer, offset, n)
        }
        var failures = 0
        while (true) {
            val source = current
            if (source != null) {
                val joining = splicing
                val started = SystemClock.elapsedRealtime()
                waitingSince = started
                val n = try {
                    if (joining) source.read(spliceBuffer, 0, spliceBuffer.size) else source.read(buffer, offset, length)
                } catch (e: IOException) {
                    waitingSince = 0L
                    if (!live || closed) throw e
                    LOST
                }
                waitingSince = 0L
                if (n > 0) {
                    if (!live) return n
                    noteWait(SystemClock.elapsedRealtime() - started)
                    if (!joining) return deliver(buffer, offset, n)
                    val out = join(n) ?: continue // still in what the player already has
                    splicing = false
                    if (timelineJumped) throw IOException(CLOCK_JUMPED)
                    if (out.isEmpty()) continue
                    val k = minOf(length, out.size)
                    System.arraycopy(out, 0, buffer, offset, k)
                    if (k < out.size) {
                        pending = out
                        pendingOffset = k
                    }
                    return deliver(buffer, offset, k)
                }
                if (n != LOST && (n != C.RESULT_END_OF_INPUT || !live)) return n
                receiving = false
                runCatching { source.close() }
                current = null
                deliveredFill = 0 // a packet cut short by the break is dropped
            }
            if (closed) throw InterruptedIOException("Closed")
            failures++
            if (failures > MAX_RECONNECTS) throw IOException("Live stream lost after $MAX_RECONNECTS reconnects")
            try {
                Thread.sleep(RETRY_DELAYS_MS[minOf(failures - 1, RETRY_DELAYS_MS.size - 1)])
            } catch (e: InterruptedException) {
                throw InterruptedIOException("Interrupted while reconnecting")
            }
            val again = newSource()
            try {
                again.open(spec ?: throw IOException("Not opened"))
                current = again
                reconnects.incrementAndGet()
                splicing = lastVideoDts >= 0L
                spliceStartedAt = SystemClock.elapsedRealtime()
                spliceDropped = 0L
                incomingFill = 0
                Log.i(TAG, "reconnected")
            } catch (e: IOException) {
                runCatching { again.close() }
            }
        }
    }

    /** Passes [n] bytes already in [buffer], up to a jump of the stream's clock (then the load ends). */
    private fun deliver(buffer: ByteArray, offset: Int, n: Int): Int {
        val ok = track(buffer, offset, n)
        if (ok < n) pending = null
        if (ok > 0) return ok
        throw IOException(CLOCK_JUMPED)
    }

    /**
     * Follows the packets passed to the player (time of the last picture, whether key frames are
     * marked) and returns how many of the [n] bytes keep the stream's clock: all of them, or those
     * before a packet where the clock jumps.
     */
    private fun track(buf: ByteArray, off: Int, n: Int): Int {
        if (!live) return n
        var i = off
        val end = off + n
        while (i < end) {
            if (deliveredFill == 0 && buf[i] != SYNC) {
                i++
                continue
            }
            val packetStart = i - deliveredFill
            val k = minOf(TS_PACKET - deliveredFill, end - i)
            System.arraycopy(buf, i, delivered, deliveredFill, k)
            deliveredFill += k
            i += k
            if (deliveredFill == TS_PACKET) {
                deliveredFill = 0
                if (videoPesStart(delivered) && (videoPid < 0 || foundPid == videoPid)) {
                    videoPid = foundPid
                    if (lastVideoDts >= 0L && clockJumped(foundDts, lastVideoDts)) {
                        timelineJumped = true
                        Log.i(TAG, "the stream's clock jumped: starting the channel again")
                        return maxOf(0, packetStart - off)
                    }
                    lastVideoDts = foundDts
                    if (foundKey) keyFramesMarked = true
                }
            }
        }
        return n
    }

    /**
     * Reads [n] new bytes of a new connection: null while they are still what the player already has,
     * else what to pass on (from the first new picture at a key frame; empty when giving up on joining).
     */
    private fun join(n: Int): ByteArray? {
        var i = 0
        while (i < n) {
            if (incomingFill == 0 && spliceBuffer[i] != SYNC) {
                i++
                continue
            }
            val k = minOf(TS_PACKET - incomingFill, n - i)
            System.arraycopy(spliceBuffer, i, incoming, incomingFill, k)
            incomingFill += k
            i += k
            if (incomingFill < TS_PACKET) break
            incomingFill = 0
            val picture = videoPesStart(incoming) && foundPid == videoPid
            if (picture && !newer(foundDts, lastVideoDts) &&
                distance(foundDts, lastVideoDts) > OVERLAP_LIMIT
            ) {
                // Far behind what was shown: the server restarted the channel on another clock.
                timelineJumped = true
                Log.i(TAG, "the new connection runs on another clock: starting the channel again")
                return ByteArray(0)
            }
            if (picture && newer(foundDts, lastVideoDts)) {
                if (distance(foundDts, lastVideoDts) > FORWARD_LIMIT) {
                    timelineJumped = true
                    Log.i(TAG, "the new connection runs on another clock: starting the channel again")
                    return ByteArray(0)
                }
                // The very next picture joins seamlessly; after a gap, wait a little for a key frame.
                val continuous = distance(foundDts, lastVideoDts) <= CONTINUOUS_GAP
                val waitForKey = !continuous && keyFramesMarked &&
                    SystemClock.elapsedRealtime() - spliceStartedAt < KEY_WAIT_MS
                if (foundKey || !waitForKey) {
                    Log.i(TAG, "joined the new connection after skipping $spliceDropped bytes")
                    val out = ByteArray(TS_PACKET + (n - i))
                    System.arraycopy(incoming, 0, out, 0, TS_PACKET)
                    System.arraycopy(spliceBuffer, i, out, TS_PACKET, n - i)
                    return out
                }
            }
            spliceDropped += TS_PACKET
        }
        if (spliceDropped > GIVE_UP_BYTES || SystemClock.elapsedRealtime() - spliceStartedAt > GIVE_UP_MS) {
            // Nothing newer came: the server restarted the channel on another clock. Start it again.
            Log.i(TAG, "could not join the new connection after $spliceDropped bytes: starting the channel again")
            timelineJumped = true
            return ByteArray(0)
        }
        return null
    }

    /** True when [p] starts a video PES with a time stamp; fills [foundDts] (decode time) and [foundKey]. */
    private fun videoPesStart(p: ByteArray): Boolean {
        if (p[0] != SYNC || (p[1].toInt() and 0x40) == 0) return false
        val adaptation = (p[3].toInt() ushr 4) and 0x3
        if (adaptation == 0 || adaptation == 2) return false
        var i = 4
        var key = false
        if (adaptation == 3) {
            val len = p[4].toInt() and 0xFF
            if (len > 0) key = (p[5].toInt() and 0x40) != 0
            i = 5 + len
        }
        if (i + 19 > TS_PACKET) return false
        if (p[i].toInt() != 0 || p[i + 1].toInt() != 0 || p[i + 2].toInt() != 1) return false
        if ((p[i + 3].toInt() and 0xF0) != 0xE0) return false // not video
        val flags = p[i + 7].toInt() and 0xC0
        if ((flags and 0x80) == 0) return false // no time stamp
        // The decode time (DTS) when given, as it grows picture after picture; else the PTS.
        foundDts = timeStamp(p, if (flags == 0xC0) i + 14 else i + 9)
        foundKey = key
        foundPid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
        return true
    }

    private fun timeStamp(p: ByteArray, b: Int): Long =
        ((p[b].toLong() and 0x0E) shl 29) or
            ((p[b + 1].toLong() and 0xFF) shl 22) or
            ((p[b + 2].toLong() and 0xFE) shl 14) or
            ((p[b + 3].toLong() and 0xFF) shl 7) or
            ((p[b + 4].toLong() and 0xFE) ushr 1)

    private fun noteWait(waited: Long) {
        if (!receiving) {
            receiving = true // the first data of a connection: its wait is the server starting up
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - windowStart > 30_000L) {
            previousMax = windowMax
            windowMax = 0L
            windowStart = now
        }
        if (waited > windowMax) windowMax = waited
    }

    /** From the watch thread: cuts a connection that stopped delivering, so [read] opens a new one. */
    private fun check(now: Long) {
        if (!live || closed || !receiving) return
        val since = waitingSince
        if (since == 0L) return // not reading now (store full), nothing to watch
        val learned = if (now - openedAt < LEARN_MS) {
            STALL_MAX_MS
        } else {
            (maxOf(windowMax, previousMax) * 3).coerceIn(STALL_MIN_MS, STALL_MAX_MS)
        }
        // With a big store there is time for the server to carry on by itself (and send what it
        // owes, which a new connection would skip); with a small one, a new connection is better.
        val limit = maxOf(learned, minOf(STALL_MAX_MS, Steadiness.reserveMs - 2_000L))
        if (now - since > limit) {
            receiving = false
            Log.i(TAG, "no data for ${now - since} ms (limit $limit ms): reconnecting")
            call?.cancel()
        }
    }

    override fun getUri(): Uri? = current?.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        closed = true
        WATCHED.remove(this)
        waitingSince = 0L
        pending = null
        val source = current
        current = null
        source?.close()
    }

    class Factory(private val context: Context, private val client: OkHttpClient, private val userAgent: String) :
        DataSource.Factory {
        override fun createDataSource(): DataSource = LiveReconnectingDataSource(context.applicationContext, client, userAgent)
    }

    companion object {
        private const val TAG = "RATVLive"
        private const val LOST = Int.MIN_VALUE
        private const val MAX_RECONNECTS = 25
        private const val STALL_MIN_MS = 3_000L
        private const val STALL_MAX_MS = 8_000L
        private const val LEARN_MS = 12_000L
        private const val KEY_WAIT_MS = 1_500L
        private const val CONTINUOUS_GAP = 45_000L // 0.5 s in 90 kHz ticks
        private const val BACK_LIMIT = 90_000L // the clock going back more than 1 s
        private const val FORWARD_LIMIT = 900_000L // or jumping ahead more than 10 s
        private const val OVERLAP_LIMIT = 5_400_000L // a new connection more than 60 s behind
        private const val CLOCK_JUMPED = "The live stream's clock jumped"
        private const val MASK33 = 0x1FFFFFFFFL
        private const val GIVE_UP_MS = 5_000L
        private const val GIVE_UP_BYTES = 16L shl 20
        private const val TS_PACKET = 188
        private const val SYNC: Byte = 0x47
        private val RETRY_DELAYS_MS = longArrayOf(0L, 250L, 500L, 1_000L, 1_000L, 2_000L)
        private val WATCHED: MutableSet<LiveReconnectingDataSource> = CopyOnWriteArraySet()

        /** Reconnections of live channels since the app started (shown in the stream information). */
        val reconnects = AtomicInteger()

        /** Distance between two 33-bit time stamps, whichever comes first. */
        fun distance(a: Long, b: Long): Long {
            val d = (a - b) and MASK33
            return if (d < 0x100000000L) d else MASK33 + 1 - d
        }

        /** True when [dts] is not the next picture's time after [last]: back over 1 s, or ahead over 10 s. */
        fun clockJumped(dts: Long, last: Long): Boolean =
            if (newer(dts, last)) distance(dts, last) > FORWARD_LIMIT else distance(dts, last) > BACK_LIMIT

        /** True when the 33-bit time stamp [pts] comes after [last]. */
        fun newer(pts: Long, last: Long): Boolean {
            if (last < 0L) return true
            val d = (pts - last) and 0x1FFFFFFFFL
            return d in 1L until 0x100000000L
        }

        init {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ratv-live-watch").apply { isDaemon = true } }
                .scheduleWithFixedDelay({
                    val now = SystemClock.elapsedRealtime()
                    WATCHED.forEach { it.check(now) }
                }, 250L, 250L, TimeUnit.MILLISECONDS)
        }
    }
}
