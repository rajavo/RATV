package com.ratv.net

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «خفّف الشاشة»: quicker animations and memory freed, ads and trackers blocked, a screen easier on the eyes,
 * and the factory apps the viewer does not need removed.
 */
@Composable
fun LiteScreen(prefs: Prefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    val busy by Lite.busy.collectAsState()
    val message by Lite.message.collectAsState()
    val changes by Lite.changes.collectAsState()
    val fast by produceState(Lite.animationsFast(context), changes) { value = Lite.animationsFast(context) }
    val extras by produceState<List<ExtraApp>?>(null, changes) {
        value = withContext(Dispatchers.IO) { Lite.extras(context) }
    }
    val removed by produceState(emptyList<ExtraApp>(), changes) {
        value = withContext(Dispatchers.IO) { Lite.removed(context, prefs) }
    }
    val adsOn by produceState(Lite.adsBlocked(context), changes) { value = Lite.adsBlocked(context) }
    LaunchedEffect(Unit) { Comfort.load(context) }
    val comfort by Comfort.level.collectAsState()
    val comfortReady by produceState(Focus.accessibilityConnected) {
        while (true) {
            value = Focus.accessibilityConnected
            delay(1000)
        }
    }
    var autoClean by remember { mutableStateOf(prefs.autoClean) }
    val homeInstalled by produceState(Lite.homeInstalled(context), changes) { value = Lite.homeInstalled(context) }
    val home by produceState(Lite.currentHome(context), changes) { value = Lite.currentHome(context) }
    // Ticked apps; the suggested ones start ticked.
    var picked by remember { mutableStateOf<Set<String>?>(null) }
    val list = extras
    LaunchedEffect(list) {
        if (picked == null && list != null) picked = list.filter { it.suggested }.map { it.pkg }.toSet()
    }
    val chosen = picked.orEmpty().filter { pkg -> list.orEmpty().any { it.pkg == pkg } }

    fun work(block: () -> Unit) {
        if (busy) return
        scope.launch(Dispatchers.IO) { block() }
    }

    val first = remember { FocusRequester() }
    if (tv) {
        LaunchedEffect(Unit) {
            repeat(6) {
                delay(150)
                runCatching { first.requestFocus() }
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            Modifier
                .fillMaxSize()
                .background(C.Ink)
                .safeDrawingPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                Modifier
                    .widthIn(max = 780.dp)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 22.dp, bottom = if (message != null) 120.dp else 22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("خفّف الشاشة", style = t(28, true))
                            Text("الشاشة تصير أخف وأسرع، وكل شي هنا ترجعه متى ما بغيت.", style = t(15, color = C.Text2))
                        }
                        Spacer(Modifier.width(12.dp))
                        Pill("رجوع", false, onBack)
                    }
                }
                item { StepCard { Lite.openDeveloperOptions(context) } }
                item {
                    Card {
                        Text("١. خفّف الحين", style = t(18, true, C.Saffron))
                        Text(
                            "يسرّع الحركة في القوائم، ويقفل التطبيقات اللي شغّالة بالخلفية، ويمسح الملفات المؤقتة.",
                            style = t(15, color = C.Text2)
                        )
                        Text("الحركة الحين: " + if (fast) "سريعة" else "عادية", style = t(14, color = C.Text3))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.focusRequester(first)) {
                                Pill("خفّف الشاشة", true) { work { Lite.lighten(context) } }
                            }
                            if (fast) Pill("رجّع الحركة العادية", false) { work { Lite.normalAnimations(context) } }
                        }
                    }
                }
                item {
                    Card {
                        Text("٢. اضغط التطبيقات", style = t(18, true, C.Saffron))
                        Text(
                            "يمر على تطبيقاتك واحد ورا الثاني ويجهّزها على قد استخدامك (تفتح أسرع وتتعب الشاشة أقل)، " +
                                "وبعدين يمسح الملفات المؤقتة لكل التطبيقات. ياخذ كم دقيقة، والتقدّم يطلع تحت.",
                            style = t(15, color = C.Text2)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill("اضغط كل التطبيقات", true) { work { Lite.packAll(context) } }
                            Pill(if (autoClean) "المسح التلقائي: شغّال" else "المسح التلقائي: طافي", false) {
                                autoClean = !autoClean
                                prefs.autoClean = autoClean
                                CleanJob.schedule(context, autoClean)
                                Lite.message.value = if (autoClean) {
                                    "تمام: الملفات المؤقتة تنمسح لحالها كل ٦ ساعات، مادام «تصحيح USB» شغّال."
                                } else {
                                    "وقف المسح التلقائي."
                                }
                            }
                        }
                    }
                }
                // The ads blocker is gone; a TV that still has it on from an older RA Net can turn it off here.
                if (adsOn) {
                    item {
                        Card {
                            Text("حاجب الإعلانات شغّال من نسخة قبل", style = t(18, true, C.Saffron))
                            Pill("طفّه", true) { work { Lite.allowAds(context) } }
                        }
                    }
                }
                item {
                    Card {
                        Text("٣. راحة العين", style = t(18, true, C.Saffron))
                        Text(
                            "ألوان أدفى وإضاءة أهدى فوق كل التطبيقات، عشان عينك ما تتعب بالليل وفي المشاهدة الطويلة.",
                            style = t(15, color = C.Text2)
                        )
                        if (comfortReady) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Comfort.NAMES.forEachIndexed { level, name ->
                                    Pill(name, level == comfort) { Comfort.set(context, level) }
                                }
                            }
                        } else {
                            Text(
                                "تحتاج تشغيل RA Net في «إمكانية الوصول» (نفس اللي يعرف التطبيق اللي على الشاشة): زر «شغّلها» يسويها لك، " +
                                    "أو فعّله بنفسك من الإعدادات.",
                                style = t(14, color = C.Text3)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Pill("شغّلها", true) { work { Lite.turnOnComfort(context, 2) } }
                                Pill("من الإعدادات", false) {
                                    if (comfort <= 0) Comfort.set(context, 2)
                                    Lite.openAccessibility(context)
                                }
                            }
                        }
                    }
                }
                item {
                    Card {
                        Text("٤. خلّ RA Home الواجهة الأساسية", style = t(18, true, C.Saffron))
                        val replaced = prefs.homeDisabled.isNotEmpty()
                        when {
                            !homeInstalled -> Text(
                                "ثبّت RA Home أول (الواجهة اللي فيها مواقيت الصلاة) من الرابط:\n" + Lite.RA_HOME_LINK,
                                style = t(15, color = C.Text2)
                            )
                            home == Lite.RA_HOME || replaced -> {
                                Text("RA Home هي الواجهة الحين: تطلع لك كل ما ضغطت زر البيت.", style = t(15, color = C.Success))
                                if (replaced) Pill("رجّع واجهة الشاشة الأصلية", false) { work { Lite.restoreHome(context, prefs) } }
                            }
                            else -> {
                                Text(
                                    "يوقف واجهة الشاشة الأصلية، وتصير RA Home هي اللي تطلع لما تضغط زر البيت. ترجع الأصلية من هنا بضغطة.",
                                    style = t(15, color = C.Text2)
                                )
                                Pill("خلّها الواجهة", true) { work { Lite.makeHome(context, prefs) } }
                            }
                        }
                        if (homeInstalled) {
                            Text("لا تحذف RA Home وهي الواجهة: رجّع الأصلية من هنا أول.", style = t(13, color = C.Text3))
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionTitle("٥. احذف تطبيقات المصنع الزايدة")
                        Text(
                            "علّم على اللي ما تبيه واضغط «احذف». المعلّمة من البداية آمن حذفها. الأساسية (الإعدادات، المتجر، الواجهة، المصادر) ما تطلع هنا.",
                            style = t(14, color = C.Text2)
                        )
                    }
                }
                when {
                    list == null -> item { Text("جارٍ تحميل التطبيقات…", style = t(15, color = C.Text2)) }
                    list.isEmpty() -> item { Text("ما فيه تطبيقات مصنع زايدة. شاشتك نظيفة.", style = t(15, color = C.Text2)) }
                    else -> {
                        item {
                            Pill(if (chosen.isEmpty()) "علّم على تطبيق أول" else "احذف المعلّمة (${chosen.size})", chosen.isNotEmpty()) {
                                if (chosen.isNotEmpty()) work { Lite.remove(context, prefs, chosen) }
                            }
                        }
                        items(list, key = { it.pkg }) { app ->
                            PickRow(app, app.pkg in picked.orEmpty()) {
                                val now = picked.orEmpty()
                                picked = if (app.pkg in now) now - app.pkg else now + app.pkg
                            }
                        }
                    }
                }
                if (removed.isNotEmpty()) {
                    item { SectionTitle("انحذفت من هنا") }
                    items(removed, key = { "r:" + it.pkg }) { app ->
                        RestoreRow(app) { work { Lite.restore(context, prefs, app.pkg, app.label) } }
                    }
                }
                item {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("قبل ما تحذف", style = t(16, true))
                        Text(
                            "• الحذف لحسابك على الشاشة بس: التطبيق يختفي ويوقف عن الشغل وعن استهلاك الذاكرة والنت، " +
                                "وترجعه من «انحذفت من هنا» بضغطة.",
                            style = t(14, color = C.Text2)
                        )
                        Text("• لو سويت «إعادة ضبط المصنع» ترجع كل تطبيقات المصنع.", style = t(14, color = C.Text2))
                        Text(
                            "• بعد ما تخلص تقدر تطفي «تصحيح USB»، وتشغّله مرة ثانية إذا بغيت تحذف أو ترجّع شي.",
                            style = t(14, color = C.Text2)
                        )
                    }
                }
            }
            // Always in sight, whatever part of the page the remote is on.
            message?.let { m ->
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = 780.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(C.Ink)
                ) { Notice(if (busy) "لحظة… $m" else m) }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(C.Surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) { content() }
}

