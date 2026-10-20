package com.ratv.app.player

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ratv.app.data.Episode
import com.ratv.app.data.Keys
import com.ratv.app.data.WatchEntry
import com.ratv.app.data.current
import com.ratv.app.data.forChannel
import com.ratv.app.data.next
import com.ratv.app.data.progress
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.Navigator
import com.ratv.app.ui.components.ChannelLogo
import com.ratv.app.ui.components.CircleIconButton
import com.ratv.app.ui.components.LiveBadge
import com.ratv.app.ui.components.OutlineButton
import com.ratv.app.ui.components.PrimaryButton
import com.ratv.app.ui.components.ProgressLine
import com.ratv.app.ui.components.QualityBadge
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.formatClock
import com.ratv.app.ui.components.hm
import com.ratv.app.ui.screens.rememberNow
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val Glass = Color(0x1FFFFFFF)
private val SoftText = Color(0xFFD9D4CC)

@androidx.annotation.OptIn(UnstableApi::class)
private val resizeModes = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "ملاءمة",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "تكبير",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "ملء"
)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(kind: String, id: String, seriesId: String?, navigator: Navigator) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val epg by repo.epg.collectAsStateWithLifecycle()
    val channels by repo.channels.collectAsStateWithLifecycle()
    val now = rememberNow(15_000L)

    var current by remember { mutableStateOf<Playable?>(null) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var notFound by remember { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var hasTrackChoice by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seekable by remember { mutableStateOf(false) }

    var controlsVisible by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    var locked by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    var resizeIndex by remember { mutableIntStateOf(0) }

    val player = remember { createPlayer(context, repo.prefs.bufferMode) }
    val inPip = rememberIsInPictureInPicture(activity)
    val canPip = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    fun touch() {
        lastTouch = System.currentTimeMillis()
    }

    fun saveProgress() {
        val item = current ?: return
        if (item.isLive) return
        val total = player.duration
        if (total == C.TIME_UNSET || total <= 0L) return
        repo.saveProgress(
            WatchEntry(
                key = if (item.kind == Playable.KIND_MOVIE) Keys.movie(item.id) else Keys.episode(item.id),
                kind = item.kind,
                itemId = item.id,
                seriesId = item.seriesId,
                title = item.title,
                subtitle = item.subtitle,
                image = item.image,
                url = item.url,
                position = player.currentPosition,
                duration = total,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun play(item: Playable) {
        saveProgress()
        current = item
        panelOpen = false
        controlsVisible = true
        touch()
    }

    // ------------------------------------------------------------ resolve
    LaunchedEffect(kind, id, seriesId) {
        val resolved = resolvePlayable(repo, kind, id, seriesId)
        if (resolved == null) {
            notFound = true
        } else {
            episodes = resolved.episodes
            current = resolved.playable
        }
    }

    // ------------------------------------------------- start the stream
    LaunchedEffect(current) {
        val item = current ?: return@LaunchedEffect
        val builder = MediaItem.Builder().setUri(item.url)
        if (item.url.contains(".m3u8", ignoreCase = true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        playerError = null
        ended = false
        player.setMediaItem(builder.build(), item.startPosition.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
        if (item.isLive) repo.prefs.lastChannelId = item.id
    }

    // ------------------------------------------------------- listeners
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                ended = playbackState == Player.STATE_ENDED
                if (playbackState == Player.STATE_READY) playerError = null
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                } else {
                    playerError = describeError(error)
                    controlsVisible = true
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.length }
                val text = tracks.groups.count { it.type == C.TRACK_TYPE_TEXT }
                hasTrackChoice = audio > 1 || text > 0
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            saveProgress()
            player.release()
        }
    }

    // ---------------------------------------------------- position tick
    LaunchedEffect(player) {
        var ticks = 0
        while (isActive) {
            position = player.currentPosition
            val d = player.duration
            duration = if (d == C.TIME_UNSET || d < 0L) 0L else d
            seekable = player.isCurrentMediaItemSeekable
            ticks++
            if (ticks % 20 == 0 && player.isPlaying) saveProgress()
            delay(500L)
        }
    }

    // ------------------------------------------------- lifecycle & window
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        var resumeOnStart = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resumeOnStart = player.playWhenReady
                    player.pause()
                    saveProgress()
                }
                Lifecycle.Event.ON_START -> {
                    if (resumeOnStart) player.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        val previousOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = previousOrientation
            view.keepScreenOn = false
        }
    }

    // ------------------------------------------------------- behaviour
    LaunchedEffect(controlsVisible, isPlaying, lastTouch, panelOpen, showTracks) {
        if (controlsVisible && isPlaying && !panelOpen && !showTracks) {
            delay(4_000L)
            controlsVisible = false
        }
    }

    val item = current
    val nextEpisode: Episode? = remember(item, episodes) {
        if (item == null || item.kind != Playable.KIND_EPISODE) null
        else {
            val index = episodes.indexOfFirst { it.id == item.id }
            if (index >= 0 && index + 1 < episodes.size) episodes[index + 1] else null
        }
    }

    fun playNextEpisode() {
        val ep = nextEpisode ?: return
        val currentItem = item ?: return
        val sid = currentItem.seriesId ?: return
        val start = repo.historyEntry(Keys.episode(ep.id))?.position ?: 0L
        play(episodePlayable(ep, sid, currentItem.title, currentItem.image, start))
    }

    LaunchedEffect(ended) {
        if (ended && nextEpisode != null) {
            delay(1_500L)
            playNextEpisode()
        }
    }

    BackHandler(enabled = locked) { locked = false }
    BackHandler(enabled = !locked && (panelOpen || showTracks)) {
        panelOpen = false
        showTracks = false
    }

    val zapList = remember(item?.channel?.categoryId, channels) {
        val cat = item?.channel?.categoryId
        if (cat == null) emptyList() else channels.filter { it.categoryId == cat }
    }
    val programmes = item?.channel?.let { epg.forChannel(it) }.orEmpty()
    val programme = programmes.current(now)
    val nextProgramme = programmes.next(now)

    fun zap(step: Int) {
        val ch = item?.channel ?: return
        if (zapList.isEmpty()) return
        val index = zapList.indexOfFirst { it.id == ch.id }
        val target = zapList[((if (index < 0) 0 else index) + step + zapList.size) % zapList.size]
        play(liveToPlayable(target))
    }

    // ------------------------------------------------------------- UI
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val exoPlayer = player
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setKeepContentOnPlayerReset(true)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setPlayer(exoPlayer)
                }
            },
            update = { it.resizeMode = resizeModes[resizeIndex].first },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (!locked) {
                                controlsVisible = !controlsVisible
                                touch()
                            } else {
                                controlsVisible = true
                                touch()
                            }
                        },
                        onDoubleTap = { offset ->
                            if (!locked && seekable) {
                                val target = if (offset.x < size.width / 2f) player.currentPosition - 10_000L else player.currentPosition + 10_000L
                                player.seekTo(target.coerceAtLeast(0L))
                                touch()
                            }
                        }
                    )
                }
        )

        if (buffering && playerError == null && !notFound) {
            CircularProgressIndicator(
                color = RColors.Saffron,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
            )
        }

        if (!inPip) {
            if (locked) {
                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Row(
                        Modifier
                            .padding(24.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xB30B0C10))
                            .clickable(role = Role.Button) { locked = false; touch() }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(RIcons.Unlock, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Text("فتح القفل", style = T.body(14.sp, FontWeight.Medium))
                    }
                }
            } else {
                AnimatedVisibility(
                    visible = controlsVisible && !panelOpen && item != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (item != null) {
                        Controls(
                            item = item,
                            isPlaying = isPlaying,
                            ended = ended,
                            position = position,
                            duration = duration,
                            seekable = seekable,
                            programmeTitle = programme?.title,
                            programmeStart = programme?.start,
                            programmeEnd = programme?.end,
                            programmeProgress = programme?.progress(now),
                            nextLabel = nextProgramme?.let { "التالي ${hm(it.start)} · ${it.title}" },
                            hasTrackChoice = hasTrackChoice,
                            canPip = canPip,
                            resizeLabel = resizeModes[resizeIndex].second,
                            hasNextEpisode = nextEpisode != null,
                            onBack = { navigator.back() },
                            onPlayPause = {
                                touch()
                                if (player.isPlaying) {
                                    player.pause()
                                } else {
                                    if (ended) player.seekTo(0L)
                                    player.play()
                                }
                            },
                            onSeekBy = { delta ->
                                touch()
                                val target = (player.currentPosition + delta).coerceAtLeast(0L)
                                player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                            },
                            onSeekTo = { ms ->
                                touch()
                                player.seekTo(ms)
                            },
                            onTracks = {
                                touch()
                                showTracks = true
                            },
                            onPip = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    try {
                                        activity?.enterPictureInPictureMode(
                                            PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                                        )
                                    } catch (e: Exception) {
                                        // PiP not available right now.
                                    }
                                }
                            },
                            onResize = {
                                touch()
                                resizeIndex = (resizeIndex + 1) % resizeModes.size
                            },
                            onChannels = {
                                touch()
                                panelOpen = true
                            },
                            onGuide = { navigator.guide() },
                            onNextEpisode = { playNextEpisode() },
                            onLock = {
                                locked = true
                                controlsVisible = false
                            },
                            onTouch = { touch() }
                        )
                    }
                }

                AnimatedVisibility(
                    visible = panelOpen,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    ChannelPanel(
                        channels = zapList,
                        currentId = item?.id,
                        categoryName = item?.channel?.let { repo.liveCategoryName(it.categoryId) },
                        epgLookup = { ch -> epg.forChannel(ch).current(now)?.title },
                        onPick = { ch -> play(liveToPlayable(ch)) },
                        onClose = { panelOpen = false }
                    )
                }
            }

            val error = playerError
            if (error != null && !panelOpen) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 420.dp)
                        .padding(24.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xE60B0C10))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(RIcons.Alert, contentDescription = null, tint = RColors.Danger, modifier = Modifier.size(32.dp))
                    Text(error, style = T.body(15.sp), textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(
                            "إعادة المحاولة",
                            onClick = {
                                playerError = null
                                player.prepare()
                                player.play()
                            },
                            icon = RIcons.Refresh,
                            height = 46.dp
                        )
                        if (item?.isLive == true && zapList.size > 1) {
                            OutlineButton("القناة التالية", onClick = { zap(1) }, height = 46.dp)
                        }
                    }
                }
            }

            if (notFound) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("تعذّر العثور على هذا المحتوى.", style = T.body(16.sp, FontWeight.Bold))
                    OutlineButton("رجوع", onClick = { navigator.back() }, icon = RIcons.Back)
                }
            }
        }
    }

    if (showTracks) {
        TracksDialog(player = player, onDismiss = { showTracks = false })
    }
}

