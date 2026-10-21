package com.ratv.app.data

import java.text.Normalizer

// Name matching used to find the same movie, series or match under other names:
// another quality of a film in a 4K section, or another channel showing the same game.

// ------------------------------------------------------------ text folding

/**
 * Lower case with Latin digits, Arabic spelling variants folded (أ/إ/آ → ا, ة → ه, ى → ي),
 * and accents and diacritics removed, so names written differently compare equal.
 */
fun foldText(s: String): String {
    val sb = StringBuilder(s.length)
    for (raw in Links.latinize(s)) {
        val c = raw.lowercaseChar()
        when (c) {
            'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
            'ة' -> sb.append('ه')
            'ى' -> sb.append('ي')
            'ؤ' -> sb.append('و')
            'ئ' -> sb.append('ي')
            'ـ' -> Unit
            else -> sb.append(c)
        }
    }
    val decomposed = Normalizer.normalize(sb, Normalizer.Form.NFD)
    val out = StringBuilder(decomposed.length)
    for (c in decomposed) {
        val code = c.code
        if (code in 0x0300..0x036F || code in 0x064B..0x065F || code == 0x0670) continue
        out.append(c)
    }
    return out.toString()
}

private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

/** The words of a name after folding. */
fun words(s: String): List<String> = foldText(s).split(NON_WORD).filter { it.isNotEmpty() }

private val YEAR_WORD = Regex("""19[5-9]\d|20[0-4]\d""")

// ------------------------------------------------------------ movies and series

private val QUALITY_WORDS = setOf(
    "4k", "uhd", "fhd", "hd", "sd", "hq", "lq", "2160p", "1080p", "720p", "480p", "2160", "1080", "720",
    "hdr", "hdr10", "dv", "dolby", "vision", "hevc", "x264", "x265", "h264", "h265", "10bit",
    "bluray", "brrip", "bdrip", "webdl", "web", "webrip", "remux", "cam", "hdcam", "ts", "hdts", "tc",
    "multi", "sub", "subs", "subbed", "dub", "dubbed", "vip", "raw", "imax",
    "مترجم", "مدبلج", "مدبلجه"
).mapTo(HashSet()) { foldText(it) }

private val PREFIX_TAGS = setOf(
    "ar", "en", "fr", "tr", "de", "es", "it", "in", "hi", "ko", "kr", "jp", "cn", "ru", "pt", "fa", "ur", "as", "us", "uk",
    "arabic", "english", "french", "turkish", "indian", "hindi", "korean", "asian", "anime", "kids", "new", "top",
    "netflix", "nf", "osn", "shahid", "amazon", "disney", "apple", "hbo",
    "عربي", "اجنبي", "تركي", "هندي", "كوري", "اسيوي", "افلام", "فيلم", "مسلسل", "مسلسلات", "جديد"
).mapTo(HashSet()) { foldText(it) } + QUALITY_WORDS

private fun isTagGroup(part: String): Boolean {
    val w = words(part)
    return w.isNotEmpty() && w.all { it in PREFIX_TAGS || it.length <= 2 }
}

/** Drops provider tags in front of a title: "AR-SUBS: Devour" → "Devour", "|AR| 4K: صقر" → "صقر". */
fun stripTags(name: String): String {
    var s = name.trim()
    var guard = 0
    var changed = true
    while (changed && guard++ < 5 && s.isNotEmpty()) {
        changed = false
        if (s[0] == '[' || s[0] == '(') {
            val close = s.indexOf(if (s[0] == '[') ']' else ')')
            if (close in 1..24 && isTagGroup(s.substring(1, close))) {
                s = s.substring(close + 1).trim()
                changed = true
                continue
            }
        }
        val cut = listOf(s.indexOf(':'), s.indexOf('|'), s.indexOf(" - ")).filter { it in 0..24 }.minOrNull() ?: continue
        val left = s.substring(0, cut)
        if (left.isBlank() || isTagGroup(left)) {
            val skip = if (s.startsWith(" - ", cut)) 3 else 1
            s = s.substring(cut + skip).trim()
            changed = true
        }
    }
    return s
}

/** A title fit for searching: provider tags, quality words and the year removed. */
fun cleanTitle(name: String): String {
    val s = stripTags(name)
    val kept = s.split(' ').filter { part ->
        if (part.isBlank()) return@filter false
        val w = words(part)
        !(w.isNotEmpty() && w.all { it in QUALITY_WORDS || YEAR_WORD.matches(it) })
    }
    val out = kept.joinToString(" ").trim().trim('-', ':', '|', '(', ')', '[', ']', '.').trim()
    return out.ifEmpty { s }
}

