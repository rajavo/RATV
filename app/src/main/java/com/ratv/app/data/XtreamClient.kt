package com.ratv.app.data

import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

class AuthException(message: String) : IOException(message)

/** Talks to an Xtream Codes compatible panel (player_api.php). */
class XtreamClient(private val p: Playlist) {
    private val base = p.server.trimEnd('/')
    private val user = Uri.encode(p.username)
    private val pass = Uri.encode(p.password)
    private val auth = "username=$user&password=$pass"

    fun apiUrl(action: String? = null, extra: String = ""): String {
        val sb = StringBuilder(base).append("/player_api.php?").append(auth)
        if (action != null) sb.append("&action=").append(action)
        sb.append(extra)
        return sb.toString()
    }

    fun liveUrl(streamId: String) = "$base/live/$user/$pass/$streamId.ts"
    fun movieUrl(streamId: String, ext: String) = "$base/movie/$user/$pass/$streamId.${ext.ifBlank { "mp4" }}"
    fun episodeUrl(episodeId: String, ext: String) = "$base/series/$user/$pass/$episodeId.${ext.ifBlank { "mp4" }}"
    fun xmltvUrl() = "$base/xmltv.php?$auth"

    fun authenticate(): AccountInfo {
        val text = Http.getString(apiUrl())
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("استجابة غير متوقعة من الخادم. تأكد من رابط الخادم.")
        }
        val ui = root.optJSONObject("user_info")
            ?: throw IOException("استجابة غير متوقعة من الخادم. تأكد من رابط الخادم.")
        if (ui.optInt("auth", 1) == 0) {
            throw AuthException("اسم المستخدم أو كلمة المرور غير صحيحة.")
        }
        return AccountInfo(
            status = ui.str("status"),
            expDate = ui.str("exp_date")?.toLongOrNull()?.let { it * 1000L },
            activeCons = ui.str("active_cons")?.toIntOrNull(),
            maxCons = ui.str("max_connections")?.toIntOrNull()
        )
    }

    // ---------------------------------------------------------------- lists

    fun parseCategories(file: File): List<Category> {
        val out = ArrayList<Category>()
        readObjectArray(file) { r ->
            val f = readFields(r, CATEGORY_FIELDS)
            val id = f["category_id"] ?: return@readObjectArray
            out.add(Category(id, f["category_name"]?.trim().orEmpty().ifBlank { "بدون اسم" }))
        }
        return out
    }

    fun parseLive(file: File): List<Channel> {
        val out = ArrayList<Channel>()
        readObjectArray(file) { r ->
            val f = readFields(r, LIVE_FIELDS)
            val id = f["stream_id"] ?: return@readObjectArray
            out.add(
                Channel(
                    id = id,
                    num = f["num"]?.toDoubleOrNull()?.toInt() ?: (out.size + 1),
                    name = f["name"]?.trim().orEmpty(),
                    logo = f["stream_icon"]?.takeIf { it.startsWith("http") },
                    categoryId = f["category_id"] ?: "",
                    epgId = f["epg_channel_id"]?.takeIf { it.isNotBlank() },
                    url = liveUrl(id)
                )
            )
        }
        return out
    }

    fun parseMovies(file: File): List<Movie> {
        val out = ArrayList<Movie>()
        readObjectArray(file) { r ->
            val f = readFields(r, VOD_FIELDS)
            val id = f["stream_id"] ?: return@readObjectArray
            out.add(
                Movie(
                    id = id,
                    name = f["name"]?.trim().orEmpty(),
                    poster = f["stream_icon"]?.takeIf { it.startsWith("http") },
                    categoryId = f["category_id"] ?: "",
                    rating = cleanRating(f["rating"]),
                    added = f["added"]?.toLongOrNull() ?: 0L,
                    url = movieUrl(id, f["container_extension"] ?: "mp4")
                )
            )
        }
        return out
    }

    fun parseSeries(file: File): List<SeriesItem> {
        val out = ArrayList<SeriesItem>()
        readObjectArray(file) { r ->
            val f = readFields(r, SERIES_FIELDS)
            val id = f["series_id"] ?: return@readObjectArray
            out.add(
                SeriesItem(
                    id = id,
                    name = f["name"]?.trim().orEmpty(),
                    cover = f["cover"]?.takeIf { it.startsWith("http") },
                    categoryId = f["category_id"] ?: "",
                    rating = cleanRating(f["rating"]),
                    plot = f["plot"]?.takeIf { it.isNotBlank() },
                    year = (f["releaseDate"] ?: f["release_date"])?.take(4)?.takeIf { it.length == 4 },
                    added = f["last_modified"]?.toLongOrNull() ?: 0L
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------- details

    fun vodInfo(id: String): MovieInfo {
        val root = JSONObject(Http.getString(apiUrl("get_vod_info", "&vod_id=$id")))
        val info = root.optJSONObject("info") ?: JSONObject()
        return MovieInfo(
            plot = info.str("plot") ?: info.str("description"),
            director = info.str("director"),
            cast = splitNames(info.str("cast") ?: info.str("actors")),
            genre = info.str("genre"),
            duration = formatDuration(info.str("duration_secs")?.toIntOrNull(), info.str("duration")),
            year = (info.str("releasedate") ?: info.str("release_date"))?.take(4),
            rating = cleanRating(info.str("rating")),
            backdrop = firstUrl(info.opt("backdrop_path")),
            cover = info.str("movie_image") ?: info.str("cover_big"),
            trailer = info.str("youtube_trailer")
        )
    }

    fun seriesInfo(id: String): SeriesInfo {
        val root = JSONObject(Http.getString(apiUrl("get_series_info", "&series_id=$id")))
        val info = root.optJSONObject("info") ?: JSONObject()
        val bySeason = HashMap<Int, MutableList<Episode>>()

        fun addEpisode(o: JSONObject, seasonHint: Int) {
            val epId = o.str("id") ?: return
            val season = o.str("season")?.toIntOrNull() ?: seasonHint
            val epInfo = o.optJSONObject("info")
            bySeason.getOrPut(season) { ArrayList() }.add(
                Episode(
                    id = epId,
                    season = season,
                    num = o.str("episode_num")?.toIntOrNull() ?: (bySeason[season]?.size ?: 0) + 1,
                    title = o.str("title") ?: "الحلقة",
                    url = episodeUrl(epId, o.str("container_extension") ?: "mp4"),
                    durationSecs = epInfo?.str("duration_secs")?.toIntOrNull() ?: 0,
                    plot = epInfo?.str("plot"),
                    image = epInfo?.str("movie_image")?.takeIf { it.startsWith("http") }
                )
            )
        }

        val episodesObj = root.optJSONObject("episodes")
        if (episodesObj != null) {
            val keys = episodesObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = episodesObj.optJSONArray(key) ?: continue
                val hint = key.toIntOrNull() ?: 1
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addEpisode(it, hint) }
            }
        } else {
            val arr = root.optJSONArray("episodes")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val inner = arr.optJSONArray(i)
                    if (inner != null) {
                        for (j in 0 until inner.length()) inner.optJSONObject(j)?.let { addEpisode(it, i + 1) }
                    } else {
                        arr.optJSONObject(i)?.let { addEpisode(it, 1) }
                    }
                }
            }
        }

        val sorted = bySeason.mapValues { (_, list) -> list.sortedBy { it.num } }
        return SeriesInfo(
            plot = info.str("plot"),
            cast = splitNames(info.str("cast")),
            genre = info.str("genre"),
            year = (info.str("releaseDate") ?: info.str("release_date"))?.take(4),
            rating = cleanRating(info.str("rating")),
            backdrop = firstUrl(info.opt("backdrop_path")),
            cover = info.str("cover"),
            episodes = sorted
        )
    }

    companion object {
        private val CATEGORY_FIELDS = setOf("category_id", "category_name")
        private val LIVE_FIELDS = setOf("num", "name", "stream_id", "stream_icon", "epg_channel_id", "category_id")
        private val VOD_FIELDS = setOf("stream_id", "name", "stream_icon", "rating", "added", "category_id", "container_extension")
        private val SERIES_FIELDS = setOf("series_id", "name", "cover", "rating", "plot", "releaseDate", "release_date", "last_modified", "category_id")

        /** Normalises what the user typed (may be a full get.php link) into a playlist. */
        fun fromUserInput(name: String, serverInput: String, username: String, password: String): Playlist {
            var server = serverInput.trim()
            var u = username.trim()
            var p = password.trim()
            if (!server.startsWith("http://", true) && !server.startsWith("https://", true)) {
                server = "http://$server"
            }
            val uri = Uri.parse(server)
            val qUser = uri.getQueryParameter("username")
            val qPass = uri.getQueryParameter("password")
            if (u.isEmpty() && qUser != null) u = qUser
            if (p.isEmpty() && qPass != null) p = qPass
            val port = if (uri.port > 0) ":" + uri.port else ""
            val scheme = uri.scheme ?: "http"
            val host = uri.host ?: ""
            server = "$scheme://$host$port"
            return Playlist(PlaylistType.XTREAM, name, server = server, username = u, password = p)
        }
    }
}