@Composable
private fun StepCard(onOpen: () -> Unit) {
    Card {
        Text("قبل أول مرة: فعّل «تصحيح USB»", style = t(18, true, C.Saffron))
        Text(
            "التطبيقات ما تقدر تحذف تطبيقات المصنع لحالها، فـ RA Net يستخدم خاصية «تصحيح الأخطاء» في الشاشة نفسها:",
            style = t(15, color = C.Text2)
        )
        Text(
            "• الإعدادات ← النظام ← حول ← اضغط «الإصدار» (Build) سبع مرات لين يقول «أنت الآن مطوّر».\n" +
                "• ارجع للإعدادات ← النظام ← «خيارات المطوّرين» وفعّل «تصحيح USB».\n" +
                "• ارجع هنا واضغط أي زر. أول مرة تطلع رسالة «السماح بتصحيح الأخطاء»: علّم «السماح دائماً» ثم موافق.",
            style = t(14, color = C.Text2)
        )
        Pill("افتح الإعدادات", false, onOpen)
    }
}

@Composable
private fun PickRow(app: ExtraApp, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .tvClick(RoundedCornerShape(14.dp), onClick)
            .background(C.Surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app.icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = t(16), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (app.suggested) Text("ينصح بحذفه", style = t(12, color = C.Text3))
        }
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = C.Live,
                checkedThumbColor = C.Ink,
                uncheckedTrackColor = C.Surface3,
                uncheckedThumbColor = C.Text2,
                uncheckedBorderColor = C.Surface3
            )
        )
    }
}

@Composable
private fun RestoreRow(app: ExtraApp, onRestore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app.icon)
        Spacer(Modifier.width(12.dp))
        Text(app.label, style = t(16), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Pill("رجّعه", false, onRestore)
    }
}
