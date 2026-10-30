package com.ratv.app.ui.screens

import com.ratv.app.i18n.tr
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ratv.app.data.Channel
import com.ratv.app.data.Keys
import com.ratv.app.data.Programme
import com.ratv.app.data.current
import com.ratv.app.data.forChannel
import com.ratv.app.data.next
import com.ratv.app.data.progress
import com.ratv.app.player.createPlayer
import com.ratv.app.player.mediaItemFor
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.Navigator
import com.ratv.app.ui.components.ChannelLogo
import com.ratv.app.ui.components.EmptyState
import com.ratv.app.ui.components.LiveBadge
import com.ratv.app.ui.components.ProgressLine
import com.ratv.app.ui.components.QualityBadge
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.RingsArt
import com.ratv.app.ui.components.TvInitialFocus
import com.ratv.app.ui.components.formatCount
import com.ratv.app.ui.components.hm
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.components.screenBackground
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RECENT = "__recent__"
private const val ALL = "__all__"
private const val FAV = "__fav__"

/** Live TV on a TV screen: sections, the channel list, and a preview of the selected channel. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LiveWideScreen(nav: Navigator) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shownChannels by repo.channels.collectAsStateWithLifecycle()
    val shownCategories by repo.liveCategories.collectAsStateWithLifecycle()
    val allChannels by repo.allChannels.collectAsStateWithLifecycle()
    val allCategories by repo.allLiveCategories.collectAsStateWithLifecycle()
    val hidden by repo.hiddenChannels.collectAsStateWithLifecycle()
    val favorites by repo.favorites.collectAsStateWithLifecycle()
    val recentIds by repo.recentChannels.collectAsStateWithLifecycle()
    val epg by repo.epg.collectAsStateWithLifecycle()
    val now = rememberNow()

    // Editing the lists, as on a receiver: every channel is listed, and OK deletes it or brings it back.
    var editing by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = editing) { editing = false }
    val channels = if (editing) allChannels else shownChannels
    val categories = if (editing) allCategories else shownCategories

    var selected by rememberSaveable { mutableStateOf(ALL) }
    val byId = remember(channels) { channels.associateBy { it.id } }
    val recent = remember(recentIds, byId) { recentIds.mapNotNull { byId[it] } }
    val counts = remember(channels) { channels.groupingBy { it.categoryId }.eachCount() }
    val favoriteCount = remember(channels, favorites) { channels.count { Keys.live(it.id) in favorites } }
    val entries = remember(categories, counts, favoriteCount, recent, channels) {
        listOf(
            SideEntry(RECENT, tr("شوهدت مؤخراً"), recent.size, RIcons.Clock),
            SideEntry(ALL, tr("الكل"), channels.size, RIcons.Tv),
            SideEntry(FAV, tr("المفضلة"), favoriteCount, RIcons.Star)
        ) + categories.map { SideEntry(it.id, it.name, counts[it.id] ?: 0) }
    }
    val visible = remember(channels, favorites, selected, recent) {
        when (selected) {
            ALL -> channels
            RECENT -> recent
            FAV -> channels.filter { Keys.live(it.id) in favorites }
            else -> channels.filter { it.categoryId == selected }
        }
    }
    val sectionName = entries.firstOrNull { it.key == selected }?.name ?: tr("القنوات")

    // ---------------------------------------------------------- focus
    val listState = rememberLazyListState()
    val lastId = remember { repo.prefs.lastChannelId }
    val startId = remember(visible) { visible.firstOrNull { it.id == lastId }?.id ?: visible.firstOrNull()?.id }
    var focusedId by remember { mutableStateOf<String?>(null) }
    val rowFocus = remember { FocusRequester() }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // The list's position is always set: a position kept from the last visit must not hide the last channel.
        val index = visible.indexOfFirst { it.id == startId }
        listState.scrollToItem(if (index > 3) index - 3 else 0)
    }
    TvInitialFocus(rowFocus)

    fun enterList() {
        scope.launch {
            listState.scrollToItem(0)
            entered = true
            delay(80L)
            runCatching { rowFocus.requestFocus() }
        }
    }
    val focusTarget = if (entered) visible.firstOrNull()?.id else startId

    // -------------------------------------------------------- preview
    val previewOn = remember { repo.prefs.livePreview }
    val preview: ExoPlayer? = remember { if (previewOn) createPlayer(context, "2", false) else null }
    var previewingId by remember { mutableStateOf<String?>(null) }
    DisposableEffect(preview) {
        onDispose { preview?.release() }
    }
    val shown: Channel? = focusedId?.let { byId[it] } ?: visible.firstOrNull { it.id == startId }
    LaunchedEffect(shown?.id, editing) {
        val p = preview ?: return@LaunchedEffect
        if (editing) {
            // No picture while editing: moving through the list deletes and restores, nothing plays.
            p.stop()
            previewingId = null
            return@LaunchedEffect
        }
        val ch = shown ?: return@LaunchedEffect
        if (previewingId == ch.id) return@LaunchedEffect
        p.stop()
        previewingId = null
        // Start only once the selection rests on a channel, so moving through the list stays quick.
        delay(1_200L)
        p.setMediaItem(mediaItemFor(ch.url))
        p.prepare()
        p.playWhenReady = true
        previewingId = ch.id
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, preview) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> preview?.pause()
                Lifecycle.Event.ON_START -> if (previewingId != null) preview?.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun open(ch: Channel) {
        // Free the connection before the full-screen player opens its own.
        preview?.stop()
        previewingId = null
        nav.playLive(ch.id)
    }

    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1_800L)
            toast = null
        }
    }
    fun toggleFavorite(ch: Channel) {
        val key = Keys.live(ch.id)
        val adding = key !in favorites
        repo.toggleFavorite(key)
        toast = if (adding) tr("أُضيفت {0} إلى المفضلة", ch.name) else tr("أُزيلت {0} من المفضلة", ch.name)
    }
    fun toggleHidden(ch: Channel) {
        val deleting = ch.id !in hidden
        repo.setChannelsHidden(listOf(ch.id), deleting)
        toast = if (deleting) tr("حُذفت {0} من القوائم", ch.name) else tr("رجعت {0} للقوائم", ch.name)
    }
    // The section shown, deleted or brought back as a whole (a real section, not "all" or the favourites).
    val sectionIds = if (selected == ALL || selected == FAV || selected == RECENT) emptyList() else visible.map { it.id }
    val sectionDeleted = sectionIds.isNotEmpty() && sectionIds.all { it in hidden }
    fun toggleSection() {
        repo.setChannelsHidden(sectionIds, !sectionDeleted)
        toast = if (sectionDeleted) tr("رجع القسم «{0}» للقوائم", sectionName) else tr("حُذف القسم «{0}» من القوائم", sectionName)
    }

    Box(
        Modifier
            .fillMaxSize()
            .screenBackground()
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionColumn(
                entries = entries,
                selected = selected,
                onSelect = { key ->
                    if (key != selected) {
                        selected = key
                        entered = true
                        scope.launch { listState.scrollToItem(0) }
                    }
                },
                onEnter = { enterList() },
                modifier = Modifier
                    .width(270.dp)
                    .fillMaxHeight()
            )

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Row(
                    Modifier.padding(start = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(sectionName, style = T.h2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(formatCount(visible.size) + tr(" قناة"), style = T.caption)
                    Spacer(Modifier.weight(1f))
                    if (editing && sectionIds.isNotEmpty()) {
                        EditPill(if (sectionDeleted) tr("إرجاع القسم") else tr("حذف القسم كله"), RIcons.Close, danger = !sectionDeleted) { toggleSection() }
                    }
                    EditPill(if (editing) tr("تم") else tr("تعديل القوائم"), if (editing) RIcons.Check else RIcons.ListIcon, active = editing) {
                        editing = !editing
                    }
                }
                if (editing) {
                    Text(
                        tr("OK يحذف القناة أو يرجعها · المحذوفة ما تطلع في القوائم ولا الدليل ولا التنقّل بالأسهم · {0} محذوفة", hidden.size),
                        style = T.small,
                        maxLines = 2,
                        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                    )
                }
                if (visible.isEmpty()) {
                    EmptyState(
                        if (selected == FAV) RIcons.Star else RIcons.Tv,
                        if (selected == FAV) tr("لا توجد قنوات مفضلة") else tr("لا توجد قنوات هنا"),
                        if (selected == FAV) tr("اضغط مطولاً على OK فوق أي قناة لإضافتها إلى المفضلة.") else tr("اختر قسماً آخر من القائمة.")
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                            .background(RColors.Surface),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(visible, key = { it.id }) { ch ->
                            WideChannelRow(
                                channel = ch,
                                programme = epg.forChannel(ch).current(now)?.title,
                                favorite = Keys.live(ch.id) in favorites,
                                active = ch.id == shown?.id,
                                deleted = editing && ch.id in hidden,
                                focusRequester = if (ch.id == focusTarget) rowFocus else null,
                                onFocused = { focusedId = ch.id },
                                onOpen = { if (editing) toggleHidden(ch) else open(ch) },
                                onToggleFavorite = { toggleFavorite(ch) }
                            )
                        }
                    }
                }
            }

            PreviewPane(
                channel = shown,
                programmes = shown?.let { epg.forChannel(it) }.orEmpty(),
                now = now,
                player = preview,
                showVideo = preview != null && previewingId != null && previewingId == shown?.id,
                onOpen = { shown?.let { open(it) } },
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
            )
        }

        val message = toast
        if (message != null) {
            Text(
                message,
                style = T.body(14.sp, FontWeight.Medium),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xE61C1F27))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun WideChannelRow(
    channel: Channel,
    programme: String?,
    favorite: Boolean,
    active: Boolean,
    deleted: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .tvLongPress(onToggleFavorite)
            .clip(shape)
            .background(if (active) RColors.Surface3 else Color.Transparent)
            .tvClickable(shape, onClickLabel = tr("مشاهدة {0}", channel.name), onClick = onOpen)
            .alpha(if (deleted) 0.45f else 1f)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            channel.num.toString(),
            style = T.body(13.sp, FontWeight.Bold, if (active) RColors.Saffron else RColors.Text3),
            modifier = Modifier.width(44.dp),
            maxLines = 1
        )
        ChannelLogo(channel.name, channel.logo, 42.dp, corner = 10.dp)
        Column(Modifier.weight(1f)) {
            Text(
                channel.name,
                style = T.body(15.sp, FontWeight.Bold, if (active) RColors.Saffron else RColors.Text)
                    .copy(textDecoration = if (deleted) TextDecoration.LineThrough else null),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (programme != null) {
                Text(programme, style = T.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        channel.quality?.let { QualityBadge(it, color = if (it == "4K") RColors.SaffronLight else RColors.Text2) }
        if (favorite) Icon(RIcons.StarFilled, contentDescription = tr("مفضلة"), tint = RColors.Saffron, modifier = Modifier.size(16.dp))
        if (deleted) DeletedMark()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PreviewPane(
    channel: Channel?,
    programmes: List<Programme>,
    now: Long,
    player: ExoPlayer?,
    showVideo: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier
) {
    Column(modifier) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(Color.Black)
        ) {
            if (player != null && showVideo) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            isFocusable = false
                            isFocusableInTouchMode = false
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                            setPlayer(player)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                RingsArt(Modifier.fillMaxSize())
                if (channel != null) {
                    ChannelLogo(channel.name, channel.logo, 96.dp, Modifier.align(Alignment.Center), corner = 20.dp)
                }
            }
            LiveBadge(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            )
        }
        if (channel == null) return@Column
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(channel.num.toString(), style = T.display(20.sp, FontWeight.Bold, RColors.Saffron))
            Text(
                channel.name,
                style = T.display(20.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            channel.quality?.let { QualityBadge(it, color = if (it == "4K") RColors.SaffronLight else RColors.Text2) }
        }
        val programme = programmes.current(now)
        val next = programmes.next(now)
        if (programme != null) {
            Spacer(Modifier.height(10.dp))
            Text(programme.title, style = T.body(16.sp, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(hm(programme.start), style = T.small)
                ProgressLine(programme.progress(now), Modifier.weight(1f))
                Text(hm(programme.end), style = T.small)
            }
            val desc = programme.desc
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(desc, style = T.body(13.sp, color = RColors.Text2), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(tr("لا يوجد دليل برامج لهذه القناة"), style = T.caption)
        }
        if (next != null) {
            Spacer(Modifier.height(8.dp))
            Text(tr("التالي {0} · {1}", hm(next.start), next.title), style = T.body(13.sp, color = RColors.Text3), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(RColors.AccentBrush)
                .tvClickable(RoundedCornerShape(14.dp), ringColor = Color.White, onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(RIcons.Play, contentDescription = null, tint = RColors.OnSaffron, modifier = Modifier.size(16.dp))
            Text(tr("مشاهدة بملء الشاشة"), style = T.body(14.sp, FontWeight.Bold, RColors.OnSaffron))
        }
        Spacer(Modifier.height(8.dp))
        Text(tr("اضغط مطولاً على OK فوق قناة لإضافتها إلى المفضلة"), style = T.small)
    }
}
