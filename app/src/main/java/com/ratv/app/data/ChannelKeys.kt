package com.ratv.app.data

/**
 * Which TV channel a name means, whatever is written around it: "AR| beIN SPORTS 1 HD", "beIN Sports
 * Mena 1" and "بي ان سبورت 1" are all beIN 1; "ALKASS Five" and "Al Kass 5 FHD" are Alkass 5. RA TV's
 * own guide of the sports channels (SportsGuide) puts each match on the subscription's channels by it.
 *
 * Known brands compare by [brand], [variant] (max, xtra, english…), [region] (beIN's French or Turkish
 * channels are not the Arab ones) and [number]. Other channels ("Sky Bundesliga 1") compare by their
 * words: the name a source gives must be all in the channel's name, with the same number.
 */
data class ChannelKey(
    val brand: String,
    val variant: String,
    val region: String,
    val number: Int?,
    val words: Set<String> = emptySet()
) {
    /** The brand, variant and region without the number: for sources that name only the brand ("Thmanyah"). */
    val family: String get() = "$brand/$variant/$region"

    /** True when a channel of the subscription called this shows what a source calls [source]. */
    fun shows(source: ChannelKey): Boolean =
        if (brand == GENERIC || source.brand == GENERIC) {
            brand == source.brand && number == source.number && source.words.isNotEmpty() && words.containsAll(source.words)
        } else {
            brand == source.brand && variant == source.variant && region == source.region && number == source.number
        }

    companion object {
        const val GENERIC = "g"
    }
}

