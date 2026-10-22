package com.ratv.app.player

import com.ratv.app.i18n.tr
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SubLanguage(val code: String, val name: String)

/** Languages offered for subtitles; all of them can also be produced by on-device translation. */
val subtitleLanguages get() = listOf(
    SubLanguage("ar", tr("العربية")),
    SubLanguage("en", tr("الإنجليزية")),
    SubLanguage("fr", tr("الفرنسية")),
    SubLanguage("tr", tr("التركية")),
    SubLanguage("es", tr("الإسبانية")),
    SubLanguage("de", tr("الألمانية")),
    SubLanguage("it", tr("الإيطالية")),
    SubLanguage("pt", tr("البرتغالية")),
    SubLanguage("ru", tr("الروسية")),
    SubLanguage("hi", tr("الهندية")),
    SubLanguage("ur", tr("الأردية")),
    SubLanguage("fa", tr("الفارسية")),
    SubLanguage("id", tr("الإندونيسية")),
    SubLanguage("ms", tr("الملايوية")),
    SubLanguage("bn", tr("البنغالية")),
    SubLanguage("tl", tr("الفلبينية")),
    SubLanguage("zh", tr("الصينية")),
    SubLanguage("ja", tr("اليابانية")),
    SubLanguage("ko", tr("الكورية")),
    SubLanguage("nl", tr("الهولندية")),
    SubLanguage("sv", tr("السويدية")),
    SubLanguage("pl", tr("البولندية")),
    SubLanguage("uk", tr("الأوكرانية")),
    SubLanguage("el", tr("اليونانية")),
    SubLanguage("ro", tr("الرومانية")),
    SubLanguage("hu", tr("المجرية")),
    SubLanguage("cs", tr("التشيكية")),
    SubLanguage("da", tr("الدنماركية")),
    SubLanguage("fi", tr("الفنلندية")),
    SubLanguage("no", tr("النرويجية")),
    SubLanguage("he", tr("العبرية")),
    SubLanguage("th", tr("التايلاندية")),
    SubLanguage("vi", tr("الفيتنامية")),
    SubLanguage("sw", tr("السواحيلية"))
)

fun languageName(code: String): String {
    val c = code.lowercase().take(2)
    subtitleLanguages.firstOrNull { it.code == c }?.let { return it.name }
    val name = Locale(c).getDisplayLanguage(com.ratv.app.i18n.I18n.locale)
    return if (name.isNullOrBlank() || name == c) code else name
}

suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
    addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    addOnCanceledListener { cont.cancel() }
}

/** On-device machine translation (Google ML Kit): one language model of about 30 MB per language, downloaded once. */
class MachineTranslator(source: String, target: String) : AutoCloseable {
    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.fromLanguageTag(source) ?: TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(target) ?: TranslateLanguage.ARABIC)
            .build()
    )

    suspend fun prepare() {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
    }

    suspend fun translate(text: String): String = translator.translate(text).awaitResult()

    override fun close() {
        translator.close()
    }

    companion object {
        fun supports(code: String): Boolean = TranslateLanguage.fromLanguageTag(code.lowercase().take(2)) != null
    }
}
