package com.ratv.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

// The scoreboard over sports channels: which match a channel is showing, and its live score.
//
// The matches are the schedule of جدول RA كورة (kora/data/fetch.py rebuilds it every 3 hours from
// ESPN and Yallakora, with Arabic team names and small logos). The live state of a match comes from
// ESPN's public scoreboard, and for the Arab leagues ESPN lacks, from Yallakora's match centre.
// Tests point them at the mock server: setprop debug.ratv.kora / debug.ratv.espn / debug.ratv.yk URL.

/** A team of the schedule. */
class ScoreTeam(val id: String, val en: String, val ar: String?, val short: String?, val logo: String) {
    /** Its name on the board: Arabic for Arabic, otherwise English (the short form of a long name). */
    fun name(arabic: Boolean): String {
        if (arabic && !ar.isNullOrBlank()) return ar
        val s = short?.takeIf { it.isNotBlank() }
        val e = en.ifBlank { ar.orEmpty() }
        return if (s != null && e.length > 16) s else e
    }

    /** The words that name it in a programme title, in English or Arabic. */
    val tokens: Set<String> by lazy { softTokens(teamTokens(en, short.orEmpty(), ar.orEmpty())) }
}

/** A competition of the schedule. */
class ScoreLeague(val id: String, val ar: String, val short: String, val en: String) {
    val tokens: Set<String> by lazy { softTokens(eventTokens("$ar $short $en")) }
}

/** One match of the schedule; [time] is the kick-off (ms). */
class ScoreMatch(
    val id: String,
    val league: String,
    val time: Long,
    val tbd: Boolean,
    val home: String,
    val away: String,
    val state: String,
    val status: String,
    /** The TV channels that show it, as Kooora names them ("beIN Sports Mena 1", "ALKASS Five"). */
    val tv: List<String> = emptyList(),
    val venue: String? = null
) {
    /** ESPN's matches have its live scores; the others (ids starting with "y") are Yallakora's. */
    val fromEspn: Boolean get() = !id.startsWith("y")
}

/** A match of another competition on the Arab world's channels: for the guide only, it has no live score. */
class GuideMatch(val time: Long, val home: String, val away: String, val competition: String, val tv: List<String>)

class ScoreSchedule(
    val updated: Long,
    val leagues: List<ScoreLeague>,
    val teams: Map<String, ScoreTeam>,
    val matches: List<ScoreMatch>,
    val guide: List<GuideMatch> = emptyList()
) {
    private val leagueById = leagues.associateBy { it.id }
    private val order = leagues.withIndex().associate { it.value.id to it.index }
    private val byId = matches.associateBy { it.id }

    fun league(id: String): ScoreLeague = leagueById[id] ?: ScoreLeague(id, id, id, id)
    fun team(id: String): ScoreTeam = teams[id] ?: ScoreTeam(id, "?", null, null, "")
    fun match(id: String): ScoreMatch? = byId[id]
    fun leagueOrder(id: String): Int = order[id] ?: Int.MAX_VALUE

    /**
     * The matches a viewer may be watching at [now]: from 20 minutes before kick-off to 3 hours
     * after (extra time and penalties included), the Gulf's competitions first.
     */
    fun around(now: Long): List<ScoreMatch> = matches
        .filter {
            !it.tbd && it.state != "post" && now >= it.time - 20 * MIN && now <= it.time + 180 * MIN && !it.status.isCalledOff()
        }
        .sortedWith(compareBy<ScoreMatch> { leagueOrder(it.league) }.thenBy { it.time })

    companion object {
        const val MIN = 60_000L
    }
}

/** True for a match that will not be played (or not finished) today. */
fun String.isCalledOff(): Boolean = contains("POSTPONED") || contains("CANCELED") || contains("CANCELLED") ||
    contains("ABANDONED") || contains("FORFEIT")

/**
 * The live state of a match. [clockSec] is the game time in seconds as the source counts it, and
 * [minute] the minute it shows (45+2 counts as 47), null when it gives none.
 */
class ScoreLive(
    val state: String,
    val status: String,
    val period: Int,
    val clockSec: Double?,
    val minute: Int?,
    val homeScore: Int?,
    val awayScore: Int?,
    val homePens: Int?,
    val awayPens: Int?
) {
    val isBreak: Boolean get() = state == "in" && (status.contains("HALFTIME") || status.contains("END_OF") || status.contains("BREAK"))
    val isShootout: Boolean get() = status.contains("SHOOTOUT") || (state == "in" && period >= 5)
    val isOver: Boolean get() = state == "post"
}