object ChannelKeys {
    // First: the brands below are read with these.
    private val QUALITY = Regex("""(?i)(?<![0-9a-z])(?:[48]\s?k|2160p?|1080[pi]?|720p?|576p?|480p?|[hx]\.?26[45]|50fps|60fps)(?![0-9a-z])""")
    private val SPLIT = Regex("""(?<=\p{L})(?=\p{N})|(?<=\p{N})(?=\p{L})""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    private fun words(s: String): List<String> =
        foldText(s).replace(SPLIT, " ").split(NON_WORD).filter { it.isNotEmpty() }

    private class Brand(val id: String, phrases: List<String>, val regional: Boolean = false) {
        val phrases: List<List<String>> = phrases.map { ChannelKeys.words(it) }.filter { it.isNotEmpty() }.sortedByDescending { it.size }
    }

    private val BRANDS = listOf(
        Brand("bein", listOf("bein", "be in", "beinsport", "beinsports", "بي ان", "بين سبورت", "بين سبورتس"), regional = true),
        Brand("ssc", listOf("ssc", "اس اس سي")),
        Brand("thmanyah", listOf("thmanyah", "thamanyah", "thmanya", "thamanya", "ثمانيه")),
        Brand("alkass", listOf("alkass", "al kass", "alkas", "الكاس")),
        Brand("adsports", listOf("ad sports", "ad sport", "adsports", "abu dhabi sports", "abu dhabi sport", "ابوظبي الرياضيه", "ابو ظبي الرياضيه")),
        Brand("dubaisports", listOf("dubai sports", "dubai sport", "دبي الرياضيه")),
        Brand("sharjahsports", listOf("sharjah sports", "sharjah sport", "الشارقه الرياضيه")),
        Brand("ontime", listOf("on time sports", "on time sport", "ontime sports", "اون تايم سبورت", "اون تايم سبورتس")),
        Brand("ktvsport", listOf("ktv sport", "ktv sports", "الكويت الرياضيه")),
        Brand("omansports", listOf("oman sports", "oman sport", "عمان الرياضيه")),
        Brand("bahrainsports", listOf("bahrain sports", "bahrain sport", "البحرين الرياضيه")),
        Brand("iraqiyasports", listOf("al iraqiya sports", "iraqiya sports", "العراقيه الرياضيه")),
        Brand("rabiaasports", listOf("al rabiaa sports", "alrabiaa sports", "الرابعه الرياضيه")),
        Brand("ksasports", listOf("ksa sports", "ksa sport", "السعوديه الرياضيه"))
    )

    private val VARIANTS = mapOf(
        "max" to "max", "xtra" to "xtra", "extra" to "xtra", "اكسترا" to "xtra", "english" to "en", "eng" to "en", "en" to "en",
        "premium" to "premium", "بريميوم" to "premium", "news" to "news", "اخبار" to "news", "connect" to "connect",
        "afc" to "afc", "shoof" to "shoof", "شوف" to "shoof", "go" to "go", "play" to "play"
    )

    private val REGIONS = mapOf(
        "fr" to "fr", "france" to "fr", "french" to "fr", "tr" to "tr", "turkey" to "tr", "turkiye" to "tr", "turk" to "tr",
        "us" to "us", "usa" to "us", "es" to "es", "spain" to "es", "espana" to "es", "au" to "au", "australia" to "au",
        "asia" to "asia", "th" to "asia", "thailand" to "asia", "my" to "asia", "malaysia" to "asia", "id" to "asia",
        "indonesia" to "asia", "hk" to "asia", "ph" to "asia", "ca" to "ca", "canada" to "ca", "nz" to "au"
    )

    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10
    )

    /** Words that never tell one channel from another: picture quality, "sports", "TV", country tags. */
    private val NOISE = setOf(
        "sport", "sports", "sportz", "tv", "channel", "live", "hd", "fhd", "sd", "hq", "lq", "uhd", "hdr", "hevc", "raw",
        "vip", "backup", "multi", "audio", "plus", "the", "mena", "ar", "arab", "arabic", "me", "p", "k", "h", "x",
        "سبورت", "سبورتس", "الرياضيه", "رياضه", "قناه", "مباشر", "عربي", "اتش", "دي"
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** The channel a name means, or null when there is too little in it to tell. */
    fun of(name: String): ChannelKey? {
        cache[name]?.let { return it as? ChannelKey }
        val key = parse(name)
        cache[name] = key ?: Unit
        return key
    }

    private fun parse(name: String): ChannelKey? {
        val uhd = QUALITY.findAll(name).any { it.value.trim().lowercase().replace(" ", "") in setOf("4k", "8k", "2160", "2160p") }
        val w = words(QUALITY.replace(name, " ")).toMutableList()
        if (w.isEmpty()) return null
        var brand: Brand? = null
        var at = -1
        var length = 0
        loop@ for (b in BRANDS) {
            for (phrase in b.phrases) {
                for (i in 0..w.size - phrase.size) {
                    if (phrase.indices.all { w[i + it] == phrase[it] }) {
                        brand = b
                        at = i
                        length = phrase.size
                        break@loop
                    }
                }
            }
        }
        if (brand != null) repeat(length) { w.removeAt(at) }
        var number: Int? = null
        var variant = ""
        var region = ""
        val rest = LinkedHashSet<String>()
        for (word in w) {
            val n = word.toIntOrNull() ?: NUMBER_WORDS[word]
            if (n != null) {
                if (number == null && n in 1..30) number = n
                continue
            }
            val v = VARIANTS[word]
            if (v != null && brand != null) {
                if (variant.isEmpty()) variant = v
                continue
            }
            val r = REGIONS[word]
            if (r != null) {
                if (brand?.regional == true && region.isEmpty()) region = r
                continue
            }
            if (word in NOISE || word.length < 2) continue
            rest.add(word)
        }
        if (brand != null) {
            if (number == null && uhd && variant.isEmpty()) variant = "4k"
            return ChannelKey(brand.id, variant, if (brand.regional) region.ifEmpty { "mena" } else "", number)
        }
        // Another channel: by its Latin words ("sky bundesliga", "arena premium"), which need a number when alone.
        val latin = rest.filterTo(LinkedHashSet()) { word -> word.all { it in 'a'..'z' } && word !in REGIONS }
        if (latin.isEmpty() || (latin.size == 1 && number == null)) return null
        return ChannelKey(ChannelKey.GENERIC, "", "", number, latin)
    }
}
