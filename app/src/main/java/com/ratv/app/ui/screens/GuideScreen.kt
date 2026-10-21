package com.ratv.app.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ratv.app.data.Channel
import com.ratv.app.data.Keys
import com.ratv.app.data.Programme
import com.ratv.app.data.forChannel
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.Navigator
import com.ratv.app.ui.components.ChannelLogo
import com.ratv.app.ui.components.Chip
import com.ratv.app.ui.components.CircleIconButton
import com.ratv.app.ui.components.EmptyState
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.ScreenHeader
import com.ratv.app.ui.components.TvInitialFocus
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.components.hm
import com.ratv.app.ui.components.minutesLeft
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

private val CHANNEL_COLUMN = 76.dp
private val PER_MINUTE = 4.dp
private const val WINDOW_MINUTES = 24 * 60
private const val MINUTE = 60_000L
private const val ALL = "__all__"
private const val FAV = "__fav__"

/** Keeps a programme's title inside the visible part of the timeline when the cell starts off-screen. */
private fun Modifier.stickToViewport(scroll: ScrollState, startPx: Int): Modifier = layout { measurable, constraints ->
    val maxShift = (constraints.maxWidth - 120.dp.roundToPx()).coerceAtLeast(0)
    val hidden = (scroll.value - startPx).coerceIn(0, maxShift)
    val placeable = measurable.measure(
        constraints.copy(minWidth = 0, maxWidth = (constraints.maxWidth - hidden).coerceAtLeast(0))
    )
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(hidden, 0) }
}

private fun windowOrigin(day: Int, now: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    if (day == 0) {
        cal.set(Calendar.MINUTE, 0)
        cal.add(Calendar.HOUR_OF_DAY, -1)
    } else {
        cal.add(Calendar.DAY_OF_YEAR, day)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
    }
    return cal.timeInMillis
}

