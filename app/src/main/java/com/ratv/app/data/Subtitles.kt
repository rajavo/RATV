package com.ratv.app.data

import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import javax.net.ssl.SSLException

/** One subtitle line on screen from [start] to [end] (milliseconds). */
data class Cue(val start: Long, val end: Long, val text: String)

/** Reads SRT, WebVTT and ASS/SSA subtitle files in any common text encoding. */
object SubtitleParser {

    private val TIME = Regex(
        """(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})"""
    )
    private val ASS_TIME = Regex("""(\d+):(\d{2}):(\d{2})[.,](\d{1,3})""")
    private val HTML_TAG = Regex("""<[^>]{1,80}>""")
    private val ASS_TAG = Regex("""\{[^}]*\}""")
    private val BLANK_LINES = Regex("""\n{2,}""")

    /** Text of a subtitle file: UTF-8 when valid, otherwise the usual code page of [languageHint]. */
    fun decode(bytes: ByteArray, languageHint: String?): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            // Not UTF-8: fall back to the code page usually used for that language.
        }
        val codePage = when (languageHint?.lowercase()?.take(2)) {
            "ar", "fa", "ur" -> "windows-1256"
            "ru", "uk", "bg", "sr", "mk", "be" -> "windows-1251"
            "el" -> "windows-1253"
            "tr" -> "windows-1254"
            "he" -> "windows-1255"
            "pl", "cs", "sk", "hu", "ro", "hr", "sl", "bs" -> "windows-1250"
            "vi" -> "windows-1258"
            "zh" -> "GBK"
            "ja" -> "Shift_JIS"
            "ko" -> "EUC-KR"
            else -> "windows-1252"
        }
        return try {
            String(bytes, Charset.forName(codePage))
        } catch (e: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    fun parse(text: String): List<Cue> {
        val t = text.replace("\r\n", "\n").replace('\r', '\n')
        if (t.contains("[Events]", ignoreCase = true) && t.contains("Dialogue:", ignoreCase = true)) return parseAss(t)
        val lines = t.split('\n')
        val out = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            val m = TIME.find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val start = millis(m, 1)
            val end = millis(m, 5)
            i++
            val body = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (TIME.containsMatchIn(lines[i])) {
                    // No blank line before the next cue: its number ended up in this text.
                    if (body.isNotEmpty() && body.last().all { it.isDigit() }) body.removeAt(body.size - 1)
                    break
                }
                body.add(lines[i].trim())
                i++
            }
            val clean = cleanText(body.joinToString("\n"))
            if (clean.isNotEmpty() && end > start) out.add(Cue(start, end, clean))
        }
        out.sortBy { it.start }
        return out
    }

    private fun millis(m: MatchResult, first: Int): Long {
        val h = m.groupValues[first].toLongOrNull() ?: 0L
        val min = m.groupValues[first + 1].toLong()
        val s = m.groupValues[first + 2].toLong()
        val frac = m.groupValues[first + 3].padEnd(3, '0').take(3).toLong()
        return ((h * 60 + min) * 60 + s) * 1000 + frac
    }

    private fun parseAss(t: String): List<Cue> {
        var startIdx = 1
        var endIdx = 2
        var textIdx = 9
        var fields = 10
        var inEvents = false
        val out = ArrayList<Cue>()
        for (raw in t.split('\n')) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                val names = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                fields = names.size
                startIdx = names.indexOf("start").takeIf { it >= 0 } ?: startIdx
                endIdx = names.indexOf("end").takeIf { it >= 0 } ?: endIdx
                textIdx = names.indexOf("text").takeIf { it >= 0 } ?: (fields - 1)
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val parts = line.substringAfter(':').split(",", limit = fields)
            if (parts.size < fields) continue
            val start = assMillis(parts[startIdx].trim()) ?: continue
            val end = assMillis(parts[endIdx].trim()) ?: continue
            val text = cleanText(parts[textIdx].replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " "))
            if (text.isNotEmpty() && end > start) out.add(Cue(start, end, text))
        }
        out.sortBy { it.start }
        return out
    }

    private fun assMillis(s: String): Long? {
        val m = ASS_TIME.find(s) ?: return null
        val h = m.groupValues[1].toLong()
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        val frac = m.groupValues[4]
        val ms = when (frac.length) {
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return ((h * 60 + min) * 60 + sec) * 1000 + ms
    }

    private fun cleanText(s: String): String {
        val noTags = s.replace(ASS_TAG, "").replace(HTML_TAG, "")
        return noTags.lines().joinToString("\n") { it.trim() }.replace(BLANK_LINES, "\n").trim()
    }

    /** Index of the first cue that has not ended at [positionMs] (cues.size when all have). */
    fun firstAtOrAfter(cues: List<Cue>, positionMs: Long): Int {
        var lo = 0
        var hi = cues.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].end <= positionMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Index of the cue to show at [positionMs], or -1. Cues must be sorted by start. */
    fun cueAt(cues: List<Cue>, positionMs: Long): Int {
        var lo = 0
        var hi = cues.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].start <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return -1
        // An earlier long cue may still be on screen when the latest one already ended.
        var i = found
        while (i >= 0 && i > found - 3) {
            if (positionMs < cues[i].end) return i
            i--
        }
        return -1
    }
}

