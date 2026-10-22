package com.ratv.app.player

import com.ratv.app.data.Channel
import com.ratv.app.data.qualityRank
import com.ratv.app.data.words

/** Language words in channel names ("beIN SPORTS 1 EN", "AR: MBC", "TR | TRT 1"), folded as [words] folds them. */
private val LANGUAGE_WORDS: Map<String, String> = listOf(
    "en" to "en", "eng" to "en", "english" to "en", "انجليزي" to "en", "الانجليزي" to "en",
    "fr" to "fr", "french" to "fr", "فرنسي" to "fr",
    "tr" to "tr", "turkish" to "tr", "تركي" to "tr",
    "es" to "es", "spanish" to "es", "de" to "de", "german" to "de", "it" to "it", "italian" to "it",
    "pt" to "pt", "fa" to "fa", "persian" to "fa", "farsi" to "fa", "ku" to "ku", "kurdish" to "ku",
    "ur" to "ur", "urdu" to "ur", "hi" to "hi", "hindi" to "hi"
).associate { (word, lang) -> words(word).joinToString("") to lang }

/** The language a channel speaks, from its name: "ar" unless the name says otherwise. */
fun languageOf(name: String): String = words(name).firstNotNullOfOrNull { LANGUAGE_WORDS[it] } ?: "ar"

/**
 * The version to move to when [current] keeps stalling: among the same channel's other versions
 * ([versions], as Repository.siblings gives them), one in the same language that was not tried
 * yet. A lighter quality first (the closest below), then another link of the same quality (often
 * from another server); never a heavier one.
 */
fun nextVersion(current: Channel, versions: List<Channel>, tried: Set<String>): Channel? {
    val lang = languageOf(current.name)
    val rank = qualityRank(current.quality)
    val options = versions.filter { it.id != current.id && it.id !in tried && languageOf(it.name) == lang }
    val lighter = options.filter { qualityRank(it.quality) < rank }.sortedByDescending { qualityRank(it.quality) }
    val same = options.filter { qualityRank(it.quality) == rank }
    return (lighter + same).firstOrNull()
}