@Composable
fun GuideScreen(nav: Navigator) {
    val repo = LocalRepo.current
    val channels by repo.channels.collectAsStateWithLifecycle()
    val categories by repo.liveCategories.collectAsStateWithLifecycle()
    val favorites by repo.favorites.collectAsStateWithLifecycle()
    val epg by repo.epg.collectAsStateWithLifecycle()
    val epgLoading by repo.epgLoading.collectAsStateWithLifecycle()
    val now = rememberNow()

    var day by rememberSaveable { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableStateOf(ALL) }
    var selected by remember { mutableStateOf<Pair<Channel, Programme>?>(null) }

    val origin = remember(day) { windowOrigin(day, System.currentTimeMillis()) }
    val windowEnd = origin + WINDOW_MINUTES * MINUTE
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    val visible = remember(channels, favorites, category) {
        when (category) {
            ALL -> channels
            FAV -> channels.filter { Keys.live(it.id) in favorites }
            else -> channels.filter { it.categoryId == category }
        }
    }

    val firstRowFocus = remember { FocusRequester() }
    TvInitialFocus(firstRowFocus, key = visible.firstOrNull()?.id to epg.isEmpty())

    LaunchedEffect(day) {
        val startMinute = if (day == 0) ((System.currentTimeMillis() - origin) / MINUTE - 30).coerceAtLeast(0) else 18L * 60L
        scroll.scrollTo(with(density) { (PER_MINUTE * startMinute.toFloat()).roundToPx() })
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RColors.Ink)
    ) {
        ScreenHeader("دليل البرامج", Modifier.statusBarsPadding(), onBack = { nav.back() }) {
            CircleIconButton(RIcons.Search, "بحث", { nav.search() })
        }

        LazyRow(
            Modifier.padding(top = 8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Chip("اليوم", day == 0, { day = 0 }) }
            item { Chip("غداً", day == 1, { day = 1 }) }
            item { Spacer(Modifier.width(8.dp)) }
            item { Chip("الكل", category == ALL, { category = ALL }) }
            item { Chip("المفضلة", category == FAV, { category = FAV }) }
            items(categories, key = { it.id }) { cat ->
                Chip(cat.name, category == cat.id, { category = cat.id })
            }
        }

        if (epg.isEmpty()) {
            if (epgLoading) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CircularProgressIndicator(color = RColors.Saffron, strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp))
                    Text("جارٍ تحميل دليل البرامج…", style = T.body(14.sp, color = RColors.Text2))
                }
            } else {
                EmptyState(
                    RIcons.Calendar,
                    "لا يتوفر دليل برامج",
                    "لم يرسل مزوّد الخدمة دليل برامج لهذه القنوات. يمكنك إضافة رابط EPG عند إضافة قائمة M3U."
                )
            }
        } else {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(Modifier.fillMaxSize()) {
                TimeHeader(origin, scroll)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = if (selected != null) 150.dp else 12.dp)) {
                    items(visible, key = { it.id }) { ch ->
                        GuideRow(
                            channel = ch,
                            programmes = epg.forChannel(ch),
                            origin = origin,
                            windowEnd = windowEnd,
                            now = now,
                            scroll = scroll,
                            selected = selected?.takeIf { it.first.id == ch.id }?.second,
                            onSelect = { p ->
                                val current = selected
                                // A second press on the selected programme plays the channel.
                                if (current != null && current.first.id == ch.id && current.second == p) {
                                    nav.playLive(ch.id)
                                } else {
                                    selected = ch to p
                                }
                            },
                            onOpenChannel = { nav.playLive(ch.id) },
                            focusRequester = if (ch.id == visible.firstOrNull()?.id) firstRowFocus else null
                        )
                    }
                }
            }

            if (now in origin until windowEnd) {
                val nowPx = with(density) { (PER_MINUTE * ((now - origin).toFloat() / MINUTE)).roundToPx() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(start = CHANNEL_COLUMN)
                        .clipToBounds()
                ) {
                    Box(
                        Modifier
                            .offset { IntOffset(nowPx - scroll.value, 0) }
                            .padding(top = 24.dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(RColors.Saffron)
                    )
                    Box(
                        Modifier
                            .offset { IntOffset(nowPx - scroll.value - with(density) { 21.dp.roundToPx() }, 0) }
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(RColors.Saffron)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(hm(now), style = T.body(11.sp, FontWeight.Bold, RColors.OnSaffron).copy(lineHeight = 16.sp))
                    }
                }
            }

            val sel = selected
            if (sel != null) {
                ProgrammeDetail(
                    channel = sel.first,
                    programme = sel.second,
                    now = now,
                    onPlay = { nav.playLive(sel.first.id) },
                    onClose = { selected = null },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }
        }
    }
}

