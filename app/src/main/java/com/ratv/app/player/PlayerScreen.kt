package com.ratv.app.player

import android.os.SystemClock
import androidx.compose.runtime.mutableFloatStateOf
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ratv.app.data.Channel
import com.ratv.app.data.Episode
import com.ratv.app.data.Keys
import com.ratv.app.data.WatchEntry
import com.ratv.app.data.cleanTitle
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
import com.ratv.app.ui.components.rememberIsTv
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.components.hm
import com.ratv.app.ui.screens.rememberNow
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private val Glass = Color(0x1FFFFFFF)
private const val MAX_RECONNECTS = 5
private val SoftText = Color(0xFFD9D4CC)

private class ResizeMode(val label: String, val resize: Int, val ratio: Float?)

/** Picture sizes: fit, stretch, zoom, or a fixed shape the picture is stretched to. */
@androidx.annotation.OptIn(UnstableApi::class)
private val resizeModes = listOf(
    ResizeMode("ملاءمة", AspectRatioFrameLayout.RESIZE_MODE_FIT, null),
    ResizeMode("ملء الشاشة", AspectRatioFrameLayout.RESIZE_MODE_FILL, null),
    ResizeMode("تكبير", AspectRatioFrameLayout.RESIZE_MODE_ZOOM, null),
    ResizeMode("16:9", AspectRatioFrameLayout.RESIZE_MODE_FILL, 16f / 9f),
    ResizeMode("4:3", AspectRatioFrameLayout.RESIZE_MODE_FILL, 4f / 3f),
    ResizeMode("21:9", AspectRatioFrameLayout.RESIZE_MODE_FILL, 21f / 9f)
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
    var resizeIndex by remember { mutableIntStateOf(repo.prefs.resizeMode.coerceIn(0, resizeModes.size - 1)) }
    var resizeNotice by remember { mutableStateOf<String?>(null) }
    var subsOpen by remember { mutableStateOf(false) }
    var subPos by remember { mutableLongStateOf(0L) }
    var upgrade by remember { mutableStateOf<Playable?>(null) }
    var upgradeEpisodes by remember { mutableStateOf<List<Episode>?>(null) }
    var banner by remember { mutableStateOf<Channel?>(null) }
    var sourcesOpen by remember { mutableStateOf(false) }
    var audioVersion by remember { mutableIntStateOf(0) }
    var audioTracks by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var reconnectAttempts by remember { mutableIntStateOf(0) }
    var reconnecting by remember { mutableStateOf(false) }
    val isTv = rememberIsTv()
    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    val player = remember { createPlayer(context, repo.prefs.bufferMode, repo.prefs.bestQuality, live = kind == "live") }
    val cushion = remember { cushionMs(repo.prefs.bufferMode) }
    val liveFormat = remember { repo.prefs.liveFormat }
    // Channel whose HLS version failed: it goes back to its own address.
    var hlsFailedFor by remember { mutableStateOf<String?>(null) }
    var usingHls by remember { mutableStateOf(false) }
    // Reserve stored ahead of the picture, and whether the internet is failing right now.
    var reserveMs by remember { mutableLongStateOf(0L) }
    var netTrouble by remember { mutableStateOf(false) }
    // Stream information (the ⓘ button), with what playback went through: finds the cause of stutter.
    var showStats by remember { mutableStateOf(false) }
    var stalls by remember { mutableIntStateOf(0) }
    var droppedFrames by remember { mutableIntStateOf(0) }
    var decoderName by remember { mutableStateOf<String?>(null) }
    var bandwidthBps by remember { mutableLongStateOf(0L) }
    var measuredFps by remember { mutableFloatStateOf(0f) }
    var videoWidth by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val mix = remember { MixController(context, scope, player, repo.prefs.bufferMode) }
    val subs = remember { SubtitleController(scope, repo.prefs) }
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

    fun play(item: Playable, showControls: Boolean = true) {
        saveProgress()
        mix.stop()
        current = item
        panelOpen = false
        sourcesOpen = false
        subsOpen = false
        reconnectAttempts = 0
        reconnecting = false
        videoHeight = 0
        controlsVisible = showControls
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
    LaunchedEffect(current, hlsFailedFor) {
        val item = current ?: return@LaunchedEffect
        playerError = null
        ended = false
        val hlsAllowed = repo.account.value?.formats?.contains("m3u8") == true
        val url = if (item.isLive && hlsFailedFor != item.id) liveStreamUrl(item.url, liveFormat, hlsAllowed) else item.url
        usingHls = url != item.url
        stalls = 0
        droppedFrames = 0
        measuredFps = 0f
        player.setMediaItem(mediaItemFor(url, live = item.isLive), item.startPosition.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
        if (item.isLive) repo.noteChannelPlayed(item.id)
    }

    // ------------------------------------- subtitles and 4K copies of films and episodes
    LaunchedEffect(current?.id) {
        val playing = current ?: return@LaunchedEffect
        upgrade = null
        upgradeEpisodes = null
        val keepSubtitles = subs.isOn
        if (playing.isLive) {
            subs.setItem(null)
            return@LaunchedEffect
        }
        when (playing.kind) {
            Playable.KIND_MOVIE -> {
                val m = repo.movie(playing.id)
                val title = cleanTitle(m?.name ?: playing.title)
                subs.setItem(SubtitleQuery(title, null, m?.year?.toIntOrNull()))
                if (m != null) {
                    if (repo.movieQuality(m) != "4K") {
                        repo.movieVersions(m).firstOrNull { repo.movieQuality(it) == "4K" }?.let { upgrade = moviePlayable(it) }
                    }
                    val info = repo.movieInfo(m.id)
                    val alt = info?.originalName?.let { cleanTitle(it) }?.takeIf { it.isNotBlank() && it != title }
                    val year = m.year?.toIntOrNull() ?: info?.year?.toIntOrNull()
                    subs.setItem(SubtitleQuery(title, alt, year))
                }
            }
            Playable.KIND_EPISODE -> {
                val show = playing.seriesId?.let { repo.seriesItem(it) }
                val ep = episodes.firstOrNull { it.id == playing.id }
                subs.setItem(SubtitleQuery(cleanTitle(show?.name ?: playing.title), null, show?.year?.toIntOrNull(), ep?.season, ep?.num))
                if (show != null && ep != null && repo.seriesQuality(show) != "4K") {
                    val better = repo.seriesVersions(show).firstOrNull { repo.seriesQuality(it) == "4K" }
                    if (better != null) {
                        val all = repo.seriesInfo(better.id)?.episodes?.toSortedMap()?.values?.flatten().orEmpty()
                        val match = all.firstOrNull { it.season == ep.season && it.num == ep.num }
                        if (match != null) {
                            upgrade = episodePlayable(match, better.id, better.name, better.cover)
                            upgradeEpisodes = all
                        }
                    }
                }
            }
        }
        if (keepSubtitles || repo.prefs.subtitleAuto) subs.search(autoPick = true)
    }

    // The subtitle line follows the picture closely.
    LaunchedEffect(subs.isOn) {
        subs.positionMs = { player.currentPosition }
        if (subs.isOn) selectTrack(player, C.TRACK_TYPE_TEXT, null)
        while (subs.isOn) {
            subPos = player.currentPosition
            delay(120L)
        }
    }

    LaunchedEffect(resizeNotice) {
        if (resizeNotice != null) {
            delay(1_600L)
            resizeNotice = null
        }
    }

    // ------------------------------------------------------- listeners
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                mix.onMainPlayWhenReady(playWhenReady)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                ended = playbackState == Player.STATE_ENDED
                if (playbackState == Player.STATE_READY) {
                    playerError = null
                    reconnectAttempts = 0
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val now = current
                when {
                    usingHls && now != null && hlsUnsupported(error) -> {
                        hlsFailedFor = now.id // try the channel's own (TS) address
                    }
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        player.seekToDefaultPosition()
                        player.prepare()
                    }
                    isRetryable(error) && reconnectAttempts < MAX_RECONNECTS -> {
                        reconnectAttempts++
                        reconnecting = true
                    }
                    else -> {
                        reconnecting = false
                        playerError = describeError(error)
                        controlsVisible = true
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) videoHeight = videoSize.height
            }

            override fun onTracksChanged(tracks: Tracks) {
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.length }
                val text = tracks.groups.count { it.type == C.TRACK_TYPE_TEXT }
                audioTracks = audio
                hasTrackChoice = audio > 1 || text > 0
                audioVersion++
            }
        }
        player.addListener(listener)
        val loadWatch = object : AnalyticsListener {
            private var readyOnce = false

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean
            ) {
                netTrouble = true
            }

            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                if (state == Player.STATE_READY) readyOnce = true
                else if (state == Player.STATE_BUFFERING && readyOnce) stalls++
                if (state == Player.STATE_IDLE) readyOnce = false
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
                droppedFrames += count
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                name: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                decoderName = name
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long
            ) {
                bandwidthBps = bitrateEstimate
            }

            override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
                if (videoSize.width > 0) videoWidth = videoSize.width
            }
        }
        player.addAnalyticsListener(loadWatch)
        onDispose {
            player.removeAnalyticsListener(loadWatch)
            player.removeListener(listener)
            saveProgress()
            mix.release()
            subs.release()
            player.release()
        }
    }

    // Watches the reserve, the connection and the real frame rate once a second. Playback
    // speed is never touched: speeding up or slowing down shows as judder on a TV.
    LaunchedEffect(current) {
        current ?: return@LaunchedEffect
        if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
        var lastBuffered = player.bufferedPosition
        var lastFrames = -1L
        var lastFramesAt = 0L
        while (true) {
            reserveMs = player.totalBufferedDuration
            val grew = player.bufferedPosition > lastBuffered + 200L
            lastBuffered = player.bufferedPosition
            if (grew) netTrouble = false
            player.videoDecoderCounters?.let { c ->
                c.ensureUpdated()
                val frames = (c.renderedOutputBufferCount + c.droppedBufferCount + c.skippedOutputBufferCount).toLong()
                val t = SystemClock.elapsedRealtime()
                if (lastFrames >= 0 && player.isPlaying && t - lastFramesAt >= 2_900L) {
                    val fps = (frames - lastFrames) * 1000f / (t - lastFramesAt)
                    if (fps > 5f) measuredFps = standardFrameRate(fps)
                    lastFrames = frames
                    lastFramesAt = t
                } else if (lastFrames < 0 || !player.isPlaying) {
                    lastFrames = frames
                    lastFramesAt = t
                }
            }
            delay(1_000L)
        }
    }

    // Optional on TVs: switch the screen to the refresh rate that shows this channel evenly
    // (25 and 50 fps on 50 Hz, 30 and 60 on 60 Hz). Put back when the player closes.
    val matchRate = remember { repo.prefs.matchFrameRate }
    val hostWindow = remember(activity) { activity?.window }
    LaunchedEffect(measuredFps) {
        if (!matchRate || measuredFps <= 0f) return@LaunchedEffect
        val display = view.display ?: return@LaunchedEffect
        val mode = displayModeFor(display, measuredFps) ?: return@LaunchedEffect
        hostWindow?.let { w -> w.attributes = w.attributes.also { it.preferredDisplayModeId = mode.modeId } }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (matchRate) hostWindow?.let { w -> w.attributes = w.attributes.also { it.preferredDisplayModeId = 0 } }
        }
    }

    // Reconnect by itself after a network drop instead of stopping with an error.
    LaunchedEffect(reconnectAttempts, reconnecting) {
        if (!reconnecting) return@LaunchedEffect
        delay(1_500L * reconnectAttempts)
        val playing = current ?: return@LaunchedEffect
        if (playing.isLive) player.seekToDefaultPosition()
        player.prepare()
        player.playWhenReady = true
        reconnecting = false
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
    LaunchedEffect(controlsVisible, isPlaying, lastTouch, panelOpen, showTracks, sourcesOpen, subsOpen) {
        if (controlsVisible && isPlaying && !panelOpen && !showTracks && !sourcesOpen && !subsOpen) {
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
    BackHandler(enabled = !locked && (panelOpen || showTracks || sourcesOpen || subsOpen)) {
        panelOpen = false
        showTracks = false
        sourcesOpen = false
        subsOpen = false
    }

    val zapList = remember(item?.channel?.categoryId, channels) {
        val cat = item?.channel?.categoryId
        if (cat == null) emptyList() else channels.filter { it.categoryId == cat }
    }
    val programmes = item?.channel?.let { epg.forChannel(it) }.orEmpty()
    val programme = programmes.current(now)
    val nextProgramme = programmes.next(now)
    val siblings = remember(item?.channel?.id, channels) {
        item?.channel?.let { repo.siblings(it) }.orEmpty()
    }
    val fourK = siblings.firstOrNull { it.quality == "4K" }?.takeIf { item?.channel?.quality != "4K" }
    val audioOptions = remember(audioVersion, sourcesOpen) { trackOptions(player, C.TRACK_TYPE_AUDIO) }
    val liveChannel = item?.channel
    val isSports = remember(liveChannel?.id) { liveChannel?.let { repo.isSportsChannel(it) } == true }
    val sameEvent by produceState<List<Channel>?>(null, liveChannel?.id, programme?.title, sourcesOpen) {
        val ch = liveChannel
        value = if (ch == null) emptyList() else withContext(Dispatchers.Default) {
            repo.sameEventChannels(ch, System.currentTimeMillis())
        }
    }
    val sportsChoices by produceState(emptyList<Channel>(), liveChannel?.id, sourcesOpen, isSports) {
        val ch = liveChannel
        value = if (ch == null || !sourcesOpen || !isSports) emptyList() else withContext(Dispatchers.Default) {
            repo.sportsChannels(ch.id)
        }
    }

    fun zap(step: Int) {
        val ch = item?.channel ?: return
        if (zapList.isEmpty()) return
        val index = zapList.indexOfFirst { it.id == ch.id }
        val target = zapList[((if (index < 0) 0 else index) + step + zapList.size) % zapList.size]
        play(liveToPlayable(target), showControls = false)
        banner = target
    }

    fun seekBy(delta: Long) {
        if (!seekable) return
        touch()
        val target = (player.currentPosition + delta).coerceAtLeast(0L)
        player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
    }

    fun togglePlay() {
        touch()
        if (player.isPlaying) {
            player.pause()
        } else {
            if (ended) player.seekTo(0L)
            player.play()
        }
    }

    fun showControls() {
        controlsVisible = true
        touch()
    }

    /** TV remote: OK shows the controls, up/down change channel, left/right seek, media keys work anywhere. */
    fun onRemoteKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val live = item?.isLive == true
        when (event.key) {
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                togglePlay()
                return true
            }
            Key.MediaFastForward -> {
                seekBy(30_000L)
                return true
            }
            Key.MediaRewind -> {
                seekBy(-30_000L)
                return true
            }
            Key.ChannelUp -> {
                if (live) zap(1)
                return true
            }
            Key.ChannelDown -> {
                if (live) zap(-1)
                return true
            }
        }
        if (locked) {
            if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                locked = false
                showControls()
                return true
            }
            if (event.key == Key.Back) return false
            showControls()
            return true
        }
        if (panelOpen || sourcesOpen || showTracks || subsOpen || playerError != null || notFound) return false
        if (controlsVisible) {
            touch()
            return false
        }
        return when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                showControls()
                true
            }
            Key.DirectionUp -> {
                if (live) zap(-1) else showControls()
                true
            }
            Key.DirectionDown -> {
                if (live) zap(1) else showControls()
                true
            }
            Key.DirectionLeft -> {
                if (live) showControls() else seekBy(-10_000L)
                true
            }
            Key.DirectionRight -> {
                if (live) showControls() else seekBy(10_000L)
                true
            }
            Key.Menu -> {
                if (live) panelOpen = true else subsOpen = true
                true
            }
            else -> false
        }
    }

    LaunchedEffect(banner) {
        if (banner != null) {
            delay(3_000L)
            banner = null
        }
    }

    LaunchedEffect(Unit) {
        delay(150L)
        runCatching { rootFocus.requestFocus() }
    }

    // Keep the remote's focus somewhere useful as the controls come and go.
    LaunchedEffect(controlsVisible, locked, panelOpen, showTracks, sourcesOpen, subsOpen) {
        delay(80L)
        if (controlsVisible && !locked && !panelOpen && !showTracks && !sourcesOpen && !subsOpen) {
            if (isTv) runCatching { playPauseFocus.requestFocus() }
        } else if (!panelOpen && !showTracks && !sourcesOpen && !subsOpen) {
            runCatching { rootFocus.requestFocus() }
        }
    }

    LaunchedEffect(playerError) {
        if (isTv && playerError != null) {
            delay(120L)
            runCatching { retryFocus.requestFocus() }
        }
    }

    // On TV the first Back only hides the controls.
    BackHandler(enabled = isTv && controlsVisible && !locked && !panelOpen && !showTracks && !sourcesOpen && !subsOpen) {
        controlsVisible = false
    }

    // ------------------------------------------------------------- UI
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onKeyEvent { onRemoteKey(it) }
            .focusable()
    ) {
        val exoPlayer = player
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setKeepContentOnPlayerReset(true)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setPlayer(exoPlayer)
                }
            },
            update = { it.resizeMode = resizeModes[resizeIndex].resize },
            modifier = resizeModes[resizeIndex].ratio?.let { Modifier.align(Alignment.Center).aspectRatio(it) } ?: Modifier.fillMaxSize()
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
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = RColors.Saffron,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(48.dp)
                )
                if (netTrouble) {
                    Text(
                        "انقطع الإنترنت… يكمل التشغيل فور عودته",
                        style = T.body(14.sp, FontWeight.Bold),
                        modifier = Modifier
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xB30B0C10))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (showStats && current != null) {
            StatsPanel(
                format = if (current?.isLive == true) (if (usingHls) "HLS" else "TS") else "ملف",
                width = videoWidth,
                height = videoHeight,
                fps = measuredFps,
                refresh = view.display?.refreshRate ?: 0f,
                video = player.videoFormat?.sampleMimeType,
                audio = player.audioFormat?.sampleMimeType,
                decoder = decoderName,
                bandwidthBps = bandwidthBps,
                reserveMs = reserveMs,
                stalls = stalls,
                dropped = droppedFrames,
                offline = netTrouble,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 20.dp, top = 84.dp)
            )
        }

        // How much reserve there is; while the internet is down, how long the picture can still go on.
        val showReserve = playerError == null && !notFound && current != null &&
            ((netTrouble && reserveMs > 0) || (controlsVisible && !buffering))
        if (showReserve) {
            val liveNow = current?.isLive == true
            val secs = (if (liveNow) reserveMs else minOf(reserveMs, cushion)) / 1000L
            Text(
                when {
                    netTrouble -> "انقطع الإنترنت — يكمل من الاحتياطي، باقي $secs ث"
                    liveNow -> "الاحتياطي ضد انقطاع النت: $secs ث"
                    else -> "الاحتياطي ضد انقطاع النت: $secs من ${cushion / 1000L} ث"
                },
                style = T.body(13.sp, FontWeight.Bold, if (netTrouble) Color.White else RColors.Text),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (netTrouble) 24.dp else 72.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (netTrouble) RColors.Live.copy(alpha = 0.9f) else Color(0xB30B0C10))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }

        if (reconnecting || (reconnectAttempts > 0 && buffering)) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xD90B0C10))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(RIcons.Refresh, contentDescription = null, tint = RColors.Saffron, modifier = Modifier.size(16.dp))
                Text(
                    "انقطع البث، جارٍ إعادة الاتصال… ($reconnectAttempts/$MAX_RECONNECTS)",
                    style = T.body(13.sp, FontWeight.Medium)
                )
            }
        }

        if (subs.isOn && !inPip) {
            SubtitleOverlay(subs.textAt(subPos), subs.size, raised = controlsVisible)
        }

        val mixing = mix.channel
        if (mixing != null && !inPip && !controlsVisible) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xB30B0C10))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(RIcons.Mic, contentDescription = null, tint = RColors.Saffron, modifier = Modifier.size(15.dp))
                Text("معلق ${mixing.name}", style = T.body(12.sp, FontWeight.Medium), maxLines = 1)
            }
        }

        val notice = resizeNotice
        if (notice != null && !inPip) {
            TopNotice("حجم الصورة: $notice", RIcons.Aspect)
        }

        val zapped = banner
        if (zapped != null && !controlsVisible && !inPip) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xD90B0C10))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(zapped.num.toString(), style = T.display(20.sp, FontWeight.Bold, RColors.Saffron))
                ChannelLogo(zapped.name, zapped.logo, 40.dp, corner = 10.dp)
                Column {
                    Text(zapped.name, style = T.body(16.sp, FontWeight.Bold), maxLines = 1)
                    val now0 = epg.forChannel(zapped).current(now)?.title
                    if (now0 != null) Text(now0, style = T.body(13.sp, color = SoftText), maxLines = 1)
                }
            }
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
                            .tvClickable(RoundedCornerShape(22.dp)) { locked = false; touch() }
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
                    visible = controlsVisible && !panelOpen && !sourcesOpen && !subsOpen && item != null,
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
                            resizeLabel = resizeModes[resizeIndex].label,
                            hasNextEpisode = nextEpisode != null,
                            resolution = resolutionLabel(videoHeight),
                            fourKName = if (item.isLive) fourK?.name else upgrade?.title,
                            onFourK = {
                                if (item.isLive) {
                                    fourK?.let { play(liveToPlayable(it)) }
                                } else {
                                    upgrade?.let { better ->
                                        upgradeEpisodes?.let { episodes = it }
                                        play(better.copy(startPosition = player.currentPosition))
                                    }
                                }
                            },
                            showSources = item.isLive && (audioTracks > 1 || siblings.isNotEmpty() || isSports || !sameEvent.isNullOrEmpty() || mixing != null),
                            subtitlesOn = subs.isOn,
                            onSubtitles = {
                                touch()
                                subsOpen = true
                            },
                            onSources = {
                                touch()
                                sourcesOpen = true
                            },
                            playPauseFocus = playPauseFocus,
                            onBack = { navigator.back() },
                            onPlayPause = { togglePlay() },
                            onSeekBy = { delta -> seekBy(delta) },
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
                                repo.prefs.resizeMode = resizeIndex
                                resizeNotice = resizeModes[resizeIndex].label
                            },
                            onStats = {
                                touch()
                                showStats = !showStats
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
                        onClose = { panelOpen = false },
                        tv = isTv
                    )
                }

                AnimatedVisibility(
                    visible = sourcesOpen,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    SourcesPanel(
                        audio = audioOptions,
                        sameEvent = sameEvent,
                        siblings = siblings,
                        sports = sportsChoices,
                        mix = mix,
                        epgLookup = { ch -> epg.forChannel(ch).current(now)?.title },
                        onPickAudio = { option ->
                            mix.stop()
                            selectTrack(player, C.TRACK_TYPE_AUDIO, option)
                            audioVersion++
                        },
                        onMix = { ch -> mix.start(ch, repo.account.value?.maxCons) },
                        onSwitch = { ch -> play(liveToPlayable(ch)) },
                        onClose = { sourcesOpen = false },
                        tv = isTv
                    )
                }

                AnimatedVisibility(
                    visible = subsOpen,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    SubtitlesPanel(
                        sub = subs,
                        hasEmbedded = hasTrackChoice,
                        onEmbedded = {
                            subsOpen = false
                            showTracks = true
                        },
                        onClose = { subsOpen = false },
                        tv = isTv
                    )
                }
            }

            val error = playerError
            if (error != null && !panelOpen && !sourcesOpen && !subsOpen) {
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
                            height = 46.dp,
                            modifier = Modifier.focusRequester(retryFocus)
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
    resolution: String?,
    fourKName: String?,
    onFourK: () -> Unit,
    showSources: Boolean,
    onSources: () -> Unit,
    subtitlesOn: Boolean,
    onSubtitles: () -> Unit,
    playPauseFocus: FocusRequester,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onTracks: () -> Unit,
    onPip: () -> Unit,
    onResize: () -> Unit,
    onStats: () -> Unit,
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
                    // The real resolution of the picture when known, otherwise what the name says.
                    val badge = resolution ?: channel?.quality
                    if (badge != null) QualityBadge(badge, color = if (resolution != null) RColors.SaffronLight else SoftText)
                    if (item.isLive) LiveBadge()
                }
                val sub = if (item.isLive) programmeTitle else item.subtitle
                if (!sub.isNullOrBlank()) {
                    Text(sub, style = T.body(13.sp, color = SoftText), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (fourKName != null) {
                Row(
                    Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(RColors.Saffron)
                        .tvClickable(RoundedCornerShape(20.dp), onClickLabel = "شاهد $fourKName", ringColor = Color.White, onClick = onFourK)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("4K متاح", style = T.body(13.sp, FontWeight.Bold, RColors.OnSaffron))
                }
                Spacer(Modifier.width(8.dp))
            }
            if (hasTrackChoice) GlassIcon(RIcons.Captions, "الصوت والترجمة", onTracks)
            if (canPip) GlassIcon(RIcons.Pip, "صورة داخل صورة", onPip)
            GlassIcon(RIcons.Aspect, "حجم الصورة: $resizeLabel", onResize)
            GlassIcon(RIcons.Info, "معلومات البث", onStats)
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
                        .focusRequester(playPauseFocus)
                        .clip(CircleShape)
                        .background(Color(0x29FFFFFF))
                        .tvClickable(CircleShape, onClick = onPlayPause),
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
                    if (showSources) PillButton(RIcons.Mic, "المعلق والجودة", onSources)
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
                    PillButton(RIcons.Captions, if (subtitlesOn) "الترجمة: مفعّلة" else "الترجمة", onSubtitles)
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
            .tvClickable(CircleShape, enabled = enabled, onClick = onClick),
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
            .tvClickable(RoundedCornerShape(22.dp), onClick = onClick)
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
    onClose: () -> Unit,
    tv: Boolean = false
) {
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    LaunchedEffect(currentId) {
        val index = channels.indexOfFirst { it.id == currentId }
        if (index > 2) listState.scrollToItem(index - 2)
        if (tv) {
            delay(120L)
            runCatching { currentFocus.requestFocus() }
        }
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
                            .then(if (selected) Modifier.focusRequester(currentFocus) else Modifier)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) RColors.Surface2 else Color.Transparent)
                            .tvClickable(RoundedCornerShape(14.dp)) { onPick(ch) }
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
            .clip(RoundedCornerShape(10.dp))
            .tvClickable(RoundedCornerShape(10.dp), role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = RColors.Saffron))
        Spacer(Modifier.width(8.dp))
        Text(label, style = T.body(15.sp))
    }
}