/** A subtitle file found online. */
data class SubtitleHit(
    val id: String,
    val language: String,
    val release: String,
    val title: String,
    val year: Int?,
    val downloads: Int
)

/**
 * Podnapisi.NET, a free subtitle library that needs no account or key.
 * Tests point it at another server with: adb shell setprop debug.ratv.subs http://host:port
 */
object Podnapisi {
    private const val DEFAULT_BASE = "https://www.podnapisi.net"

    private fun base(): String {
        val override = systemProperty("debug.ratv.subs")
        return (if (override != null && override.startsWith("http")) override else DEFAULT_BASE).trimEnd('/')
    }

    /** Subtitles for a film ([season] and [episode] null) or an episode, in [language] (ISO 639-1). */
    fun search(keyword: String, language: String, year: Int?, season: Int?, episode: Int?): List<SubtitleHit> {
        if (keyword.isBlank()) return emptyList()
        val q = StringBuilder("/subtitles/search/advanced?keywords=")
            .append(enc(keyword))
            .append("&language=").append(enc(language))
        if (season != null && episode != null) {
            q.append("&seasons=").append(season).append("&episodes=").append(episode)
            q.append("&movie_type=tv-series&movie_type=mini-series")
        } else {
            q.append("&movie_type=movie")
        }
        if (year != null && year > 1900) q.append("&year=").append(year)
        val json = String(get(q.toString(), "application/json"), Charsets.UTF_8)
        return parseSearch(json, season != null && episode != null)
    }

    fun parseSearch(json: String, episodes: Boolean = false): List<SubtitleHit> {
        val root = JSONObject(json)
        val data = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<SubtitleHit>()
        val seen = HashSet<String>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(id)) continue
            val movie = o.optJSONObject("movie")
            if (episodes && movie?.optString("type") == "movie") continue
            val releases = ArrayList<String>()
            listOf("releases", "custom_releases").forEach { key ->
                val arr = o.optJSONArray(key)
                if (arr != null) for (j in 0 until arr.length()) arr.optString(j).takeIf { it.isNotBlank() }?.let { releases.add(it) }
            }
            val title = movie?.optString("title").orEmpty()
            val year = movie?.optInt("year", 0)?.takeIf { it > 0 }
            val downloads = o.optJSONObject("stats")?.optInt("downloads", 0) ?: 0
            out.add(
                SubtitleHit(
                    id = id,
                    language = o.optString("language").ifBlank { "en" },
                    release = releases.firstOrNull() ?: listOfNotNull(title.ifBlank { null }, year?.toString()).joinToString(" "),
                    title = title,
                    year = year,
                    downloads = downloads
                )
            )
        }
        return out.sortedByDescending { it.downloads }
    }

    /** The subtitle file itself (unzipped). */
    fun download(hit: SubtitleHit): ByteArray {
        val zip = get("/subtitles/${enc(hit.id)}/download?container=zip", "*/*")
        return unzipFirstSubtitle(zip) ?: zip
    }

    fun unzipFirstSubtitle(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var fallback: ByteArray? = null
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory) continue
                val data = zin.readBytes()
                val name = entry.name.lowercase()
                if (name.endsWith(".srt") || name.endsWith(".vtt") || name.endsWith(".ass") || name.endsWith(".ssa")) return data
                if (fallback == null) fallback = data
            }
            return fallback
        }
    }

    private fun get(path: String, accept: String): ByteArray {
        val base = base()
        return try {
            fetch(base + path, accept)
        } catch (e: SSLException) {
            // Some boxes cannot complete the site's secure connection; the plain address works.
            if (base.startsWith("https://")) fetch("http://" + base.removePrefix("https://") + path, accept) else throw e
        }
    }

    private fun fetch(url: String, accept: String): ByteArray {
        val request = Request.Builder().url(url).header("Accept", accept).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code)
            return response.body?.bytes() ?: throw IOException("Empty response")
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun systemProperty(name: String): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, name) as? String)?.takeIf { it.isNotBlank() }
    } catch (e: Throwable) {
        null
    }
}
