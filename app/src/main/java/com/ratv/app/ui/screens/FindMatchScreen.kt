package com.ratv.app.ui.screens

import com.ratv.app.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ratv.app.data.Channel
import com.ratv.app.data.MatchEvent
import com.ratv.app.data.MatchLinks
import com.ratv.app.data.MatchRequest
import com.ratv.app.data.current
import com.ratv.app.data.forChannel
import com.ratv.app.data.regionOf
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.Navigator
import com.ratv.app.ui.components.CircleIconButton
import com.ratv.app.ui.components.PrimaryButton
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.TvInitialFocus
import com.ratv.app.ui.components.rememberIsTv
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.components.screenBackground
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import java.util.Calendar
import java.util.Locale

private class Search(val event: MatchEvent?)

/**
 * "Where is this match on?": opened by the schedule app (جدول RA كورة) with a match, lists every
 * channel of the subscription that shows it, from the programme guide.
 */
@Composable
fun FindMatchScreen(nav: Navigator) {
    val req by MatchLinks.current.collectAsStateWithLifecycle()
    val r = req
    if (r == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val repo = LocalRepo.current
    val tv = rememberIsTv()
    val epg by repo.epg.collectAsStateWithLifecycle()
    val channels by repo.channels.collectAsStateWithLifecycle()
    val epgLoading by repo.epgLoading.collectAsStateWithLifecycle()
    val search by produceState<Search?>(null, r, epg, channels) { value = Search(repo.channelsForMatch(r)) }
    val now = remember(search) { System.currentTimeMillis() }
    val event = search?.event
    val sports = remember(search, channels) { if (search != null && event == null) repo.sportsChannels(null).take(80) else emptyList() }
    val regions = remember(event) {
        event?.channels.orEmpty()
            .groupBy { regionOf(it.name, repo.liveCategoryName(it.categoryId)) }
            .toList()
            .sortedByDescending { it.second.size }
    }
    val firstId = regions.firstOrNull()?.second?.firstOrNull()?.id ?: sports.firstOrNull()?.id
    val first = remember { FocusRequester() }
    val notFoundFocus = remember { FocusRequester() }
    TvInitialFocus(if (event == null && search != null) notFoundFocus else first, search)

    LazyColumn(
        Modifier
            .fillMaxSize()
            .screenBackground(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    Text(tr("وين تنعرض المباراة؟"), style = T.display(24.sp))
                    Text(r.title, style = T.body(20.sp, FontWeight.Bold, RColors.Saffron))
                    Text(
                        listOf(r.league, kickOff(r.start)).filter { it.isNotBlank() }.joinToString("  ·  "),
                        style = T.body(14.sp, color = RColors.Text2)
                    )
                }
            }
        }
        when {
            search == null -> item {
                Text(tr("جارٍ البحث في دليل البرامج…"), style = T.body(16.sp, FontWeight.Bold, RColors.Text2))
            }
            event != null -> {
                item {
                    Text(
                        if (event.isLive(now)) tr("المباراة شغّالة الحين على {0} قناة، اختر وحدة:", event.channels.size)
                        else tr("{0} قناة بتعرض المباراة، اختر وحدة:", event.channels.size),
                        style = T.body(16.sp, FontWeight.Bold)
                    )
                }
                regions.forEach { (region, list) ->
                    item(key = "region-$region") { Text(region, style = T.body(13.sp, FontWeight.Bold, RColors.Text2)) }
                    items(list, key = { it.id }) { ch ->
                        ChannelLine(
                            ch,
                            epg.forChannel(ch).current(now)?.title,
                            if (ch.id == firstId) Modifier.focusRequester(first) else Modifier
                        ) {
                            MatchLinks.watching = ch.id to r
                            nav.playLive(ch.id)
                        }
                    }
                }
            }
            else -> {
                item { NotFound(r, epgLoading, now, notFoundFocus) { nav.matches() } }
                if (sports.isNotEmpty()) {
                    item(key = "sports") {
                        Text(tr("قنوات الرياضة في اشتراكك"), style = T.body(15.sp, FontWeight.Bold), modifier = Modifier.padding(top = 6.dp))
                    }
                    items(sports, key = { it.id }) { ch ->
                        ChannelLine(ch, epg.forChannel(ch).current(now)?.title, Modifier) {
                            MatchLinks.watching = ch.id to r
                            nav.playLive(ch.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotFound(r: MatchRequest, epgLoading: Boolean, now: Long, focus: FocusRequester, onSchedule: () -> Unit) {
    val daysAway = ((r.start - now) / (24L * 60L * 60L * 1000L)).toInt()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RColors.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(tr("ما لقينا المباراة في دليل البرامج"), style = T.body(17.sp, FontWeight.Bold))
        Text(
            when {
                epgLoading -> tr("دليل البرامج لسّه يتحمّل، وبندوّر مرة ثانية أول ما يخلص.")
                daysAway >= 2 -> tr("المباراة بعد {0} أيام، ودليل البرامج عادةً يغطي يوم أو يومين قدّام. ارجع لها يوم المباراة.", daysAway)
                daysAway == 1 -> tr("المباراة بكرة، ودليل البرامج ممكن ما يكون نزل لها للحين. ارجع لها قبل المباراة بشوي.")
                else -> tr("المزوّد ما كتب المباراة في دليل البرامج. جرّب قنوات الرياضة تحت.")
            },
            style = T.body(14.sp, color = RColors.Text2)
        )
        PrimaryButton(tr("جدول المباريات"), onSchedule, modifier = Modifier.focusRequester(focus), height = 46.dp)
    }
}

@Composable
private fun ChannelLine(ch: Channel, nowOn: String?, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RColors.Surface)
            .tvClickable(shape, onClickLabel = tr("مشاهدة {0}", ch.name), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(RIcons.Play, contentDescription = null, tint = RColors.Saffron, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, style = T.body(16.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!nowOn.isNullOrBlank()) {
                Text(tr("الحين: {0}", nowOn), style = T.body(13.sp, color = RColors.Text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val WEEKDAYS get() = arrayOf(tr("الأحد"), tr("الاثنين"), tr("الثلاثاء"), tr("الأربعاء"), tr("الخميس"), tr("الجمعة"), tr("السبت"))
private val MONTHS get() = arrayOf(
    tr("يناير"), tr("فبراير"), tr("مارس"), tr("أبريل"), tr("مايو"), tr("يونيو"), tr("يوليو"), tr("أغسطس"), tr("سبتمبر"), tr("أكتوبر"), tr("نوفمبر"), tr("ديسمبر")
)

/** "الخميس 23 أكتوبر 21:00" */
private fun kickOff(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(
        Locale.US, "%s %d %s %02d:%02d",
        WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1], c.get(Calendar.DAY_OF_MONTH), MONTHS[c.get(Calendar.MONTH)],
        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
    )
}
