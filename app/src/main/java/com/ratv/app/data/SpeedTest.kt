package com.ratv.app.data

import com.ratv.app.i18n.tr
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Internet speed test: download speed over a few parallel connections for a few seconds,
 * latency to the test server, and latency to the subscription's own server.
 *
 * Tests point it at another server with: adb shell setprop debug.ratv.speed http://host:port
 */
object SpeedTest {

    class Result(val mbps: Double, val pingMs: Long?, val serverPingMs: Long?)

    private const val DEFAULT_BASE = "https://speed.cloudflare.com"
    private const val CONNECTIONS = 4
    private const val SECONDS = 8L

    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .cache(null)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun base(): String = debugProperty("debug.ratv.speed") ?: DEFAULT_BASE

    /** Address of the subscription's server, for its latency. */
    fun serverOf(p: Playlist?): String? {
        p ?: return null
        val raw = if (p.type == PlaylistType.XTREAM) p.server else p.m3uUrl
        val url = raw.toHttpUrlOrNull() ?: return null
        return url.newBuilder().encodedPath("/").query(null).build().toString()
    }

    /** Runs the test, reporting the speed so far (Mbit/s) on the main thread; null when nothing downloaded. */
    suspend fun run(serverUrl: String?, onProgress: (Double) -> Unit): Result? = withContext(Dispatchers.IO) {
        val b = base()
        val ping = ping("$b/__down?bytes=0")
        val serverPing = serverUrl?.let { ping(it) }
        val total = AtomicLong(0)
        val start = SystemClock.elapsedRealtime()
        val deadline = start + SECONDS * 1000L
        val reporter = launch {
            while (isActive) {
                delay(300L)
                val sec = (SystemClock.elapsedRealtime() - start) / 1000.0
                if (sec > 0.5) {
                    val now = total.get() * 8 / sec / 1_000_000.0
                    withContext(Dispatchers.Main) { onProgress(now) }
                }
            }
        }
        (1..CONNECTIONS).map {
            async {
                runCatching {
                    val buf = ByteArray(64 * 1024)
                    while (isActive && SystemClock.elapsedRealtime() < deadline) {
                        val request = Request.Builder()
                            .url("$b/__down?bytes=25000000")
                            .header("Cache-Control", "no-cache")
                            .build()
                        client.newCall(request).execute().use { response ->
                            val input = response.body?.byteStream()
                            if (!response.isSuccessful || input == null) return@runCatching
                            while (SystemClock.elapsedRealtime() < deadline) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total.addAndGet(n.toLong())
                            }
                        }
                    }
                }
            }
        }.awaitAll()
        reporter.cancel()
        val bytes = total.get()
        if (bytes == 0L) return@withContext null
        val sec = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L) / 1000.0
        Result(bytes * 8 / sec / 1_000_000.0, ping, serverPing)
    }

    /**
     * The server's answer time (ms): the best of five on a connection already open, as the app's
     * requests are (the first one, which also looks up the name and connects, is not counted).
     */
    private fun ping(url: String): Long? {
        runCatching { client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() } }
        var best: Long? = null
        repeat(5) {
            val t0 = SystemClock.elapsedRealtime()
            val ok = runCatching { client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() } }.isSuccess
            val dt = SystemClock.elapsedRealtime() - t0
            if (ok) best = minOf(best ?: dt, dt)
        }
        return best
    }

    /** Advice when answers are slow ([wifi]: the TV is on Wi-Fi), or null when they are fine. */
    fun latencyAdvice(pingMs: Long?, wifi: Boolean): String? = when {
        pingMs == null || pingMs <= 80L -> null
        wifi -> tr("الاستجابة بطيئة. وصّل التلفزيون بكيبل إنترنت إذا تقدر، أو قرّب الراوتر منه: الكيبل أسرع استجابة من الواي فاي.")
        else -> tr("الاستجابة بطيئة من جهة النت أو السيرفر. جرّب تطفي الراوتر وتشغله، وإذا استمرت كلّم مزوّد الاشتراك.")
    }

    /** What the speed is enough for. */
    fun verdict(mbps: Double): String = when {
        mbps >= 25 -> tr("ممتاز: يكفي لبث 4K")
        mbps >= 12 -> tr("جيد جداً: يكفي لبث FHD")
        mbps >= 6 -> tr("جيد: يكفي لبث HD")
        mbps >= 3 -> tr("مقبول: يكفي لبث SD، وقد يتقطع HD")
        else -> tr("ضعيف: قد يتقطع البث")
    }
}

internal fun debugProperty(name: String): String? = try {
    val c = Class.forName("android.os.SystemProperties")
    (c.getMethod("get", String::class.java).invoke(null, name) as? String)?.takeIf { it.isNotBlank() }
} catch (e: Throwable) {
    null
}
