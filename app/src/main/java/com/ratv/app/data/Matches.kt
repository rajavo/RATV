package com.ratv.app.data

import kotlin.math.abs

/** One game in the schedule, with every channel of the subscription that shows it. */
data class MatchEvent(
    val title: String,
    val start: Long,
    val end: Long,
    val channels: List<Channel>
) {
    fun isLive(now: Long) = now >= start && now < end
}

/** "Real Madrid v Barcelona", "ريال مدريد × برشلونة", "Flamengo x Palmeiras", "الهلال ضد النصر". */
private val GAME_SEPARATOR = Regex("""\s(?:vs\.?|v|x|ضد|-|–)\s|×""", RegexOption.IGNORE_CASE)
private val NOT_A_GAME = listOf(
    "highlights", "ملخص", "استوديو", "studio", "preview", "review", "magazine", "news", "أخبار",
    "replay", "إعادة", "معاد", "countdown", "talk", "برنامج"
).map { foldText(it) }

/** True for a programme that is a game between two sides, not a studio show or a summary. */
fun looksLikeGame(title: String): Boolean {
    if (!GAME_SEPARATOR.containsMatchIn(title)) return false
    val f = foldText(title)
    return NOT_A_GAME.none { f.contains(it) }
}

/**
 * Games on the given channels between [from] and [to], found in the programme guide. The same
 * game on several channels (also when one names it in Arabic and another in English) is one entry.
 */
fun buildMatches(channels: List<Channel>, epg: Map<String, List<Programme>>, from: Long, to: Long): List<MatchEvent> {
    class Group(val tokens: Set<String>, val start: Long, var end: Long) {
        val chans = LinkedHashMap<String, Channel>()
        val titles = ArrayList<String>()
    }
    val groups = ArrayList<Group>()
    val byToken = HashMap<String, MutableList<Group>>()
    val tokenCache = HashMap<String, Set<String>>()
    for (ch in channels) {
        for (p in epg.forChannel(ch)) {
            if (p.end <= from || p.start >= to || !looksLikeGame(p.title)) continue
            val t = tokenCache.getOrPut(p.title) { eventTokens(p.title) }
            if (t.size < 2) continue
            val found = t.asSequence()
                .flatMap { byToken[it].orEmpty().asSequence() }
                .firstOrNull { abs(it.start - p.start) <= 45 * 60_000L && sameEvent(it.tokens, t) }
            val g = found ?: Group(t, p.start, p.end).also { n ->
                groups.add(n)
                t.forEach { byToken.getOrPut(it) { ArrayList() }.add(n) }
            }
            g.chans.putIfAbsent(ch.id, ch)
            g.titles.add(p.title)
            g.end = maxOf(g.end, p.end)
        }
    }
    return groups
        .map { g ->
            MatchEvent(
                title = g.titles.firstOrNull { t -> t.any { it in '\u0600'..'\u06FF' } }
                    ?: g.titles.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key,
                start = g.start,
                end = g.end,
                channels = g.chans.values.sortedByDescending { qualityRank(it.quality) }
            )
        }
        .sortedWith(compareBy<MatchEvent> { it.start }.thenByDescending { it.channels.size })
}

private val REGION_PREFIX = Regex("""^\s*[\[(]?\s*([A-Za-z]{2,5}(?:-[A-Za-z]{2})?)\s*[|:\])]""")
private val REGIONS = mapOf(
    "ar" to "🌍 القنوات العربية", "arab" to "🌍 القنوات العربية", "arb" to "🌍 القنوات العربية", "mena" to "🌍 القنوات العربية",
    "sa" to "🇸🇦 السعودية", "ksa" to "🇸🇦 السعودية", "ae" to "🇦🇪 الإمارات", "uae" to "🇦🇪 الإمارات", "qa" to "🇶🇦 قطر",
    "kw" to "🇰🇼 الكويت", "bh" to "🇧🇭 البحرين", "om" to "🇴🇲 عُمان", "iq" to "🇮🇶 العراق", "jo" to "🇯🇴 الأردن",
    "eg" to "🇪🇬 مصر", "ma" to "🇲🇦 المغرب", "dz" to "🇩🇿 الجزائر", "tn" to "🇹🇳 تونس", "ly" to "🇱🇾 ليبيا",
    "uk" to "🇬🇧 بريطانيا", "gb" to "🇬🇧 بريطانيا", "ie" to "🇮🇪 أيرلندا", "us" to "🇺🇸 أمريكا", "usa" to "🇺🇸 أمريكا",
    "ca" to "🇨🇦 كندا", "fr" to "🇫🇷 فرنسا", "es" to "🇪🇸 إسبانيا", "de" to "🇩🇪 ألمانيا", "ger" to "🇩🇪 ألمانيا",
    "it" to "🇮🇹 إيطاليا", "pt" to "🇵🇹 البرتغال", "nl" to "🇳🇱 هولندا", "be" to "🇧🇪 بلجيكا", "ch" to "🇨🇭 سويسرا",
    "at" to "🇦🇹 النمسا", "tr" to "🇹🇷 تركيا", "pl" to "🇵🇱 بولندا", "ru" to "🇷🇺 روسيا", "gr" to "🇬🇷 اليونان",
    "ro" to "🇷🇴 رومانيا", "se" to "🇸🇪 السويد", "no" to "🇳🇴 النرويج", "dk" to "🇩🇰 الدنمارك", "fi" to "🇫🇮 فنلندا",
    "al" to "🇦🇱 ألبانيا", "exyu" to "البلقان", "ex-yu" to "البلقان", "in" to "🇮🇳 الهند", "pk" to "🇵🇰 باكستان",
    "ir" to "🇮🇷 إيران", "af" to "🌍 أفريقيا", "afr" to "🌍 أفريقيا", "br" to "🇧🇷 البرازيل", "mx" to "🇲🇽 المكسيك",
    "latam" to "🌎 أمريكا اللاتينية", "lat" to "🌎 أمريكا اللاتينية", "ar-la" to "🌎 أمريكا اللاتينية",
    "au" to "🇦🇺 أستراليا", "nz" to "🇳🇿 نيوزيلندا"
)

/** Country or region of a channel from the "UK:", "AR|", "[FR]" prefix of its name or section. */
fun regionOf(channelName: String, categoryName: String?): String {
    for (text in listOfNotNull(channelName, categoryName)) {
        val code = REGION_PREFIX.find(text)?.groupValues?.get(1)?.lowercase() ?: continue
        REGIONS[code]?.let { return it }
    }
    val all = foldText(channelName + " " + categoryName.orEmpty())
    return when {
        all.contains("عربي") || channelName.any { it in '\u0600'..'\u06FF' } -> "🌍 القنوات العربية"
        else -> "📺 قنوات أخرى"
    }
}