object Scores {
    private const val TAG = "RATVScores"
    private const val DATA_URL = "https://github.com/rajavo/RATV/releases/download/kora-data/fixtures.json"
    private const val ESPN = "https://site.api.espn.com"
    private const val YALLAKORA = "https://www.yallakora.com"
    private const val FRESH_MS = 90L * 60_000L
    private const val RETRY_MS = 10L * 60_000L
    private const val MIN = 60_000L

    private fun dataUrl() = debugProperty("debug.ratv.kora") ?: DATA_URL
    private fun espn() = debugProperty("debug.ratv.espn") ?: ESPN
    private fun yallakora() = debugProperty("debug.ratv.yk") ?: YALLAKORA

    @Volatile private var schedule: ScoreSchedule? = null
    @Volatile private var fetchedAt = 0L
    @Volatile private var failedAt = 0L
    private val lock = Mutex()

    private fun cacheFile(context: Context) = File(context.filesDir, "score_fixtures.json")

    /** The schedule: kept on the device, downloaded again when an hour and a half old. Null when there is none yet. */
    suspend fun schedule(context: Context): ScoreSchedule? = withContext(Dispatchers.IO) {
        lock.withLock {
            val now = System.currentTimeMillis()
            if (schedule == null) {
                val f = cacheFile(context)
                if (f.exists()) {
                    try {
                        schedule = parse(f.readText())
                        fetchedAt = f.lastModified()
                    } catch (e: Exception) {
                        Log.w(TAG, "saved schedule unreadable: $e")
                    }
                }
            }
            val saved = schedule
            if (saved != null && now - fetchedAt in 0 until FRESH_MS) return@withLock saved
            if (now - failedAt in 0 until RETRY_MS) return@withLock saved
            try {
                val text = Http.getString(dataUrl())
                val parsed = parse(text)
                val f = cacheFile(context)
                val part = File(f.parentFile, f.name + ".part")
                part.writeText(text)
                if (!part.renameTo(f)) f.writeText(text)
                schedule = parsed
                fetchedAt = now
                failedAt = 0L
                Log.i(TAG, "schedule: ${parsed.matches.size} matches")
                parsed
            } catch (e: Exception) {
                Log.w(TAG, "schedule download failed: $e")
                failedAt = now
                saved
            }
        }
    }

    /** The live state of [m] now, or null when its source could not be reached. */
    suspend fun live(m: ScoreMatch): ScoreLive? = withContext(Dispatchers.IO) {
        try {
            if (m.fromEspn) espnLive(m) else yallakoraLive(m)
        } catch (e: Exception) {
            Log.w(TAG, "live ${m.league} ${m.id}: $e")
            null
        }
    }

    private fun espnLive(m: ScoreMatch): ScoreLive? {
        // ESPN files every match under its day in New York.
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        val url = "${espn()}/apis/site/v2/sports/soccer/${m.league}/scoreboard?dates=${day.format(Date(m.time))}"
        return parseEspn(Http.getString(url), m.id)
    }

    private fun yallakoraLive(m: ScoreMatch): ScoreLive? {
        // The match centre lists a day's matches by Cairo's date.
        val cairo = java.util.Calendar.getInstance(TimeZone.getTimeZone("Africa/Cairo")).apply { timeInMillis = m.time }
        val url = "${yallakora()}/match-center/?date=${cairo.get(java.util.Calendar.MONTH) + 1}/" +
            "${cairo.get(java.util.Calendar.DAY_OF_MONTH)}/${cairo.get(java.util.Calendar.YEAR)}"
        return parseYallakora(get(url, BROWSER), m.id.removePrefix("y"))
    }

    /** Yallakora answers browsers (as kora/data/fetch.py reads it). */
    private const val BROWSER = "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36"

