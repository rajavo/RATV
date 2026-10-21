package com.ratv.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Keeps a live TS channel flowing when the connection breaks.
 *
 * A live channel is one endless download. When the server drops it or stops sending for a moment,
 * the stock player keeps showing what it has stored, waits until that store is empty, and only
 * then connects again, so every short network hiccup becomes a visible stall. Other IPTV players
 * reconnect at once. This source does the same: on a read error, or when the server ends the
 * stream, it opens the channel again straight away and goes on with the new data, while the
 * picture keeps playing from the store. Only live streams (no known length, not HLS) are handled
 * this way; films, series and HLS segments resume by themselves.
 */
@UnstableApi
class LiveReconnectingDataSource(private val upstream: DataSource.Factory) : DataSource {

    private val listeners = ArrayList<TransferListener>()
    private var current: DataSource? = null
    private var spec: DataSpec? = null
    private var live = false

    @Volatile
    private var closed = false

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
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var failures = 0
        while (true) {
            val source = current
            if (source != null) {
                val n = try {
                    source.read(buffer, offset, length)
                } catch (e: IOException) {
                    if (!live || closed) throw e
                    LOST
                }
                if (n != LOST && (n != C.RESULT_END_OF_INPUT || !live)) return n
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
            } catch (e: IOException) {
                runCatching { again.close() }
            }
        }
    }

    override fun getUri(): Uri? = current?.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        closed = true
        val source = current
        current = null
        source?.close()
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = LiveReconnectingDataSource(upstream)
    }

    private companion object {
        const val LOST = Int.MIN_VALUE
        const val MAX_RECONNECTS = 25
        val RETRY_DELAYS_MS = longArrayOf(0L, 250L, 500L, 1_000L, 1_000L, 2_000L)
    }
}