/** One key for every copy of the same film or series, whatever its quality, tags or spelling. */
fun vodKey(name: String): String {
    val w = words(stripTags(name)).filter { it !in QUALITY_WORDS && !YEAR_WORD.matches(it) }
    val key = w.joinToString("")
    return key.ifEmpty { foldText(name).filter { it.isLetterOrDigit() } }
}

/** Quality of a film or series from its name, or from its section ("4K افلام فائقة الوضوح"). */
fun vodQuality(name: String, categoryName: String?): String? {
    qualityOf(name)?.let { return it }
    if (categoryName.isNullOrBlank()) return null
    qualityOf(categoryName)?.let { return it }
    val f = foldText(categoryName)
    return if (f.contains("فائقه الوضوح") || f.contains("فائق الوضوح") || f.contains("فائقه الدقه")) "4K" else null
}

// ------------------------------------------------------------ sport

private val SPORT_HINTS = listOf(
    "sport", "bein", "ssc", "alkass", "al kass", "dazn", "espn", "eurosport", "supersport", "football", "soccer",
    "nba", "nfl", "ufc", "wwe", "laliga", "premier league", "رياضه", "رياضي", "الكاس", "مباريات", "كره القدم"
).map { foldText(it) }

/** True for a sports channel or section name. */
fun looksSports(text: String): Boolean {
    val f = foldText(text)
    return SPORT_HINTS.any { f.contains(it) }
}

private val EVENT_STOP = setOf(
    "vs", "live", "the", "and", "club", "league", "cup", "match", "game", "round", "week", "matchday", "day",
    "premier", "championship", "liga", "laliga", "serie", "bundesliga", "ligue", "uefa", "fifa", "afc", "caf",
    "champions", "europa", "conference", "saudi", "roshn", "pro", "spl", "epl", "final", "semi", "quarter", "group",
    "stage", "leg", "first", "second", "half", "kick", "off", "full", "time", "coverage", "special", "studio",
    "مباشر", "مباشره", "ضد", "مع", "في", "من", "الي", "على", "نادي", "الدوري", "دوري", "كاس", "بطوله", "البطوله",
    "مباراه", "المباراه", "الجوله", "جوله", "الاسبوع", "نهائي", "النهائي", "الشوط", "الاول", "الثاني", "قبل", "بعد",
    "استوديو", "التحليلي", "تحليل", "نقل", "بث", "hd", "fhd", "4k"
).mapTo(HashSet()) { foldText(it) }

