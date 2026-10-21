package com.ratv.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ratv.app.data.Episode
import com.ratv.app.data.Keys
import com.ratv.app.data.SeriesInfo
import com.ratv.app.data.WatchEntry
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.Navigator
import com.ratv.app.ui.components.Chip
import com.ratv.app.ui.components.CircleIconButton
import com.ratv.app.ui.components.EmptyState
import com.ratv.app.ui.components.PosterImage
import com.ratv.app.ui.components.PrimaryButton
import com.ratv.app.ui.components.ProgressLine
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.TvInitialFocus
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import androidx.compose.ui.graphics.Brush

@Composable
fun SeriesDetailScreen(seriesId: String, nav: Navigator) {
    val repo = LocalRepo.current
    val seriesList by repo.series.collectAsStateWithLifecycle()
    val favorites by repo.favorites.collectAsStateWithLifecycle()
    val history by repo.history.collectAsStateWithLifecycle()
    val watched by repo.watched.collectAsStateWithLifecycle()
    val show = remember(seriesId, seriesList) { repo.seriesItem(seriesId) }

    if (show == null) {
        MissingItem("المسلسل غير متاح", nav)
        return
    }

    var loadingDone by remember(seriesId) { mutableStateOf(repo.cachedSeriesInfo(seriesId) != null) }
    val info by produceState<SeriesInfo?>(initialValue = repo.cachedSeriesInfo(seriesId), seriesId) {
        if (value == null) value = repo.seriesInfo(seriesId)
        loadingDone = true
    }

    val favKey = Keys.series(show.id)
    val isFavorite = favKey in favorites
    val lastEntry: WatchEntry? = history.firstOrNull { it.seriesId == show.id }
    val seasons = info?.seasons.orEmpty()
    var season by rememberSaveable(seriesId) { mutableStateOf(-1) }
    val activeSeason = when {
        season in seasons -> season
        lastEntry != null -> findSeasonOf(info, lastEntry.itemId) ?: seasons.firstOrNull() ?: 1
        else -> seasons.firstOrNull() ?: 1
    }
    val episodes = info?.episodes?.get(activeSeason).orEmpty()
    val continueEpisode: Episode? = remember(info, lastEntry) {
        val i = info ?: return@remember null
        lastEntry?.let { e -> i.episodes.values.flatten().firstOrNull { it.id == e.itemId } }
            ?: i.seasons.firstOrNull()?.let { s -> i.episodes[s]?.firstOrNull() }
    }

    val playFocus = remember { FocusRequester() }
    TvInitialFocus(playFocus, key = continueEpisode?.id)
    val showQuality = remember(show) { repo.seriesQuality(show) }
    val versions = remember(show, seriesList) { repo.seriesVersions(show) }
    val better = remember(versions, showQuality) {
        if (showQuality == "4K") null else versions.firstOrNull { repo.seriesQuality(it) == "4K" }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(RColors.Ink),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(340.dp)
            ) {
                PosterImage(show.name, info?.backdrop ?: info?.cover ?: show.cover, Modifier.fillMaxSize(), showTitle = false)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0x730B0C10),
                                0.3f to Color.Transparent,
                                0.5f to Color(0x330B0C10),
                                1f to RColors.Ink
                            )
                        )
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    CircleIconButton(RIcons.Back, "رجوع", { nav.back() }, background = Color(0x8C0B0C10))
                    CircleIconButton(
                        if (isFavorite) RIcons.HeartFilled else RIcons.Heart,
                        if (isFavorite) "إزالة من المفضلة" else "إضافة إلى المفضلة",
                        { repo.toggleFavorite(favKey) },
                        background = Color(0x8C0B0C10),
                        tint = if (isFavorite) RColors.Saffron else RColors.Text
                    )
                }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(show.name, style = T.display(28.sp, FontWeight.ExtraBold), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val rating = info?.rating ?: show.rating
                        if (rating != null) {
                            Icon(RIcons.StarFilled, contentDescription = null, tint = RColors.Saffron, modifier = Modifier.size(14.dp))
                            Text(rating, style = T.body(13.sp, FontWeight.Bold))
                        }
                        val meta = listOfNotNull(
                            info?.year ?: show.year,
                            info?.genre,
                            if (seasons.isNotEmpty()) "${seasons.size} ${if (seasons.size == 1) "موسم" else "مواسم"}" else null
                        ).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, style = T.body(13.sp, color = RColors.Text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        item {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val ep = continueEpisode
                if (ep != null) {
                    val resuming = lastEntry != null && lastEntry.itemId == ep.id
                    PrimaryButton(
                        text = if (resuming) "متابعة · الموسم ${ep.season}، الحلقة ${ep.num}" else "تشغيل الحلقة ${ep.num}",
                        onClick = { nav.playEpisode(show.id, ep.id) },
                        icon = RIcons.Play,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(playFocus),
                        height = 52.dp
                    )
                }
                if (better != null) {
                    UpgradeButton(onClick = { nav.show(better.id) })
                }
                if (versions.isNotEmpty()) {
                    Text("نسخ أخرى من المسلسل في اشتراكك", style = T.h2)
                    versions.take(6).forEach { v ->
                        VersionRow(
                            quality = repo.seriesQuality(v),
                            title = v.name,
                            subtitle = listOfNotNull(repo.seriesCategoryName(v.categoryId), v.year).joinToString(" · ").ifBlank { null },
                            onClick = { nav.show(v.id) }
                        )
                    }
                }
                val plot = info?.plot ?: show.plot
                if (!plot.isNullOrBlank()) {
                    Text(plot, style = T.body(14.sp, color = RColors.Text2).copy(lineHeight = 24.sp), maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
                val cast = info?.cast.orEmpty()
                if (cast.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        cast.forEach { CastMember(it) }
                    }
                }
            }
        }

        if (info == null) {
            item {
                if (!loadingDone) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = RColors.Saffron, strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp))
                    }
                } else {
                    EmptyState(RIcons.Layers, "تعذّر تحميل الحلقات", "تحقّق من الاتصال ثم حاول مرة أخرى.")
                }
            }
        } else {
            if (seasons.size > 1) {
                item {
                    LazyRow(
                        Modifier.padding(top = 12.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(seasons) { s ->
                            Chip("الموسم $s", s == activeSeason, { season = s })
                        }
                    }
                }
            }
            items(episodes, key = { it.id }) { ep ->
                val entry = history.firstOrNull { it.key == Keys.episode(ep.id) }
                EpisodeRow(
                    episode = ep,
                    entry = entry,
                    watched = Keys.episode(ep.id) in watched,
                    onClick = { nav.playEpisode(show.id, ep.id) }
                )
            }
            if (episodes.isEmpty()) {
                item { EmptyState(RIcons.Layers, "لا توجد حلقات", "لم يرسل المزوّد حلقات لهذا الموسم.") }
            }
        }
    }
}