@Composable
private fun TimeHeader(origin: Long, scroll: ScrollState) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
    ) {
        Spacer(Modifier.width(CHANNEL_COLUMN))
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(scroll)
        ) {
            Box(
                Modifier
                    .width(PER_MINUTE * WINDOW_MINUTES.toFloat())
                    .fillMaxHeight()
            ) {
                for (i in 0 until WINDOW_MINUTES / 30) {
                    val x = PER_MINUTE * (i * 30).toFloat()
                    Box(
                        Modifier
                            .offset(x = x)
                            .width(1.dp)
                            .height(14.dp)
                            .background(RColors.Surface3)
                    )
                    Text(
                        hm(origin + i * 30L * MINUTE),
                        style = T.body(12.sp, FontWeight.Medium, RColors.Text3).copy(lineHeight = 14.sp),
                        modifier = Modifier
                            .offset(x = x)
                            .padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    programmes: List<Programme>,
    origin: Long,
    windowEnd: Long,
    now: Long,
    scroll: ScrollState,
    selected: Programme?,
    onSelect: (Programme) -> Unit,
    onOpenChannel: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
    ) {
        Column(
            Modifier
                .width(CHANNEL_COLUMN)
                .fillMaxHeight()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .padding(3.dp)
                .clip(RoundedCornerShape(10.dp))
                .tvClickable(RoundedCornerShape(10.dp), onClickLabel = "تشغيل ${channel.name}", onClick = onOpenChannel),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ChannelLogo(channel.name, channel.logo, 36.dp, corner = 10.dp)
            Spacer(Modifier.height(3.dp))
            Text(channel.num.toString(), style = T.body(11.sp, color = RColors.Text3).copy(lineHeight = 12.sp), maxLines = 1)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(scroll)
        ) {
            Box(
                Modifier
                    .width(PER_MINUTE * WINDOW_MINUTES.toFloat())
                    .fillMaxHeight()
            ) {
                val inWindow = programmes.filter { it.end > origin && it.start < windowEnd }
                if (inWindow.isEmpty()) {
                    val startMinute = ((max(now, origin) - origin) / MINUTE).toFloat()
                    Box(
                        Modifier
                            .offset(x = PER_MINUTE * startMinute)
                            .width(240.dp)
                            .fillMaxHeight()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(RColors.NavBg)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(channel.name + " · لا تتوفر معلومات", style = T.body(12.sp, color = RColors.Text3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                inWindow.forEach { p ->
                    val startMinute = (max(p.start, origin) - origin).toFloat() / MINUTE
                    val endMinute = (min(p.end, windowEnd) - origin).toFloat() / MINUTE
                    val cellWidth = PER_MINUTE * (endMinute - startMinute) - 4.dp
                    if (cellWidth > 6.dp) {
                        val isNow = now >= p.start && now < p.end
                        val isPast = p.end <= now
                        val isSelected = selected == p
                        val startPx = with(LocalDensity.current) { (PER_MINUTE * startMinute).roundToPx() }
                        Box(
                            Modifier
                                .offset(x = PER_MINUTE * startMinute)
                                .width(cellWidth)
                                .fillMaxHeight()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    when {
                                        isSelected -> RColors.Surface3
                                        isNow -> androidx.compose.ui.graphics.Color(0xFF251D10)
                                        isPast -> androidx.compose.ui.graphics.Color(0xFF101216)
                                        else -> androidx.compose.ui.graphics.Color(0xFF16181E)
                                    }
                                )
                                .tvClickable(RoundedCornerShape(10.dp)) { onSelect(p) }
                        ) {
                        Column(
                            Modifier
                                .stickToViewport(scroll, startPx)
                                .fillMaxHeight()
                                .padding(horizontal = 10.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                p.title,
                                style = T.body(
                                    13.sp,
                                    FontWeight.Bold,
                                    when {
                                        isNow -> RColors.SaffronLight
                                        isPast -> RColors.Text3
                                        else -> RColors.Text
                                    }
                                ).copy(lineHeight = 18.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${hm(p.start)} – ${hm(p.end)}",
                                style = T.body(11.sp, color = if (isNow) androidx.compose.ui.graphics.Color(0xFFC9A46A) else RColors.Text3).copy(lineHeight = 14.sp),
                                maxLines = 1
                            )
                        }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgrammeDetail(
    channel: Channel,
    programme: Programme,
    now: Long,
    onPlay: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val live = now >= programme.start && now < programme.end
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(RColors.Surface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (live) "الآن على ${channel.name}" else channel.name,
                style = T.body(12.sp, FontWeight.Bold, RColors.Saffron),
                maxLines = 1
            )
            Text(programme.title, style = T.display(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val timing = "${hm(programme.start)} – ${hm(programme.end)}" +
                when {
                    live -> " · " + minutesLeft(programme.end, now)
                    programme.start > now -> " · يبدأ بعد ${(programme.start - now) / MINUTE} د"
                    else -> " · انتهى"
                }
            Text(timing, style = T.caption, maxLines = 1)
            programme.desc?.let { Text(it, style = T.body(12.sp, color = RColors.Text2), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(RColors.Saffron)
                    .tvClickable(CircleShape, onClickLabel = "شاهد ${channel.name}", ringColor = androidx.compose.ui.graphics.Color.White, onClick = onPlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(RIcons.Play, contentDescription = "شاهد القناة", tint = RColors.OnSaffron, modifier = Modifier.size(22.dp))
            }
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .tvClickable(CircleShape, onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(RIcons.Close, contentDescription = "إغلاق", tint = RColors.Text3, modifier = Modifier.size(18.dp))
            }
        }
    }
}
