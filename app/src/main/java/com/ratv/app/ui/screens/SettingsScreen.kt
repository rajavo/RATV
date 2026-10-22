package com.ratv.app.ui.screens

import com.ratv.app.i18n.I18n
import com.ratv.app.i18n.tr
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.ratv.app.data.Updater
import com.ratv.app.player.languageName
import com.ratv.app.player.subtitleLanguages
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ratv.app.BuildConfig
import com.ratv.app.data.Playlist
import com.ratv.app.data.PlaylistType
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.Navigator
import com.ratv.app.ui.components.rememberIsTv
import com.ratv.app.ui.components.CircleIconButton
import com.ratv.app.ui.components.AppIcon
import com.ratv.app.ui.components.OutlineButton
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.ScreenHeader
import com.ratv.app.ui.components.TvInitialFocus
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.components.arabicDate
import com.ratv.app.ui.components.formatCount
import com.ratv.app.ui.components.relativeTime
import com.ratv.app.ui.components.screenBackground
import com.ratv.app.ui.theme.RColors
import androidx.compose.ui.graphics.Brush
import com.ratv.app.ui.components.SloganText
import com.ratv.app.ui.theme.Palette
import com.ratv.app.ui.theme.AppTheme
import com.ratv.app.ui.theme.T

private val subtitleSizeLabels get() = listOf(tr("صغير"), tr("متوسط"), tr("كبير"), tr("كبير جداً"))

private val bufferLabels get() = linkedMapOf(
    "30" to tr("يكمل 30 ثانية بدون إنترنت"),
    "60" to tr("يكمل دقيقة بدون إنترنت (موصى به)"),
    "120" to tr("يكمل دقيقتين بدون إنترنت"),
    "180" to tr("يكمل 3 دقائق بدون إنترنت")
)

private val steadyLabels get() = linkedMapOf(
    "auto" to tr("تلقائي (موصى به)"),
    "fast" to tr("سريع: القنوات تفتح أسرع"),
    "max" to tr("أقصى ثبات: للنت أو السيرفر الضعيف")
)

private val liveFormatLabels get() = linkedMapOf(
    "ts" to tr("TS (موصى به)"),
    "hls" to "HLS",
    "auto" to tr("تلقائي (HLS إذا يدعمه السيرفر)")
)