/** What the stream is and what playback went through, with the likely cause of any stutter. */
@Composable
private fun StatsPanel(
    format: String,
    width: Int,
    height: Int,
    fps: Float,
    refresh: Float,
    video: String?,
    audio: String?,
    decoder: String?,
    bandwidthBps: Long,
    reserveMs: Long,
    stalls: Int,
    dropped: Int,
    offline: Boolean,
    modifier: Modifier = Modifier
) {
    fun codec(mime: String?) = mime?.substringAfter('/')?.uppercase() ?: "—"
    val fpsText = if (fps > 0f) String.format(java.util.Locale.US, if (fps % 1f == 0f) "%.0f" else "%.2f", fps) else "—"
    val uneven = fps > 0f && refresh > 0f && !refreshFits(refresh, fps)
    val verdict = when {
        dropped > 50 -> "الجهاز لا يلحق على فك هذا البث ($dropped إطار سقط): جرّب نسخة HD من القناة."
        stalls > 0 && offline -> "التوقفات من انقطاع الاتصال بالإنترنت."
        stalls > 0 -> "توقف البث $stalls مرة: الاتصال أو السيرفر لم يرسل البث بسرعة كافية."
        uneven -> "البث $fpsText إطار والشاشة ${refresh.toInt()} هرتز فتظهر الحركة متقطعة قليلاً: فعّل «مطابقة معدل الإطارات» من الإعدادات."
        else -> "البث سليم: لا توقفات ولا إطارات ساقطة."
    }
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE60B0C10))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text("معلومات البث", style = T.body(14.sp, FontWeight.Bold, RColors.Saffron))
        val lines = listOf(
            "الصيغة" to format,
            "الصورة" to (if (height > 0) "${if (width > 0) "$width×" else ""}$height · $fpsText إطار/ث" else "—"),
            "الشاشة" to (if (refresh > 0f) "${refresh.toInt()} هرتز" else "—"),
            "الترميز" to "${codec(video)} · صوت ${codec(audio)}",
            "فك الترميز" to (decoder ?: "—"),
            "سرعة الاتصال" to (if (bandwidthBps > 0 && bandwidthBps != 12_000_000L) String.format(java.util.Locale.US, "%.1f ميجابت", bandwidthBps / 1_000_000.0) else "—"),
            "الاحتياطي" to "${reserveMs / 1000L} ث",
            "التوقفات" to "$stalls",
            "إطارات ساقطة" to "$dropped"
        )
        lines.forEach { (k, v) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(k, style = T.body(12.5.sp, color = RColors.Text2), modifier = Modifier.width(96.dp))
                Text(v, style = T.body(12.5.sp, FontWeight.Bold).copy(textDirection = androidx.compose.ui.text.style.TextDirection.ContentOrLtr))
            }
        }
        Text(
            verdict,
            style = T.body(12.5.sp, FontWeight.Bold, if (verdict.startsWith("البث سليم")) RColors.Text else RColors.Saffron),
            modifier = Modifier.width(300.dp)
        )
    }
}
