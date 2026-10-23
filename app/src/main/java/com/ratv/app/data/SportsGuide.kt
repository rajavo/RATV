package com.ratv.app.data

import com.ratv.app.i18n.tr
import kotlin.math.abs

/**
 * RA TV's own guide of the sports channels: every match of the schedule (and the other matches the
 * Arab channels show) on the channels of the subscription that carry it, from the channels the
 * schedule gives each match (kora/data/fetch.py reads them on Kooora). So a channel whose provider
 * guide is empty, or says only "Premier League", still says "الدوري الإنجليزي: ليفربول × أرسنال",
 * the scoreboard knows its match, and the match lists find it.
 *
 * Its programmes go into the guide map under [KEY] + the channel's id, already put together with
 * the provider's programmes of that channel ([forChannel] reads them first).
 */
object SportsGuide {
    /**
     * The matches being played on [ch]'s brand when the sources name only the brand ("Thmanyah"):
     * more than one means nobody can tell which of them this channel shows.
     */
    fun brandMatches(s: ScoreSchedule, ch: Channel, now: Long): List<ScoreMatch> {
        val k = ChannelKeys.of(ch.name) ?: return emptyList()
        if (k.brand == ChannelKey.GENERIC) return emptyList()
        return s.matches.filter { m ->
            now >= m.time - 10 * MIN && now <= m.time + APART_MS && !m.status.isCalledOff() && m.state != "post" &&
                m.tv.any { n -> ChannelKeys.of(n)?.let { it.number == null && it.family == k.family } == true }
        }
    }

    const val KEY = "ratv.guide."
    private const val MIN = 60_000L
    /** How long a match stays in the guide: kick-off often comes late, and half time and added time run long. */
    private const val MATCH_MS = 140 * MIN

    /** Two matches of one brand this far apart do not overlap on its channels (a match lasts under two hours). */
    private const val APART_MS = 120 * MIN

    /** A match the guide knows, with the channels the schedule says show it. */
    private class Entry(val id: String?, val time: Long, val title: String, val desc: String, val tv: List<String>)

    /**
     * The programmes of the guide for [channels] around [now], put together with [provider]'s own:
     * a programme of the provider that already names a game keeps its place (it knows its channel);
     * a vaguer one ("beIN SPORTS 1", "Premier League") is cut around the match.
     */
    fun build(
        s: ScoreSchedule,
        channels: List<Channel>,
        sports: Set<String>,
        provider: Map<String, List<Programme>>,
        now: Long,
        arabic: Boolean
    ): Map<String, List<Programme>> {
        val from = now - 6 * 60 * MIN
        val to = now + 36 * 60 * MIN
        val entries = ArrayList<Entry>()
        for (m in s.matches) {
            if (m.tv.isEmpty() || m.time < from || m.time > to || m.status.isCalledOff()) continue
            entries.add(Entry(m.id, m.time, titleOf(s, m, arabic), descOf(s, m, arabic), m.tv))
        }
        for (g in s.guide) {
            if (g.time < from || g.time > to) continue
            val sep = if (arabic) " × " else " vs "
            entries.add(Entry(null, g.time, listOf(g.competition, g.home + sep + g.away).filter { it.isNotBlank() }.joinToString(": "),
                tr("من دليل مباريات RA TV"), g.tv))
        }
        if (entries.isEmpty()) return emptyMap()

        // The channels the sources name, by key: with a number, without one (only the brand), other names.
        val numbered = HashMap<ChannelKey, MutableList<Entry>>()
        val brandOnly = HashMap<String, MutableList<Entry>>()
        val generic = ArrayList<Pair<ChannelKey, Entry>>()
        for (e in entries) {
            for (name in e.tv) {
                val k = ChannelKeys.of(name) ?: continue
                when {
                    k.brand == ChannelKey.GENERIC -> generic.add(k to e)
                    k.number != null -> numbered.getOrPut(k) { ArrayList() }.add(e)
                    else -> brandOnly.getOrPut(k.family) { ArrayList() }.add(e)
                }
            }
        }
        val out = HashMap<String, List<Programme>>()
        for (ch in channels) {
            val k = ChannelKeys.of(ch.name) ?: continue
            val found = LinkedHashSet<Entry>()
            if (k.brand == ChannelKey.GENERIC) {
                if (ch.id in sports) generic.forEach { (src, e) -> if (k.shows(src)) found.add(e) }
            } else {
                numbered[k]?.let { found.addAll(it) }
                // A source that names only the brand ("Thmanyah", not which of its channels): while that match is
                // the brand's only one, it is on the brand's channels (the viewer's "ثمانية 3" showed it).
                brandOnly[k.family]?.let { list ->
                    list.filterTo(found) { e -> list.none { o -> o !== e && abs(o.time - e.time) < APART_MS } }
                }
            }
            if (found.isEmpty()) continue
            val ours = ArrayList<Programme>()
            for (e in found.sortedBy { it.time }) {
                val prev = ours.lastOrNull()
                if (prev != null && prev.start == e.time) continue
                // Back to back on one channel: the first ends when the next begins.
                if (prev != null && prev.end > e.time) ours[ours.size - 1] = prev.copy(end = e.time)
                ours.add(Programme(e.time, e.time + MATCH_MS, e.title, e.desc, matchId = e.id))
            }
            val own = ch.epgId?.lowercase()?.trim()?.takeIf { it.isNotEmpty() }?.let { provider[it] }.orEmpty()
            val merged = merge(own, ours)
            if (merged !== own) out[KEY + ch.id] = merged
        }
        return out
    }

    private fun titleOf(s: ScoreSchedule, m: ScoreMatch, arabic: Boolean): String {
        val league = s.league(m.league)
        val name = if (arabic) league.ar else league.en.ifBlank { league.ar }
        val sep = if (arabic) " × " else " vs "
        val game = s.team(m.home).name(arabic) + sep + s.team(m.away).name(arabic)
        return if (name.isBlank()) game else "$name: $game"
    }

    private fun descOf(s: ScoreSchedule, m: ScoreMatch, arabic: Boolean): String =
        listOfNotNull(m.venue, tr("من دليل مباريات RA TV")).joinToString(" · ")

    /** The provider's programmes with ours put in (see [build]); the same list when nothing changes. */
    fun merge(own: List<Programme>, ours: List<Programme>): List<Programme> {
        val keep = ours.filter { g -> own.none { p -> p.start < g.end && g.start < p.end && looksLikeGame(p.title) } }
        if (keep.isEmpty()) return own
        val out = ArrayList<Programme>(own.size + keep.size)
        for (p in own) {
            var pieces = listOf(p)
            for (g in keep) pieces = pieces.flatMap { cut(it, g) }
            out.addAll(pieces)
        }
        out.addAll(keep)
        out.sortBy { it.start }
        return out
    }

    private fun cut(p: Programme, g: Programme): List<Programme> {
        if (p.end <= g.start || p.start >= g.end) return listOf(p)
        val pieces = ArrayList<Programme>(2)
        if (g.start - p.start >= 5 * MIN) pieces.add(p.copy(end = g.start))
        if (p.end - g.end >= 5 * MIN) pieces.add(p.copy(start = g.end))
        return pieces
    }
}
