package com.ratv.net

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            turnOn()
        } else {
            Focus.message.value = "لازم توافق على طلب الاتصال (VPN) عشان يشتغل التركيز."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContent {
            var lite by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = lite) { lite = false }
            if (lite) LiteScreen(prefs) { lite = false }
            else MainScreen(prefs, ::toggle, ::openUsageAccess, ::openAccessibility) { lite = true }
        }
    }

    private fun toggle() {
        if (Focus.running.value) {
            FocusVpnService.stop(this)
            return
        }
        if (!Detect.ready(this)) {
            Focus.message.value = "قبل التشغيل: اسمح لـ RA Net يعرف التطبيق اللي على الشاشة (الخطوة تحت)."
            return
        }
        val ask = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            null
        }
        if (ask == null) {
            turnOn()
            return
        }
        try {
            consent.launch(ask)
        } catch (e: ActivityNotFoundException) {
            Focus.message.value = "هذا الجهاز ما يسمح بخاصية VPN، فما يقدر التركيز يشتغل عليه."
        }
    }

    private fun turnOn() {
        prefs.enabled = true
        Focus.message.value = null
        FocusVpnService.start(this)
    }

    private fun openUsageAccess() {
        if (open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))) return
        if (open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))) return
        Focus.message.value = "صفحة «إذن الاستخدام» مو موجودة في هذا التلفزيون، استخدم «إمكانية الوصول»."
        openAccessibility()
    }

    private fun openAccessibility() {
        if (!open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) open(Intent(Settings.ACTION_SETTINGS))
    }

    private fun open(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}

// ------------------------------------------------------------------ look

internal object C {
    val Ink = Color(0xFF0B0C10)
    val Surface = Color(0xFF14161C)
    val Surface3 = Color(0xFF262A34)
    val Text = Color(0xFFF4F2EE)
    val Text2 = Color(0xFFB4B0A8)
    val Text3 = Color(0xFF7D7A73)
    val Saffron = Color(0xFFFF9F1C)
    val OnSaffron = Color(0xFF1A1205)
    val Live = Color(0xFFFF6B76)
    val Success = Color(0xFF5BE29A)
    val SuccessBg = Color(0xFF10261A)
}

internal val Tajawal = FontFamily(Font(R.font.tajawal_regular, FontWeight.Normal), Font(R.font.tajawal_bold, FontWeight.Bold))

internal fun t(size: Int, bold: Boolean = false, color: Color = C.Text) = TextStyle(
    fontFamily = Tajawal,
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    color = color,
    lineHeight = (size * 1.45).sp
)

/** Clickable with the remote: a saffron ring shows the selected item. */
@Composable
internal fun Modifier.tvClick(shape: Shape, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    return this
        .clip(shape)
        .border(BorderStroke(3.dp, if (focused) C.Saffron else Color.Transparent), shape)
        .clickable(interactionSource = source, indication = ripple(), onClick = onClick)
}

/** A row to read: the remote can select it (to scroll to it), pressing does nothing. */
@Composable
internal fun Modifier.tvRow(shape: Shape): Modifier {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    return this
        .clip(shape)
        .border(BorderStroke(3.dp, if (focused) C.Saffron else Color.Transparent), shape)
        .focusable(interactionSource = source)
}

private fun mbps(v: Double) = String.format(Locale.US, "%.1f ميجابت", v)

private class UsageLine(val label: String, val icon: ImageBitmap?, val mbps: Double, val onScreen: Boolean)

// ---------------------------------------------------------------- screen