@Composable
fun SettingsScreen(nav: Navigator) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val playlist by repo.playlist.collectAsStateWithLifecycle()
    val account by repo.account.collectAsStateWithLifecycle()
    val channels by repo.channels.collectAsStateWithLifecycle()
    val movies by repo.movies.collectAsStateWithLifecycle()
    val series by repo.series.collectAsStateWithLifecycle()
    val lastUpdated by repo.lastUpdated.collectAsStateWithLifecycle()
    val hideAdult by repo.hideAdult.collectAsStateWithLifecycle()
    val epgLoading by repo.epgLoading.collectAsStateWithLifecycle()
    val users by repo.playlists.collectAsStateWithLifecycle()

    var bufferMode by remember { mutableStateOf(repo.prefs.bufferMode) }
    var autoplay by remember { mutableStateOf(repo.prefs.autoplayLast) }
    var bestQuality by remember { mutableStateOf(repo.prefs.bestQuality) }
    var epgAuto by remember { mutableStateOf(repo.prefs.epgAuto) }
    var livePreview by remember { mutableStateOf(repo.prefs.livePreview) }
    var subLang by remember { mutableStateOf(repo.prefs.subtitleLang) }
    var subSize by remember { mutableStateOf(repo.prefs.subtitleSize.coerceIn(0, 3)) }
    var subAuto by remember { mutableStateOf(repo.prefs.subtitleAuto) }
    var showSubLang by remember { mutableStateOf(false) }
    var showBuffer by remember { mutableStateOf(false) }
    var showFormat by remember { mutableStateOf(false) }
    var liveFormat by remember { mutableStateOf(repo.prefs.liveFormat) }
    var showSteady by remember { mutableStateOf(false) }
    var showPalette by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    val hostContext = androidx.compose.ui.platform.LocalContext.current
    var liveSteady by remember { mutableStateOf(repo.prefs.liveSteady) }
    var matchFrameRate by remember { mutableStateOf(repo.prefs.matchFrameRate) }
    var autoVersion by remember { mutableStateOf(repo.prefs.autoVersion) }
    val onTv = rememberIsTv()
    var showAbout by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Playlist?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    val update by Updater.available.collectAsStateWithLifecycle()
    var showUpdate by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var pinMode by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    TvInitialFocus(firstFocus)

    Column(
        Modifier
            .fillMaxSize()
            .screenBackground()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenHeader(tr("حسابي"), Modifier.statusBarsPadding())

        val p = playlist
        Column(
            Modifier
                .padding(start = 20.dp, end = 20.dp, top = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(RColors.Surface)
                .border(1.dp, RColors.Line, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(RColors.SaffronTint),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(RIcons.ListIcon, contentDescription = null, tint = RColors.Saffron, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(p?.name ?: tr("بلا اشتراك"), style = T.body(16.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p != null) {
                            Text(
                                if (p.type == PlaylistType.XTREAM) "Xtream" else "M3U",
                                style = T.body(10.5.sp, FontWeight.Bold, RColors.Text2).copy(lineHeight = 12.sp),
                                modifier = Modifier
                                    .border(1.dp, RColors.LineStrong, RoundedCornerShape(5.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                    val source = when {
                        p == null -> ""
                        p.type == PlaylistType.XTREAM -> Uri.parse(p.server).let { u -> (u.host ?: p.server) + if (u.port > 0) ":" + u.port else "" }
                        p.m3uUrl.startsWith("content://") -> tr("ملف من الجهاز")
                        else -> Uri.parse(p.m3uUrl).host ?: p.m3uUrl
                    }
                    if (source.isNotBlank()) Text(source, style = T.body(13.sp, color = RColors.Text3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusPill(account?.status)
            }
            HorizontalDivider(color = RColors.Line)
            Row(Modifier.fillMaxWidth()) {
                val acc = account
                if (p?.type == PlaylistType.XTREAM && acc != null) {
                    Stat(tr("ينتهي في"), acc.expDate?.let { arabicDate(it) } ?: tr("غير محدود"), Modifier.weight(1f))
                    Stat(tr("الاتصالات"), tr("{0} من {1}", acc.activeCons ?: 0, acc.maxCons ?: 1), Modifier.weight(1f))
                } else {
                    Stat(tr("القنوات"), formatCount(channels.size), Modifier.weight(1f))
                    Stat(tr("الأفلام والمسلسلات"), formatCount(movies.size + series.size), Modifier.weight(1f))
                }
                Stat(tr("آخر تحديث"), relativeTime(lastUpdated), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton(
                    tr("إضافة مستخدم"),
                    onClick = { nav.addUser() },
                    icon = RIcons.Plus,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(firstFocus),
                    height = 44.dp
                )
                OutlineButton(
                    tr("تحديث المحتوى"),
                    onClick = {
                        repo.refresh()
                        Toast.makeText(context, tr("جارٍ تحديث المحتوى…"), Toast.LENGTH_SHORT).show()
                    },
                    icon = RIcons.Refresh,
                    modifier = Modifier.weight(1f),
                    height = 44.dp
                )
            }
        }

        if (users.isNotEmpty()) {
            GroupTitle(tr("المستخدمون"))
            Group {
                users.forEachIndexed { i, u ->
                    if (i > 0) Divider()
                    UserRow(
                        user = u,
                        current = u.id == p?.id,
                        onOpen = {
                            if (u.id != p?.id) {
                                repo.switchTo(u)
                                nav.reload()
                            }
                        },
                        onDelete = { confirmDelete = u }
                    )
                }
                Divider()
                ValueRow(tr("إضافة مستخدم"), tr("يبقى المستخدمون الآخرون كما هم")) { nav.addUser() }
            }
        }

        GroupTitle(tr("المشغّل"))
        Group {
            ValueRow(tr("قياس سرعة النت"), "") { showSpeed = true }
            Divider()
            ValueRow(tr("الاحتياطي ضد انقطاع النت"), bufferLabels[bufferMode] ?: tr("يكمل دقيقة بدون إنترنت")) { showBuffer = true }
            Divider()
            ValueRow(tr("صيغة البث المباشر"), liveFormatLabels[liveFormat] ?: "TS") { showFormat = true }
            Divider()
            ValueRow(tr("ثبات البث المباشر"), steadyLabels[liveSteady] ?: tr("تلقائي")) { showSteady = true }
            Divider()
            ToggleRow(
                tr("نسخة بديلة للقناة لما تقطّع"),
                tr("لو القناة قطّعت كثير، ينتقل لنسخة ثانية منها في اشتراكك (جودة أخف أو رابط احتياطي)"),
                autoVersion
            ) {
                autoVersion = it
                repo.prefs.autoVersion = it
            }
            if (onTv) {
                Divider()
                ToggleRow(
                    tr("مطابقة معدل الإطارات"),
                    tr("يضبط الشاشة على 50 أو 60 هرتز حسب القناة فتصير الحركة ناعمة بدون تقطيع، وقد تسودّ الشاشة لحظة عند تشغيل القناة"),
                    matchFrameRate
                ) {
                    matchFrameRate = it
                    repo.prefs.matchFrameRate = it
                }
            }
            Divider()
            ToggleRow(tr("أعلى جودة متاحة دائماً"), tr("يختار أعلى جودة يرسلها المزوّد للبث"), bestQuality) {
                bestQuality = it
                repo.prefs.bestQuality = it
            }
            Divider()
            ToggleRow(tr("فتح آخر قناة عند التشغيل"), null, autoplay) {
                autoplay = it
                repo.prefs.autoplayLast = it
            }
            Divider()
            ToggleRow(tr("معاينة القناة في قائمة المباشر"), tr("على التلفزيون تُعرض القناة المختارة في نافذة صغيرة"), livePreview) {
                livePreview = it
                repo.prefs.livePreview = it
            }
        }

        GroupTitle(tr("الترجمة"))
        Group {
            ValueRow(tr("لغة الترجمة"), languageName(subLang)) { showSubLang = true }
            Divider()
            ValueRow(tr("حجم خط الترجمة"), subtitleSizeLabels[subSize]) {
                subSize = (subSize + 1) % subtitleSizeLabels.size
                repo.prefs.subtitleSize = subSize
            }
            Divider()
            ToggleRow(tr("تشغيل الترجمة تلقائياً"), tr("تبحث عن ترجمة بلغتك عند بدء أي فيلم أو حلقة، وتترجمها آلياً إن لم تتوفر"), subAuto) {
                subAuto = it
                repo.prefs.subtitleAuto = it
            }
        }

        GroupTitle(tr("دليل البرامج"))
        Group {
            ToggleRow(tr("تحديث الدليل تلقائياً"), tr("كل 12 ساعة"), epgAuto) {
                epgAuto = it
                repo.prefs.epgAuto = it
            }
            Divider()
            ValueRow(tr("تحديث الدليل الآن"), if (epgLoading) tr("جارٍ التحديث…") else "") {
                repo.refreshEpg()
                Toast.makeText(context, tr("جارٍ تحديث دليل البرامج…"), Toast.LENGTH_SHORT).show()
            }
        }

        GroupTitle(tr("الأمان"))
        Group {
            ToggleRow(
                tr("حجب محتوى البالغين"),
                tr("يحجب أقسام وقنوات وأفلام ومسلسلات البالغين في كل المستخدمين، ويلزم رمز سري لإظهارها"),
                hideAdult
            ) { enable ->
                pinMode = if (enable) "set" else "check"
            }
            Divider()
            ValueRow(tr("حذف سجل المشاهدة"), "") { confirmClearHistory = true }
        }

        GroupTitle(tr("التطبيق"))
        Group {
            // "Language" stays in the title whatever the language, so it can always be found again.
            ValueRow(
                if (I18n.lang == "en") "Language" else tr("لغة التطبيق") + " · Language",
                I18n.LANGUAGES.first { it.first == I18n.lang }.second
            ) { showLanguage = true }
            Divider()
            ValueRow(tr("ألوان التطبيق"), tr(AppTheme.palette.label)) { showPalette = true }
            Divider()
            ValueRow(
                tr("تحديث التطبيق"),
                when {
                    checkingUpdate -> tr("جارٍ البحث…")
                    update != null -> tr("متاح {0}", update?.versionName)
                    else -> tr("الإصدار {0}", BuildConfig.VERSION_NAME)
                }
            ) {
                if (checkingUpdate) return@ValueRow
                checkingUpdate = true
                scope.launch {
                    val found = runCatching { Updater.check() }
                    checkingUpdate = false
                    found.onSuccess { info ->
                        if (info != null) {
                            showUpdate = true
                        } else {
                            Toast.makeText(context, tr("عندك آخر إصدار ({0})", BuildConfig.VERSION_NAME), Toast.LENGTH_SHORT).show()
                        }
                    }.onFailure {
                        Toast.makeText(context, tr("تعذّر الوصول إلى GitHub. تحقّق من الاتصال."), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        Row(
            Modifier
                .padding(start = 20.dp, end = 20.dp, top = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(RColors.Surface)
                .tvClickable(RoundedCornerShape(18.dp)) { showAbout = true }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppIcon(52.dp)
            Column(Modifier.weight(1f)) {
                Text(tr("حول RA TV"), style = T.body(15.sp, FontWeight.Bold))
                SloganText(13.sp)
                Text(tr("الإصدار {0} · مشغّل وسائط فقط", BuildConfig.VERSION_NAME), style = T.caption)
            }
            Icon(RIcons.ChevronLeft, contentDescription = null, tint = RColors.Text3, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(28.dp))
    }

    if (showSubLang) {
        AlertDialog(
            onDismissRequest = { showSubLang = false },
            confirmButton = { TextButton(onClick = { showSubLang = false }) { Text(tr("تم"), color = RColors.Saffron) } },
            title = { Text(tr("لغة الترجمة"), style = T.h2) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    subtitleLanguages.forEach { lang ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 46.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .tvClickable(RoundedCornerShape(10.dp), role = Role.RadioButton) {
                                    subLang = lang.code
                                    repo.prefs.subtitleLang = lang.code
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = subLang == lang.code,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = RColors.Saffron)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(lang.name, style = T.body(15.sp))
                        }
                    }
                }
            },
            containerColor = RColors.Surface
        )
    }

    if (showLanguage) {
        AlertDialog(
            onDismissRequest = { showLanguage = false },
            confirmButton = { TextButton(onClick = { showLanguage = false }) { Text(tr("تم"), color = RColors.Saffron) } },
            title = { Text(if (I18n.lang == "en") "Language" else tr("لغة التطبيق"), style = T.h2) },
            text = {
                Column {
                    I18n.LANGUAGES.forEach { (code, name) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .tvClickable(RoundedCornerShape(10.dp), role = Role.RadioButton) {
                                    showLanguage = false
                                    if (code != I18n.lang) {
                                        repo.prefs.uiLanguage = code
                                        I18n.set(code)
                                        // Built again in the new language and direction.
                                        hostActivityOf(hostContext)?.recreate()
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = I18n.lang == code,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = RColors.Saffron)
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(name, style = T.body(15.sp))
                        }
                    }
                }
            },
            containerColor = RColors.Surface
        )
    }

    if (showPalette) {
        AlertDialog(
            onDismissRequest = { showPalette = false },
            confirmButton = { TextButton(onClick = { showPalette = false }) { Text(tr("تم"), color = RColors.Saffron) } },
            title = { Text(tr("ألوان التطبيق"), style = T.h2) },
            text = {
                Column {
                    Palette.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .tvClickable(RoundedCornerShape(10.dp), role = Role.RadioButton) {
                                    AppTheme.palette = option
                                    repo.prefs.palette = option.key
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = AppTheme.palette == option,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = option.accent)
                            )
                            Spacer(Modifier.size(8.dp))
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(option.light, option.accent, option.deep)))
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(tr(option.label), style = T.body(15.sp))
                        }
                    }
                }
            },
            containerColor = RColors.Surface
        )
    }

    if (showSteady) {
        AlertDialog(
            onDismissRequest = { showSteady = false },
            confirmButton = { TextButton(onClick = { showSteady = false }) { Text(tr("تم"), color = RColors.Saffron) } },
            title = { Text(tr("ثبات البث المباشر"), style = T.h2) },
            text = {
                Column {
                    steadyLabels.forEach { (key, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .tvClickable(RoundedCornerShape(10.dp), role = Role.RadioButton) {
                                    liveSteady = key
                                    repo.prefs.liveSteady = key
                                    com.ratv.app.player.Steadiness.mode = key
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = liveSteady == key,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = RColors.Saffron)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(label, style = T.body(15.sp))
                        }
                    }
                    Text(
                        tr("مع «تلقائي»: إذا وقف البث، يجمع التطبيق احتياطياً أكبر قبل ما يكمل (5 ثوانٍ زيادة، لين 10)، فتقل التوقفات مع النت المتقطع أو السيرفر الضعيف، ويرجع لطبيعته بعد 20 دقيقة بدون تقطيع. «أقصى ثبات» يجمع أكبر احتياطي دائماً، والقنوات تفتح أبطأ بثوانٍ."),
                        style = T.caption
                    )
                }
            },
            containerColor = RColors.Surface
        )
    }

    if (showFormat) {
        AlertDialog(
            onDismissRequest = { showFormat = false },
            confirmButton = { TextButton(onClick = { showFormat = false }) { Text(tr("تم"), color = RColors.Saffron) } },
            title = { Text(tr("صيغة البث المباشر"), style = T.h2) },
            text = {
                Column {
                    liveFormatLabels.forEach { (key, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .tvClickable(RoundedCornerShape(10.dp), role = Role.RadioButton) {
                                    liveFormat = key
                                    repo.prefs.liveFormat = key
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = liveFormat == key,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = RColors.Saffron)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(label, style = T.body(15.sp))
                        }
                    }
                    Text(
                        tr("TS هو البث الأصلي من السيرفر وهو الأنسب لأغلب الاشتراكات. جرّب HLS إذا كان التقطيع مستمراً مع TS؛ وإذا لم تعمل قناة بصيغة HLS يرجع التطبيق إلى TS تلقائياً."),
                        style = T.caption
                    )
                }
            },
            containerColor = RColors.Surface
        )
    }

    if (showBuffer) {
        AlertDialog(
            onDismissRequest = { showBuffer = false },
            confirmButton = { TextButton(onClick = { showBuffer = false }) { Text(tr("تم"), color = RColors.Saffron) } },
            title = { Text(tr("الاحتياطي ضد انقطاع النت"), style = T.h2) },
            text = {
                Column {
                    bufferLabels.forEach { (key, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .tvClickable(RoundedCornerShape(10.dp), role = Role.RadioButton) {
                                    bufferMode = key
                                    repo.prefs.bufferMode = key
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = bufferMode == key,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = RColors.Saffron)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(label, style = T.body(15.sp))
                        }
                    }
                    Text(
                        tr("الأفلام والمسلسلات: يتجمّع الاحتياطي أثناء المشاهدة حتى المدة المختارة، فتكمل طوالها بدون إنترنت. القنوات المباشرة: البث يصل لحظة بلحظة فلا يمكن سبقه، فيخزّن التطبيق ثوانٍ احتياطية في بداية كل قناة ويعبّيها من جديد بعد أي توقف. إذا انقطع النت يكمل من الاحتياطي ويعيد الاتصال كل ثانيتين حتى يعود. الاحتياطي في ذاكرة الجهاز، فقد يكون أقصر مع 4K."),
                        style = T.caption
                    )
                }
            },
            containerColor = RColors.Surface
        )
    }

    confirmDelete?.let { u ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    val wasCurrent = u.id == playlist?.id
                    val next = repo.removePlaylist(u.id)
                    when {
                        next == null -> nav.login()
                        wasCurrent -> nav.reload()
                    }
                }) { Text(tr("حذف"), color = RColors.Live) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr("إلغاء"), color = RColors.Text2) } },
            title = { Text(tr("حذف المستخدم «{0}»؟", u.name), style = T.h2) },
            text = {
                Text(
                    tr("سيُحذف هذا المستخدم ومفضلته وسجل مشاهدته من هذا الجهاز. باقي المستخدمين يبقون كما هم."),
                    style = T.body(14.sp, color = RColors.Text2)
                )
            },
            containerColor = RColors.Surface
        )
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearHistory = false
                    repo.clearWatchHistory()
                    Toast.makeText(context, tr("تم حذف سجل المشاهدة"), Toast.LENGTH_SHORT).show()
                }) { Text(tr("حذف"), color = RColors.Live) }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text(tr("إلغاء"), color = RColors.Text2) } },
            title = { Text(tr("حذف سجل المشاهدة؟"), style = T.h2) },
            text = {
                Text(
                    tr("يُحذف «تابع المشاهدة» والقنوات التي شوهدت مؤخراً وعلامات المشاهدة لهذا المستخدم. المفضلة تبقى."),
                    style = T.body(14.sp, color = RColors.Text2)
                )
            },
            containerColor = RColors.Surface
        )
    }

    update?.let { info -> if (showUpdate) UpdateDialog(info) { showUpdate = false } }

    if (showSpeed) {
        SpeedTestDialog(playlist = playlist, onDismiss = { showSpeed = false })
    }

    val mode = pinMode
    if (mode != null) {
        PinDialog(
            title = if (mode == "set") tr("اختر رمزاً سرياً من 4 أرقام") else tr("أدخل الرمز السري"),
            onDismiss = { pinMode = null },
            onSubmit = { pin ->
                if (mode == "set") {
                    repo.prefs.pin = pin
                    repo.setHideAdult(true)
                    pinMode = null
                    true
                } else if (pin == repo.prefs.pin) {
                    repo.setHideAdult(false)
                    pinMode = null
                    true
                } else {
                    false
                }
            }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(tr("حسناً"), color = RColors.Saffron) } },
            title = { Text("RATV ${BuildConfig.VERSION_NAME}", style = T.h2) },
            text = {
                Text(
                    tr("RATV مشغّل وسائط لقوائم IPTV الخاصة بك (Xtream Codes وM3U). ") +
                        tr("التطبيق لا يقدّم أي قنوات أو محتوى، والمستخدم مسؤول عن استخدام اشتراك مرخّص.\n\n") +
                        tr("الخطوط: Alexandria وTajawal برخصة SIL Open Font License."),
                    style = T.body(14.sp, color = RColors.Text2)
                )
            },
            containerColor = RColors.Surface
        )
    }
}