private fun findSeasonOf(info: SeriesInfo?, episodeId: String): Int? {
    val i = info ?: return null
    return i.episodes.entries.firstOrNull { (_, list) -> list.any { it.id == episodeId } }?.key
}

@Composable
private fun EpisodeRow(episode: Episode, entry: WatchEntry?, watched: Boolean, onClick: () -> Unit) {
    val progress = when {
        entry != null -> entry.progress
        watched -> 1f
        else -> 0f
    }
    val overline = when {
        entry != null -> "الحلقة ${episode.num} · متبقي ${((entry.duration - entry.position) / 60_000L).coerceAtLeast(0)} د"
        watched -> "الحلقة ${episode.num} · شوهدت"
        else -> "الحلقة ${episode.num}"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .tvClickable(RoundedCornerShape(14.dp), onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .width(128.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            PosterImage(episode.title, episode.image, Modifier.fillMaxSize(), showTitle = false)
            if (episode.durationSecs > 0) {
                Text(
                    "${episode.durationSecs / 60} د",
                    style = T.body(10.5.sp, FontWeight.Bold).copy(lineHeight = 14.sp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xC70B0C10))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (progress > 0f) {
                ProgressLine(
                    progress,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    track = Color(0x33FFFFFF)
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                overline,
                style = T.body(12.sp, FontWeight.Medium, if (entry != null) RColors.Saffron else RColors.Text3),
                maxLines = 1
            )
            Text(episode.title, style = T.body(15.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val plot = episode.plot
            if (!plot.isNullOrBlank()) {
                Text(plot, style = T.body(12.5.sp, color = RColors.Text3), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
