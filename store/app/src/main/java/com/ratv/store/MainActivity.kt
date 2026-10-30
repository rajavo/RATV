package com.ratv.store

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** Bumped every time the screen comes back (after an install, say), so everything is read again. */
    private val resumed = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StoreScreen(resumed.intValue) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { resumed.intValue++ }
        }
    }
}

/** Downloads and installs keep going while the TV's installer is on the screen. */
private object Work {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Package → what is happening to it now ("٤٥٪", "يثبّت…"); absent when nothing is. */
    val doing = MutableStateFlow<Map<String, String>>(emptyMap())
    val message = MutableStateFlow<String?>(null)

    private fun set(pkg: String, what: String?) {
        doing.value = if (what == null) doing.value - pkg else doing.value + (pkg to what)
    }

    fun get(context: Context, releases: List<Release>) {
        val app = context.applicationContext
        if (releases.any { it.pkg in doing.value }) return
        scope.launch {
            for (r in releases) {
                val before = Store.installed(app, r.pkg)
                try {
                    set(r.pkg, "0٪")
                    val file = Store.download(app, r) { p -> set(r.pkg, "$p٪") }
                    set(r.pkg, "يثبّت…")
                    withContext(Dispatchers.Main) { Store.install(app, file) }
                    // One install at a time: wait for this one before the next (or give up after three minutes).
                    if (releases.size > 1) {
                        for (k in 0 until 90) {
                            delay(2000)
                            val now = Store.installed(app, r.pkg)
                            if (now != null && (before == null || now.updatedAt != before.updatedAt)) break
                        }
                    }
                } catch (e: Exception) {
                    message.value = "ما قدرت أنزّل «${r.title}». تأكد من النت وجرّب مرة ثانية."
                } finally {
                    set(r.pkg, null)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ look

private object C {
    val Ink = Color(0xFF0E1222)
    val Surface = Color(0xFF181D33)
    val Surface3 = Color(0xFF262C48)
    val Text = Color(0xFFF5F5F7)
    val Text2 = Color(0xB3F5F5F7)
    val Text3 = Color(0x80F5F5F7)
    val Gold = Color(0xFFFFB340)
    val OnGold = Color(0xFF1A1205)
    val Green = Color(0xFF5BE29A)
}

private val Tajawal = FontFamily(Font(R.font.tajawal_regular, FontWeight.Normal), Font(R.font.tajawal_bold, FontWeight.Bold))

private fun t(size: Int, bold: Boolean = false, color: Color = C.Text) = TextStyle(
    fontFamily = Tajawal,
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    color = color,
    lineHeight = (size * 1.4).sp
)

/** Clickable with the remote: a white edge shows the selected item. */
@Composable
private fun Modifier.tvClick(shape: Shape, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    return this
        .clip(shape)
        .border(BorderStroke(3.dp, if (focused) Color.White else Color.Transparent), shape)
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

@Composable
private fun Pill(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        style = t(16, true, if (primary) C.OnGold else C.Text),
        modifier = modifier
            .tvClick(RoundedCornerShape(14.dp), onClick)
            .background(if (primary) C.Gold else C.Surface3)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    )
}

// ---------------------------------------------------------------- screen

private class Entry(val release: Release, val installed: Installed?) {
    val update get() = Store.hasUpdate(release, installed)
}

@Composable
private fun StoreScreen(resumed: Int) {
    val context = LocalContext.current
    val doing by Work.doing.collectAsState()
    val message by Work.message.collectAsState()
    val catalog by produceState<Result<List<Release>>?>(null, resumed) {
        value = withContext(Dispatchers.IO) { runCatching { Catalog.load() } }
    }
    val rows = remember(catalog, resumed, doing.isEmpty()) {
        catalog?.getOrNull()?.map { Entry(it, Store.installed(context, it.pkg)) }
    }
    val mayInstall = remember(resumed) { Store.mayInstall(context) }
    val updates = rows.orEmpty().filter { it.update }
    val first = remember { FocusRequester() }
    LaunchedEffect(rows != null) {
        repeat(5) {
            delay(120)
            runCatching { first.requestFocus() }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize().background(C.Ink), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 820.dp).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("RA Store", style = t(28, true))
                            Text("كل تطبيقات RA في مكان واحد: ثبّت وحدّث من هنا.", style = t(15, color = C.Text2))
                        }
                    }
                }
                message?.let { m ->
                    item { Text(m, style = t(15, true), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x40D92B3A)).padding(14.dp)) }
                }
                if (!mayInstall) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("خطوة وحدة أول مرة", style = t(18, true, C.Gold))
                            Text("اسمح لـ RA Store يثبّت التطبيقات: افتح الإعدادات، فعّل RA Store، وارجع هنا.", style = t(15, color = C.Text2))
                            Pill("اسمح بالتثبيت", true) { Store.askToInstall(context) }
                        }
                    }
                }
                val list = rows
                when {
                    catalog == null -> item { Text("أجيب قائمة التطبيقات…", style = t(16, color = C.Text2)) }
                    list == null -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("ما قدرت أجيب القائمة. تأكد من النت.", style = t(16, color = C.Text2))
                            Pill("جرّب مرة ثانية", true, Modifier.focusRequester(first)) { (context as? MainActivity)?.recreate() }
                        }
                    }
                    else -> {
                        if (updates.isNotEmpty()) {
                            item {
                                Pill("حدّث الكل (${updates.size})", true, Modifier.focusRequester(first)) {
                                    if (Store.mayInstall(context)) Work.get(context, updates.map { it.release }) else Store.askToInstall(context)
                                }
                            }
                        }
                        items(list, key = { it.release.pkg }) { row ->
                            val isFirst = updates.isEmpty() && row == list.first()
                            AppRow(row, doing[row.release.pkg], if (isFirst) Modifier.focusRequester(first) else Modifier) {
                                when {
                                    row.installed != null && !row.update -> Store.open(context, row.release.pkg)
                                    !Store.mayInstall(context) -> Store.askToInstall(context)
                                    else -> Work.get(context, listOf(row.release))
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "أي تطبيق RA جديد ينزل يطلع هنا لحاله. التحديث ينزل فوق القديم وما تضيع إعداداتك.",
                        style = t(13, color = C.Text3),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(row: Entry, doing: String?, buttonModifier: Modifier, onAction: () -> Unit) {
    val r = row.release
    val i = row.installed
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(i?.icon, r.title)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.title, style = t(19, true), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val latest = r.versionName?.let { " $it" }.orEmpty()
            Text(
                when {
                    i == null -> "مو مثبّت" + if (latest.isNotEmpty()) " · آخر نسخة$latest" else ""
                    row.update -> "فيه تحديث" + latest + " · عندك " + (i.versionName ?: "")
                    else -> "مثبّت ومحدّث" + (i.versionName?.let { " · $it" } ?: "")
                },
                style = t(14, color = if (row.update) C.Gold else if (i != null) C.Green else C.Text2)
            )
            if (r.about.isNotBlank()) Text(r.about, style = t(13, color = C.Text3), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        if (doing != null) {
            Text(doing, style = t(16, true, C.Gold), modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        } else {
            Pill(
                when {
                    i == null -> "ثبّت"
                    row.update -> "حدّث"
                    else -> "افتح"
                },
                i == null || row.update,
                buttonModifier,
                onAction
            )
        }
    }
}

@Composable
private fun AppIcon(icon: ImageBitmap?, title: String) {
    val m = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
    if (icon != null) {
        Image(icon, null, m)
    } else {
        Box(m.background(C.Surface3), contentAlignment = Alignment.Center) {
            Text(title.filter { it.isLetter() }.take(2).uppercase(), style = t(16, true, C.Gold))
        }
    }
}