    private fun get(url: String, agent: String): String {
        val request = Request.Builder().url(url).header("User-Agent", agent).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code)
            return response.body?.string() ?: ""
        }
    }

    // ------------------------------------------------------------ which match a channel shows

    /**
     * The match a programme of the guide shows: both of its teams named in the title, kicking off
     * between half an hour before the programme starts and its end. The best of several: the one
     * whose competition the title names too, then the nearest kick-off.
     */
    fun matchFor(s: ScoreSchedule, title: String, start: Long, end: Long): ScoreMatch? {
        val words = softTokens(eventTokens(title))
        if (words.size < 2) return null
        var best: ScoreMatch? = null
        var bestScore = Long.MIN_VALUE
        for (m in s.matches) {
            if (m.time < start - 30 * MIN || m.time > end || m.status.isCalledOff()) continue
            val home = s.teams[m.home] ?: continue
            val away = s.teams[m.away] ?: continue
            if (words.none { it in home.tokens } || words.none { it in away.tokens }) continue
            val leagueNamed = words.any { it in s.league(m.league).tokens }
            val score = (if (leagueNamed) 1_000_000L else 0L) - abs(m.time - start) / MIN
            if (score > bestScore) {
                best = m
                bestScore = score
            }
        }
        return best
    }

    /** The match of a link from جدول RA كورة: by its id, else by its teams and kick-off. */
    fun matchFor(s: ScoreSchedule, req: MatchRequest): ScoreMatch? {
        req.id.takeIf { it.isNotBlank() }?.let { id -> s.match(id)?.let { return it } }
        val home = softTokens(teamTokens(req.home, req.homeAr))
        val away = softTokens(teamTokens(req.away, req.awayAr))
        if (home.isEmpty() || away.isEmpty()) return null
        return s.matches.firstOrNull { m ->
            abs(m.time - req.start) <= 30 * MIN &&
                s.team(m.home).tokens.any { it in home } && s.team(m.away).tokens.any { it in away }
        }
    }

    // ------------------------------------------------------------ parsing

    /** Reads fixtures.json, as written by kora/data/fetch.py. */
    fun parse(text: String): ScoreSchedule {
        val o = JSONObject(text)
        val leagues = ArrayList<ScoreLeague>()
        o.optJSONArray("leagues")?.forEachObject { l ->
            leagues.add(ScoreLeague(l.getString("id"), l.optString("ar"), l.optString("short"), l.optString("en")))
        }
        val teams = HashMap<String, ScoreTeam>()
        o.optJSONObject("teams")?.let { t ->
            for (id in t.keys()) {
                val x = t.optJSONObject(id) ?: continue
                teams[id] = ScoreTeam(id, x.optString("en"), x.optStringOrNull("ar"), x.optStringOrNull("s"), x.optString("logo"))
            }
        }
        val matches = ArrayList<ScoreMatch>()
        o.optJSONArray("matches")?.forEachObject { m ->
            matches.add(
                ScoreMatch(
                    id = m.getString("id"),
                    league = m.getString("lg"),
                    time = m.getLong("t") * 1000L,
                    tbd = m.optInt("tbd") == 1,
                    home = m.getString("h"),
                    away = m.getString("a"),
                    state = m.optString("st", "pre"),
                    status = m.optString("sn"),
                    tv = m.optJSONArray("tv")?.strings().orEmpty(),
                    venue = m.optStringOrNull("v")
                )
            )
        }
        matches.sortBy { it.time }
        val guide = ArrayList<GuideMatch>()
        o.optJSONArray("guide")?.forEachObject { g ->
            val tv = g.optJSONArray("tv")?.strings().orEmpty()
            val t = g.optLong("t")
            if (tv.isNotEmpty() && t > 0L) {
                guide.add(GuideMatch(t * 1000L, g.optString("a"), g.optString("b"), g.optString("c"), tv))
            }
        }
        return ScoreSchedule(o.optLong("updated") * 1000L, leagues, teams, matches, guide)
    }

    /** The state of match [id] in one answer of ESPN's scoreboard, null when it is not there. */
    fun parseEspn(text: String, id: String): ScoreLive? {
        val events = JSONObject(text).optJSONArray("events") ?: return null
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            if (ev.optString("id") != id) continue
            val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: return null
            val status = comp.optJSONObject("status") ?: ev.optJSONObject("status") ?: return null
            val type = status.optJSONObject("type") ?: return null
            var home: JSONObject? = null
            var away: JSONObject? = null
            comp.optJSONArray("competitors")?.forEachObject { c ->
                if (c.optString("homeAway") == "home") home = c else if (c.optString("homeAway") == "away") away = c
            }
            val state = type.optString("state", "pre")
            val display = status.optStringOrNull("displayClock")
            return ScoreLive(
                state = state,
                status = type.optString("name").removePrefix("STATUS_"),
                period = status.optInt("period"),
                clockSec = if (status.has("clock") && !status.isNull("clock")) status.optDouble("clock").takeIf { !it.isNaN() } else null,
                minute = display?.let { minuteOf(it) },
                homeScore = if (state == "pre") null else home?.scoreOrNull(),
                awayScore = if (state == "pre") null else away?.scoreOrNull(),
                homePens = home?.optIntOrNull("shootoutScore"),
                awayPens = away?.optIntOrNull("shootoutScore")
            )
        }
        return null
    }

    private val MINUTE = Regex("""^\s*(\d{1,3})(?:\s*'?\s*\+\s*(\d{1,2}))?""")

    /** "67'" → 67, "45'+2'" → 47; null for anything else ("HT", "FT"). */
    fun minuteOf(display: String): Int? {
        val m = MINUTE.find(display) ?: return null
        val base = m.groupValues[1].toIntOrNull() ?: return null
        return base + (m.groupValues[2].toIntOrNull() ?: 0)
    }

    /**
     * The state of match [mid] on one day page of Yallakora's match centre (the same page
     * kora/data/fetch.py reads), null when it is not there.
     */
    fun parseYallakora(html: String, mid: String): ScoreLive? {
        val at = html.indexOf("liveScoreMatchId=\"$mid\"")
        if (at < 0) return null
        val nextCard = html.indexOf("liveScoreMatchId=\"", at + 20).let { if (it < 0) html.length else it }
        val card = html.substring(at, minOf(nextCard, at + 12_000))
        val kind = Regex("""class="item ([^"]*?) ?liItem"""").find(card.take(300))?.groupValues?.get(1).orEmpty()
        val status = Regex("""matchStatus">\s*<span>\s*([^<]*?)\s*</span>""").find(card)?.groupValues?.get(1)?.trim().orEmpty()
        val scores = Regex("""<span class="score">\s*([^<]*?)\s*</span>""").findAll(card).map { it.groupValues[1].trim() }.toList()
        val state = when {
            "finish" in kind || "end" in kind || "انته" in status -> "post"
            "now" in kind || "live" in kind || "جار" in status || "مباشر" in status || "الشوط" in status || "استراحة" in status -> "in"
            else -> "pre"
        }
        val name = when {
            "تأجل" in status || "مؤجل" in status -> "POSTPONED"
            state == "post" -> "FULL_TIME"
            "استراحة" in status || "بين الشوطين" in status -> "HALFTIME"
            "الثاني" in status -> "SECOND_HALF"
            "الأول" in status || "الاول" in status -> "FIRST_HALF"
            state == "in" -> "IN_PROGRESS"
            else -> "SCHEDULED"
        }
        val hs = scores.getOrNull(0)?.toIntOrNull()
        val aws = scores.getOrNull(1)?.toIntOrNull()
        return ScoreLive(
            state = state,
            status = name,
            period = when (name) { "SECOND_HALF" -> 2; "FIRST_HALF" -> 1; else -> 0 },
            clockSec = null,
            // The page gives no minute: the board shows the match as live, without a clock.
            minute = null,
            homeScore = if (state == "pre") null else hs,
            awayScore = if (state == "pre") null else aws,
            homePens = null,
            awayPens = null
        )
    }

    private fun JSONObject.scoreOrNull(): Int? {
        val v = opt("score") ?: return null
        val s = if (v is JSONObject) v.opt("displayValue") ?: v.opt("value") else v
        return s?.toString()?.toDoubleOrNull()?.toInt()
    }

    private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(block)
    }

    private fun JSONArray.strings(): List<String> =
        (0 until length()).mapNotNull { i -> optString(i).trim().takeIf { it.isNotEmpty() && it != "null" } }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) opt(key)?.toString()?.toDoubleOrNull()?.toInt() else null
}

/** Arabic words compared without their article ("الفيصلي" and "فيصلي" are the same team). */
fun softTokens(tokens: Set<String>): Set<String> = tokens.mapTo(LinkedHashSet()) { t ->
    if (t.length >= 5 && t.startsWith("ال")) t.substring(2) else t
}
