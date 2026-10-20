package com.ratv.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ratv.app.ui.theme.Alexandria
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ----------------------------------------------------------------- logo

/** The "A" of RATV drawn as a rounded triangle with a play cut-out. */
@Composable
fun LogoMark(modifier: Modifier = Modifier, triangle: Color = RColors.Saffron, cutout: Color = RColors.Ink) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val tri = Path().apply {
            moveTo(13.03f * s, 4.94f * s)
            lineTo(19.97f * s, 18.06f * s)
            quadraticBezierTo(21f * s, 20f * s, 18.8f * s, 20f * s)
            lineTo(5.2f * s, 20f * s)
            quadraticBezierTo(3f * s, 20f * s, 4.03f * s, 18.06f * s)
            lineTo(10.97f * s, 4.94f * s)
            quadraticBezierTo(12f * s, 3f * s, 13.03f * s, 4.94f * s)
            close()
        }
        drawPath(tri, triangle)
        val play = Path().apply {
            moveTo(10.5f * s, 11.8f * s)
            lineTo(14.9f * s, 14.6f * s)
            lineTo(10.5f * s, 17.4f * s)
            close()
        }
        drawPath(play, cutout)
    }
}

@Composable
fun RatvLogo(fontSize: TextUnit = 24.sp, modifier: Modifier = Modifier) {
    val markSize = with(LocalDensity.current) { fontSize.toDp() }
    val style = T.display(fontSize, FontWeight.ExtraBold)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text("R", style = style)
            LogoMark(Modifier.size(markSize))
            Text("TV", style = style)
        }
    }
}

@Composable
fun AppIcon(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.29f))
            .background(RColors.Saffron),
        contentAlignment = Alignment.Center
    ) {
        LogoMark(Modifier.size(size * 0.62f), triangle = RColors.Ink, cutout = RColors.Saffron)
    }
}

// -------------------------------------------------------------- buttons

@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = RColors.Surface,
    tint: Color = RColors.Text,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 54.dp
) {
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) RColors.Saffron else RColors.Surface3)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (enabled) RColors.OnSaffron else RColors.Text3, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text,
            style = T.body(16.sp, FontWeight.Bold, if (enabled) RColors.OnSaffron else RColors.Text3),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 48.dp
) {
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, RColors.Surface3, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = RColors.Text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = T.body(14.sp, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A pill chip with a 44dp tall touch target. */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(44.dp)
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(if (selected) RColors.Saffron else RColors.Surface)
                .border(1.dp, if (selected) RColors.Saffron else RColors.Line, RoundedCornerShape(17.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = T.body(14.sp, if (selected) FontWeight.Bold else FontWeight.Medium, if (selected) RColors.OnSaffron else RColors.Text2),
                maxLines = 1
            )
        }
    }
}

// --------------------------------------------------------------- badges

@Composable
fun QualityBadge(text: String, modifier: Modifier = Modifier, color: Color = RColors.Text2) {
    Box(
        modifier
            .border(1.dp, RColors.LineStrong, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(text, style = T.body(10.sp, FontWeight.Bold, color).copy(lineHeight = 12.sp))
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(RColors.Live)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        Text("مباشر", style = T.body(11.sp, FontWeight.Bold, Color.White).copy(lineHeight = 14.sp))
    }
}

@Composable
fun ProgressLine(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
    track: Color = RColors.Surface3,
    fill: Color = RColors.Saffron
) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height))
            .background(track)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(fill)
        )
    }
}

// --------------------------------------------------------------- images

private val monogramColors = listOf(
    Color(0xFF1F6F5C), Color(0xFF2B4C8C), Color(0xFF5A3D8A), Color(0xFF8C3B2B),
    Color(0xFF8C2B5A), Color(0xFF2B7A8C), Color(0xFF6B5A2B), Color(0xFF7A2B2B), Color(0xFF8C6A1F)
)

fun monogramOf(name: String): String {
    val words = name.trim().split(' ').filter { w -> w.isNotEmpty() && w.any { it.isLetterOrDigit() } }
    if (words.isEmpty()) return "TV"
    val first = words[0].first { it.isLetterOrDigit() }.toString()
    val digit = words.drop(1).firstOrNull { w -> w.all { it.isDigit() } }
    return if (digit != null) first + digit.take(1) else first
}

