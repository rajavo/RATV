package com.ratv.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ratv.app.data.Cue
import com.ratv.app.data.Podnapisi
import com.ratv.app.data.Prefs
import com.ratv.app.data.SubtitleHit
import com.ratv.app.data.SubtitleParser
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What to look subtitles up by: a film (no season) or an episode. */
data class SubtitleQuery(
    val keyword: String,
    val altKeyword: String?,
    val year: Int?,
    val season: Int? = null,
    val episode: Int? = null
)

private fun hasArabic(s: String): Boolean = s.any { it.code in 0x0600..0x06FF }

/**
 * Finds, loads and shows subtitles for the film or episode playing, in the viewer's language.
 * When no subtitle exists in that language, another one (English first) is translated on the device.
 */
@Stable
class SubtitleController(private val scope: CoroutineScope, private val prefs: Prefs) {
    var language by mutableStateOf(prefs.subtitleLang)
        private set
    var size by mutableIntStateOf(prefs.subtitleSize.coerceIn(0, 3))
        private set
    var offsetMs by mutableLongStateOf(0L)
        private set

    var searching by mutableStateOf(false)
        private set
    /** Results for the current title and language; null until searched. */
    var hits by mutableStateOf<List<SubtitleHit>?>(null)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set

    var active by mutableStateOf<SubtitleHit?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var cues by mutableStateOf<List<Cue>>(emptyList())
        private set
    val translations = mutableStateMapOf<Int, String>()
    var translating by mutableStateOf(false)
        private set
    var needsTranslation by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set

    /** Where playback is now, for translating the lines about to be shown first. Read on the main thread. */
    var positionMs: () -> Long = { 0L }

    private var query: SubtitleQuery? = null
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var translator: MachineTranslator? = null

    val isOn: Boolean get() = active != null

    fun setItem(q: SubtitleQuery?) {
        if (q == query) return
        searchJob?.cancel()
        stop()
        query = q
        hits = null
        searchError = null
        searching = false
    }

    fun chooseLanguage(code: String) {
        if (code == language) return
        language = code
        prefs.subtitleLang = code
        hits = null
        val current = active
        if (current != null) choose(current)
        search()
    }

    fun chooseSize(level: Int) {
        size = level.coerceIn(0, 3)
        prefs.subtitleSize = size
    }

    fun shift(deltaMs: Long) {
        offsetMs += deltaMs
    }

    fun resetOffset() {
        offsetMs = 0L
    }

