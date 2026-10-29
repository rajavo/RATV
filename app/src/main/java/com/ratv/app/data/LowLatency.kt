package com.ratv.app.data

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Shorter waits on every request, while the app is on screen.
 *
 * Most TVs keep their Wi-Fi in power saving, which holds each answer from the router back by tens of
 * milliseconds: the app asks for the low-latency Wi-Fi mode (Android 10+, else "high performance")
 * while it is shown, and gives it back when it is left.
 *
 * Opening a channel or a film, or jumping in one, needs a new connection to the server: one more
 * round trip (three with https) before anything comes. A spare connection to the subscription's
 * server (and to where it sends the streams on) is kept open and fresh, so the next one starts at once.
 */
object LowLatency {
    private const val TAG = "RATVNet"
    private const val REFRESH_S = 40L

    private var lock: WifiManager.WifiLock? = null
    private val origins = LinkedHashSet<String>()
    @Volatile private var shown = false
    private var loop: ScheduledFuture<*>? = null
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "RATVWarm").apply { isDaemon = true } }

    /** Same connections as every other request (the same pool), but never waiting long. */
    private val client: OkHttpClient by lazy {
        Http.client.newBuilder().callTimeout(6, TimeUnit.SECONDS).followRedirects(false).build()
    }

    @Synchronized
    fun onShown(context: Context) {
        shown = true
        if (lock?.isHeld != true) {
            runCatching {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return@runCatching
                val mode = if (Build.VERSION.SDK_INT >= 29) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                lock = wm.createWifiLock(mode, "RATV:low-latency").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "Wi-Fi low latency on (" + (if (Build.VERSION.SDK_INT >= 29) "low latency" else "high performance") + ")")
            }.onFailure { Log.w(TAG, "no low-latency Wi-Fi: ${it.message}") }
        }
        if (loop == null) loop = worker.scheduleWithFixedDelay({ warmAll() }, 1L, REFRESH_S, TimeUnit.SECONDS)
    }

    @Synchronized
    fun onHidden() {
        shown = false
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
        loop?.cancel(false)
        loop = null
    }

    /** A server the app talks to (a playlist, or where a stream was sent on): kept warm from now. */
    fun note(url: String?) {
        val origin = originOf(url) ?: return
        val added = synchronized(origins) {
            val fresh = origins.remove(origin).not()
            origins.add(origin)
            while (origins.size > 3) origins.remove(origins.first())
            fresh
        }
        if (added) Log.i(TAG, "keeping a connection ready to ${origin.substringAfter("://").substringBefore(':')}")
    }

    /** A stream just took the spare connection: open the next one now. */
    fun spareTaken() {
        if (shown) worker.schedule({ warmAll() }, 2L, TimeUnit.SECONDS)
    }

    private fun warmAll() {
        if (!shown) return
        val list = synchronized(origins) { origins.toList() }
        for (o in list) {
            runCatching {
                client.newCall(Request.Builder().url("$o/").header("Cache-Control", "no-cache").build()).execute().use { r ->
                    // Read the (small) answer to its end, so the connection goes back to the pool.
                    r.body?.source()?.let { src ->
                        src.request(64L * 1024 + 1)
                        src.buffer.clear()
                    }
                }
            }
        }
    }

    fun originOf(url: String?): String? {
        val u = url?.toHttpUrlOrNull() ?: return null
        val host = if (':' in u.host) "[" + u.host + "]" else u.host
        return u.scheme + "://" + host + ":" + u.port
    }
}
