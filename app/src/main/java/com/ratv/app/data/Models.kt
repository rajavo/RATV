package com.ratv.app.data

enum class PlaylistType { XTREAM, M3U }

/** A user subscription: either Xtream Codes credentials or an M3U source. */
data class Playlist(
    val type: PlaylistType,
    val name: String,
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = ""
) {
    /** Stable id used to namespace cache files. */
    val id: String
        get() = (type.name + "|" + server + "|" + username + "|" + m3uUrl).hashCode().toUInt().toString(16)
}

data class Category(val id: String, val name: String)

data class Channel(
    val id: String,
    val num: Int,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val epgId: String?,
    val url: String
) {
    val quality: String? get() = qualityOf(name)
}

data class Movie(
    val id: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val rating: String?,
    val added: Long,
    val url: String
) {
    val year: String? get() = yearOf(name)
}

data class SeriesItem(
    val id: String,
    val name: String,
    val cover: String?,
    val categoryId: String,
    val rating: String?,
    val plot: String?,
    val year: String?,
    val added: Long
)

data class Episode(
    val id: String,
    val season: Int,
    val num: Int,
    val title: String,
    val url: String,
    val durationSecs: Int,
    val plot: String?,
    val image: String?
)

data class MovieInfo(
    val plot: String?,
    val director: String?,
    val cast: List<String>,
    val genre: String?,
    val duration: String?,
    val year: String?,
    val rating: String?,
    val backdrop: String?,
    val cover: String?,
    val trailer: String?,
    val originalName: String? = null
)

data class SeriesInfo(
    val plot: String?,
    val cast: List<String>,
    val genre: String?,
    val year: String?,
    val rating: String?,
    val backdrop: String?,
    val cover: String?,
    val episodes: Map<Int, List<Episode>>
) {
    val seasons: List<Int> get() = episodes.keys.sorted()
}

data class Programme(val start: Long, val end: Long, val title: String, val desc: String?)

data class AccountInfo(
    val status: String?,
    val expDate: Long?,
    val activeCons: Int?,
    val maxCons: Int?
)

/** A resume point for a movie or an episode ("تابع المشاهدة"). */
data class WatchEntry(
    val key: String,
    val kind: String,
    val itemId: String,
    val seriesId: String?,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val url: String,
    val position: Long,
    val duration: Long,
    val updatedAt: Long
) {
    val progress: Float
        get() = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
}

sealed interface LoadState {
    data object Idle : LoadState
    data class Loading(val message: String) : LoadState
    data object Ready : LoadState
    data class Error(val message: String) : LoadState
}

object Keys {
    fun live(id: String) = "live:$id"
    fun movie(id: String) = "movie:$id"
    fun series(id: String) = "series:$id"
    fun episode(id: String) = "ep:$id"
}

private val QUALITY_REGEX = Regex("""(?<![A-Za-z0-9])(4K|UHD|FHD|HD|SD|HEVC)(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
private val YEAR_REGEX = Regex("""(?<![0-9])(19[5-9][0-9]|20[0-4][0-9])(?![0-9])""")
private val ADULT_WORDS = listOf("adult", "xxx", "18+", "+18", "porn", "للكبار", "كبار فقط")

fun qualityOf(name: String): String? {
    val m = QUALITY_REGEX.find(name) ?: return null
    return when (val q = m.value.uppercase()) {
        "UHD" -> "4K"
        else -> q
    }
}

/** Higher is better: used to list the best version of a channel first. */
fun qualityRank(quality: String?): Int = when (quality) {
    "4K" -> 4
    "FHD" -> 3
    "HD", "HEVC" -> 2
    "SD" -> 1
    else -> 0
}

private val KEY_PUNCTUATION = Regex("""[\[\](){}|:;,_\-+*/\\#@!~"'.«»]""")
private val KEY_CODECS = Regex("""h\.?26[45]""")
private val KEY_SPACES = Regex("""\s+""")
private val KEY_NOISE = setOf(
    "4k", "uhd", "fhd", "hd", "sd", "hq", "lq", "hevc", "1080", "1080p", "720", "720p", "2160", "2160p",
    "50fps", "60fps", "fps", "backup", "vip", "raw", "plus", "multi", "audio", "live",
    "ar", "en", "fr", "tr", "de", "es", "it", "arabic", "english", "french", "turkish",
    "عربي", "العربي", "انجليزي", "إنجليزي", "الانجليزي", "فرنسي", "احتياطي", "بديل", "مباشر"
)

/**
 * The same channel under different qualities or languages ("AR: beIN SPORTS 1 FHD",
 * "beIN Sports 1 4K", "beIN SPORTS 1 EN") shares one key.
 */
fun channelKey(name: String): String {
    val cleaned = name.lowercase()
        .replace(KEY_CODECS, " ")
        .replace(KEY_PUNCTUATION, " ")
    val tokens = cleaned.split(KEY_SPACES)
        .filter { it.isNotBlank() && it !in KEY_NOISE }
        .map { if (it == "sports") "sport" else it }
    return if (tokens.isEmpty()) name.lowercase().trim() else tokens.joinToString(" ")
}

fun yearOf(name: String): String? = YEAR_REGEX.find(name)?.value

fun isAdultName(name: String): Boolean {
    val n = name.lowercase()
    return ADULT_WORDS.any { n.contains(it) }
}

fun Map<String, List<Programme>>.forChannel(ch: Channel): List<Programme> {
    val id = ch.epgId?.lowercase()?.takeIf { it.isNotBlank() } ?: return emptyList()
    return this[id] ?: emptyList()
}

fun List<Programme>.current(now: Long): Programme? = firstOrNull { now >= it.start && now < it.end }

fun List<Programme>.next(now: Long): Programme? = firstOrNull { it.start > now }

fun Programme.progress(now: Long): Float {
    val total = (end - start).toFloat()
    if (total <= 0f) return 0f
    return ((now - start).toFloat() / total).coerceIn(0f, 1f)
}
