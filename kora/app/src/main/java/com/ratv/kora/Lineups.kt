package com.ratv.kora

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A player of a match sheet. [line] is 0 goalkeeper, 1 defence, 2 midfield, 3 attack, 4 bench. */
class Player(
    val name: String,
    val number: String?,
    val line: Int,
    val starter: Boolean,
    val subbedIn: Boolean,
    val subbedOut: Boolean,
    /** For a substitute who came on: the player he replaced. */
    val replaced: String?,
    /** ESPN's position code (G, CD-L, RB, DM, AM, CF…), for the place on the pitch. */
    val pos: String? = null
)

class Side(val formation: String?, val players: List<Player>) {
    val starters: List<Player> get() = players.filter { it.starter }
    val bench: List<Player> get() = players.filter { !it.starter }
}

/** The two teams' sheets, as ESPN publishes them about an hour before kick-off; null sides before that. */
class Lineups(val home: Side?, val away: Side?) {
    val announced: Boolean get() = home != null || away != null
}

/** A player who misses the match. [reason] is in Arabic; [doubtful] when he may still play. */
class Absent(val name: String, val reason: String, val doubtful: Boolean)

object LineupParser {
    /** Reads the "rosters" of ESPN's match summary; [names] puts the players' names in Arabic where known. */
    fun parse(text: String, names: Map<String, String> = emptyMap()): Lineups {
        var home: Side? = null
        var away: Side? = null
        JSONObject(text).optJSONArray("rosters")?.let { rosters ->
            for (i in 0 until rosters.length()) {
                val r = rosters.optJSONObject(i) ?: continue
                val side = side(r, names) ?: continue
                when (r.optString("homeAway")) {
                    "home" -> home = side
                    "away" -> away = side
                }
            }
        }
        return Lineups(home, away)
    }

    private fun side(r: JSONObject, names: Map<String, String>): Side? {
        val roster = r.optJSONArray("roster") ?: return null
        val players = ArrayList<Pair<Int, Player>>()
        for (i in 0 until roster.length()) {
            val p = roster.optJSONObject(i) ?: continue
            val a = p.optJSONObject("athlete") ?: continue
            val name = a.str("displayName") ?: a.str("shortName") ?: continue
            val starter = p.optBoolean("starter")
            val pos = p.optJSONObject("position")?.str("abbreviation")
            players.add(
                (p.str("formationPlace")?.toIntOrNull() ?: 99) to Player(
                    name = names[name] ?: name,
                    number = p.str("jersey"),
                    line = if (starter) line(pos) else BENCH,
                    starter = starter,
                    subbedIn = p.optBoolean("subbedIn"),
                    subbedOut = p.optBoolean("subbedOut"),
                    replaced = p.optJSONObject("subbedInFor")?.optJSONObject("athlete")?.str("displayName")?.let { names[it] ?: it },
                    pos = pos
                )
            )
        }
        if (players.none { it.second.starter }) return null
        // Starters in the formation's order (goalkeeper first), then the bench, those who came on first.
        val sorted = players.sortedWith(compareBy({ it.second.line }, { if (it.second.subbedIn) 0 else 1 }, { it.first }))
        return Side(r.str("formation"), sorted.map { it.second })
    }

    const val BENCH = 4

    /** ESPN's position codes: G, then D/CD-L/LB/RB…, DM/CM/AM/LM…, F/CF/LW/RF/ST. */
    fun line(pos: String?): Int {
        val p = pos?.uppercase()?.substringBefore('-') ?: return 2
        return when {
            p == "G" || p == "GK" -> 0
            p == "D" || p == "CD" || p.endsWith("B") || p == "SW" -> 1
            p == "F" || p == "CF" || p == "ST" || p.endsWith("F") || p.endsWith("W") || p == "S" -> 3
            else -> 2
        }
    }

    /** How far up the pitch a position plays: 0 the goalkeeper … 4 the centre-forward. */
    fun depth(pos: String?): Double {
        val p = pos?.uppercase()?.substringBefore('-') ?: return 2.5
        return when (p) {
            "G", "GK" -> 0.0
            "D", "CD", "CB", "SW", "LB", "RB" -> 1.0
            "LWB", "RWB", "WB" -> 1.5
            "DM", "CDM" -> 2.0
            "AM", "CAM" -> 3.0
            "LW", "RW", "W" -> 3.5
            "LF", "RF" -> 3.6
            "F", "CF", "ST", "S" -> 4.0
            else -> 2.5
        }
    }

    /** Where across the pitch, facing the other goal: -2 the left wing, -1 left of centre, 0 centre, 1, 2 the right wing. */
    fun side(pos: String?): Int {
        val p = pos?.uppercase() ?: return 0
        val base = p.substringBefore('-')
        return when {
            base.length > 1 && base.startsWith("L") -> -2
            base.length > 1 && base.startsWith("R") -> 2
            p.endsWith("-L") -> -1
            p.endsWith("-R") -> 1
            else -> 0
        }
    }

    /**
     * The starters in the formation's rows, from the goalkeeper up ("4-2-3-1" → 1, 4, 2, 3, 1), each row
     * from left to right. When the formation does not fit the sheet, the rows are the lines.
     */
    fun rows(side: Side): List<List<Player>> {
        val sorted = side.starters.sortedBy { depth(it.pos) }
        if (sorted.isEmpty()) return emptyList()
        val digits = side.formation?.split('-')?.map { it.trim().toIntOrNull() ?: 0 }.orEmpty()
        val sizes = if (digits.isNotEmpty() && digits.all { it > 0 } && digits.sum() == sorted.size - 1 && depth(sorted[0].pos) == 0.0) {
            listOf(1) + digits
        } else {
            sorted.groupBy { if (depth(it.pos) == 0.0) 0 else it.line.coerceAtLeast(1) }.toSortedMap().values.map { it.size }
        }
        var i = 0
        return sizes.map { n -> sorted.subList(i, i + n).also { i += n }.sortedBy { side(it.pos) } }
    }

    private fun JSONObject.str(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotEmpty() } else null

    /** players_ar.json: {"names": {"English name": "الاسم بالعربي"}}. */
    fun names(text: String): Map<String, String> {
        val o = JSONObject(text).optJSONObject("names") ?: return emptyMap()
        val out = HashMap<String, String>(o.length() * 2)
        for (k in o.keys()) o.str(k)?.let { out[k] = it }
        return out
    }

    /** Absent players from fixtures.json: [{"n": name, "r": reason, "d": 1 when doubtful}]. */
    fun absents(a: JSONArray?): List<Absent> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val n = o.str("n") ?: return@mapNotNull null
            Absent(n, o.str("r") ?: "غياب", o.optInt("d") == 1)
        }
    }
}

/** Fetches the sheets of one match from ESPN when the viewer opens them; kept a minute so reopening is instant. */
object LineupSource {
    private class Kept(val at: Long, val lineups: Lineups)

    private val kept = HashMap<String, Kept>()

    suspend fun load(m: Match): Lineups = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        synchronized(kept) { kept[m.id] }?.takeIf { now - it.at < 60_000L }?.let { return@withContext it.lineups }
        val url = "${Kora.espnBase()}/apis/site/v2/sports/soccer/${m.league}/summary?event=${m.id}"
        val l = LineupParser.parse(Kora.get(url), Kora.playerNames)
        Log.i("RAKora", "lineups ${m.id}: home ${l.home?.players?.size ?: 0}, away ${l.away?.players?.size ?: 0}")
        synchronized(kept) { kept[m.id] = Kept(now, l) }
        l
    }
}
