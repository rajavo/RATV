package com.ratv.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ratv.app.data.Channel
import com.ratv.app.data.Programme
import com.ratv.app.i18n.tr
import com.ratv.app.ui.components.ChannelLogo
import com.ratv.app.ui.components.hm
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The live channel's screens as a satellite receiver shows them: the info bar along the bottom
 * (number, logo, name, clock, now and next with their times, picture badges and the signal and
 * quality meters), the number being typed in its slots, and the channel list's numbers.
 */

private val BarTop = Color(0xF2172A45)
private val BarBottom = Color(0xF2070D18)
private val BarEdge = Color(0x3361A8FF)
private val Soft = Color(0xFFBFCADB)
private val Dim = Color(0xFF7D8BA1)
private val Track = Color(0x33FFFFFF)
private val Good = Color(0xFF4CD787)
private val Fair = Color(0xFFF2C14E)
private val Poor = Color(0xFFFF6B6B)

/** A channel number as receivers print it: at least three digits (007). */
fun receiverNumber(num: Int): String = if (num in 0..999) String.format(Locale.US, "%03d", num) else num.toString()

/** The digits typed so far in the number's slots ("12" is "12-"; four slots past channel 999). */
fun numberSlots(typed: String, highest: Int): String {
    val slots = maxOf(if (highest > 999) 4 else 3, typed.length)
    return typed.padEnd(slots, '-')
}

/** How well the line carries the channel (0..1): the speed measured against what HD needs, low while it fails. */
fun signalLevel(bandwidthBps: Long, netTrouble: Boolean): Float = when {
    netTrouble -> 0.2f
    bandwidthBps <= 0L -> 0.85f
    else -> (bandwidthBps / 10_000_000f).coerceIn(0.15f, 1f)
}

/** How safe the picture is from stopping (0..1): the picture stored ahead, 20 s or more is full. */
fun qualityLevel(reserveMs: Long, buffering: Boolean): Float =
    if (buffering) minOf(0.15f, reserveMs / 20_000f) else (reserveMs / 20_000f).coerceIn(0.1f, 1f)

private fun meterColor(level: Float): Color = when {
    level >= 0.6f -> Good
    level >= 0.3f -> Fair
    else -> Poor
}

private val dayFormat = ThreadLocal<SimpleDateFormat>()

private fun dayLabel(ms: Long): String {
    val f = dayFormat.get() ?: SimpleDateFormat("EEE d/M", com.ratv.app.i18n.I18n.locale).also { dayFormat.set(it) }
    return f.format(Date(ms))
}

@Composable
internal fun BoxScope.ReceiverInfoBar(
    channel: Channel,
    categoryName: String?,
    programme: Programme?,
    next: Programme?,
    now: Long,
    resolution: String?,
    audioTracks: Int,
    favorite: Boolean,
    signal: Float,
    quality: Float
) {
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 36.dp, vertical = 24.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(BarTop, BarBottom)))
            .border(1.dp, BarEdge, RoundedCornerShape(18.dp))
    ) {
        // The accent line receivers draw along the top of their bar.
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Brush.horizontalGradient(listOf(RColors.SaffronDeep, RColors.Saffron, RColors.SaffronLight)))
        )
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.verticalGradient(listOf(RColors.SaffronLight, RColors.SaffronDeep)))
                    .widthIn(min = 92.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    receiverNumber(channel.num),
                    style = T.display(32.sp, FontWeight.ExtraBold, RColors.OnSaffron).copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1
                )
                Text("TV", style = T.body(11.sp, FontWeight.Bold, RColors.OnSaffron.copy(alpha = 0.75f)))
            }
            ChannelLogo(channel.name, channel.logo, 72.dp, corner = 14.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        channel.name,
                        style = T.display(22.sp, FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (favorite) Text("★", style = T.body(18.sp, FontWeight.Bold, RColors.Saffron))
                    val picture = resolution ?: channel.quality
                    if (picture != null) Badge(picture, RColors.Saffron)
                    if (audioTracks > 1) Badge(tr("{0} صوت", audioTracks), Soft)
                    if (categoryName != null) Badge(categoryName, Dim)
                }
                if (programme != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${hm(programme.start)} – ${hm(programme.end)}", style = T.body(14.sp, FontWeight.Bold, RColors.Saffron), maxLines = 1)
                        Text(
                            programme.title,
                            style = T.body(16.sp, FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        val left = ((programme.end - now) / 60_000L).coerceAtLeast(0L)
                        Text(tr("باقي {0} د", left), style = T.body(13.sp, color = Soft), maxLines = 1)
                    }
                    val total = (programme.end - programme.start).coerceAtLeast(1L)
                    Meter(((now - programme.start).toFloat() / total).coerceIn(0f, 1f), RColors.Saffron, Modifier.fillMaxWidth())
                } else {
                    Text(tr("لا يوجد دليل برامج لهذه القناة"), style = T.body(14.sp, color = Soft), maxLines = 1)
                }
                if (next != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr("التالي"), style = T.body(13.sp, FontWeight.Bold, Dim), maxLines = 1)
                        Text(hm(next.start), style = T.body(13.sp, FontWeight.Bold, Soft), maxLines = 1)
                        Text(next.title, style = T.body(13.sp, color = Soft), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Box(
                Modifier
                    .width(1.dp)
                    .height(92.dp)
                    .background(BarEdge)
            )
            Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(hm(now), style = T.display(30.sp, FontWeight.Bold).copy(fontFamily = FontFamily.Monospace), maxLines = 1)
                Text(dayLabel(now), style = T.body(12.sp, color = Soft), maxLines = 1)
                Spacer(Modifier.height(2.dp))
                LevelRow(tr("الإشارة"), signal)
                LevelRow(tr("الجودة"), quality)
            }
        }
        Text(
            tr("▲▼ تغيير القناة  ·  الأرقام للانتقال  ·  OK الأدوات  ·  ☰ قائمة القنوات"),
            style = T.body(11.sp, color = Dim),
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x33000000))
                .padding(horizontal = 18.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        style = T.body(11.sp, FontWeight.Bold, color),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
private fun Meter(level: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Track)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .background(color)
        )
    }
}

@Composable
private fun LevelRow(label: String, level: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = T.body(10.sp, FontWeight.Bold, Soft), maxLines = 1, modifier = Modifier.width(44.dp))
        Meter(level, meterColor(level), Modifier.weight(1f))
        Text("${(level * 100).toInt()}%", style = T.body(10.sp, color = Soft), maxLines = 1, modifier = Modifier.width(30.dp))
    }
}

/** The number being typed, in its slots, with the channel it will open (or none). */
@Composable
internal fun BoxScope.ReceiverNumberEntry(typed: String, highest: Int, target: Channel?) {
    Column(
        Modifier
            .align(Alignment.TopEnd)
            .padding(horizontal = 40.dp, vertical = 30.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(BarTop, BarBottom)))
            .border(1.dp, BarEdge, RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            numberSlots(typed, highest),
            style = T.display(42.sp, FontWeight.ExtraBold, RColors.Saffron).copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
            maxLines = 1
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (target != null) {
                ChannelLogo(target.name, target.logo, 24.dp, corner = 6.dp)
                Text(target.name, style = T.body(13.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            } else {
                Text(tr("لا توجد قناة بهذا الرقم"), style = T.body(12.sp, color = Dim), maxLines = 1)
            }
        }
        Spacer(Modifier.size(2.dp))
    }
}