    /** Looks the title up in the chosen language and in English; [autoPick] turns the best one on. */
    fun search(autoPick: Boolean = false) {
        val q = query ?: return
        searchJob?.cancel()
        searching = true
        searchError = null
        val lang = language
        searchJob = scope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    val keywords = listOfNotNull(q.altKeyword, q.keyword)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        // Latin titles first: the library is mostly indexed by the original title.
                        .sortedBy { if (hasArabic(it)) 1 else 0 }
                    val langs = if (lang == "en") listOf("en") else listOf(lang, "en")
                    val jobs = keywords.flatMap { k ->
                        langs.map { l -> async { runCatching { Podnapisi.search(k, l, q.year, q.season, q.episode) } } }
                    }
                    val results = jobs.map { it.await() }
                    if (results.isNotEmpty() && results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
                    val all = results.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.id }
                    // The chosen language first, then English, each by popularity.
                    all.sortedWith(compareBy<SubtitleHit> { if (it.language.startsWith(lang)) 0 else 1 }.thenByDescending { it.downloads })
                }
                hits = found
                if (autoPick && active == null) found.firstOrNull()?.let { choose(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hits = emptyList()
                searchError = "تعذّر الوصول إلى مكتبة الترجمات. تحقّق من الإنترنت ثم أعد المحاولة."
            } finally {
                searching = false
            }
        }
    }

    fun choose(hit: SubtitleHit) {
        loadJob?.cancel()
        closeTranslator()
        active = hit
        loading = true
        cues = emptyList()
        translations.clear()
        status = null
        val target = language
        val translate = !hit.language.lowercase().startsWith(target)
        needsTranslation = translate
        loadJob = scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val bytes = Podnapisi.download(hit)
                    SubtitleParser.parse(SubtitleParser.decode(bytes, hit.language))
                }
                if (list.isEmpty()) {
                    status = "ملف الترجمة فارغ أو بصيغة غير مدعومة."
                    active = null
                    return@launch
                }
                cues = list
                loading = false
                if (translate) translateAll(list, hit.language, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "تعذّر تنزيل الترجمة. جرّب ترجمة أخرى."
                active = null
            } finally {
                loading = false
            }
        }
    }

    private suspend fun translateAll(list: List<Cue>, from: String, to: String) {
        if (!MachineTranslator.supports(from) || !MachineTranslator.supports(to)) {
            status = "الترجمة الآلية لا تدعم هذه اللغة، فتظهر الترجمة الأصلية."
            return
        }
        val t = MachineTranslator(from, to)
        translator = t
        translating = true
        try {
            status = "جارٍ تجهيز الترجمة الآلية إلى ${languageName(to)}… (يُنزَّل مرة واحدة فقط)"
            t.prepare()
            status = null
            while (translations.size < list.size) {
                // Always translate the next line the viewer is about to see, even after seeking.
                var i = SubtitleParser.firstAtOrAfter(list, positionMs() - offsetMs)
                while (i < list.size && translations.containsKey(i)) i++
                if (i >= list.size) {
                    i = 0
                    while (i < list.size && translations.containsKey(i)) i++
                }
                if (i >= list.size) break
                translations[i] = t.translate(list[i].text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "الترجمة الآلية غير متاحة على هذا الجهاز الآن، فتظهر الترجمة الأصلية."
        } finally {
            translating = false
        }
    }

    /** The line to show at [positionMs], translated when ready. */
    fun textAt(positionMs: Long): String? {
        val list = cues
        if (list.isEmpty()) return null
        val i = SubtitleParser.cueAt(list, positionMs - offsetMs)
        if (i < 0) return null
        return translations[i] ?: list[i].text
    }

    fun stop() {
        loadJob?.cancel()
        closeTranslator()
        active = null
        loading = false
        cues = emptyList()
        translations.clear()
        translating = false
        needsTranslation = false
        status = null
        offsetMs = 0L
    }

    fun release() {
        searchJob?.cancel()
        stop()
    }

    private fun closeTranslator() {
        translator?.let { runCatching { it.close() } }
        translator = null
    }
}

private val subtitleSizes = listOf(18, 23, 29, 36)
private val subtitleSizeNames = listOf("صغير", "متوسط", "كبير", "كبير جداً")

/** The subtitle line over the picture, lifted above the controls while they show. */
@Composable
fun BoxScope.SubtitleOverlay(text: String?, sizeLevel: Int, raised: Boolean) {
    if (text.isNullOrBlank()) return
    val size = subtitleSizes[sizeLevel.coerceIn(0, subtitleSizes.size - 1)].sp
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(start = 48.dp, end = 48.dp, bottom = if (raised) 150.dp else 32.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            text,
            style = TextStyle(
                fontSize = size,
                lineHeight = size * 1.3f,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                shadow = Shadow(Color.Black, Offset(2f, 2f), 6f)
            ),
            modifier = Modifier
                .background(Color(0x8C000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

/** Search, language, size and timing of subtitles. */
@Composable
internal fun SubtitlesPanel(
    sub: SubtitleController,
    hasEmbedded: Boolean,
    onEmbedded: () -> Unit,
    onClose: () -> Unit,
    tv: Boolean
) {
    var showLanguages by rememberSaveable { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (sub.hits == null && !sub.searching) sub.search()
        if (tv) {
            delay(150L)
            runCatching { firstFocus.requestFocus() }
        }
    }
    SidePanel(
        title = "الترجمة",
        subtitle = "ترجمة الفيلم أو الحلقة بأي لغة، وتُترجم آلياً إذا لم تتوفر بلغتك",
        onClose = onClose,
        width = 430.dp
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            val current = sub.active
            if (current != null || sub.status != null) {
                item {
                    PanelCard {
                        if (current != null) {
                            Text("الترجمة مفعّلة", style = T.caption)
                            Text(current.release, style = T.body(14.sp, FontWeight.Bold), maxLines = 2)
                            val line = if (sub.needsTranslation) {
                                val total = sub.cues.size
                                val done = sub.translations.size
                                val pct = if (total > 0) done * 100 / total else 0
                                "${languageName(current.language)} ← ترجمة آلية إلى ${languageName(sub.language)}" +
                                    if (total > 0) " · $pct%" else ""
                            } else {
                                languageName(current.language)
                            }
                            Text(line, style = T.body(13.sp, FontWeight.Medium, RColors.SaffronLight))
                            if (sub.loading) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(color = RColors.Saffron, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                    Text("جارٍ تنزيل الترجمة…", style = T.caption)
                                }
                            }
                        }
                        val status = sub.status
                        if (status != null) Text(status, style = T.body(12.5.sp, color = RColors.Text2))
                        if (current != null) ActionChip("إيقاف الترجمة", { sub.stop() }, icon = RIcons.Close)
                    }
                }
            }

            item { PanelHeader("لغة الترجمة") }
            item {
                SourceRow(
                    title = languageName(sub.language),
                    subtitle = if (showLanguages) "اختر اللغة" else "اضغط لتغيير اللغة",
                    badge = null,
                    selected = false,
                    icon = RIcons.Captions,
                    modifier = Modifier.focusRequester(firstFocus),
                    onClick = { showLanguages = !showLanguages }
                )
            }
            if (showLanguages) {
                items(subtitleLanguages, key = { "l" + it.code }) { lang ->
                    SourceRow(
                        title = lang.name,
                        subtitle = null,
                        badge = null,
                        selected = lang.code == sub.language,
                        icon = RIcons.Captions,
                        modifier = Modifier,
                        onClick = {
                            sub.chooseLanguage(lang.code)
                            showLanguages = false
                        }
                    )
                }
            }

            item { PanelHeader("ترجمات متاحة") }
            val hits = sub.hits
            when {
                sub.searching -> item {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(color = RColors.Saffron, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text("جارٍ البحث عن ترجمة…", style = T.caption)
                    }
                }
                sub.searchError != null -> item {
                    PanelNote(sub.searchError.orEmpty(), RColors.Danger)
                    ActionChip("إعادة البحث", { sub.search() }, modifier = Modifier.padding(horizontal = 8.dp), icon = RIcons.Refresh)
                }
                hits.isNullOrEmpty() -> item {
                    PanelNote("لم نجد ترجمة لهذا العنوان في مكتبة الترجمات.")
                }
                else -> items(hits.orEmpty().take(40), key = { "h" + it.id }) { hit ->
                    val translated = !hit.language.lowercase().startsWith(sub.language)
                    SourceRow(
                        title = hit.release,
                        subtitle = languageName(hit.language) +
                            (if (translated) " · تُترجم آلياً إلى ${languageName(sub.language)}" else "") +
                            (if (hit.downloads > 0) " · ${hit.downloads} تنزيل" else ""),
                        badge = null,
                        selected = sub.active?.id == hit.id,
                        icon = RIcons.FileIcon,
                        modifier = Modifier,
                        onClick = { sub.choose(hit) }
                    )
                }
            }

            if (hasEmbedded) {
                item { PanelHeader("ترجمات داخل الملف") }
                item {
                    SourceRow(
                        title = "الصوت والترجمة المضمّنة",
                        subtitle = "ترجمات يوفرها مزودك داخل الفيلم",
                        badge = null,
                        selected = false,
                        icon = RIcons.Captions,
                        modifier = Modifier,
                        onClick = onEmbedded
                    )
                }
            }

            item { PanelHeader("حجم الخط") }
            item {
                Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    subtitleSizeNames.forEachIndexed { index, name ->
                        ActionChip(name, { sub.chooseSize(index) }, selected = sub.size == index)
                    }
                }
            }

            item { PanelHeader("التوقيت") }
            item {
                Row(
                    Modifier.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActionChip("−½ ث", { sub.shift(-500L) })
                    ActionChip("+½ ث", { sub.shift(500L) })
                    ActionChip("ضبط", { sub.resetOffset() })
                }
            }
            item {
                val o = sub.offsetMs
                PanelNote(
                    when {
                        o == 0L -> "الترجمة في وقتها الأصلي"
                        o > 0L -> "الترجمة متأخرة ${seconds(o)} ث"
                        else -> "الترجمة مقدَّمة ${seconds(o)} ث"
                    }
                )
            }
        }
    }
}