private val TEAM_ALIASES: List<Pair<String, List<String>>> = listOf(
    "realmadrid" to listOf("real madrid", "ريال مدريد", "الريال"),
    "barcelona" to listOf("barcelona", "fc barcelona", "barca", "برشلونه"),
    "atletico" to listOf("atletico madrid", "atletico de madrid", "atletico", "اتلتيكو مدريد", "اتلتيكو"),
    "sevilla" to listOf("sevilla", "اشبيليه"),
    "liverpool" to listOf("liverpool", "ليفربول"),
    "mancity" to listOf("manchester city", "man city", "مانشستر سيتي", "السيتي"),
    "manutd" to listOf("manchester united", "man united", "man utd", "مانشستر يونايتد", "اليونايتد"),
    "arsenal" to listOf("arsenal", "ارسنال", "الارسنال"),
    "chelsea" to listOf("chelsea", "تشيلسي"),
    "tottenham" to listOf("tottenham hotspur", "tottenham", "spurs", "توتنهام"),
    "newcastle" to listOf("newcastle united", "newcastle", "نيوكاسل"),
    "astonvilla" to listOf("aston villa", "استون فيلا"),
    "bayern" to listOf("bayern munich", "bayern munchen", "bayern", "بايرن ميونخ", "بايرن ميونيخ", "البايرن"),
    "dortmund" to listOf("borussia dortmund", "dortmund", "بوروسيا دورتموند", "دورتموند"),
    "leverkusen" to listOf("bayer leverkusen", "leverkusen", "باير ليفركوزن", "ليفركوزن"),
    "psg" to listOf("paris saint germain", "paris sg", "psg", "باريس سان جيرمان", "باريس"),
    "marseille" to listOf("olympique de marseille", "marseille", "مارسيليا"),
    "juventus" to listOf("juventus", "juve", "يوفنتوس"),
    "inter" to listOf("inter milan", "internazionale", "inter", "انتر ميلان", "الانتر", "انتر"),
    "milan" to listOf("ac milan", "milan", "ميلان"),
    "napoli" to listOf("napoli", "نابولي"),
    "roma" to listOf("as roma", "roma", "روما"),
    "benfica" to listOf("benfica", "بنفيكا"),
    "porto" to listOf("porto", "بورتو"),
    "ajax" to listOf("ajax", "اياكس"),
    "galatasaray" to listOf("galatasaray", "غلطه سراي", "جالطه سراي"),
    "fenerbahce" to listOf("fenerbahce", "فنربخشه"),
    "hilal" to listOf("al hilal", "alhilal", "hilal", "الهلال"),
    "nassr" to listOf("al nassr", "alnassr", "nassr", "النصر"),
    "ittihad" to listOf("al ittihad", "alittihad", "ittihad", "الاتحاد"),
    "ahli" to listOf("al ahli", "alahli", "al ahly", "ahly", "ahli", "الاهلي"),
    "shabab" to listOf("al shabab", "الشباب"),
    "ettifaq" to listOf("al ettifaq", "ettifaq", "الاتفاق"),
    "qadsiah" to listOf("al qadsiah", "qadsiah", "القادسيه"),
    "taawoun" to listOf("al taawoun", "taawoun", "التعاون"),
    "zamalek" to listOf("zamalek", "الزمالك"),
    "pyramids" to listOf("pyramids", "بيراميدز"),
    "wydad" to listOf("wydad", "الوداد"),
    "raja" to listOf("raja casablanca", "raja", "الرجاء"),
    "esperance" to listOf("esperance", "الترجي"),
    "alsadd" to listOf("al sadd", "alsadd", "السد"),
    "alain" to listOf("al ain", "العين"),
    "saudiarabia" to listOf("saudi arabia", "السعوديه", "المنتخب السعودي"),
    "egypt" to listOf("egypt", "مصر"),
    "morocco" to listOf("morocco", "المغرب"),
    "qatar" to listOf("qatar", "قطر"),
    "uae" to listOf("uae", "united arab emirates", "الامارات"),
    "jordan" to listOf("jordan", "الاردن"),
    "iraq" to listOf("iraq", "العراق"),
    "algeria" to listOf("algeria", "الجزائر"),
    "tunisia" to listOf("tunisia", "تونس"),
    "argentina" to listOf("argentina", "الارجنتين"),
    "brazil" to listOf("brazil", "البرازيل"),
    "france" to listOf("france", "فرنسا"),
    "spain" to listOf("spain", "اسبانيا"),
    "england" to listOf("england", "انجلترا"),
    "germany" to listOf("germany", "المانيا"),
    "portugal" to listOf("portugal", "البرتغال"),
    "italy" to listOf("italy", "ايطاليا"),
    "netherlands" to listOf("netherlands", "holland", "هولندا")
)

private val ALIAS_PHRASES: List<Pair<List<String>, String>> by lazy {
    TEAM_ALIASES.flatMap { (canonical, variants) -> variants.map { words(it) to canonical } }
        .filter { it.first.isNotEmpty() }
        .sortedByDescending { it.first.size }
}

/** The meaningful words of a programme title, with team names in Arabic or English made the same. */
fun eventTokens(title: String): Set<String> {
    // "والنصر" is "and Al Nassr": drop the joined "and".
    val w = words(title).map { if (it.startsWith("وال") && it.length > 4) it.substring(1) else it }
    val out = LinkedHashSet<String>()
    var i = 0
    while (i < w.size) {
        val hit = ALIAS_PHRASES.firstOrNull { (phrase, _) ->
            i + phrase.size <= w.size && phrase.indices.all { w[i + it] == phrase[it] }
        }
        if (hit != null) {
            out.add(hit.second)
            i += hit.first.size
            continue
        }
        val t = w[i]
        if (t.length >= 3 && t !in EVENT_STOP && !t.all { it.isDigit() }) out.add(t)
        i++
    }
    return out
}

/** True when two programme titles name the same game (at least two shared names, such as both teams). */
fun sameEvent(a: Set<String>, b: Set<String>): Boolean {
    if (a.size < 2 || b.size < 2) return false
    val common = a.count { it in b }
    return common >= 2 && common * 2 >= minOf(a.size, b.size)
}
