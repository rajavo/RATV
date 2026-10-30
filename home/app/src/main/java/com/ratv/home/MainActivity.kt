package com.ratv.home

import android.content.Intent
import android.icu.util.IslamicCalendar
import android.icu.util.ULocale
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {

    /** Bumped every time the screen comes back, so the apps and the city are read again. */
    private val resumed = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HomeScreen(resumed.intValue) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                resumed.intValue++
                if (withContext(Dispatchers.IO) { Place.findFromNetwork(this@MainActivity) }) resumed.intValue++
            }
        }
    }
}

// ------------------------------------------------------------------ look

private object C {
    val Ink = Color(0xFF0E1222)
    val Text = Color(0xFFF5F5F7)
    val Text2 = Color(0xB3F5F5F7)
    val Text3 = Color(0x80F5F5F7)
    val Glass = Color(0x1FFFFFFF)
    val GlassLine = Color(0x26FFFFFF)
    val Gold = Color(0xFFFFB340)
}

private val Tajawal = FontFamily(Font(R.font.tajawal_regular, FontWeight.Normal), Font(R.font.tajawal_bold, FontWeight.Bold))

private fun t(size: Int, bold: Boolean = false, color: Color = C.Text) = TextStyle(
    fontFamily = Tajawal,
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    color = color,
    lineHeight = (size * 1.35).sp
)

/** Selected with the remote the way Apple TV does it: the item grows and lifts, with a soft shadow. */
@Composable
private fun Modifier.lift(shape: Shape, scale: Float = 1.1f, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val s by animateFloatAsState(if (focused) scale else 1f, tween(160), label = "lift")
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = s
            scaleY = s
        }
        .shadow(if (focused) 24.dp else 0.dp, shape, clip = false)
        .clip(shape)
        .border(BorderStroke(if (focused) 2.dp else 0.dp, if (focused) Color.White.copy(alpha = 0.85f) else Color.Transparent), shape)
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

private fun clock(minutes: Int): String {
    val h = minutes / 60 % 24
    val m = minutes % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return String.format(Locale.US, "%d:%02d", h12, m) + if (h < 12) " ص" else " م"
}

private fun left(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 && m == 0 -> "الحين"
        h == 0 -> "بعد $m دقيقة"
        m == 0 -> "بعد $h ساعة"
        else -> "بعد $h ساعة و$m دقيقة"
    }
}

private class Today(
    val now: Int,
    val times: Map<Salah, Int>,
    val next: Salah,
    val untilNext: Int,
    val date: String,
    val hijri: String
)

private fun today(lat: Double, lng: Double): Today {
    val cal = Calendar.getInstance()
    val tz = TimeZone.getDefault()
    val offset = tz.getOffset(cal.timeInMillis) / 3_600_000.0
    val hijriCal = IslamicCalendar(ULocale("ar@calendar=islamic-umalqura")).apply {
        calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
        timeInMillis = cal.timeInMillis
    }
    val ramadan = hijriCal.get(android.icu.util.Calendar.MONTH) == IslamicCalendar.RAMADAN
    val y = cal.get(Calendar.YEAR)
    val mo = cal.get(Calendar.MONTH) + 1
    val d = cal.get(Calendar.DAY_OF_MONTH)
    val times = Prayer.times(y, mo, d, lat, lng, offset, ramadan)
    val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val upcoming = times.entries.filter { it.key != Salah.SUNRISE }.firstOrNull { it.value > now }
    val next: Salah
    val until: Int
    if (upcoming != null) {
        next = upcoming.key
        until = upcoming.value - now
    } else { // after isha: tomorrow's fajr
        val tomorrow = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        val fajr = Prayer.times(
            tomorrow.get(Calendar.YEAR), tomorrow.get(Calendar.MONTH) + 1, tomorrow.get(Calendar.DAY_OF_MONTH),
            lat, lng, offset, ramadan
        ).getValue(Salah.FAJR)
        next = Salah.FAJR
        until = 24 * 60 - now + fajr
    }
    val date = android.icu.text.SimpleDateFormat("EEEE، d MMMM", ULocale("ar")).format(cal.time)
    val hijri = android.icu.text.SimpleDateFormat("d MMMM y هـ", ULocale("ar@calendar=islamic-umalqura")).format(cal.time)
    return Today(now, times, next, until, date, hijri)
}

// ---------------------------------------------------------------- screen