/** Channel logo from the provider, falling back to a coloured monogram. */
@Composable
fun ChannelLogo(name: String, url: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    var state by remember(url) { mutableIntStateOf(if (url.isNullOrBlank()) 2 else 0) }
    val color = monogramColors[artSeed(name) % monogramColors.size]
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(if (state == 2) color else RColors.Surface2),
        contentAlignment = Alignment.Center
    ) {
        if (state == 2) {
            Text(
                monogramOf(name),
                style = T.display((size.value * 0.32f).sp, FontWeight.ExtraBold, Color.White),
                maxLines = 1
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(size * 0.1f),
                onSuccess = { state = 1 },
                onError = { state = 2 }
            )
        }
    }
}

/** Poster or still image with generated art underneath until (or unless) it loads. */
@Composable
fun PosterImage(
    title: String,
    url: String?,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    titleSize: TextUnit = 14.sp
) {
    var loaded by remember(url) { mutableIntStateOf(0) }
    Box(modifier) {
        if (loaded != 1) {
            PosterArt(artSeed(title), Modifier.fillMaxSize())
            if (showTitle) {
                Text(
                    title,
                    style = T.display(titleSize, FontWeight.ExtraBold, Color(0xFFF7EFE2)),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                )
            }
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { loaded = 1 },
                onError = { loaded = 2 }
            )
        }
    }
}

// --------------------------------------------------------------- layout

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = T.h2)
        if (action != null && onAction != null) {
            Row(
                Modifier
                    .height(44.dp)
                    .clickable(role = Role.Button, onClick = onAction),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(action, style = T.body(13.sp, FontWeight.Medium, RColors.Saffron))
                Icon(RIcons.ChevronLeft, contentDescription = null, tint = RColors.Saffron, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    horizontalPadding: Dp = 20.dp,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = horizontalPadding, end = horizontalPadding, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (onBack != null) {
            CircleIconButton(RIcons.Back, "رجوع", onBack)
        }
        Text(title, style = if (onBack != null) T.display(22.sp) else T.h1, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(RColors.Surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = RColors.Text2, modifier = Modifier.size(26.dp))
        }
        Text(title, style = T.title, textAlign = TextAlign.Center)
        Text(message, style = T.body(13.sp, color = RColors.Text3), textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(6.dp))
            action()
        }
    }
}

// ------------------------------------------------------------ bottom nav

data class NavItem(val route: String, val label: String, val icon: ImageVector)

val navItems = listOf(
    NavItem("home", "الرئيسية", RIcons.Home),
    NavItem("live", "مباشر", RIcons.Tv),
    NavItem("movies", "أفلام", RIcons.Film),
    NavItem("series", "مسلسلات", RIcons.Layers),
    NavItem("settings", "حسابي", RIcons.User)
)

@Composable
fun BottomNav(current: String, onSelect: (String) -> Unit) {
    Column(Modifier.background(RColors.NavBg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(RColors.Line))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(72.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navItems.forEach { item ->
                val selected = item.route == current
                val color = if (selected) RColors.Saffron else RColors.Text3
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(role = Role.Tab) { onSelect(item.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .width(56.dp)
                            .height(30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (selected) RColors.SaffronSoft else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(item.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.label,
                        style = T.body(11.5.sp, if (selected) FontWeight.Bold else FontWeight.Medium, color).copy(lineHeight = 14.sp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- format

private val hmFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm", Locale.US)
}

fun hm(ms: Long): String = (hmFormat.get() ?: SimpleDateFormat("HH:mm", Locale.US)).format(Date(ms))

fun formatCount(n: Int): String = String.format(Locale.US, "%,d", n)

fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

fun minutesLeft(endMs: Long, now: Long): String {
    val m = ((endMs - now) / 60_000L).coerceAtLeast(0)
    return if (m >= 60) "متبقي ${m / 60}س ${m % 60}د" else "متبقي ${m}د"
}

private val arabicMonths = listOf(
    "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
    "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
)

fun arabicDate(ms: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = ms
    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val month = arabicMonths[cal.get(java.util.Calendar.MONTH)]
    val year = cal.get(java.util.Calendar.YEAR)
    return "$day $month $year"
}

fun relativeTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return "—"
    val minutes = ((now - ms) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "الآن"
        minutes < 60 -> "قبل $minutes د"
        minutes < 24 * 60 -> "قبل ${minutes / 60} س"
        else -> "قبل ${minutes / (24 * 60)} يوم"
    }
}
