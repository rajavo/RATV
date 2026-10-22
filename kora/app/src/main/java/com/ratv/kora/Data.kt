package com.ratv.kora

import org.json.JSONArray
import org.json.JSONObject

/** A competition: a league, a cup or a tournament. */
class League(val id: String, val ar: String, val short: String, val en: String, val logo: String)

class Team(val id: String, val en: String, val ar: String?, val short: String?, val logo: String) {
    /** The Arabic name, else the English one (its short form when the full one is long). */
    val name: String
        get() = ar?.takeIf { it.isNotBlank() } ?: short?.takeIf { en.length > 20 && it.isNotBlank() } ?: en
}

/**
 * One match. [time] is the kick-off in ms (UTC). [state] is "pre", "in" or "post" and [status] the
 * detail (SCHEDULED, HALFTIME, FULL_TIME, POSTPONED…), as ESPN names them.
 */
data class Match(
    val id: String,
    val league: String,
    val time: Long,
    val tbd: Boolean,
    val home: String,
    val away: String,
    val state: String,
    val status: String,
    val homeScore: Int?,
    val awayScore: Int?,
    val homePens: Int?,
    val awayPens: Int?,
    val clock: String?,
    val venue: String?,
    val city: String?,
    val note: String?
) {
    /** Matches from ESPN get live scores; the Arab leagues ESPN lacks come from another source. */
    val fromEspn: Boolean get() = !id.startsWith("y")

    fun withLive(l: Live?): Match = if (l == null) this else copy(
        state = l.state,
        status = l.status,
        homeScore = l.homeScore ?: homeScore,
        awayScore = l.awayScore ?: awayScore,
        homePens = l.homePens ?: homePens,
        awayPens = l.awayPens ?: awayPens,
        clock = l.clock
    )
}

/** The latest state of a match being played, from ESPN's live scoreboard. */
class Live(
    val state: String,
    val status: String,
    val homeScore: Int?,
    val awayScore: Int?,
    val homePens: Int?,
    val awayPens: Int?,
    val clock: String?
)

class Schedule(val updated: Long, val leagues: List<League>, val teams: Map<String, Team>, val matches: List<Match>) {
    private val leagueById = leagues.associateBy { it.id }
    private val order = leagues.withIndex().associate { it.value.id to it.index }

    fun league(id: String): League = leagueById[id] ?: League(id, id, id, id, "")
    fun team(id: String): Team = teams[id] ?: Team(id, "?", null, null, "")
    fun leagueOrder(id: String): Int = order[id] ?: Int.MAX_VALUE
}

/** Reads fixtures.json, as written by kora/data/fetch.py. */
object ScheduleParser {
    fun parse(text: String): Schedule {
        val o = JSONObject(text)
        val leagues = ArrayList<League>()
        o.optJSONArray("leagues")?.forEachObject { l ->
            leagues.add(League(l.getString("id"), l.optString("ar"), l.optString("short"), l.optString("en"), l.optString("logo")))
        }
        val teams = HashMap<String, Team>()
        o.optJSONObject("teams")?.let { t ->
            for (id in t.keys()) {
                val x = t.getJSONObject(id)
                teams[id] = Team(id, x.optString("en"), x.optStringOrNull("ar"), x.optStringOrNull("s"), x.optString("logo"))
            }
        }
        val matches = ArrayList<Match>()
        o.optJSONArray("matches")?.forEachObject { m ->
            matches.add(
                Match(
                    id = m.getString("id"),
                    league = m.getString("lg"),
                    time = m.getLong("t") * 1000L,
                    tbd = m.optInt("tbd") == 1,
                    home = m.getString("h"),
                    away = m.getString("a"),
                    state = m.optString("st", "pre"),
                    status = m.optString("sn"),
                    homeScore = m.optIntOrNull("hs"),
                    awayScore = m.optIntOrNull("as"),
                    homePens = m.optIntOrNull("hp"),
                    awayPens = m.optIntOrNull("ap"),
                    clock = m.optStringOrNull("clk"),
                    venue = m.optStringOrNull("v"),
                    city = m.optStringOrNull("c"),
                    note = m.optStringOrNull("n")
                )
            )
        }
        matches.sortBy { it.time }
        return Schedule(o.optLong("updated") * 1000L, leagues, teams, matches)
    }

    /** Live state of the given matches from one ESPN scoreboard answer. */
    fun parseLive(text: String, wanted: Set<String>): Map<String, Live> {
        val out = HashMap<String, Live>()
        JSONObject(text).optJSONArray("events")?.forEachObject { ev ->
            val id = ev.optString("id")
            if (id !in wanted) return@forEachObject
            val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: return@forEachObject
            val status = comp.optJSONObject("status") ?: ev.optJSONObject("status") ?: return@forEachObject
            val type = status.optJSONObject("type") ?: return@forEachObject
            var home: JSONObject? = null
            var away: JSONObject? = null
            comp.optJSONArray("competitors")?.forEachObject { c ->
                if (c.optString("homeAway") == "home") home = c else if (c.optString("homeAway") == "away") away = c
            }
            val state = type.optString("state", "pre")
            out[id] = Live(
                state = state,
                status = type.optString("name").removePrefix("STATUS_"),
                homeScore = if (state == "pre") null else home?.scoreOrNull(),
                awayScore = if (state == "pre") null else away?.scoreOrNull(),
                homePens = home?.optIntOrNull("shootoutScore"),
                awayPens = away?.optIntOrNull("shootoutScore"),
                clock = if (state == "in") status.optStringOrNull("displayClock") else null
            )
        }
        return out
    }

    private fun JSONObject.scoreOrNull(): Int? {
        val v = opt("score") ?: return null
        val s = if (v is JSONObject) v.opt("displayValue") ?: v.opt("value") else v
        return s?.toString()?.toDoubleOrNull()?.toInt()
    }

    private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(block)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) opt(key)?.toString()?.toDoubleOrNull()?.toInt() else null
}
