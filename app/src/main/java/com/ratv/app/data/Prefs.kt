package com.ratv.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small persistent store built on SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ratv", Context.MODE_PRIVATE)

    fun loadPlaylist(): Playlist? {
        val raw = sp.getString(KEY_PLAYLIST, null) ?: return null
        return try {
            val o = JSONObject(raw)
            Playlist(
                type = if (o.optString("type") == PlaylistType.M3U.name) PlaylistType.M3U else PlaylistType.XTREAM,
                name = o.optString("name"),
                server = o.optString("server"),
                username = o.optString("username"),
                password = o.optString("password"),
                m3uUrl = o.optString("m3uUrl"),
                epgUrl = o.optString("epgUrl")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun savePlaylist(p: Playlist?) {
        if (p == null) {
            sp.edit().remove(KEY_PLAYLIST).apply()
            return
        }
        val o = JSONObject()
            .put("type", p.type.name)
            .put("name", p.name)
            .put("server", p.server)
            .put("username", p.username)
            .put("password", p.password)
            .put("m3uUrl", p.m3uUrl)
            .put("epgUrl", p.epgUrl)
        sp.edit().putString(KEY_PLAYLIST, o.toString()).apply()
    }

    var favorites: Set<String>
        get() = sp.getStringSet("favorites", null)?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet("favorites", HashSet(value)).apply()

    var watched: Set<String>
        get() = sp.getStringSet("watched", null)?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet("watched", HashSet(value)).apply()

    fun loadHistory(): List<WatchEntry> {
        val raw = sp.getString("history", null) ?: return emptyList()
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
        sp.edit().putString("history", arr.toString()).apply()
    }

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

    var lastChannelId: String?
        get() = sp.getString("lastChannel", null)
        set(value) = sp.edit().putString("lastChannel", value).apply()

    var epgAuto: Boolean
        get() = sp.getBoolean("epgAuto", true)
        set(value) = sp.edit().putBoolean("epgAuto", value).apply()

    var hideAdult: Boolean
        get() = sp.getBoolean("hideAdult", false)
        set(value) = sp.edit().putBoolean("hideAdult", value).apply()

    var pin: String?
        get() = sp.getString("pin", null)
        set(value) = sp.edit().putString("pin", value).apply()

    /** Channels played lately, newest first. */
    var recentChannels: List<String>
        get() = try {
            val arr = JSONArray(sp.getString("recentChannels", "[]") ?: "[]")
            List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
        set(value) = sp.edit().putString("recentChannels", JSONArray(value).toString()).apply()

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

    fun clearUserData() {
        sp.edit()
            .remove(KEY_PLAYLIST)
            .remove("favorites")
            .remove("watched")
            .remove("history")
            .remove("lastChannel")
            .remove("recentChannels")
            .apply()
    }

    private companion object {
        const val KEY_PLAYLIST = "playlist"
    }
}