// -------------------------------------------------------------------- helpers

internal fun JSONObject.str(name: String): String? {
    val v = opt(name) ?: return null
    if (v == JSONObject.NULL) return null
    val s = v.toString().trim()
    return if (s.isEmpty() || s == "null") null else s
}

private fun firstUrl(value: Any?): String? = when (value) {
    is JSONArray -> (0 until value.length()).map { value.optString(it) }.firstOrNull { it.startsWith("http") }
    is String -> value.takeIf { it.startsWith("http") }
    else -> null
}

private fun splitNames(raw: String?): List<String> =
    raw?.split(',', '،')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(12) ?: emptyList()

private fun cleanRating(raw: String?): String? {
    val d = raw?.toDoubleOrNull() ?: return null
    if (d <= 0.0) return null
    return String.format(java.util.Locale.US, "%.1f", d)
}

private fun formatDuration(secs: Int?, text: String?): String? {
    if (secs != null && secs > 0) {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        return if (h > 0) "${h}س ${m}د" else "${m}د"
    }
    return text?.takeIf { it.isNotBlank() }
}

internal inline fun readObjectArray(file: File, onObject: (JsonReader) -> Unit) {
    file.inputStream().buffered(64 * 1024).use { input ->
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.isLenient = true
            if (r.peek() != JsonToken.BEGIN_ARRAY) return
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() == JsonToken.BEGIN_OBJECT) onObject(r) else r.skipValue()
            }
            r.endArray()
        }
    }
}

/** Reads one JSON object, keeping only [wanted] scalar fields as strings. */
internal fun readFields(r: JsonReader, wanted: Set<String>): Map<String, String?> {
    val map = HashMap<String, String?>(16)
    r.beginObject()
    while (r.hasNext()) {
        val name = r.nextName()
        if (name in wanted) map[name] = readScalar(r) else r.skipValue()
    }
    r.endObject()
    return map
}

private fun readScalar(r: JsonReader): String? = when (r.peek()) {
    JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
    JsonToken.BOOLEAN -> r.nextBoolean().toString()
    JsonToken.NULL -> {
        r.nextNull()
        null
    }
    else -> {
        r.skipValue()
        null
    }
}
