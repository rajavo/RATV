package com.ratv.store

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One RA app as published: its name, its file and, when the release says it, its version and package. */
class Release(
    val title: String,
    val apk: String,
    val url: String,
    /** When the file was last replaced (milliseconds), for releases without a version file. */
    val updatedAt: Long,
    val versionCode: Int?,
    val versionName: String?,
    val pkg: String,
    val about: String
)

/**
 * The RA apps, read from the project's releases: every release with a file named RA….apk is an app,
 * so a new RA app shows here on its own as soon as it is published.
 */
object Catalog {
    const val REPO = "rajavo/RATV"
    private val APK = Regex("""^RA[A-Za-z0-9]*\.apk$""")

    private var cached: List<Release>? = null
    private var cachedAt = 0L

    /** Read again at most every two minutes (the screen comes back after each install). Network: call off the main thread. */
    @Synchronized
    fun load(): List<Release> {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < 120_000L) return it }
        return fetch().also {
            cached = it
            cachedAt = now
        }
    }

    private fun fetch(): List<Release> {
        val releases = JSONArray(get("https://api.github.com/repos/$REPO/releases?per_page=100"))
        val out = ArrayList<Release>()
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val assets = r.optJSONArray("assets") ?: continue
            var apk: JSONObject? = null
            var version: JSONObject? = null
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val name = a.optString("name")
                if (APK.matches(name)) apk = a
                if (name == "version.json") version = a
            }
            if (apk == null) continue
            val v = version?.let { runCatching { JSONObject(get(it.getString("browser_download_url"))) }.getOrNull() }
            val name = apk.getString("name")
            out.add(
                Release(
                    title = r.optString("name").ifBlank { name.removeSuffix(".apk") },
                    apk = name,
                    url = apk.getString("browser_download_url"),
                    updatedAt = time(apk.optString("updated_at")),
                    versionCode = v?.optInt("versionCode", -1)?.takeIf { it > 0 },
                    versionName = v?.optString("versionName")?.ifBlank { null },
                    pkg = v?.optString("package")?.ifBlank { null } ?: packageFor(name),
                    about = r.optString("body").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(140)
                )
            )
        }
        // RA TV first, then the others by name; RA Store last.
        return out.sortedWith(compareBy({ if (it.pkg == "com.ratv.app") 0 else if (it.pkg == "com.ratv.store") 2 else 1 }, { it.title }))
    }

    /** The package an RA app's file belongs to: RATV.apk is com.ratv.app, RAKora.apk com.ratv.kora, and so on. */
    fun packageFor(apk: String): String {
        val base = apk.removeSuffix(".apk")
        if (base == "RATV") return "com.ratv.app"
        return "com.ratv." + base.removePrefix("RA").lowercase()
    }

    /** GitHub's times ("2026-10-30T18:00:52Z") in milliseconds; 0 when unreadable. */
    fun time(s: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time
    }.getOrDefault(0L)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "RAStore")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
