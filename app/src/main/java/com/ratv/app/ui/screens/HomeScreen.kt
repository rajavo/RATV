package com.ratv.app.ui.screens

import com.ratv.app.i18n.tr
import com.ratv.app.data.MatchEvent
import com.ratv.app.data.Updater
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ratv.app.data.Channel
import com.ratv.app.data.Keys
import com.ratv.app.data.current
import com.ratv.app.data.forChannel
import com.ratv.app.data.progress
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.Navigator
import com.ratv.app.ui.components.ChannelLogo
import com.ratv.app.ui.components.CircleIconButton
import com.ratv.app.ui.components.LiveBadge
import com.ratv.app.ui.components.PosterImage
import com.ratv.app.ui.components.ProgressLine
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.RatvLogo
import com.ratv.app.ui.components.rememberIsWide
import com.ratv.app.ui.components.RingsArt
import com.ratv.app.ui.components.SectionHeader
import com.ratv.app.ui.components.TvInitialFocus
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.components.formatCount
import com.ratv.app.ui.components.hm
import com.ratv.app.ui.components.minutesLeft
import com.ratv.app.ui.components.screenBackground
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import kotlinx.coroutines.delay

@Composable
fun rememberNow(intervalMs: Long = 30_000L): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(intervalMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun HomeScreen(nav: Navigator) {
    val repo = LocalRepo.current
    val wide = rememberIsWide()
    val update by Updater.available.collectAsStateWithLifecycle()
    var showUpdate by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Updater.checkIfDue() }
    val channels by repo.channels.collectAsStateWithLifecycle()
    val movies by repo.movies.collectAsStateWithLifecycle()
    val series by repo.series.collectAsStateWithLifecycle()
    val favorites by repo.favorites.collectAsStateWithLifecycle()
    val history by repo.history.collectAsStateWithLifecycle()
    val epg by repo.epg.collectAsStateWithLifecycle()
    val libraryLoading by repo.libraryLoading.collectAsStateWithLifecycle()
    val now = rememberNow()

    val featured: Channel? = remember(channels, favorites) {
        val last = repo.prefs.lastChannelId?.let { id -> channels.firstOrNull { it.id == id } }
        channels.firstOrNull { Keys.live(it.id) in favorites } ?: last ?: channels.firstOrNull()
    }
    val favoriteChannels = remember(channels, favorites) { channels.filter { Keys.live(it.id) in favorites }.take(20) }
    val recentMovies = remember(movies) { movies.sortedByDescending { it.added }.take(15) }
    val recentSeries = remember(series) { series.sortedByDescending { it.added }.take(15) }
    val heroFocus = remember { FocusRequester() }
    TvInitialFocus(heroFocus, key = featured?.id)

    val todayMatches by produceState(emptyList<MatchEvent>(), epg, channels) {
        val t = System.currentTimeMillis()
        value = repo.matchSchedule(t, startOfDay(t) + 24L * 60L * 60L * 1000L).filter { it.end > t }
    }
    update?.let { info -> if (showUpdate) UpdateDialog(info) { showUpdate = false } }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .screenBackground(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Always an item (empty without an update), so the banner appearing later does not move
        // the other rows and the remote keeps its place on the TV.
        item {
            update?.let { info ->
                Row(
                    Modifier
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(RColors.AccentBrush)
                        .tvClickable(RoundedCornerShape(16.dp)) { showUpdate = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(RIcons.Refresh, contentDescription = null, tint = RColors.OnSaffron, modifier = Modifier.size(20.dp))
                    Text(
                        tr("تحديث جديد متاح ({0}) — اضغط للتحديث", info.versionName),
                        style = T.body(14.sp, FontWeight.Bold, RColors.OnSaffron),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // On TV and wide screens the top bar already has the logo, search and account.
        if (!wide) item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RatvLogo(24.sp)
                Spacer(Modifier.weight(1f))
                CircleIconButton(RIcons.Search, tr("بحث"), { nav.search() })
                Spacer(Modifier.width(8.dp))
                CircleIconButton(RIcons.User, tr("حسابي"), { nav.tab("settings") })
            }
        }

        if (featured != null) {
            item {
                val programmes = epg.forChannel(featured)
                val programme = programmes.current(now)
                HeroCard(
                    channel = featured,
                    title = programme?.title ?: featured.name,
                    meta = if (programme != null) tr("بدأ {0} · {1}", hm(programme.start), minutesLeft(programme.end, now))
                    else (repo.liveCategoryName(featured.categoryId) ?: tr("بث مباشر")),
                    progress = programme?.progress(now),
                    onWatch = { nav.playLive(featured.id) },
                    modifier = Modifier
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp)
                        .focusRequester(heroFocus)
                )
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CategoryTile(RIcons.Broadcast, tr("مباشر"), tr("{0} قناة", formatCount(channels.size)), Modifier.weight(1f)) { nav.tab("live") }
                CategoryTile(
                    RIcons.Film, tr("أفلام"),
                    if (libraryLoading && movies.isEmpty()) tr("جارٍ التحميل…") else tr("{0} فيلم", formatCount(movies.size)),
                    Modifier.weight(1f)
                ) { nav.tab("movies") }
                CategoryTile(
                    RIcons.Layers, tr("مسلسلات"),
                    if (libraryLoading && series.isEmpty()) tr("جارٍ التحميل…") else tr("{0} مسلسل", formatCount(series.size)),
                    Modifier.weight(1f)
                ) { nav.tab("series") }
            }
        }

        if (history.isNotEmpty()) {
            item {
                Column {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader(tr("تابع المشاهدة"))
                }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(history.take(15), key = { it.key }) { entry ->
                        Column(
                            Modifier
                                .width(236.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .tvClickable(RoundedCornerShape(14.dp)) { nav.resume(entry.key) }
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(132.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            ) {
                                PosterImage(entry.title, entry.image, Modifier.fillMaxSize(), showTitle = false)
                                Box(
                                    Modifier
                                        .align(Alignment.Center)
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x99000000)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(RIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                                ProgressLine(
                                    entry.progress,
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth(),
                                    height = 4.dp,
                                    track = Color(0x33FFFFFF)
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(entry.title, style = T.title.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val left = ((entry.duration - entry.position) / 60_000L).coerceAtLeast(0)
                            Text(
                                listOfNotNull(entry.subtitle, tr("متبقي {0} د", left)).joinToString(" · "),
                                style = T.caption,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (todayMatches.isNotEmpty()) {
            item {
                Column {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader(tr("مباريات اليوم"), action = tr("الجدول"), onAction = { if (wide) nav.tab("matches") else nav.matches() })
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        todayMatches.take(3).forEach { m ->
                            MatchCard(m, now, { repo.liveCategoryName(it) }, compact = true) { ch -> nav.playLive(ch.id) }
                        }
                    }
                }
            }
        }

        if (favoriteChannels.isNotEmpty()) {
            item {
                Column {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader(tr("قنواتك المفضلة"), action = tr("الكل"), onAction = { nav.tab("live") })
                }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(favoriteChannels, key = { it.id }) { ch ->
                        Column(
                            Modifier
                                .width(76.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .tvClickable(RoundedCornerShape(14.dp)) { nav.playLive(ch.id) },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ChannelLogo(ch.name, ch.logo, 64.dp, corner = 18.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(ch.name, style = T.body(12.sp, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (recentMovies.isNotEmpty()) {
            item {
                Column {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader(tr("أُضيف حديثاً"), action = tr("عرض الكل"), onAction = { nav.tab("movies") })
                }
            }
            item {
                PosterRow(recentMovies.map { PosterData(it.id, it.name, it.poster, it.year ?: (it.rating?.let { r -> "★ $r" } ?: "")) }) { nav.movie(it) }
            }
        }

        if (recentSeries.isNotEmpty()) {
            item {
                Column {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader(tr("مسلسلات جديدة"), action = tr("عرض الكل"), onAction = { nav.tab("series") })
                }
            }
            item {
                PosterRow(recentSeries.map { PosterData(it.id, it.name, it.cover, it.year ?: "") }) { nav.show(it) }
            }
        }

        if (channels.isEmpty() && movies.isEmpty() && series.isEmpty()) {
            item {
                com.ratv.app.ui.components.EmptyState(
                    RIcons.Tv,
                    tr("لا يوجد محتوى بعد"),
                    tr("تحقّق من اشتراكك أو حدّث المحتوى من صفحة حسابي.")
                )
            }
        }
    }
}

data class PosterData(val id: String, val title: String, val image: String?, val meta: String)

@Composable
fun PosterRow(posters: List<PosterData>, onOpen: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(posters, key = { it.id }) { p ->
            Column(
                Modifier
                    .width(116.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .tvClickable(RoundedCornerShape(12.dp)) { onOpen(p.id) }
            ) {
                PosterImage(
                    p.title,
                    p.image,
                    Modifier
                        .fillMaxWidth()
                        .height(174.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(8.dp))
                Text(p.title, style = T.body(13.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.meta.isNotBlank()) Text(p.meta, style = T.caption, maxLines = 1)
            }
        }
    }
}

@Composable
private fun HeroCard(
    channel: Channel,
    title: String,
    meta: String,
    progress: Float?,
    onWatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(RColors.SaffronDeep.copy(alpha = 0.32f), RColors.Surface)))
            .tvClickable(RoundedCornerShape(22.dp), onClick = onWatch)
    ) {
        RingsArt(Modifier.fillMaxSize())
        ChannelLogo(
            channel.name,
            channel.logo,
            84.dp,
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 24.dp, bottom = 40.dp),
            corner = 20.dp
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6080A0D))))
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiveBadge()
            Text(
                channel.name,
                style = T.body(12.sp, FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color(0xA80B0C10))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = T.display(18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta, style = T.body(12.5.sp, color = Color(0xFFD9D4CC)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (progress != null) {
                    ProgressLine(progress, Modifier.fillMaxWidth(), height = 4.dp, track = Color(0x33FFFFFF))
                }
            }
            Row(
                Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(RColors.AccentBrush)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(RIcons.Play, contentDescription = null, tint = RColors.OnSaffron, modifier = Modifier.size(16.dp))
                Text(tr("شاهد الآن"), style = T.body(14.sp, FontWeight.Bold, RColors.OnSaffron))
            }
        }
    }
}

@Composable
private fun CategoryTile(icon: ImageVector, label: String, count: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(108.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(RColors.Surface2, RColors.Surface)))
            .border(1.dp, RColors.Line, RoundedCornerShape(18.dp))
            .tvClickable(RoundedCornerShape(18.dp), onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(RColors.AccentBrush),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = RColors.OnSaffron, modifier = Modifier.size(22.dp))
        }
        Column {
            Text(label, style = T.title)
            Text(count, style = T.caption, maxLines = 1)
        }
    }
}