@Composable
private fun StatusPill(status: String?) {
    if (status == null) return
    val active = status.equals("Active", ignoreCase = true)
    val (label, fg, bg) = when {
        active -> Triple(tr("نشط"), RColors.Success, RColors.SuccessBg)
        status.equals("Expired", ignoreCase = true) -> Triple(tr("منتهي"), RColors.Danger, RColors.DangerBg)
        status.equals("Banned", ignoreCase = true) || status.equals("Disabled", ignoreCase = true) -> Triple(tr("موقوف"), RColors.Danger, RColors.DangerBg)
        else -> Triple(status, RColors.Text2, RColors.Surface2)
    }
    Row(
        Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(bg)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Text(label, style = T.body(12.sp, FontWeight.Bold, fg).copy(lineHeight = 14.sp))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = T.body(11.5.sp, color = RColors.Text3), maxLines = 1)
        Text(value, style = T.body(14.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = T.body(13.sp, FontWeight.Bold, RColors.Text3),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 8.dp)
    )
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(RColors.Surface),
        content = content
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = RColors.Line)
}

@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(14.dp))
            .tvClickable(RoundedCornerShape(14.dp), onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(label, style = T.body(15.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, style = T.body(14.sp, color = RColors.Text2))
        Icon(RIcons.ChevronLeft, contentDescription = null, tint = RColors.Text3, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ToggleRow(label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(14.dp))
            .tvClickable(RoundedCornerShape(14.dp), role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = T.body(15.sp, FontWeight.Medium))
            if (sub != null) Text(sub, style = T.caption)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RColors.OnSaffron,
                checkedTrackColor = RColors.Saffron,
                checkedBorderColor = RColors.Saffron,
                uncheckedThumbColor = RColors.Text2,
                uncheckedTrackColor = RColors.LineStrong,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun PinDialog(title: String, onDismiss: () -> Unit, onSubmit: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { if (pin.length == 4) wrong = !onSubmit(pin) },
                enabled = pin.length == 4
            ) { Text(tr("تأكيد"), color = if (pin.length == 4) RColors.Saffron else RColors.Text3) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("إلغاء"), color = RColors.Text2) } },
        title = { Text(title, style = T.h2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v ->
                        pin = v.filter { it.isDigit() }.take(4)
                        wrong = false
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = T.body(20.sp, FontWeight.Bold),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RColors.Saffron,
                        unfocusedBorderColor = RColors.Surface3,
                        cursorColor = RColors.Saffron
                    )
                )
                if (wrong) Text(tr("الرمز غير صحيح."), style = T.body(13.sp, color = RColors.Danger))
            }
        },
        containerColor = RColors.Surface
    )
}