@Composable
private fun HomeScreen(resumed: Int) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var cityVersion by remember { mutableIntStateOf(0) }
    val city = remember(resumed, cityVersion) { Place.current(context) }
    val day by produceState(today(city.lat, city.lng), city) {
        while (true) {
            value = today(city.lat, city.lng)
            delay(15_000)
        }
    }
    val apps by produceState<List<Tile>?>(null, resumed) {
        value = withContext(Dispatchers.IO) { Apps.load(context) }
    }
    val firstTile = remember { FocusRequester() }
    LaunchedEffect(apps != null, picking) {
        if (apps != null && !picking) {
            repeat(5) {
                delay(120)
                runCatching { firstTile.requestFocus() }
            }
        }
    }
    BackHandler(enabled = picking) { picking = false }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize().background(C.Ink)) {
            Wallpaper()
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 64.dp, vertical = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalArrangement = Arrangement.spacedBy(34.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "top") { TopBar(day, city.name) }
                item(span = { GridItemSpan(maxLineSpan) }, key = "shelf") { PrayerShelf(day) }
                val list = apps
                if (list == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text("جارٍ تحميل التطبيقات…", style = t(18, color = C.Text2)) }
                } else {
                    items(list, key = { it.pkg }) { tile ->
                        val first = tile == list.firstOrNull()
                        AppTile(tile, if (first) Modifier.focusRequester(firstTile) else Modifier) { Apps.open(context, tile.pkg) }
                    }
                    item(key = "city") {
                        ActionTile("المدينة", city.name) { picking = true }
                    }
                    item(key = "settings") {
                        ActionTile("الإعدادات", "إعدادات الشاشة") {
                            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                }
            }
            if (picking) {
                CityPicker(
                    current = city.name,
                    auto = !Place.picked(context),
                    onPick = { c ->
                        Place.pick(context, c)
                        picking = false
                        cityVersion++
                    },
                    onAuto = {
                        Place.pick(context, null)
                        picking = false
                        cityVersion++
                    }
                )
            }
        }
    }
}

/** A calm night sky in blues, like Apple TV's dark wallpapers. */
@Composable
private fun Wallpaper() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF121A36), Color(0xFF0B0E1C))))
        drawCircle(
            Brush.radialGradient(listOf(Color(0x553A5BD9), Color.Transparent), center = Offset(size.width * 0.15f, size.height * 0.1f), radius = size.width * 0.55f),
            radius = size.width * 0.55f, center = Offset(size.width * 0.15f, size.height * 0.1f)
        )
        drawCircle(
            Brush.radialGradient(listOf(Color(0x40FF9F1C), Color.Transparent), center = Offset(size.width * 0.9f, size.height * 0.95f), radius = size.width * 0.45f),
            radius = size.width * 0.45f, center = Offset(size.width * 0.9f, size.height * 0.95f)
        )
    }
}

@Composable
private fun TopBar(day: Today, city: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(day.date, style = t(22, true))
            Text(day.hijri + " · " + city, style = t(17, color = C.Text2))
        }
        Text(clock(day.now), style = t(40, true))
    }
}

@Composable
private fun PrayerShelf(day: Today) {
    val shape = RoundedCornerShape(30.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(C.Glass)
            .border(BorderStroke(1.dp, C.GlassLine), shape)
            .padding(horizontal = 36.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("الصلاة القادمة", style = t(17, color = C.Text2))
                Text(day.next.ar, style = t(46, true))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(left(day.untilNext), style = t(26, true, C.Gold))
                Text("الساعة " + clock(day.times.getValue(day.next)), style = t(17, color = C.Text2))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (s in Salah.entries) {
                val isNext = s == day.next
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (isNext) Color.White else Color(0x14FFFFFF))
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(s.ar, style = t(17, isNext, if (isNext) C.Ink else C.Text2))
                    Text(clock(day.times.getValue(s)), style = t(22, true, if (isNext) C.Ink else C.Text))
                }
            }
        }
    }
}

@Composable
private fun AppTile(tile: Tile, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .lift(shape, onClick = onClick)
                .background(Color(0xFF1E2440)),
            contentAlignment = Alignment.Center
        ) {
            val banner = tile.banner
            val icon = tile.icon
            when {
                banner != null -> Image(banner, tile.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                icon != null -> Image(icon, tile.label, Modifier.size(64.dp))
                else -> Text(tile.label, style = t(18, true), textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(tile.label, style = t(15, color = C.Text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionTile(title: String, subtitle: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .lift(shape, onClick = onClick)
                .background(C.Glass)
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = t(20, true))
            Text(subtitle, style = t(14, color = C.Text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = t(15, color = C.Text2))
    }
}

@Composable
private fun CityPicker(current: String, auto: Boolean, onPick: (City) -> Unit, onAuto: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(5) {
            delay(120)
            runCatching { first.requestFocus() }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC05070F)),
        contentAlignment = Alignment.Center
    ) {
        val shape = RoundedCornerShape(30.dp)
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(vertical = 40.dp)
                .clip(shape)
                .background(Color(0xFF1A2038))
                .border(BorderStroke(1.dp, C.GlassLine), shape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("مدينة مواقيت الصلاة", style = t(24, true))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    PickRow("تلقائي (حسب شبكة البيت)", auto, Modifier.focusRequester(first), onAuto)
                }
                items(Place.CITIES, key = { it.name }) { c ->
                    PickRow(c.name, !auto && c.name == current, Modifier) { onPick(c) }
                }
            }
        }
    }
}

@Composable
private fun PickRow(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .lift(RoundedCornerShape(14.dp), scale = 1.03f, onClick = onClick)
            .background(if (on) Color(0x33FFFFFF) else Color(0x14FFFFFF))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = t(18, on), modifier = Modifier.weight(1f))
        if (on) Text("✓", style = t(18, true, C.Gold))
    }
}