@Composable
private fun MainScreen(prefs: Prefs, onToggle: () -> Unit, onUsage: () -> Unit, onAccessibility: () -> Unit, onLite: () -> Unit) {
    val context = LocalContext.current
    val tv = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    val running by Focus.running.collectAsState()
    val fg by Focus.foreground.collectAsState()
    val allowedNow by Focus.allowed.collectAsState()
    val blocked by Focus.blocked.collectAsState()
    val message by Focus.message.collectAsState()
    val ready by produceState(Detect.ready(context)) {
        while (true) {
            value = Detect.ready(context)
            delay(1000)
        }
    }
    val speed by produceState<Double?>(null) {
        var lastBytes = TrafficStats.getTotalRxBytes()
        var lastTime = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            val bytes = TrafficStats.getTotalRxBytes()
            val time = SystemClock.elapsedRealtime()
            value = if (bytes < 0 || lastBytes < 0 || time <= lastTime) null
            else (bytes - lastBytes) * 8.0 / ((time - lastTime) / 1000.0) / 1_000_000.0
            lastBytes = bytes
            lastTime = time
        }
    }
    val usageOk by produceState(Detect.usageGranted(context)) {
        while (true) {
            value = Detect.usageGranted(context)
            delay(2000)
        }
    }
    val usage by produceState<List<UsageMeter.AppSpeed>?>(null, usageOk) {
        if (!usageOk) {
            value = null
            return@produceState
        }
        val meter = UsageMeter(context)
        withContext(Dispatchers.IO) { meter.sample() }
        while (true) {
            delay(meter.interval)
            value = withContext(Dispatchers.IO) { meter.sample() } ?: emptyList()
        }
    }
    val usageLines = remember(usage, fg) {
        usage.orEmpty().take(8).map { s ->
            val pkg = Apps.packageForUid(context, s.uid)
            val label = when (s.uid) {
                -5 -> "مشاركة النت من التلفزيون"
                -4 -> "تطبيقات محذوفة"
                0 -> "النظام"
                else -> Apps.labelForUid(context, s.uid)
            }
            UsageLine(label, pkg?.let { Apps.icon(context, it) }, s.downMbps + s.upMbps, pkg != null && pkg == fg)
        }
    }
    val apps by produceState<List<AppInfo>?>(null) {
        value = withContext(Dispatchers.IO) { Apps.forSettings(context) }
    }
    var always by remember { mutableStateOf(prefs.alwaysAllowed) }
    val onScreen = fg?.let { if (it == context.packageName) "RA Net (هذي الشاشة)" else Apps.label(context, it) }
    val withInternet = remember(allowedNow) {
        allowedNow.filter { it != Prefs.SYSTEM && it != context.packageName && it != "com.google.android.gsf" }
            .mapNotNull { pkg -> Apps.label(context, pkg).takeIf { it != pkg } } // installed ones only
    }
    val topBlocked = remember(blocked) {
        blocked.entries.sortedByDescending { it.value }.take(8).map { (uid, count) ->
            Triple(Apps.labelForUid(context, uid), Apps.packageForUid(context, uid)?.let { Apps.icon(context, it) }, count)
        }
    }
    val toggleFocus = remember { FocusRequester() }
    if (tv) {
        LaunchedEffect(Unit) {
            repeat(6) {
                delay(150)
                runCatching { toggleFocus.requestFocus() }
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
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Header() }
                message?.let { m -> item { Notice(m) } }
                item { ToggleCard(running, onScreen, Modifier.focusRequester(toggleFocus), onToggle) }
                if (!ready) item { SetupCard(onUsage, onAccessibility) }
                item { LiteCard(onLite) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionTitle("استهلاك النت الحين")
                        Text(
                            "التلفزيون كله: " + (speed?.let { mbps(it) } ?: "—") + " · وكل تطبيق في آخر ١٥ ثانية تقريباً",
                            style = t(14, color = C.Text2)
                        )
                    }
                }
                val live = usage
                when {
                    !usageOk -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("عشان يبان استهلاك كل تطبيق، فعّل «إذن الاستخدام» لـ RA Net.", style = t(14, color = C.Text2))
                            Pill("إذن الاستخدام", true, onUsage)
                        }
                    }
                    live == null -> item { Text("أول قراءة بعد ١٥ ثانية تقريباً…", style = t(14, color = C.Text2)) }
                    live.isEmpty() -> item { Text("ما فيه تطبيق يستخدم النت الحين.", style = t(14, color = C.Text2)) }
                    else -> items(usageLines) { line -> UsageRow(line) }
                }
                if (running) {
                    item { StatsRow(speed, blocked) }
                    if (withInternet.isNotEmpty()) {
                        item { Text("معه النت الحين: " + withInternet.joinToString("، "), style = t(14, color = C.Text2)) }
                    }
                }
                if (topBlocked.isNotEmpty()) {
                    item { SectionTitle("حاولت تستخدم النت وانحجبت") }
                    items(topBlocked) { (label, icon, count) -> BlockedRow(label, icon, count) }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionTitle("مسموح لها النت دائماً")
                        Text(
                            "حتى لو كانت بالخلفية. الأفضل تخليها قليلة: كل تطبيق هنا يشارك التطبيق اللي على الشاشة في النت.",
                            style = t(14, color = C.Text2)
                        )
                    }
                }
                val list = apps
                if (list == null) {
                    item { Text("جارٍ تحميل التطبيقات…", style = t(15, color = C.Text2)) }
                } else {
                    items(list, key = { it.pkg }) { app ->
                        AppRow(app, app.pkg in always) {
                            always = if (app.pkg in always) always - app.pkg else always + app.pkg
                            prefs.alwaysAllowed = always
                        }
                    }
                }
                item { Footer() }
            }
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text("RA Net", style = t(28, true))
            Text("النت كله للتطبيق اللي على الشاشة", style = t(15, color = C.Text2))
        }
    }
}

@Composable
internal fun Notice(text: String) {
    Text(
        text,
        style = t(15, true, C.Text),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x40D92B3A))
            .padding(horizontal = 18.dp, vertical = 12.dp)
    )
}

