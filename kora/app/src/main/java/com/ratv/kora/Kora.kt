package com.ratv.kora

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The schedule: fixtures.json from the kora-data release (rebuilt by GitHub every 3 hours), kept on
 * the device so the app opens at once and still works without internet, plus live scores from ESPN
 * for the matches being played.
 *
 * Tests point it at other servers with: adb shell setprop debug.kora.data URL / debug.kora.espn URL
 */
object Kora {
    private const val TAG = "RAKora"
    private const val DATA_URL = "https://github.com/rajavo/RATV/releases/download/kora-data/fixtures.json"
    private const val ESPN = "https://site.api.espn.com"
    private const val FRESH_MS = 30L * 60L * 1000L
    private const val MIN = 60_000L

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val _schedule = MutableStateFlow<Schedule?>(null)
    val schedule: StateFlow<Schedule?> = _schedule.asStateFlow()

    private val _live = MutableStateFlow<Map<String, Live>>(emptyMap())
    val live: StateFlow<Map<String, Live>> = _live.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Why the schedule could not be downloaded, while there is none on the device either. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** When the schedule on the device was last downloaded (ms), 0 for never. */
    private val _fetchedAt = MutableStateFlow(0L)
    val fetchedAt: StateFlow<Long> = _fetchedAt.asStateFlow()

    private val lock = Mutex()

    private fun dataUrl() = debugProperty("debug.kora.data") ?: DATA_URL
    private fun espn() = debugProperty("debug.kora.espn") ?: ESPN
    private fun cacheFile(context: Context) = File(context.filesDir, "fixtures.json")

    /** The schedule kept on the device, at once. */
    suspend fun loadSaved(context: Context) = withContext(Dispatchers.IO) {
        if (_schedule.value != null) return@withContext
        val f = cacheFile(context)
        if (!f.exists()) return@withContext
        try {
            _schedule.value = ScheduleParser.parse(f.readText())
            _fetchedAt.value = f.lastModified()
        } catch (e: Exception) {
            Log.w(TAG, "saved schedule unreadable", e)
        }
    }

    /** Downloads the schedule when the one on the device is older than half an hour (or always, with [force]). */
    suspend fun refresh(context: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            loadSaved(context)
            if (!force && _schedule.value != null && System.currentTimeMillis() - _fetchedAt.value < FRESH_MS) {
                return@withLock true
            }
            _loading.value = true
            try {
                val text = get(dataUrl())
                val parsed = ScheduleParser.parse(text)
                val f = cacheFile(context)
                val part = File(f.parentFile, f.name + ".part")
                part.writeText(text)
                if (!part.renameTo(f)) f.writeText(text)
                _schedule.value = parsed
                _fetchedAt.value = System.currentTimeMillis()
                _error.value = null
                Log.i(TAG, "schedule: ${parsed.matches.size} matches, ${parsed.leagues.size} competitions")
                true
            } catch (e: Exception) {
                Log.w(TAG, "schedule download failed: $e")
                if (_schedule.value == null) {
                    _error.value = "ما قدرنا نحمّل الجدول. تأكد إن النت شغّال وجرّب مرة ثانية."
                }
                false
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Live scores for the matches being played now (from 10 minutes before kick-off to 2.5 hours
     * after), one ESPN request per competition and day.
     */
    suspend fun refreshLive() = withContext(Dispatchers.IO) {
        val s = _schedule.value ?: return@withContext
        val now = System.currentTimeMillis()
        val due = s.matches.filter { m ->
            m.fromEspn && !m.tbd && now >= m.time - 10 * MIN && now <= m.time + 150 * MIN &&
                (_live.value[m.id]?.state ?: m.state) != "post"
        }
        if (due.isEmpty()) return@withContext
        // ESPN files every match under its day in New York.
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        val groups = due.groupBy { it.league to day.format(Date(it.time)) }
        val found = coroutineScope {
            groups.map { (key, list) ->
                async {
                    try {
                        val url = "${espn()}/apis/site/v2/sports/soccer/${key.first}/scoreboard?dates=${key.second}"
                        ScheduleParser.parseLive(get(url), list.map { it.id }.toSet())
                    } catch (e: Exception) {
                        Log.w(TAG, "live ${key.first}: $e")
                        emptyMap()
                    }
                }
            }.awaitAll()
        }
        val merged = HashMap(_live.value)
        found.forEach { merged.putAll(it) }
        // Forget matches long over.
        val keep = s.matches.filter { now - it.time < 6 * 60 * MIN }.map { it.id }.toSet()
        merged.keys.retainAll(keep)
        _live.value = merged
        Log.i(TAG, "live: ${found.sumOf { it.size }} of ${due.size} matches")
    }

    fun get(url: String): String =
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            r.body?.string() ?: throw IOException("empty answer")
        }
}

/** A system property set by the tests (adb shell setprop), null when unset or on a normal device. */
fun debugProperty(name: String): String? = try {
    val c = Class.forName("android.os.SystemProperties")
    (c.getMethod("get", String::class.java).invoke(null, name) as? String)?.takeIf { it.isNotBlank() }
} catch (e: Exception) {
    null
}