@Composable
private fun UserRow(user: Playlist, current: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val source = when {
        user.type == PlaylistType.XTREAM -> Uri.parse(user.server).let { u -> (u.host ?: user.server) + if (u.port > 0) ":" + u.port else "" }
        user.m3uUrl.startsWith("content://") -> tr("ملف من الجهاز")
        else -> Uri.parse(user.m3uUrl).host ?: user.m3uUrl
    }
    val detail = (if (user.type == PlaylistType.XTREAM && user.username.isNotBlank()) user.username + " · " else "") + source
    Row(
        Modifier
            .fillMaxWidth()
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 60.dp)
                .clip(RoundedCornerShape(14.dp))
                .tvClickable(RoundedCornerShape(14.dp), onClickLabel = tr("فتح {0}", user.name), onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(RIcons.User, contentDescription = null, tint = if (current) RColors.Saffron else RColors.Text3, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(user.name, style = T.body(15.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = T.body(12.5.sp, color = RColors.Text3), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (current) {
                Text(
                    tr("الحالي"),
                    style = T.body(11.sp, FontWeight.Bold, RColors.Saffron),
                    modifier = Modifier
                        .border(1.dp, RColors.Saffron, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        CircleIconButton(RIcons.Close, tr("حذف {0}", user.name), onDelete)
    }
}

private fun hostActivityOf(context: android.content.Context): android.app.Activity? {
    var c: android.content.Context? = context
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}
