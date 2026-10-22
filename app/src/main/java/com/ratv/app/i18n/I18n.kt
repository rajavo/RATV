package com.ratv.app.i18n

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/**
 * The app's interface language: Arabic (the texts as written in the code), English or Spanish.
 *
 * Every text shown is written in Arabic and passed through [tr], which looks it up in
 * assets/i18n/<lang>.json. A text missing there is shown in Arabic rather than not at all.
 */
object I18n {
    val LANGUAGES = listOf("ar" to "العربية", "en" to "English", "es" to "Español")

    /** "ar", "en" or "es". Read in composition, so the screen follows a change. */
    var lang by mutableStateOf("ar")
        private set

    @Volatile
    private var table: Map<String, String> = emptyMap()

    @Volatile
    private var tableLang = "ar"

    private var appContext: Context? = null

    fun init(context: Context, language: String?) {
        appContext = context.applicationContext
        set(language)
    }

    fun set(language: String?) {
        val l = if (language in setOf("ar", "en", "es")) language!! else "ar"
        if (l != "ar" && l != tableLang) {
            table = load(l)
            tableLang = l
        }
        lang = l
    }

    val rtl: Boolean get() = lang == "ar"

    /** For dates, numbers and language names in the interface language. */
    val locale: Locale
        get() = when (lang) {
            "en" -> Locale.ENGLISH
            "es" -> Locale("es")
            else -> Locale("ar")
        }

    internal fun lookup(ar: String): String {
        if (lang == "ar") return ar
        return table[ar] ?: ar
    }

    private fun load(l: String): Map<String, String> = runCatching {
        val text = appContext!!.assets.open("i18n/$l.json").bufferedReader().use { it.readText() }
        val o = JSONObject(text)
        val m = HashMap<String, String>(o.length() * 2)
        o.keys().forEach { k -> m[k] = o.getString(k) }
        m
    }.getOrDefault(emptyMap())
}

/** [ar] in the interface language. */
fun tr(ar: String): String = I18n.lookup(ar)

/** [ar] in the interface language, with {0}, {1}… replaced by [args]. */
fun tr(ar: String, vararg args: Any?): String {
    var s = I18n.lookup(ar)
    args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
    return s
}
