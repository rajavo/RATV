package com.ratv.kora

/**
 * The channels that show a match, as viewers in the Gulf know them. The schedule lists every
 * channel in the world that carries it (Kooora's names: "beIN Sports Mena 1", "ALKASS Five",
 * "Viaplay Denmark"…); only the Arab world's TV channels are kept, under their usual names
 * ("beIN SPORTS 1", "الكأس 5", "ثمانية"), and apps and foreign channels are left out.
 */
object Channels {
    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10
    )

    /** Apps and services rather than channels. */
    private val NOT_CHANNELS = Regex("""(?i)connect|shoof|shahid|\btod\b|stc tv|starz|\bplay\b|watch|\bgo\b|\bapp\b|شاشا|المنصة|شاهد""")

    /** beIN's channels of other countries. */
    private val FOREIGN_BEIN = Regex("""(?i)france|\bfr\b|turk|\btr\b|usa|\bus\b|asia|australia|canada|espa|spain|thailand|malaysia|indonesia""")

    private val ARABIC = Regex("""[؀-ۿ]""")

    /** The Arab world's channels among [tv], by their usual names, each once. */
    fun arab(tv: List<String>): List<String> = tv.mapNotNull { name(it) }.distinct()

    /** The usual name of one channel, or null when it is not an Arab TV channel. */
    fun name(raw: String): String? {
        val s = raw.trim().replace(Regex("""\s+"""), " ")
        if (s.isEmpty() || NOT_CHANNELS.containsMatchIn(s)) return null
        val low = s.lowercase()
        val words = low.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
        val number = words.firstNotNullOfOrNull { w -> w.toIntOrNull()?.takeIf { it in 1..30 } ?: NUMBER_WORDS[w] }
        val n = number?.let { " $it" }.orEmpty()
        fun has(vararg w: String) = w.any { it in words }
        return when {
            "bein" in low -> when {
                FOREIGN_BEIN.containsMatchIn(s) -> null
                has("max") -> "beIN SPORTS MAX$n"
                has("xtra", "extra") -> "beIN SPORTS XTRA$n"
                has("eng", "english") -> "beIN SPORTS$n English"
                has("news") -> "beIN SPORTS NEWS"
                number == null && ("4k" in low || "uhd" in low) -> "beIN SPORTS 4K"
                number != null -> "beIN SPORTS$n"
                else -> null
            }
            "alkass" in low || "al kass" in low -> if (has("extra", "xtra")) "الكأس إكسترا$n" else "الكأس$n"
            "thmanyah" in low || "thamanyah" in low -> "ثمانية$n"
            Regex("""\bssc\b""").containsMatchIn(low) -> if (has("extra", "xtra")) "SSC إكسترا$n" else "SSC$n"
            "abu dhabi sport" in low || "ad sport" in low -> if (has("premium")) "أبوظبي الرياضية بريميوم$n" else "أبوظبي الرياضية$n"
            "dubai sport" in low || "dubai racing" in low -> "دبي الرياضية$n"
            "sharjah sport" in low -> "الشارقة الرياضية$n"
            "on time sport" in low -> "أون تايم سبورتس$n"
            "iraqiya sport" in low -> "العراقية الرياضية$n"
            "ktv sport" in low -> "الكويت الرياضية$n"
            "oman sport" in low -> "عمان الرياضية$n"
            "bahrain sport" in low -> "البحرين الرياضية$n"
            "ksa sport" in low -> "السعودية الرياضية$n"
            ARABIC.containsMatchIn(s) -> s
            else -> null
        }
    }
}
