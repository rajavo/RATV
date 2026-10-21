package com.ratv.net

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock

/**
 * How fast each app uses the internet now, from Android's own counters (needs usage access).
 * Android refreshes these counters for an app at most every 15 seconds, so each reading covers
 * about that long.
 */
class UsageMeter(context: Context) {

    class AppSpeed(val uid: Int, val downMbps: Double, val upMbps: Double)

    private val nsm = context.getSystemService(NetworkStatsManager::class.java)

    // A fixed start and an end in the future, so the running bucket always counts in full.
    private val start = System.currentTimeMillis() - 3L * 60L * 60L * 1000L
    private var last: Map<Int, LongArray>? = null
    private var lastAt = 0L

    /** Asking more often than this returns old counters. */
    val interval: Long = if (Build.VERSION.SDK_INT >= 30) 16_000L else 4_000L

    /** Speeds since the previous call, fastest first; null on the first call. */
    @Suppress("DEPRECATION")
    fun sample(): List<AppSpeed>? {
        val now = SystemClock.elapsedRealtime()
        val totals = HashMap<Int, LongArray>()
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_ETHERNET)) {
            val stats = try {
                nsm?.querySummary(type, null, start, System.currentTimeMillis() + 24L * 60L * 60L * 1000L)
            } catch (e: Exception) {
                null
            } ?: continue
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val t = totals.getOrPut(bucket.uid) { LongArray(2) }
                t[0] += bucket.rxBytes
                t[1] += bucket.txBytes
            }
            stats.close()
        }
        val previous = last
        val seconds = (now - lastAt) / 1000.0
        last = totals
        lastAt = now
        if (previous == null || seconds <= 0) return null
        return totals.mapNotNull { (uid, t) ->
            val p = previous[uid] ?: LongArray(2)
            val down = (t[0] - p[0]).coerceAtLeast(0L) * 8.0 / seconds / 1_000_000.0
            val up = (t[1] - p[1]).coerceAtLeast(0L) * 8.0 / seconds / 1_000_000.0
            if (down < 0.01 && up < 0.01) null else AppSpeed(uid, down, up)
        }.sortedByDescending { it.downMbps + it.upMbps }
    }
}
