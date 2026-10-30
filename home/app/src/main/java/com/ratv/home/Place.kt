package com.ratv.home

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class City(val name: String, val lat: Double, val lng: Double)

/** Where the TV is, for the prayer times: found once from the home network's address, or picked by the viewer. */
object Place {
    val CITIES = listOf(
        City("الرياض", 24.7136, 46.6753),
        City("جدة", 21.4858, 39.1925),
        City("مكة المكرمة", 21.4225, 39.8262),
        City("المدينة المنورة", 24.4672, 39.6111),
        City("الدمام", 26.4207, 50.0888),
        City("الخبر", 26.2172, 50.1971),
        City("الأحساء", 25.3833, 49.5833),
        City("الجبيل", 27.0174, 49.6225),
        City("الطائف", 21.2703, 40.4158),
        City("بريدة", 26.3260, 43.9750),
        City("حائل", 27.5114, 41.7208),
        City("تبوك", 28.3835, 36.5662),
        City("أبها", 18.2164, 42.5053),
        City("خميس مشيط", 18.3000, 42.7333),
        City("جازان", 16.8892, 42.5511),
        City("نجران", 17.4917, 44.1322),
        City("ينبع", 24.0895, 38.0618),
        City("الكويت", 29.3759, 47.9774),
        City("الدوحة", 25.2854, 51.5310),
        City("المنامة", 26.2285, 50.5860),
        City("دبي", 25.2048, 55.2708),
        City("أبوظبي", 24.4539, 54.3773),
        City("مسقط", 23.5880, 58.3829)
    )

    private val DEFAULT = CITIES.first()

    private fun prefs(context: Context) = context.getSharedPreferences("rahome", Context.MODE_PRIVATE)

    /** The city in use: the one the viewer picked, else the one found from the network, else Riyadh. */
    fun current(context: Context): City {
        val sp = prefs(context)
        val name = sp.getString("city", null) ?: return DEFAULT
        return City(name, sp.getFloat("lat", DEFAULT.lat.toFloat()).toDouble(), sp.getFloat("lng", DEFAULT.lng.toFloat()).toDouble())
    }

    fun picked(context: Context) = prefs(context).getBoolean("picked", false)

    fun pick(context: Context, city: City?) {
        val sp = prefs(context).edit()
        if (city == null) {
            sp.putBoolean("picked", false).remove("city").remove("found_at")
        } else {
            sp.putBoolean("picked", true).putString("city", city.name)
                .putFloat("lat", city.lat.toFloat()).putFloat("lng", city.lng.toFloat())
        }
        sp.apply()
    }

    /** Finds the city from the home network's address, at most once a day; true when it changed. Network: call off the main thread. */
    fun findFromNetwork(context: Context): Boolean {
        val sp = prefs(context)
        if (sp.getBoolean("picked", false)) return false
        if (System.currentTimeMillis() - sp.getLong("found_at", 0) < 24 * 3600_000L && sp.contains("city")) return false
        val json = SOURCES.firstNotNullOfOrNull { fetch(it) } ?: return false
        val lat = json.optDouble("latitude", Double.NaN)
        val lng = json.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return false
        val name = nearest(lat, lng)?.name ?: json.optString("city").ifBlank { "موقعك" }
        val old = current(context)
        sp.edit().putString("city", name).putFloat("lat", lat.toFloat()).putFloat("lng", lng.toFloat())
            .putLong("found_at", System.currentTimeMillis()).apply()
        return old.name != name || old.lat != lat
    }

    /** Two free services that tell the city of an internet address; the second when the first does not answer. */
    private val SOURCES = listOf("https://ipwho.is/?fields=success,city,latitude,longitude", "https://ipapi.co/json/")

    private fun fetch(url: String): JSONObject? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "RAHome")
        try {
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                .takeIf { it.optBoolean("success", true) && !it.optBoolean("error", false) && it.has("latitude") }
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** The listed city within about 60 km, to show its Arabic name. */
    fun nearest(lat: Double, lng: Double): City? =
        CITIES.minByOrNull { (it.lat - lat) * (it.lat - lat) + (it.lng - lng) * (it.lng - lng) }
            ?.takeIf { (it.lat - lat) * (it.lat - lat) + (it.lng - lng) * (it.lng - lng) < 0.3 }
}