@Composable
private fun Controls(
    item: Playable,
    isPlaying: Boolean,
    ended: Boolean,
    position: Long,
    duration: Long,
    seekable: Boolean,
    programmeTitle: String?,
    programmeStart: Long?,
    programmeEnd: Long?,
    programmeProgress: Float?,
    nextLabel: String?,
    hasTrackChoice: Boolean,
    canPip: Boolean,
    resizeLabel: String,
    hasNextEpisode: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onTracks: () -> Unit,
    onPip: () -> Unit,
    onResize: () -> Unit,
    onChannels: () -> Unit,
    onGuide: () -> Unit,
    onNextEpisode: () -> Unit,
    onLock: () -> Unit,
    onTouch: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Brush.verticalGradient(listOf(Color(0xD9050608), Color.Transparent)))
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(180.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6050608))))
        )

        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleIconButton(RIcons.Back, "رجوع", onBack, background = Glass)
            Spacer(Modifier.width(12.dp))
            val channel = item.channel
            if (channel != null) {
                ChannelLogo(channel.name, channel.logo, 40.dp, corner = 11.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        item.title,
                        style = T.body(16.sp, FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    channel?.quality?.let { QualityBadge(it, color = SoftText) }
                    if (item.isLive) LiveBadge()
                }
                val sub = if (item.isLive) programmeTitle else item.subtitle
                if (!sub.isNullOrBlank()) {
                    Text(sub, style = T.body(13.sp, color = SoftText), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (hasTrackChoice) GlassIcon(RIcons.Captions, "الصوت والترجمة", onTracks)
            if (canPip) GlassIcon(RIcons.Pip, "صورة داخل صورة", onPip)
            GlassIcon(RIcons.Aspect, "حجم الصورة: $resizeLabel", onResize)
        }

        // Center transport (media controls stay left-to-right)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(40.dp)
            ) {
                SeekButton(RIcons.Rewind, "رجوع 10 ثوانٍ", seekable) { onSeekBy(-10_000L) }
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Color(0x29FFFFFF))
                        .clickable(role = Role.Button, onClick = onPlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) RIcons.Pause else RIcons.Play,
                        contentDescription = if (isPlaying) "إيقاف مؤقت" else if (ended) "إعادة التشغيل" else "تشغيل",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
                SeekButton(RIcons.FastForward, "تقديم 10 ثوانٍ", seekable) { onSeekBy(10_000L) }
            }
        }

        // Bottom area
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                if (item.isLive) {
                    if (programmeProgress != null && programmeStart != null && programmeEnd != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(hm(programmeStart), style = T.body(12.sp, FontWeight.Medium, SoftText))
                            ProgressLine(programmeProgress, Modifier.weight(1f), height = 4.dp, track = Color(0x38FFFFFF))
                            Text(hm(programmeEnd), style = T.body(12.sp, FontWeight.Medium, SoftText))
                        }
                    }
                } else if (duration > 0L) {
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(formatClock(position), style = T.body(12.sp, FontWeight.Medium, SoftText))
                        Slider(
                            value = dragging ?: (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f),
                            onValueChange = {
                                dragging = it
                                onTouch()
                            },
                            onValueChangeFinished = {
                                val d = dragging
                                if (d != null) onSeekTo((d * duration).toLong())
                                dragging = null
                            },
                            enabled = seekable,
                            colors = SliderDefaults.colors(
                                thumbColor = RColors.Saffron,
                                activeTrackColor = RColors.Saffron,
                                inactiveTrackColor = Color(0x38FFFFFF)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Text(formatClock(duration), style = T.body(12.sp, FontWeight.Medium, SoftText))
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (item.isLive) {
                    PillButton(RIcons.ListIcon, "القنوات", onChannels)
                    PillButton(RIcons.Calendar, "الدليل", onGuide)
                    if (nextLabel != null) {
                        Text(
                            nextLabel,
                            style = T.body(13.sp, color = SoftText),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                } else {
                    if (hasNextEpisode) PillButton(RIcons.SkipNext, "الحلقة التالية", onNextEpisode)
                    Spacer(Modifier.weight(1f))
                }
                GlassIcon(RIcons.Lock, "قفل الشاشة", onLock)
            }
        }
    }
}

@Composable
private fun GlassIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    CircleIconButton(icon, description, onClick, background = Color.Transparent, tint = Color.White)
}

