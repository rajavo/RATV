package com.ratv.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small persistent store built on SharedPreferences.
 *
 * Several subscriptions ("users") can be saved side by side. Favourites, watch history and
 * recently watched channels belong to one user each (keys end with "@<playlist id>"), so
 * switching users never deletes anything. Settings such as the parental filter are shared.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ratv", Context.MODE_PRIVATE)

    init {
        migrate()
    }

    // ------------------------------------------------------------- users

    /** Every saved subscription, in the order they were added. */
    val playlists: List<Playlist>
        get() = try {
            val arr = JSONArray(sp.getString(KEY_PLAYLISTS, "[]") ?: "[]")
            List(arr.length()) { arr.optJSONObject(it)?.let(::playlistOf) }.filterNotNull()
        } catch (e: Exception) {
            emptyList()
        }

    private var currentId: String?
        get() = sp.getString(KEY_CURRENT, null)
        set(value) {
            sp.edit().apply { if (value == null) remove(KEY_CURRENT) else putString(KEY_CURRENT, value) }.apply()
        }

    /** The user in use, if any. */
    fun loadPlaylist(): Playlist? {
        val id = currentId ?: return null
        return playlists.firstOrNull { it.id == id }
    }

    /** Saves [p] (replacing the same subscription if it is already there) and makes it the current user; null only signs out. */
    fun savePlaylist(p: Playlist?) {
        if (p == null) {
            currentId = null
            return
        }
        val list = playlists.toMutableList()
        val at = list.indexOfFirst { it.id == p.id }
        if (at >= 0) list[at] = p else list.add(p)
        writePlaylists(list)
        currentId = p.id
    }

    /** Deletes one saved user with its favourites and history. */
    fun removePlaylist(id: String) {
        writePlaylists(playlists.filterNot { it.id == id })
        val e = sp.edit()
        USER_KEYS.forEach { e.remove("$it@$id") }
        e.apply()
        if (currentId == id) currentId = null
    }

    private fun writePlaylists(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { arr.put(jsonOf(it)) }
        sp.edit().putString(KEY_PLAYLISTS, arr.toString()).apply()
    }

    private fun jsonOf(p: Playlist) = JSONObject()
        .put("type", p.type.name)
        .put("name", p.name)
        .put("server", p.server)
        .put("username", p.username)
        .put("password", p.password)
        .put("m3uUrl", p.m3uUrl)
        .put("epgUrl", p.epgUrl)

    private fun playlistOf(o: JSONObject) = Playlist(
        type = if (o.optString("type") == PlaylistType.M3U.name) PlaylistType.M3U else PlaylistType.XTREAM,
        name = o.optString("name"),
        server = o.optString("server"),
        username = o.optString("username"),
        password = o.optString("password"),
        m3uUrl = o.optString("m3uUrl"),
        epgUrl = o.optString("epgUrl")
    )

    /** Earlier versions kept one playlist and unscoped user data: move them to the first user. */
    private fun migrate() {
        if (sp.contains(KEY_PLAYLISTS)) return
        val e = sp.edit()
        val old = sp.getString(KEY_OLD_PLAYLIST, null)?.let { raw -> runCatching { playlistOf(JSONObject(raw)) }.getOrNull() }
        if (old != null) {
            e.putString(KEY_PLAYLISTS, JSONArray().put(jsonOf(old)).toString())
            e.putString(KEY_CURRENT, old.id)
            val all = sp.all
            USER_KEYS.forEach { key ->
                when (val v = all[key]) {
                    is String -> e.putString("$key@${old.id}", v)
                    is Set<*> -> e.putStringSet("$key@${old.id}", v.filterIsInstance<String>().toHashSet())
                }
                e.remove(key)
            }
        } else {
            e.putString(KEY_PLAYLISTS, "[]")
        }
        e.remove(KEY_OLD_PLAYLIST).apply()
    }

    /** Key of a piece of user data for the current user. */
    private fun k(base: String) = "$base@${currentId ?: "none"}"

    // ------------------------------------------------------- user data

    var favorites: Set<String>
        get() = sp.getStringSet(k("favorites"), null)?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(k("favorites"), HashSet(value)).apply()

    var watched: Set<String>
        get() = sp.getStringSet(k("watched"), null)?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(k("watched"), HashSet(value)).apply()

    fun loadHistory(): List<WatchEntry> {
        val raw = sp.getString(k("history"), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<WatchEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    WatchEntry(
                        key = o.optString("key"),
                        kind = o.optString("kind"),
                        itemId = o.optString("itemId"),
                        seriesId = o.optString("seriesId").takeIf { it.isNotBlank() },
                        title = o.optString("title"),
                        subtitle = o.optString("subtitle").takeIf { it.isNotBlank() },
                        image = o.optString("image").takeIf { it.isNotBlank() },
                        url = o.optString("url"),
                        position = o.optLong("position"),
                        duration = o.optLong("duration"),
                        updatedAt = o.optLong("updatedAt")
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveHistory(list: List<WatchEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("key", e.key)
                    .put("kind", e.kind)
                    .put("itemId", e.itemId)
                    .put("seriesId", e.seriesId ?: "")
                    .put("title", e.title)
                    .put("subtitle", e.subtitle ?: "")
                    .put("image", e.image ?: "")
                    .put("url", e.url)
                    .put("position", e.position)
                    .put("duration", e.duration)
                    .put("updatedAt", e.updatedAt)
            )
        }
        sp.edit().putString(k("history"), arr.toString()).apply()
    }

    var lastChannelId: String?
        get() = sp.getString(k("lastChannel"), null)
        set(value) = sp.edit().putString(k("lastChannel"), value).apply()

    /** Channels played lately, newest first. */
    var recentChannels: List<String>
        get() = try {
            val arr = JSONArray(sp.getString(k("recentChannels"), "[]") ?: "[]")
            List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
        set(value) = sp.edit().putString(k("recentChannels"), JSONArray(value).toString()).apply()

    /** "Continue watching", watched marks and recently watched channels of the current user; favourites stay. */
    fun clearWatchHistory() {
        val e: SharedPreferences.Editor = sp.edit()
        listOf("history", "watched", "lastChannel", "recentChannels").forEach { e.remove(k(it)) }
        e.apply()
    }

    // ---------------------------------------------------- shared settings

    var bufferMode: String
        get() = sp.getString("buffer", "large") ?: "large"
        set(value) = sp.edit().putString("buffer", value).apply()

    /** Always take the highest quality an adaptive stream offers. */
    var bestQuality: Boolean
        get() = sp.getBoolean("bestQuality", true)
        set(value) = sp.edit().putBoolean("bestQuality", value).apply()

    var autoplayLast: Boolean
        get() = sp.getBoolean("autoplayLast", false)
        set(value) = sp.edit().putBoolean("autoplayLast", value).apply()

    var epgAuto: Boolean
        get() = sp.getBoolean("epgAuto", true)
        set(value) = sp.edit().putBoolean("epgAuto", value).apply()

    /** Hides adult sections, channels, films and series for every user. */
    var hideAdult: Boolean
        get() = sp.getBoolean("hideAdult", false)
        set(value) = sp.edit().putBoolean("hideAdult", value).apply()

    var pin: String?
        get() = sp.getString("pin", null)
        set(value) = sp.edit().putString("pin", value).apply()

    /** Play the selected channel in a small window on the TV channel list. */
    var livePreview: Boolean
        get() = sp.getBoolean("livePreview", true)
        set(value) = sp.edit().putBoolean("livePreview", value).apply()

    /** Language subtitles are shown in (ISO 639-1). */
    var subtitleLang: String
        get() = sp.getString("subLang", "ar") ?: "ar"
        set(value) = sp.edit().putString("subLang", value).apply()

    /** 0 small, 1 medium, 2 large, 3 extra large. */
    var subtitleSize: Int
        get() = sp.getInt("subSize", 1)
        set(value) = sp.edit().putInt("subSize", value).apply()

    /** Turn subtitles on by themselves when a film or episode starts. */
    var subtitleAuto: Boolean
        get() = sp.getBoolean("subAuto", false)
        set(value) = sp.edit().putBoolean("subAuto", value).apply()

    var resizeMode: Int
        get() = sp.getInt("resizeMode", 0)
        set(value) = sp.edit().putInt("resizeMode", value).apply()

    var movieSort: String
        get() = sp.getString("movieSort", "added") ?: "added"
        set(value) = sp.edit().putString("movieSort", value).apply()

    var seriesSort: String
        get() = sp.getString("seriesSort", "added") ?: "added"
        set(value) = sp.edit().putString("seriesSort", value).apply()

    private companion object {
        const val KEY_OLD_PLAYLIST = "playlist"
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_CURRENT = "currentPlaylist"
        val USER_KEYS = listOf("favorites", "watched", "history", "lastChannel", "recentChannels")
    }
}
