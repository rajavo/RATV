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
        })
            .setUserAgent(userAgent)
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

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
    }

    private fun newSource(): DataSource = upstream.createDataSource().also { s -> listeners.forEach { s.addTransferListener(it) } }

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        spec = dataSpec
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
        var failures = 0
        while (true) {
            val source = current
            if (source != null) {
                val started = SystemClock.elapsedRealtime()
                waitingSince = started
                val n = try {
                    source.read(buffer, offset, length)
                } catch (e: IOException) {
                    waitingSince = 0L
                    if (!live || closed) throw e
                    LOST
                }
                waitingSince = 0L
                if (n > 0) {
                    if (live) noteWait(SystemClock.elapsedRealtime() - started)
                    return n
                }
                if (n != LOST && (n != C.RESULT_END_OF_INPUT || !live)) return n
                receiving = false
                runCatching { source.close() }
                current = null
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
                Log.i(TAG, "reconnected")
            } catch (e: IOException) {
                runCatching { again.close() }
            }
        }
    }

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
        val limit = if (now - openedAt < LEARN_MS) {
            STALL_MAX_MS
        } else {
            (maxOf(windowMax, previousMax) * 3).coerceIn(STALL_MIN_MS, STALL_MAX_MS)
        }
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
        val source = current
        current = null
        source?.close()
    }

    class Factory(private val context: Context, private val client: OkHttpClient, private val userAgent: String) :
        DataSource.Factory {
        override fun createDataSource(): DataSource = LiveReconnectingDataSource(context.applicationContext, client, userAgent)
    }

    private companion object {
        const val TAG = "RATVLive"
        const val LOST = Int.MIN_VALUE
        const val MAX_RECONNECTS = 25
        const val STALL_MIN_MS = 3_000L
        const val STALL_MAX_MS = 8_000L
        const val LEARN_MS = 12_000L
        val RETRY_DELAYS_MS = longArrayOf(0L, 250L, 500L, 1_000L, 1_000L, 2_000L)
        val WATCHED: MutableSet<LiveReconnectingDataSource> = CopyOnWriteArraySet()

        init {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ratv-live-watch").apply { isDaemon = true } }
                .scheduleWithFixedDelay({
                    val now = SystemClock.elapsedRealtime()
                    WATCHED.forEach { it.check(now) }
                }, 250L, 250L, TimeUnit.MILLISECONDS)
        }
    }
}
