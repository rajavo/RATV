package com.ratv.app.player

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
val subtitleLanguages = listOf(
    SubLanguage("ar", "العربية"),
    SubLanguage("en", "الإنجليزية"),
    SubLanguage("fr", "الفرنسية"),
    SubLanguage("tr", "التركية"),
    SubLanguage("es", "الإسبانية"),
    SubLanguage("de", "الألمانية"),
    SubLanguage("it", "الإيطالية"),
    SubLanguage("pt", "البرتغالية"),
    SubLanguage("ru", "الروسية"),
    SubLanguage("hi", "الهندية"),
    SubLanguage("ur", "الأردية"),
    SubLanguage("fa", "الفارسية"),
    SubLanguage("id", "الإندونيسية"),
    SubLanguage("ms", "الملايوية"),
    SubLanguage("bn", "البنغالية"),
    SubLanguage("tl", "الفلبينية"),
    SubLanguage("zh", "الصينية"),
    SubLanguage("ja", "اليابانية"),
    SubLanguage("ko", "الكورية"),
    SubLanguage("nl", "الهولندية"),
    SubLanguage("sv", "السويدية"),
    SubLanguage("pl", "البولندية"),
    SubLanguage("uk", "الأوكرانية"),
    SubLanguage("el", "اليونانية"),
    SubLanguage("ro", "الرومانية"),
    SubLanguage("hu", "المجرية"),
    SubLanguage("cs", "التشيكية"),
    SubLanguage("da", "الدنماركية"),
    SubLanguage("fi", "الفنلندية"),
    SubLanguage("no", "النرويجية"),
    SubLanguage("he", "العبرية"),
    SubLanguage("th", "التايلاندية"),
    SubLanguage("vi", "الفيتنامية"),
    SubLanguage("sw", "السواحيلية")
)

fun languageName(code: String): String {
    val c = code.lowercase().take(2)
    subtitleLanguages.firstOrNull { it.code == c }?.let { return it.name }
    val name = Locale(c).getDisplayLanguage(Locale("ar"))
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
