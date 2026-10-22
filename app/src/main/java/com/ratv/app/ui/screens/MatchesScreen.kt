package com.ratv.app.ui.screens

import com.ratv.app.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ratv.app.data.Channel
import com.ratv.app.data.MatchEvent
import com.ratv.app.data.regionOf
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.Navigator
import com.ratv.app.ui.components.CircleIconButton
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.TvInitialFocus
import com.ratv.app.ui.components.rememberIsTv
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.components.screenBackground
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val DAY_MS = 24L * 60L * 60L * 1000L

fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun matchTime(start: Long, end: Long): String {
    val f = SimpleDateFormat("HH:mm", Locale.US)
    return f.format(Date(start)) + " – " + f.format(Date(end))
}

/** Games of the day from the programme guide, each with the channels of the subscription showing it. */
@Composable
fun MatchesScreen(nav: Navigator) {
    val repo = LocalRepo.current
    val tv = rememberIsTv()
    val epg by repo.epg.collectAsStateWithLifecycle()
    val channels by repo.channels.collectAsStateWithLifecycle()
    val epgLoading by repo.epgLoading.collectAsStateWithLifecycle()
    var day by rememberSaveable { mutableIntStateOf(0) }
    val now = remember(day, epg) { System.currentTimeMillis() }
    val today = remember { startOfDay(System.currentTimeMillis()) }
    val matches by produceState<List<MatchEvent>?>(null, epg, channels, day) {
        value = when (day) {
            0 -> repo.matchSchedule(now, now + 1)
            1 -> repo.matchSchedule(today, today + DAY_MS)
            else -> repo.matchSchedule(today + DAY_MS, today + 2 * DAY_MS)
        }
    }
    val firstFocus = remember { FocusRequester() }
    TvInitialFocus(firstFocus)

    LazyColumn(
        Modifier
            .fillMaxSize()
            .screenBackground(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (tv) Modifier else Modifier.statusBarsPadding()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!tv) {
                    CircleIconButton(RIcons.Back, tr("رجوع"), { nav.back() })
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    // One line on a phone too: wrapped, the title ran into the line under it.
                    Text(
                        tr("جدول المباريات"),
                        style = T.display(if (tv) 26.sp else 21.sp),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(tr("من دليل البرامج في اشتراكك، مع كل القنوات الناقلة لكل مباراة"), style = T.caption)
                }
                Spacer(Modifier.width(12.dp))
                KoraButton()
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(tr("الآن"), tr("اليوم"), tr("غداً")).forEachIndexed { i, label ->
                    DayChip(label, i == day, if (i == 0) Modifier.focusRequester(firstFocus) else Modifier) { day = i }
                }
            }
        }
        val list = matches
        when {
            list == null -> item { Text(tr("جارٍ تجهيز الجدول…"), style = T.body(15.sp, color = RColors.Text2)) }
            list.isEmpty() -> item {
                Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        when {
                            epgLoading -> tr("جارٍ تحميل دليل البرامج…")
                            day == 0 -> tr("لا توجد مباريات تُعرض الآن في دليل البرامج.")
                            else -> tr("لا توجد مباريات في دليل البرامج لهذا اليوم.")
                        },
                        style = T.body(16.sp, FontWeight.Bold)
                    )
                    Text(
                        tr("الجدول يُبنى من دليل البرامج (EPG) الذي يرسله مزوّد الاشتراك لقنوات الرياضة."),
                        style = T.caption
                    )
                }
            }
            else -> items(list, key = { it.title + it.start }) { m ->
                MatchCard(m, now, { repo.liveCategoryName(it) }) { ch -> nav.playLive(ch.id) }
            }
        }
    }
}

@Composable
private fun DayChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Text(
        label,
        style = T.body(14.sp, FontWeight.Bold, if (selected) RColors.OnSaffron else RColors.Text),
        modifier = modifier
            .clip(shape)
            .background(if (selected) RColors.Saffron else RColors.Surface)
            .tvClickable(shape, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MatchCard(
    m: MatchEvent,
    now: Long,
    categoryName: (String) -> String?,
    compact: Boolean = false,
    onPlay: (Channel) -> Unit
) {
    // On the home screen only the first channels are shown; the schedule has them all.
    val shown = if (compact) m.channels.take(6) else m.channels
    val regions = remember(m, compact) {
        shown.groupBy { regionOf(it.name, categoryName(it.categoryId)) }.toList().sortedByDescending { it.second.size }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RColors.Surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(matchTime(m.start, m.end), style = T.body(13.sp, FontWeight.Bold, RColors.Saffron))
            if (m.isLive(now)) {
                Text(
                    tr("مباشر الآن"),
                    style = T.body(11.sp, FontWeight.Bold, Color.White),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(RColors.Live)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(tr("{0} قناة ناقلة", m.channels.size), style = T.caption)
        }
        Text(m.title, style = T.body(16.sp, FontWeight.Bold))
        regions.forEach { (region, list) ->
            Text(region, style = T.body(12.5.sp, FontWeight.Bold, RColors.Text2))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                list.forEach { ch -> ChannelChip(ch) { onPlay(ch) } }
            }
        }
        if (shown.size < m.channels.size) {
            Text(tr("و{0} قنوات أخرى في جدول المباريات", m.channels.size - shown.size), style = T.caption)
        }
    }
}

@Composable
private fun ChannelChip(ch: Channel, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .clip(shape)
            .background(RColors.Surface2)
            .tvClickable(shape, onClickLabel = tr("مشاهدة {0}", ch.name), onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(RIcons.Play, contentDescription = null, tint = RColors.Saffron, modifier = Modifier.size(11.dp))
        Text(ch.name, style = T.body(13.sp, FontWeight.Medium), maxLines = 1)
    }
}
