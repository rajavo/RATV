package com.ratv.app.ui.screens

import com.ratv.app.i18n.tr
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ratv.app.data.Channel
import com.ratv.app.data.Keys
import com.ratv.app.data.Programme
import com.ratv.app.data.current
import com.ratv.app.data.forChannel
import com.ratv.app.data.progress
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.Navigator
import com.ratv.app.ui.components.ChannelLogo
import com.ratv.app.ui.components.Chip
import com.ratv.app.ui.components.CircleIconButton
import com.ratv.app.ui.components.EmptyState
import com.ratv.app.ui.components.LiveBadge
import com.ratv.app.ui.components.ProgressLine
import com.ratv.app.ui.components.QualityBadge
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.RingsArt
import com.ratv.app.ui.components.ScreenHeader
import com.ratv.app.ui.components.TvInitialFocus
import com.ratv.app.ui.components.rememberIsWide
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.components.hm
import com.ratv.app.ui.components.screenBackground
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T

private const val ALL = "__all__"
private const val FAV = "__fav__"
private const val RECENT = "__recent__"

@Composable
fun LiveScreen(nav: Navigator) {
    if (rememberIsWide()) {
        LiveWideScreen(nav)
    } else {
        LivePhoneScreen(nav)
    }
}

@Composable
private fun LivePhoneScreen(nav: Navigator) {
    val repo = LocalRepo.current
    val shownChannels by repo.channels.collectAsStateWithLifecycle()
    val shownCategories by repo.liveCategories.collectAsStateWithLifecycle()
    val allChannels by repo.allChannels.collectAsStateWithLifecycle()
    val allCategories by repo.allLiveCategories.collectAsStateWithLifecycle()
    val hidden by repo.hiddenChannels.collectAsStateWithLifecycle()
    val favorites by repo.favorites.collectAsStateWithLifecycle()
    val recentIds by repo.recentChannels.collectAsStateWithLifecycle()
    val epg by repo.epg.collectAsStateWithLifecycle()
    val now = rememberNow()
    // Editing the lists, as on a receiver: every channel is listed, and its button deletes it or brings it back.
    var editing by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = editing) { editing = false }
    val channels = if (editing) allChannels else shownChannels
    val categories = if (editing) allCategories else shownCategories
    var selected by rememberSaveable { mutableStateOf(ALL) }

    val visible = remember(channels, favorites, selected, recentIds) {
        when (selected) {
            ALL -> channels
            FAV -> channels.filter { Keys.live(it.id) in favorites }
            RECENT -> {
                val byId = channels.associateBy { it.id }
                recentIds.mapNotNull { byId[it] }
            }
            else -> channels.filter { it.categoryId == selected }
        }
    }
    val featured = remember(shownChannels) {
        val last = repo.prefs.lastChannelId
        shownChannels.firstOrNull { it.id == last } ?: shownChannels.firstOrNull()
    }
    val focusTarget = remember(visible, featured) {
        visible.firstOrNull { it.id == featured?.id }?.id ?: visible.firstOrNull()?.id
    }
    val rowFocus = remember { FocusRequester() }
    TvInitialFocus(rowFocus, key = focusTarget)

    LazyColumn(
        Modifier
            .fillMaxSize()
            .screenBackground(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            ScreenHeader(tr("البث المباشر"), Modifier.statusBarsPadding()) {
                CircleIconButton(
                    if (editing) RIcons.Check else RIcons.ListIcon,
                    if (editing) tr("تم") else tr("تعديل القوائم"),
                    { editing = !editing },
                    background = if (editing) RColors.Saffron else RColors.Surface,
                    tint = if (editing) RColors.OnSaffron else RColors.Text
                )
                Spacer(Modifier.width(8.dp))
                CircleIconButton(RIcons.Search, tr("بحث في القنوات"), { nav.search() })
                CircleIconButton(RIcons.Trophy, tr("جدول المباريات"), { nav.matches() })
                Spacer(Modifier.width(8.dp))
                CircleIconButton(RIcons.Calendar, tr("دليل البرامج"), { nav.guide() })
            }
        }

        if (editing) {
            item {
                val sectionIds = if (selected == ALL || selected == FAV || selected == RECENT) emptyList() else visible.map { it.id }
                val sectionDeleted = sectionIds.isNotEmpty() && sectionIds.all { it in hidden }
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr("✕ يحذف القناة من القوائم و↺ يرجعها · المحذوفة ما تطلع في القوائم ولا الدليل ولا التنقّل · {0} محذوفة", hidden.size),
                        style = T.small
                    )
                    if (sectionIds.isNotEmpty()) {
                        EditPill(if (sectionDeleted) tr("إرجاع القسم") else tr("حذف القسم كله"), RIcons.Close, danger = !sectionDeleted) {
                            repo.setChannelsHidden(sectionIds, !sectionDeleted)
                        }
                    }
                }
            }
        }

        if (featured != null && !editing) {
            item {
                val programme = epg.forChannel(featured).current(now)
                PreviewCard(
                    channel = featured,
                    programme = programme,
                    now = now,
                    onClick = { nav.playLive(featured.id) },
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)
                )
            }
        }

        item {
            LazyRow(
                Modifier.padding(top = 10.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Chip(tr("الكل"), selected == ALL, { selected = ALL }) }
                if (recentIds.isNotEmpty()) item { Chip(tr("شوهدت مؤخراً"), selected == RECENT, { selected = RECENT }) }
                item { Chip(tr("المفضلة"), selected == FAV, { selected = FAV }) }
                items(categories, key = { it.id }) { cat ->
                    Chip(cat.name, selected == cat.id, { selected = cat.id })
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                if (selected == FAV) {
                    EmptyState(RIcons.Star, tr("لا توجد قنوات مفضلة"), tr("اضغط على النجمة بجانب أي قناة لإضافتها هنا."))
                } else {
                    EmptyState(RIcons.Tv, tr("لا توجد قنوات"), tr("لم نجد قنوات في هذه الفئة."))
                }
            }
        }

        items(visible, key = { it.id }) { ch ->
            val programme = epg.forChannel(ch).current(now)
            ChannelRow(
                channel = ch,
                programme = programme,
                categoryName = if (selected == ALL || selected == FAV) repo.liveCategoryName(ch.categoryId) else null,
                now = now,
                favorite = Keys.live(ch.id) in favorites,
                active = ch.id == featured?.id,
                onOpen = { if (editing) repo.setChannelsHidden(listOf(ch.id), ch.id !in hidden) else nav.playLive(ch.id) },
                onToggleFavorite = { repo.toggleFavorite(Keys.live(ch.id)) },
                editing = editing,
                deleted = ch.id in hidden,
                onToggleDeleted = { repo.setChannelsHidden(listOf(ch.id), ch.id !in hidden) },
                focusRequester = if (ch.id == focusTarget) rowFocus else null
            )
        }
    }
}