@Composable
private fun ToggleCard(running: Boolean, onScreen: String?, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier
            .fillMaxWidth()
            .tvClick(shape, onClick)
            .background(if (running) C.SuccessBg else C.Surface)
            .padding(22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (running) C.Success else C.Surface3),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painterResource(R.drawable.ic_stat),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                colorFilter = ColorFilter.tint(if (running) C.Ink else C.Text2)
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (running) "التركيز شغّال" else "التركيز طافي", style = t(22, true, if (running) C.Success else C.Text))
            Text(
                if (running) "النت الحين لـ: " + (onScreen ?: "…")
                else "شغّله، وكل النت يروح للتطبيق اللي تفتحه، والباقي ينقطع عنه.",
                style = t(15, color = C.Text2)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (running) "إيقاف" else "تشغيل",
            style = t(16, true, if (running) C.Text else C.OnSaffron),
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (running) C.Surface3 else C.Saffron)
                .padding(horizontal = 22.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun SetupCard(onUsage: () -> Unit, onAccessibility: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(C.Surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("خطوة وحدة قبل التشغيل", style = t(18, true, C.Saffron))
        Text(
            "اسمح لـ RA Net يعرف وش التطبيق اللي على الشاشة، عشان يعطيه النت. افتح الإعدادات، اختر RA Net وفعّله، وارجع هنا.",
            style = t(15, color = C.Text2)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("إذن الاستخدام", true, onUsage)
            Pill("أو: إمكانية الوصول", false, onAccessibility)
        }
        Text(
            "إذا ما لقيت RA Net في «إذن الاستخدام»، فعّله من «إمكانية الوصول». أي واحد منهم يكفي.",
            style = t(13, color = C.Text3)
        )
    }
}

@Composable
private fun LiteCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .tvClick(shape, onClick)
            .background(C.Surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("خفّف الشاشة", style = t(18, true, C.Saffron))
            Text("حركة أسرع، ذاكرة أفضى، وحذف تطبيقات المصنع اللي ما تحتاجها.", style = t(14, color = C.Text2))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "افتح",
            style = t(16, true, C.OnSaffron),
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(C.Saffron)
                .padding(horizontal = 22.dp, vertical = 10.dp)
        )
    }
}

@Composable
internal fun Pill(label: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = t(15, true, if (primary) C.OnSaffron else C.Text),
        modifier = Modifier
            .tvClick(RoundedCornerShape(14.dp), onClick)
            .background(if (primary) C.Saffron else C.Surface3)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    )
}

@Composable
private fun StatsRow(speed: Double?, blocked: Map<Int, Int>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat("سرعة التحميل الحين", speed?.let { mbps(it) } ?: "—", Modifier.weight(1f))
        Stat("تطبيقات انحجبت", blocked.size.toString(), Modifier.weight(1f))
        Stat("محاولات انحجبت", blocked.values.sum().toString(), Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(C.Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(value, style = t(20, true))
        Text(label, style = t(13, color = C.Text2))
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = t(18, true), modifier = Modifier.padding(top = 10.dp))
}

@Composable
internal fun AppIcon(icon: ImageBitmap?) {
    val m = Modifier
        .size(36.dp)
        .clip(RoundedCornerShape(9.dp))
    if (icon != null) Image(icon, contentDescription = null, modifier = m) else Box(m.background(C.Surface3))
}

@Composable
private fun UsageRow(line: UsageLine) {
    Row(
        Modifier
            .fillMaxWidth()
            .tvRow(RoundedCornerShape(14.dp))
            .background(C.Surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(line.icon)
        Spacer(Modifier.width(12.dp))
        Text(line.label, style = t(16), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (line.onScreen) {
            Text(
                "على الشاشة",
                style = t(12, true, C.Success),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(C.SuccessBg)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(mbps(line.mbps), style = t(15, true, C.Saffron))
    }
}

@Composable
private fun BlockedRow(label: String, icon: ImageBitmap?, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .tvRow(RoundedCornerShape(14.dp))
            .background(C.Surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(icon)
        Spacer(Modifier.width(12.dp))
        Text(label, style = t(16), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count محاولة", style = t(14, true, C.Live))
    }
}

@Composable
private fun AppRow(app: AppInfo, on: Boolean, onClick: () -> Unit) {
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
        Text(app.label, style = t(16), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = C.Saffron,
                checkedThumbColor = C.OnSaffron,
                uncheckedTrackColor = C.Surface3,
                uncheckedThumbColor = C.Text2,
                uncheckedBorderColor = C.Surface3
            )
        )
    }
}

@Composable
private fun Footer() {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("وش يسوي بالضبط؟", style = t(16, true))
        Text(
            "• التطبيق اللي على الشاشة ياخذ النت كله، والتطبيقات الثانية ينقطع عنها النت لين تفتحها. " +
                "التطبيق اللي طلعت منه يكمل معه النت دقيقة، عشان لو رحت للإعدادات ورجعت ما ينقطع عليك.",
            style = t(14, color = C.Text2)
        )
        Text("• ما يزيد سرعة الخط نفسه، ولا يتحكم في الجوالات والأجهزة الثانية على الراوتر.", style = t(14, color = C.Text2))
        Text(
            "• يمنع الإنترنت بس: الشبكة اللي في البيت (ريموت الجوال، الكاست، السيرفرات المحلية) تبقى شغّالة لكل التطبيقات.",
            style = t(14, color = C.Text2)
        )
        Text(
            "• يستخدم خاصية VPN داخلية في أندرويد: النت ما يمر على أي سيرفر، وRA Net ما يقرأ بياناتك.",
            style = t(14, color = C.Text2)
        )
    }
}