@Composable
private fun SeekButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val tint = if (enabled) Color.White else Color(0x61FFFFFF)
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(36.dp))
        Text("10", style = T.body(10.sp, FontWeight.Bold, tint).copy(lineHeight = 12.sp), modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Glass)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(label, style = T.body(14.sp, FontWeight.Medium))
    }
}

@Composable
private fun ChannelPanel(
    channels: List<com.ratv.app.data.Channel>,
    currentId: String?,
    categoryName: String?,
    epgLookup: (com.ratv.app.data.Channel) -> String?,
    onPick: (com.ratv.app.data.Channel) -> Unit,
    onClose: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentId) {
        val index = channels.indexOfFirst { it.id == currentId }
        if (index > 2) listState.scrollToItem(index - 2)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
    ) {
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .width(340.dp)
                .fillMaxHeight()
                .background(Color(0xF00B0C10))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 12.dp, end = 12.dp, top = 16.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("القنوات", style = T.display(17.sp))
                    Text(
                        listOfNotNull(categoryName, "${channels.size} قناة").joinToString(" · "),
                        style = T.caption,
                        maxLines = 1
                    )
                }
                CircleIconButton(RIcons.Close, "إغلاق قائمة القنوات", onClose, background = RColors.Surface2, size = 40.dp, iconSize = 20.dp)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                items(channels, key = { it.id }) { ch ->
                    val selected = ch.id == currentId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) RColors.Surface2 else Color.Transparent)
                            .clickable(role = Role.Button) { onPick(ch) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            ch.num.toString(),
                            style = T.body(12.sp, color = if (selected) RColors.Saffron else RColors.Text3),
                            modifier = Modifier.width(30.dp),
                            maxLines = 1
                        )
                        ChannelLogo(ch.name, ch.logo, 38.dp, corner = 10.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                ch.name,
                                style = T.body(14.sp, FontWeight.Bold, if (selected) RColors.Saffron else RColors.Text),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val now = epgLookup(ch)
                            if (now != null) Text(now, style = T.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun TracksDialog(player: Player, onDismiss: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val audio = remember(version) { trackOptions(player, C.TRACK_TYPE_AUDIO) }
    val text = remember(version) { trackOptions(player, C.TRACK_TYPE_TEXT) }
    val textOff = remember(version) { isTrackTypeDisabled(player, C.TRACK_TYPE_TEXT) || text.none { it.selected } }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم", color = RColors.Saffron) } },
        title = { Text("الصوت والترجمة", style = T.h2) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (audio.isNotEmpty()) {
                    Text("الصوت", style = T.label, modifier = Modifier.padding(vertical = 6.dp))
                    audio.forEach { option ->
                        TrackRow(option.label, option.selected) {
                            selectTrack(player, C.TRACK_TYPE_AUDIO, option)
                            version++
                        }
                    }
                }
                Text("الترجمة", style = T.label, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                TrackRow("إيقاف", textOff) {
                    selectTrack(player, C.TRACK_TYPE_TEXT, null)
                    version++
                }
                text.forEach { option ->
                    TrackRow(option.label, option.selected && !textOff) {
                        selectTrack(player, C.TRACK_TYPE_TEXT, option)
                        version++
                    }
                }
            }
        },
        containerColor = RColors.Surface
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = RColors.Saffron))
        Spacer(Modifier.width(8.dp))
        Text(label, style = T.body(15.sp))
    }
}