@Composable
fun ChannelRow(
    channel: Channel,
    programme: Programme?,
    categoryName: String?,
    now: Long,
    favorite: Boolean,
    active: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    focusRequester: FocusRequester? = null,
    editing: Boolean = false,
    deleted: Boolean = false,
    onToggleDeleted: () -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(70.dp)
            .background(if (active) RColors.Surface else Color.Transparent)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(70.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .clip(RoundedCornerShape(12.dp))
                .tvClickable(RoundedCornerShape(12.dp), onClick = onOpen)
                .alpha(if (editing && deleted) 0.45f else 1f)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                channel.num.toString(),
                style = T.body(12.sp, FontWeight.Medium, if (active) RColors.Saffron else RColors.Text3),
                modifier = Modifier.width(30.dp),
                maxLines = 1
            )
            ChannelLogo(channel.name, channel.logo, 46.dp, corner = 13.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        channel.name,
                        style = T.body(15.sp, FontWeight.Bold)
                            .copy(textDecoration = if (editing && deleted) TextDecoration.LineThrough else null),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    channel.quality?.let { QualityBadge(it) }
                }
                Text(
                    programme?.title ?: categoryName ?: tr("بث مباشر"),
                    style = T.body(13.sp, color = RColors.Text2),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (programme != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProgressLine(programme.progress(now), Modifier.weight(1f))
                        Text("${hm(programme.start)} – ${hm(programme.end)}", style = T.small)
                    }
                }
            }
        }
        if (editing) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (deleted) RColors.Surface2 else RColors.DangerBg)
                    .tvClickable(CircleShape, onClick = onToggleDeleted),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (deleted) RIcons.Refresh else RIcons.Close,
                    contentDescription = if (deleted) tr("إرجاع القناة") else tr("حذف القناة من القوائم"),
                    tint = if (deleted) RColors.Text else RColors.Danger,
                    modifier = Modifier.size(20.dp)
                )
            }
            return@Row
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .tvClickable(CircleShape, onClick = onToggleFavorite),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (favorite) RIcons.StarFilled else RIcons.Star,
                contentDescription = if (favorite) tr("إزالة من المفضلة") else tr("إضافة إلى المفضلة"),
                tint = if (favorite) RColors.Saffron else RColors.Text3,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun PreviewCard(
    channel: Channel,
    programme: Programme?,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF172028), RColors.Surface)))
            .tvClickable(RoundedCornerShape(18.dp), onClickLabel = tr("تشغيل {0}", channel.name), onClick = onClick)
    ) {
        RingsArt(Modifier.fillMaxSize())
        ChannelLogo(
            channel.name,
            channel.logo,
            76.dp,
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 24.dp, bottom = 36.dp),
            corner = 18.dp
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6080A0D))))
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiveBadge()
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(RColors.AccentBrush),
                contentAlignment = Alignment.Center
            ) {
                Icon(RIcons.Play, contentDescription = null, tint = RColors.OnSaffron, modifier = Modifier.size(18.dp))
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${channel.num} · ${channel.name}", style = T.body(12.sp, FontWeight.Medium, Color(0xFFD9D4CC)), maxLines = 1)
                channel.quality?.let { QualityBadge(it, color = Color(0xFFD9D4CC)) }
            }
            Text(programme?.title ?: channel.name, style = T.display(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (programme != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProgressLine(programme.progress(now), Modifier.weight(1f), track = Color(0x33FFFFFF))
                    Text("${hm(programme.start)} – ${hm(programme.end)}", style = T.body(11.sp, color = Color(0xFFD9D4CC)))
                }
            } else {
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
