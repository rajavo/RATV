package com.ratv.app.data

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/**
 * A match to watch, sent by the schedule app (جدول RA كورة):
 * ratv://match?home=Al%20Hilal&away=Al%20Nassr&homeAr=الهلال&awayAr=النصر&start=<ms>&league=دوري%20روشن
 */
data class MatchRequest(
    val home: String,
    val away: String,
    val homeAr: String,
    val awayAr: String,
    val start: Long,
    val league: String
) {
    val homeName: String get() = homeAr.ifBlank { home }
    val awayName: String get() = awayAr.ifBlank { away }
    val title: String get() = "$homeName × $awayName"

    companion object {
        fun from(uri: Uri?): MatchRequest? {
            if (uri == null || uri.scheme != "ratv" || uri.host != "match") return null
            fun q(name: String) = runCatching { uri.getQueryParameter(name) }.getOrNull().orEmpty().trim()
            val start = q("start").toLongOrNull() ?: return null
            val r = MatchRequest(q("home"), q("away"), q("homeAr"), q("awayAr"), start, q("league"))
            if (r.homeName.isBlank() || r.awayName.isBlank()) return null
            return r
        }
    }
}

object MatchLinks {
    /** A match from the schedule app, waiting until the app is ready (signed in, lists loaded). */
    val pending = MutableStateFlow<MatchRequest?>(null)

    /** The match the "where is it on" screen shows. */
    val current = MutableStateFlow<MatchRequest?>(null)
}

/** Words of club names too common to tell two clubs apart ("Leeds United", "West Ham United"). */
private val GENERIC_TEAM_WORDS = listOf(
    "united", "city", "town", "county", "rovers", "wanderers", "albion", "athletic", "sporting", "club",
    "real", "racing", "deportivo", "olympique", "calcio", "sport", "sports"
).mapTo(HashSet()) { foldText(it) }

/** The words that name a team, in English or Arabic (the same words [eventTokens] finds in a programme title). */
fun teamTokens(vararg names: String): Set<String> {
    val all = names.filter { it.isNotBlank() }.flatMapTo(LinkedHashSet()) { eventTokens(it) }
    val specific = all.filterTo(LinkedHashSet()) { it !in GENERIC_TEAM_WORDS }
    return specific.ifEmpty { all }
}

private const val WINDOW_MS = 150L * 60_000L
private val ARABIC = '؀'..'ۿ'

/**
 * Every channel whose programme guide names both teams in a programme starting within 2.5 hours
 * of the kick-off, best quality first. Null when the guide has no such programme.
 */
fun findMatch(req: MatchRequest, channels: List<Channel>, epg: Map<String, List<Programme>>): MatchEvent? {
    val home = teamTokens(req.home, req.homeAr)
    val away = teamTokens(req.away, req.awayAr)
    if (home.isEmpty() || away.isEmpty()) return null
    val found = LinkedHashMap<String, Channel>()
    val tokens = HashMap<String, Set<String>>()
    var start = Long.MAX_VALUE
    var end = Long.MIN_VALUE
    var title: String? = null
    for (ch in channels) {
        for (p in epg.forChannel(ch)) {
            if (abs(p.start - req.start) > WINDOW_MS) continue
            val t = tokens.getOrPut(p.title) { eventTokens(p.title) }
            if (t.none { it in home } || t.none { it in away }) continue
            found.putIfAbsent(ch.id, ch)
            start = minOf(start, p.start)
            end = maxOf(end, p.end)
            val current = title
            if (current == null || (current.none { it in ARABIC } && p.title.any { it in ARABIC })) title = p.title
        }
    }
    if (found.isEmpty()) return null
    return MatchEvent(
        title = title ?: req.title,
        start = start,
        end = end,
        channels = found.values.sortedByDescending { qualityRank(it.quality) }
    )
}
