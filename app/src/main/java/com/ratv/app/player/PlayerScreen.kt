package com.ratv.app.player

import com.ratv.app.i18n.tr
import android.util.Log
import com.ratv.app.data.Playback
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
import com.ratv.app.ui.components.BrandLogo
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.ui.AbsoluteAlignment
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
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
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

/** Stalls counted toward moving to another version of the channel: those of the last 4 minutes. */
private const val VERSION_WINDOW_MS = 4L * 60L * 1000L
private val SoftText = Color(0xFFD9D4CC)

private class ResizeMode(val label: String, val resize: Int, val ratio: Float?)

/** Picture sizes: fit, stretch, zoom, or a fixed shape the picture is stretched to. */
@androidx.annotation.OptIn(UnstableApi::class)
private val resizeModes get() = listOf(
    ResizeMode(tr("ملاءمة"), AspectRatioFrameLayout.RESIZE_MODE_FIT, null),
    ResizeMode(tr("ملء الشاشة"), AspectRatioFrameLayout.RESIZE_MODE_FILL, null),
    ResizeMode(tr("تكبير"), AspectRatioFrameLayout.RESIZE_MODE_ZOOM, null),
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
    // Digits typed on the remote (a channel number), and the channel watched before this one.
    var typedNumber by remember { mutableStateOf("") }
    var previousChannel by remember { mutableStateOf<Channel?>(null) }
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

    // Smart enhancement of weak pictures (Enhance.kt): this player has it when the setting is on and
    // the item playing is known (or seen) to be under 720 lines. HD goes straight to the screen.
    var enhanceSetting by remember { mutableStateOf(repo.prefs.enhance) }
    var enhanceBroken by remember { mutableStateOf(repo.prefs.enhanceBroken) }
    // The strongest enhancement this device is given (full, light, none): it steps down by itself.
    var enhanceStrength by remember { mutableIntStateOf(repo.prefs.enhanceStrength) }
    var enhanceFor by remember { mutableStateOf<String?>(null) }
    // Whether the playing channel or film has shown its first picture yet.
    var pictureShown by remember { mutableStateOf(false) }
    // Where a film or episode carries on when its player is made again (enhancement on or off).
    var resumeAt by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val enhanceWanted = current?.let { enhanceFor == enhanceKey(it) } == true
    val playerViewRef = remember { arrayOfNulls<PlayerView>(1) }

    /** True when [item] should be enhanced: a weak picture, on a device that can still enhance. */
    fun wantsEnhance(item: Playable, seenHeight: Int? = null): Boolean {
        if (!enhanceSetting || enhanceBroken || enhanceStrength <= ENHANCE_OFF) return false
        val known = EnhanceMemory.get(context, enhanceKey(item))
        val h = seenHeight ?: known?.height ?: nameHintHeight(item.channel?.name ?: item.title) ?: return false
        return h in 1 until ENHANCE_BELOW_HEIGHT
    }

    /** The shape the enhanced picture is stretched to (fill or a fixed shape), or null to keep its own. */
    fun stretchFor(index: Int): Float? {
        val mode = resizeModes[index]
        if (mode.resize != AspectRatioFrameLayout.RESIZE_MODE_FILL) return null
        return mode.ratio ?: if (view.width > 0 && view.height > 0) view.width.toFloat() / view.height else 16f / 9f
    }

    val player = remember(enhanceWanted, if (enhanceWanted) enhanceStrength else ENHANCE_OFF) {
        Steadiness.mode = repo.prefs.liveSteady
        Steadiness.attach(context)
        createPlayer(
            context, repo.prefs.bufferMode, repo.prefs.bestQuality, live = kind == "live",
            tunneling = isTv && repo.prefs.tunneling,
            enhance = enhanceWanted, stretchAspect = stretchFor(resizeIndex), enhanceStrength = enhanceStrength
        )
    }
    val cushion = remember { cushionMs(repo.prefs.bufferMode) }
    val liveFormat = remember { repo.prefs.liveFormat }
    // Channel whose HLS version failed: it goes back to its own address.
    var hlsFailedFor by remember { mutableStateOf<String?>(null) }

    // A channel that keeps stalling as TS is tried as HLS (when the server offers it): HLS can store
    // much more ahead, as its pieces come faster than they are shown.
    var hlsPreferredFor by remember { mutableStateOf<String?>(null) }
    var autoHlsNoticed by remember { mutableStateOf(false) }
    val hlsAfterStalls = remember { com.ratv.app.data.debugProperty("debug.ratv.hlsafter")?.toIntOrNull() ?: 3 }
    var usingHls by remember { mutableStateOf(false) }
    // A channel that keeps stalling (or failing) moves to another version of itself from the
    // subscription, a lighter quality or a backup link, when there is one. Versions already tried
    // in this chain are skipped; a version the viewer picked by hand is never left automatically.
    val autoVersion = remember { repo.prefs.autoVersion }
    val versionAfterStalls = remember { com.ratv.app.data.debugProperty("debug.ratv.versionafter")?.toIntOrNull() ?: 3 }
    val versionsTried = remember { HashSet<String>() }
    var keepVersionFor by remember { mutableStateOf<String?>(null) }
    val stallTimes = remember { ArrayDeque<Long>() }
    // What the player did by itself about a bad stream (switched to HLS, to another version).
    var streamNotice by remember { mutableStateOf<String?>(null) }
    // Reserve stored ahead of the picture, and whether the internet is failing right now.
    var reserveMs by remember { mutableLongStateOf(0L) }
    var netTrouble by remember { mutableStateOf(false) }
    // Stream information (the ⓘ button), with what playback went through: finds the cause of stutter.
    var showStats by remember { mutableStateOf(false) }
    var stalls by remember { mutableIntStateOf(0) }
    var liveReconnects by remember { mutableIntStateOf(0) }
    var droppedFrames by remember { mutableIntStateOf(0) }
    var decoderName by remember { mutableStateOf<String?>(null) }
    var bandwidthBps by remember { mutableLongStateOf(0L) }
    var measuredFps by remember { mutableFloatStateOf(0f) }
    var videoWidth by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val mix = remember(player) { MixController(context, scope, player, repo.prefs.bufferMode) }
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

    fun play(item: Playable, showControls: Boolean = true, auto: Boolean = false) {
        if (!auto) versionsTried.clear()
        // The channel left behind, for the remote's "previous channel" key.
        current?.channel?.let { old -> if (old.id != item.channel?.id) previousChannel = old }
        saveProgress()
        mix.stop()
        current = item
        enhanceFor = if (wantsEnhance(item)) enhanceKey(item) else null
        panelOpen = false
        sourcesOpen = false
        subsOpen = false
        reconnectAttempts = 0
        reconnecting = false
        videoHeight = 0
        controlsVisible = showControls
        touch()
    }

    /** True when the device is on a network: switching versions does not help while the internet is down. */
    fun onNetwork(): Boolean {
        val cm = context.applicationContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Moves a struggling channel to its next version (lighter quality first, then a backup). False when there is none. */
    fun switchVersion(item: Playable, why: String): Boolean {
        val ch = item.channel ?: return false
        if (!autoVersion || keepVersionFor == ch.id || !onNetwork()) return false
        versionsTried.add(ch.id)
        val next = nextVersion(ch, repo.siblings(ch), versionsTried) ?: return false
        Log.i("RATVPlayback", "$why: switching to another version, ${next.name}")
        streamNotice = tr("البث كان يقطّع، فانتقلنا لـ «{0}». ترجع للأصلية من «المعلق والجودة».", next.name)
        play(liveToPlayable(next), showControls = false, auto = true)
        return true
    }

    // ------------------------------------------------------------ resolve
    LaunchedEffect(kind, id, seriesId) {
        val resolved = resolvePlayable(repo, kind, id, seriesId)
        if (resolved == null) {
            notFound = true
        } else {
            episodes = resolved.episodes
            current = resolved.playable
            enhanceFor = if (wantsEnhance(resolved.playable)) enhanceKey(resolved.playable) else null
        }
    }

    // ------------------------------------------------- start the stream
    LaunchedEffect(player, current, hlsFailedFor, hlsPreferredFor) {
        val item = current ?: return@LaunchedEffect
        playerError = null
        ended = false
        val hlsAllowed = repo.account.value?.formats?.contains("m3u8") == true
        // On a line that keeps stalling, a server where HLS proved steadier opens its channels as HLS:
        // HLS fetches its pieces as fast as the line allows, so the store refills in the good moments.
        val host = android.net.Uri.parse(item.url).host.orEmpty()
        val autoHls = item.isLive && liveFormat == "ts" && hlsAllowed && Steadiness.unsteadyLine &&
            repo.prefs.hlsHelps(host) && !repo.prefs.hlsBroken(host)
        val format = if (hlsPreferredFor == item.id || autoHls) "hls" else liveFormat
        val url = if (item.isLive && hlsFailedFor != item.id) liveStreamUrl(item.url, format, hlsAllowed) else item.url
        usingHls = url != item.url
        if (autoHls && usingHls && !autoHlsNoticed) {
            autoHlsNoticed = true
            Log.i("RATVPlayback", "unsteady line: channels open as HLS on $host")
            streamNotice = tr("النت متذبذب: صرنا نشغّل القنوات بصيغة HLS لأنها تتحمّل التذبذب أكثر")
        }
        stalls = 0
        stallTimes.clear()
        droppedFrames = 0
        measuredFps = 0f
        val resume = resumeAt?.takeIf { it.first == item.id && !item.isLive }?.second
        resumeAt = null
        player.setMediaItem(mediaItemFor(url, live = item.isLive), (resume ?: item.startPosition).coerceAtLeast(0L))
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
    LaunchedEffect(player, subs.isOn) {
        subs.positionMs = { player.currentPosition }
        if (subs.isOn) selectTrack(player, C.TRACK_TYPE_TEXT, null)
        while (subs.isOn) {
            subPos = player.currentPosition
            delay(120L)
        }
    }

    LaunchedEffect(streamNotice) {
        if (streamNotice != null) {
            delay(7_000L)
            streamNotice = null
        }
    }

    LaunchedEffect(resizeNotice) {
        if (resizeNotice != null) {
            delay(1_600L)
            resizeNotice = null
        }
    }

    // ------------------------------------------------------- listeners
    // When the network comes back, a live channel waiting to reconnect tries at once.
    DisposableEffect(player) {
        val cm = context.applicationContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                main.post {
                    if (current?.isLive == true && player.mediaItemCount > 0 &&
                        (reconnecting || playerError != null || player.playbackState == Player.STATE_IDLE)
                    ) {
                        Log.i("RATVPlayback", "network back: reconnecting now")
                        playerError = null
                        reconnecting = false
                        player.seekToDefaultPosition()
                        player.prepare()
                        player.playWhenReady = true
                    }
                }
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }

    // Wi-Fi without its power saving pauses while something plays (Android 10 and later).
    DisposableEffect(Unit) {
        val lock = if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                (context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                    ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ratv:playback")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.getOrNull()
        } else {
            null
        }
        onDispose { runCatching { lock?.release() } }
    }

    /**
     * The enhancement does not work out on this device ([failed]: its graphics broke; otherwise it
     * falls behind): the full strength steps down to the light one, the light one to none.
     */
    fun enhanceStepDown(why: String, failed: Boolean) {
        val item = current ?: return
        if (enhanceFor == null) return
        val used = EnhanceStatus.strength.coerceAtMost(enhanceStrength)
        if (!item.isLive) resumeAt = item.id to player.currentPosition
        if (used >= ENHANCE_FULL) {
            Log.i("RATVEnhance", "full enhancement does not work out ($why): light enhancement from now on")
            repo.prefs.enhanceStrength = ENHANCE_LIGHT
            enhanceStrength = ENHANCE_LIGHT // a new player with the light strength
            streamNotice = tr("التحسين الكامل ثقيل على هذا التلفزيون، فشغّلنا التحسين الخفيف")
        } else {
            Log.i("RATVEnhance", "the enhancement does not work out ($why): off on this device")
            repo.prefs.enhanceStrength = ENHANCE_OFF
            enhanceStrength = ENHANCE_OFF
            streamNotice = if (failed) tr("تحسين الصورة ما اشتغل على هذا الجهاز، فأوقفناه") else tr("التلفزيون ما لحق على تحسين الصورة، فأوقفناه")
            enhanceFor = null
        }
    }

    fun enhanceTooSlow(why: String) = enhanceStepDown(why, failed = false)

    DisposableEffect(player) {
        // Whether this player enhances the picture (it is made again to change that).
        val pipeline = enhanceWanted
        val deviceStalls = ArrayDeque<Long>()
        pictureShown = false
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onRenderedFirstFrame() {
                pictureShown = true
            }

            // A new channel or film on the same player has not shown its first picture yet.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                pictureShown = false
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
                    // The device's graphics could not run the enhancement: play the usual way.
                    pipeline && (error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED) -> {
                        Log.w("RATVEnhance", "the enhancement failed on this device", error)
                        enhanceStepDown(error.cause?.message ?: error.message ?: "graphics error", failed = true)
                    }
                    usingHls && now != null && hlsUnsupported(error) -> {
                        hlsFailedFor = now.id // try the channel's own (TS) address
                        val host = android.net.Uri.parse(now.url).host.orEmpty()
                        repo.prefs.noteHlsFailed(host)
                        if (repo.prefs.hlsBroken(host)) repo.prefs.setHlsHelps(host, false)
                    }
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        player.seekToDefaultPosition()
                        player.prepare()
                    }
                    // A live channel never gives up: it keeps trying (10 s apart at most) until the line is back.
                    // After four failed tries with the internet up, its other version is tried instead.
                    isRetryable(error) && (reconnectAttempts < MAX_RECONNECTS || now?.isLive == true) -> {
                        reconnectAttempts++
                        if (now?.isLive == true && reconnectAttempts >= 4 && switchVersion(now, "the channel keeps failing")) {
                            reconnecting = false
                        } else {
                            reconnecting = true
                        }
                    }
                    now?.isLive == true && switchVersion(now, "the channel failed") -> {
                        reconnecting = false
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
                // The picture's size, remembered for the enhancement. It is known well before the
                // store is full, so the enhancement can still be switched on (or off) before the
                // channel shows; later it waits for the next time the channel is opened.
                val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                    ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
                val item = current
                if (video != null && video.height > 0 && item != null) {
                    EnhanceMemory.put(context, enhanceKey(item), video.height, video.frameRate)
                    if (!pictureShown && !player.isPlaying) {
                        val want = wantsEnhance(item, seenHeight = video.height)
                        if (want != pipeline) {
                            Log.i("RATVEnhance", "picture ${video.width}x${video.height}: enhancement " + if (want) "on" else "off")
                            enhanceFor = if (want) enhanceKey(item) else null
                        }
                    }
                }
            }
        }
        player.addListener(listener)
        val loadWatch = object : AnalyticsListener {
            private var readyOnce = false

            // A new channel or film, or a seek, waits for its picture: no stall of the line. (Counted,
            // every change of channel raised the steadiness wait and brought the HLS and version
            // switches nearer, though the line was fine.)
            override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) {
                readyOnce = false
            }

            override fun onPositionDiscontinuity(
                eventTime: AnalyticsListener.EventTime,
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) readyOnce = false
            }

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
                if (state == Player.STATE_READY) {
                    if (readyOnce && stalls > 0) Log.i("RATVPlayback", "playing again after stall $stalls")
                    readyOnce = true
                } else if (state == Player.STATE_BUFFERING && readyOnce && pipeline && player.totalBufferedDuration >= 3_000L) {
                    // Through the enhancement, a stall with plenty stored is the TV falling behind, not the
                    // line: no HLS, no other version, no extra steadiness. Twice in half a minute turns it off.
                    val t = SystemClock.elapsedRealtime()
                    deviceStalls.addLast(t)
                    while (deviceStalls.isNotEmpty() && t - deviceStalls.first() > 30_000L) deviceStalls.removeFirst()
                    Log.i("RATVEnhance", "stall with ${player.totalBufferedDuration} ms stored: the TV falls behind the enhancement")
                    if (deviceStalls.size >= 2 && com.ratv.app.data.debugProperty("debug.ratv.enhancekeep") != "1") {
                        enhanceTooSlow("stalls with the store full")
                    }
                } else if (state == Player.STATE_BUFFERING && readyOnce) {
                    stalls++
                    val now = current
                    if (now?.isLive == true) {
                        Steadiness.onStall()
                        val t = SystemClock.elapsedRealtime()
                        stallTimes.addLast(t)
                        while (stallTimes.isNotEmpty() && t - stallTimes.first() > VERSION_WINDOW_MS) stallTimes.removeFirst()
                        // Third stall as TS: try the channel as HLS once, if the server offers it.
                        val hlsAllowed = repo.account.value?.formats?.contains("m3u8") == true
                        if (stalls >= hlsAfterStalls && !usingHls && hlsAllowed && hlsPreferredFor != now.id && hlsFailedFor != now.id &&
                            liveStreamUrl(now.url, "hls", true) != now.url
                        ) {
                            Log.i("RATVPlayback", "stalls keep coming as TS: trying HLS")
                            hlsPreferredFor = now.id
                            streamNotice = tr("البث يتقطع: جرّبنا صيغة HLS لهذي القناة")
                        } else if (stallTimes.size >= versionAfterStalls) {
                            // Still stalling (in a few minutes): another version of the channel, if it has one.
                            switchVersion(now, "stalls keep coming")
                        }
                    }
                    Log.i("RATVPlayback", "stall $stalls, reserve ${player.totalBufferedDuration} ms")
                }
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

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                if (format.height <= 0) return
                videoHeight = format.height
                if (format.width > 0) videoWidth = format.width
                // Through the enhancement the player does not report the picture's shape: the view gets it here.
                if (pipeline && format.width > 0) {
                    val par = if (format.pixelWidthHeightRatio > 0f) format.pixelWidthHeightRatio else 1f
                    playerViewRef[0]?.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                        ?.setAspectRatio(format.width * par / format.height)
                }
            }
        }
        player.addAnalyticsListener(loadWatch)
        onDispose {
            player.removeAnalyticsListener(loadWatch)
            player.removeListener(listener)
            // Its position belongs to the film still playing (not to the next one it is left for).
            val playingUrl = player.currentMediaItem?.localConfiguration?.uri?.toString()
            if (playingUrl != null && playingUrl == current?.url) saveProgress()
            mix.release()
            player.release()
        }
    }
    DisposableEffect(Unit) {
        onDispose { subs.release() }
    }

    // Watches the reserve, the connection and the real frame rate once a second. Playback
    // speed is never touched: speeding up or slowing down shows as judder on a TV.
    LaunchedEffect(player, current) {
        val watched = current ?: return@LaunchedEffect
        val pipeline = enhanceWanted
        val loopStart = SystemClock.elapsedRealtime()
        if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
        // A picture that stops while the sound goes on (the decoder stuck), or a clock that stops
        // while playing, is started again: at most three times for one channel.
        var outFramesSeen = -1L
        var framesStuckSince = 0L
        var watchPos = -1L
        var posStuckSince = 0L
        var watchRestarts = 0
        var fpsRemembered = false
        // Seconds a channel that went HLS after its stalls has played calmly since.
        var hlsCalmSeconds = 0
        // How the TV keeps up with the enhancement: dropped pictures over 6 s, after 5 s of playing.
        var playingSince = 0L
        var windowStart = 0L
        var windowFrames = -1L
        var windowDropped = 0L
        var lastBuffered = player.bufferedPosition
        var lastFrames = -1L
        var lastFramesAt = 0L
        var lastPos = -1L
        var lastPosAt = 0L
        val reconnectBase = LiveReconnectingDataSource.reconnects.get()
        liveReconnects = 0
        var bufferingSince = 0L
        while (true) {
            liveReconnects = LiveReconnectingDataSource.reconnects.get() - reconnectBase
            if (player.isPlaying || player.playbackState == Player.STATE_BUFFERING) Playback.markActive()
            // A live load ended to start the channel again (its clock jumped, or the line was lost)
            // only starts again once the store runs out. Stalled, the store never runs out and the
            // picture would stay frozen for good: start the channel again here.
            val tick = SystemClock.elapsedRealtime()
            if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
                if (bufferingSince == 0L) bufferingSince = tick
            } else {
                bufferingSince = 0L
            }
            val playingUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
            if (current?.isLive == true && playingUri != null && bufferingSince > 0L && tick - bufferingSince >= 1_500L) {
                val ended = LiveReconnectingDataSource.loadEndedAt(playingUri)
                if (ended > 0L && tick - ended >= 1_500L) {
                    Log.i("RATVPlayback", "stalled after the load ended: starting the channel again")
                    LiveReconnectingDataSource.clearEnded(playingUri)
                    player.seekToDefaultPosition()
                    player.prepare()
                }
            }
            // Jumps of the picture's clock (for the emulator checks): a joined reconnect shows none.
            val pos = player.currentPosition
            val posAt = SystemClock.elapsedRealtime()
            if (lastPos >= 0 && player.isPlaying) {
                if (pos < lastPos - 500L) Log.i("RATVPlayback", "position went back ${lastPos - pos} ms")
                else if (pos > lastPos + (posAt - lastPosAt) + 1_500L) Log.i("RATVPlayback", "position jumped ahead ${pos - lastPos - (posAt - lastPosAt)} ms")
            }
            lastPos = pos
            lastPosAt = posAt
            reserveMs = player.totalBufferedDuration
            Steadiness.reserveMs = reserveMs
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
            val counters = player.videoDecoderCounters
            val outFrames = counters?.let { (it.renderedOutputBufferCount + it.droppedBufferCount + it.skippedOutputBufferCount).toLong() } ?: -1L
            val hasVideo = (player.videoFormat?.height ?: 0) > 0
            if (!fpsRemembered && measuredFps > 0f && videoHeight > 0) {
                fpsRemembered = true
                EnhanceMemory.put(context, enhanceKey(watched), videoHeight, measuredFps)
            }
            if (player.isPlaying && hasVideo && outFrames >= 0) {
                if (outFrames != outFramesSeen) {
                    outFramesSeen = outFrames
                    framesStuckSince = 0L
                } else if (framesStuckSince == 0L) {
                    framesStuckSince = tick
                }
            } else {
                outFramesSeen = outFrames
                framesStuckSince = 0L
            }
            if (player.isPlaying) {
                if (watchPos < 0L || kotlin.math.abs(pos - watchPos) > 200L) {
                    watchPos = pos
                    posStuckSince = 0L
                } else if (posStuckSince == 0L) {
                    posStuckSince = tick
                }
            } else {
                watchPos = pos
                posStuckSince = 0L
            }
            // Only a channel that was really moving (10 pictures a second or more) counts as frozen:
            // some radio channels show one still picture.
            // (Not in the last half minute of a film, whose picture may end before its sound.)
            val nearEnd = !watched.isLive && player.duration > 0L && player.duration - pos < 30_000L
            val pictureFrozen = framesStuckSince > 0L && tick - framesStuckSince >= 5_000L && measuredFps >= 10f && !nearEnd
            val clockStopped = posStuckSince > 0L && tick - posStuckSince >= 6_000L
            if ((pictureFrozen || clockStopped) && watchRestarts < 3) {
                watchRestarts++
                Log.i("RATVPlayback", if (pictureFrozen) "the picture froze while the sound went on: starting again" else "the clock stopped while playing: starting again")
                framesStuckSince = 0L
                posStuckSince = 0L
                outFramesSeen = -1L
                watchPos = -1L
                val at = player.currentPosition
                player.stop()
                player.prepare()
                if (watched.isLive) player.seekToDefaultPosition() else player.seekTo(at)
                player.playWhenReady = true
            }
            // A channel that kept stalling as TS and then plays two calm minutes as HLS: HLS is steadier on
            // this server, and an unsteady line then opens all its channels that way.
            if (watched.isLive && usingHls && hlsPreferredFor == watched.id) {
                if (stalls > 0) hlsCalmSeconds = 0 else if (player.isPlaying) hlsCalmSeconds++
                if (hlsCalmSeconds == 120) {
                    val host = android.net.Uri.parse(watched.url).host.orEmpty()
                    if (!repo.prefs.hlsHelps(host)) {
                        Log.i("RATVPlayback", "HLS played two calm minutes after the stalls: steadier on $host")
                        repo.prefs.setHlsHelps(host, true)
                    }
                }
            }
            // Through the enhancement, a channel with its store full that still shows no picture after
            // 7 s: the chip cannot keep up with it (or draws nothing).
            if (pipeline && enhanceWanted && !pictureShown && player.playWhenReady && tick - loopStart >= 7_000L &&
                player.totalBufferedDuration >= 3_000L && com.ratv.app.data.debugProperty("debug.ratv.enhancekeep") != "1"
            ) {
                enhanceTooSlow("no picture after 7 s")
            }
            if (pipeline && counters != null && enhanceWanted) {
                val running = player.playWhenReady &&
                    (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
                if (running && hasVideo) {
                    if (playingSince == 0L) playingSince = tick
                    if (tick - playingSince >= 5_000L) {
                        if (windowFrames < 0L) {
                            windowStart = tick
                            windowFrames = outFrames
                            windowDropped = counters.droppedBufferCount.toLong()
                        } else if (tick - windowStart >= 6_000L) {
                            val frames = outFrames - windowFrames
                            val dropped = counters.droppedBufferCount - windowDropped
                            windowStart = tick
                            windowFrames = outFrames
                            windowDropped = counters.droppedBufferCount.toLong()
                            val keep = com.ratv.app.data.debugProperty("debug.ratv.enhancekeep") == "1"
                            if (frames >= 30L && dropped * 100L > frames * 12L && !keep) {
                                enhanceTooSlow("$dropped of $frames pictures dropped")
                            }
                        }
                    }
                } else {
                    playingSince = 0L
                    windowFrames = -1L
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
    LaunchedEffect(player, reconnectAttempts, reconnecting) {
        if (!reconnecting) return@LaunchedEffect
        delay(minOf(1_500L * reconnectAttempts, 10_000L))
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
    val audioOptions = remember(player, audioVersion, sourcesOpen) { trackOptions(player, C.TRACK_TYPE_AUDIO) }
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

    /** Opens the channel whose number was typed on the remote. */
    fun openTypedNumber() {
        val n = typedNumber.toIntOrNull()
        typedNumber = ""
        if (n == null) return
        val target = channels.firstOrNull { it.num == n }
        if (target == null) {
            streamNotice = tr("لا توجد قناة برقم {0}", n)
            return
        }
        if (target.id != item?.channel?.id) {
            play(liveToPlayable(target), showControls = false)
            banner = target
        }
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

    /** The player's "enhance picture" button: the same switch as in the settings. */
    fun toggleEnhance() {
        val item = current ?: return
        touch()
        val on = !enhanceWanted
        if (on) {
            repo.prefs.enhance = true
            enhanceSetting = true
            // Turned on by hand: try again even where it was stopped before.
            repo.prefs.enhanceBroken = false
            enhanceBroken = false
            repo.prefs.enhanceStrength = ENHANCE_FULL
            enhanceStrength = ENHANCE_FULL
        } else {
            repo.prefs.enhance = false
            enhanceSetting = false
        }
        val next = if (on && wantsEnhance(item, seenHeight = videoHeight.takeIf { it > 0 })) enhanceKey(item) else null
        if (next != enhanceFor) {
            if (!item.isLive) resumeAt = item.id to player.currentPosition
            enhanceFor = next
        }
        streamNotice = if (on) tr("تحسين الصورة الذكي: يشتغل") else tr("تحسين الصورة الذكي: مطفي")
    }

    /** TV remote: OK shows the controls, up/down change channel, left/right seek, media keys work anywhere. */
    fun onRemoteKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val live = item?.isLive == true
        val code = event.nativeKeyEvent.keyCode
        val panels = panelOpen || sourcesOpen || showTracks || subsOpen
        // Number keys: type a channel number; it opens 1.5 s after the last digit (or at once with OK).
        val digit = when (code) {
            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> code - android.view.KeyEvent.KEYCODE_0
            in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> code - android.view.KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0 && live && !panels && !locked) {
            typedNumber = (typedNumber + digit).takeLast(4)
            return true
        }
        if (typedNumber.isNotEmpty() && (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)) {
            openTypedNumber()
            return true
        }
        when (code) {
            android.view.KeyEvent.KEYCODE_LAST_CHANNEL -> {
                val back = previousChannel
                if (live && back != null) {
                    play(liveToPlayable(back), showControls = false)
                    banner = back
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_INFO -> {
                showControls()
                return true
            }
            android.view.KeyEvent.KEYCODE_GUIDE -> {
                if (live) navigator.guide()
                return true
            }
            android.view.KeyEvent.KEYCODE_CAPTIONS -> {
                if (!live) subsOpen = true
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_STOP -> {
                navigator.back()
                return true
            }
        }
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

    LaunchedEffect(typedNumber) {
        if (typedNumber.isNotEmpty()) {
            delay(1_500L)
            openTypedNumber()
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
                    playerViewRef[0] = this
                }
            },
            update = {
                it.resizeMode = resizeModes[resizeIndex].resize
                if (it.player !== exoPlayer) it.player = exoPlayer
            },
            modifier = resizeModes[resizeIndex].ratio?.let { Modifier.align(Alignment.Center).aspectRatio(it) } ?: Modifier.fillMaxSize()
        )

        // RA TV's mark in the corner of the picture, as channels show theirs (hidden with the controls up).
        if (!controlsVisible && !inPip) {
            BrandLogo(
                if (isTv) 44.dp else 30.dp,
                Modifier
                    .align(AbsoluteAlignment.TopRight)
                    .absolutePadding(top = if (isTv) 26.dp else 14.dp, right = if (isTv) 34.dp else 16.dp),
                alpha = 0.6f,
                small = true
            )
        }

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
                        tr("انقطع الإنترنت… يكمل التشغيل فور عودته"),
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
                format = if (current?.isLive == true) (if (usingHls) "HLS" else "TS") else tr("ملف"),
                width = videoWidth,
                height = videoHeight,
                fps = measuredFps,
                refresh = view.display?.refreshRate ?: 0f,
                refreshOptions = view.display?.let { d ->
                    val m = d.mode
                    d.supportedModes.filter { it.physicalWidth == m.physicalWidth && it.physicalHeight == m.physicalHeight }
                        .map { kotlin.math.round(it.refreshRate).toInt() }.distinct().sorted()
                }.orEmpty(),
                matchOn = repo.prefs.matchFrameRate,
                video = player.videoFormat?.sampleMimeType,
                audio = player.audioFormat?.sampleMimeType,
                decoder = decoderName,
                bandwidthBps = bandwidthBps,
                reserveMs = reserveMs,
                stalls = stalls,
                reconnects = liveReconnects,
                steadyExtraMs = if (current?.isLive == true) Steadiness.extraMs else 0L,
                dropped = droppedFrames,
                offline = netTrouble,
                enhanced = if (enhanceWanted && videoHeight in 1 until ENHANCE_BELOW_HEIGHT) {
                    if (EnhanceStatus.strength >= ENHANCE_FULL) "${videoHeight}p → ${enhancedHeight(videoHeight)}p · " + tr("كامل")
                    else "${videoHeight}p · " + tr("خفيف")
                } else {
                    null
                },
                gpu = EnhanceStatus.gpu,
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
                    netTrouble -> tr("انقطع الإنترنت — يكمل من الاحتياطي، باقي {0} ث", secs)
                    liveNow -> tr("الاحتياطي ضد انقطاع النت: {0} ث", secs)
                    else -> tr("الاحتياطي ضد انقطاع النت: {0} من {1} ث", secs, cushion / 1000L)
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
                    if (current?.isLive == true) tr("انقطع البث، جارٍ إعادة الاتصال… ({0})", reconnectAttempts)
                    else tr("انقطع البث، جارٍ إعادة الاتصال… ({0}/{1})", reconnectAttempts, MAX_RECONNECTS),
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
                Text(tr("معلق {0}", mixing.name), style = T.body(12.sp, FontWeight.Medium), maxLines = 1)
            }
        }

        val notice = resizeNotice
        if (notice != null && !inPip) {
            TopNotice(tr("حجم الصورة: {0}", notice), RIcons.Aspect)
        }
        val told = streamNotice
        if (told != null && notice == null && !inPip) {
            TopNotice(told, RIcons.Swap)
        }

        // The channel number being typed on the remote, as TVs show it.
        if (typedNumber.isNotEmpty() && !inPip) {
            Box(
                Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .absolutePadding(left = 40.dp, top = 30.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xCC0B0C10))
                    .padding(horizontal = 22.dp, vertical = 8.dp)
            ) {
                Text(typedNumber, style = T.display(40.sp, FontWeight.ExtraBold, RColors.Saffron))
            }
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
                        Text(tr("فتح القفل"), style = T.body(14.sp, FontWeight.Medium))
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
                            nextLabel = nextProgramme?.let { tr("التالي {0} · {1}", hm(it.start), it.title) },
                            hasTrackChoice = hasTrackChoice,
                            canPip = canPip,
                            resizeLabel = resizeModes[resizeIndex].label,
                            hasNextEpisode = nextEpisode != null,
                            resolution = resolutionLabel(videoHeight)?.let { if (enhanceWanted) "$it ✦" else it },
                            enhanceLabel = if (videoHeight in 1 until ENHANCE_BELOW_HEIGHT) {
                                if (enhanceWanted) tr("تحسين الصورة: يعمل") else tr("تحسين الصورة")
                            } else {
                                null
                            },
                            onEnhance = { toggleEnhance() },
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
                                if (enhanceWanted) player.setVideoEffects(listOf(TvEnhanceEffect(stretchFor(resizeIndex), enhanceStrength)))
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
                        onSwitch = { ch ->
                            keepVersionFor = ch.id
                            play(liveToPlayable(ch))
                        },
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
                            tr("إعادة المحاولة"),
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
                            OutlineButton(tr("القناة التالية"), onClick = { zap(1) }, height = 46.dp)
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
                    Text(tr("تعذّر العثور على هذا المحتوى."), style = T.body(16.sp, FontWeight.Bold))
                    OutlineButton(tr("رجوع"), onClick = { navigator.back() }, icon = RIcons.Back)
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
    enhanceLabel: String?,
    onEnhance: () -> Unit,
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
            CircleIconButton(RIcons.Back, tr("رجوع"), onBack, background = Glass)
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
                        .background(RColors.AccentBrush)
                        .tvClickable(RoundedCornerShape(20.dp), onClickLabel = tr("شاهد {0}", fourKName), ringColor = Color.White, onClick = onFourK)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(tr("4K متاح"), style = T.body(13.sp, FontWeight.Bold, RColors.OnSaffron))
                }
                Spacer(Modifier.width(8.dp))
            }
            if (hasTrackChoice) GlassIcon(RIcons.Captions, tr("الصوت والترجمة"), onTracks)
            if (canPip) GlassIcon(RIcons.Pip, tr("صورة داخل صورة"), onPip)
            GlassIcon(RIcons.Aspect, tr("حجم الصورة: {0}", resizeLabel), onResize)
            GlassIcon(RIcons.Info, tr("معلومات البث"), onStats)
        }

        // Center transport (media controls stay left-to-right)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(40.dp)
            ) {
                SeekButton(RIcons.Rewind, tr("رجوع 10 ثوانٍ"), seekable) { onSeekBy(-10_000L) }
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
                        contentDescription = if (isPlaying) tr("إيقاف مؤقت") else if (ended) tr("إعادة التشغيل") else tr("تشغيل"),
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
                SeekButton(RIcons.FastForward, tr("تقديم 10 ثوانٍ"), seekable) { onSeekBy(10_000L) }
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
                    PillButton(RIcons.ListIcon, tr("القنوات"), onChannels)
                    PillButton(RIcons.Calendar, tr("الدليل"), onGuide)
                    if (showSources) PillButton(RIcons.Mic, tr("المعلق والجودة"), onSources)
                    if (enhanceLabel != null) PillButton(RIcons.Sparkle, enhanceLabel, onEnhance)
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
                    PillButton(RIcons.Captions, if (subtitlesOn) tr("الترجمة: مفعّلة") else tr("الترجمة"), onSubtitles)
                    if (hasNextEpisode) PillButton(RIcons.SkipNext, tr("الحلقة التالية"), onNextEpisode)
                    if (enhanceLabel != null) PillButton(RIcons.Sparkle, enhanceLabel, onEnhance)
                    Spacer(Modifier.weight(1f))
                }
                GlassIcon(RIcons.Lock, tr("قفل الشاشة"), onLock)
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
                    Text(tr("القنوات"), style = T.display(17.sp))
                    Text(
                        listOfNotNull(categoryName, tr("{0} قناة", channels.size)).joinToString(" · "),
                        style = T.caption,
                        maxLines = 1
                    )
                }
                CircleIconButton(RIcons.Close, tr("إغلاق قائمة القنوات"), onClose, background = RColors.Surface2, size = 40.dp, iconSize = 20.dp)
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
    val audio = remember(player, version) { trackOptions(player, C.TRACK_TYPE_AUDIO) }
    val text = remember(player, version) { trackOptions(player, C.TRACK_TYPE_TEXT) }
    val textOff = remember(player, version) { isTrackTypeDisabled(player, C.TRACK_TYPE_TEXT) || text.none { it.selected } }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("تم"), color = RColors.Saffron) } },
        title = { Text(tr("الصوت والترجمة"), style = T.h2) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (audio.isNotEmpty()) {
                    Text(tr("الصوت"), style = T.label, modifier = Modifier.padding(vertical = 6.dp))
                    audio.forEach { option ->
                        TrackRow(option.label, option.selected) {
                            selectTrack(player, C.TRACK_TYPE_AUDIO, option)
                            version++
                        }
                    }
                }
                Text(tr("الترجمة"), style = T.label, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                TrackRow(tr("إيقاف"), textOff) {
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
    refreshOptions: List<Int>,
    matchOn: Boolean,
    video: String?,
    audio: String?,
    decoder: String?,
    bandwidthBps: Long,
    reserveMs: Long,
    stalls: Int,
    reconnects: Int,
    steadyExtraMs: Long,
    dropped: Int,
    offline: Boolean,
    enhanced: String?,
    gpu: String?,
    modifier: Modifier = Modifier
) {
    fun codec(mime: String?) = mime?.substringAfter('/')?.uppercase() ?: "—"
    val fpsText = if (fps > 0f) String.format(java.util.Locale.US, if (fps % 1f == 0f) "%.0f" else "%.2f", fps) else "—"
    val uneven = fps > 0f && refresh > 0f && !refreshFits(refresh, fps)
    // Whether the TV offers a refresh rate that shows this frame rate evenly.
    val canMatch = refreshOptions.any { refreshFits(it.toFloat(), fps) }
    val verdict = when {
        dropped > 50 -> tr("الجهاز لا يلحق على فك هذا البث ({0} إطار سقط): جرّب نسخة HD من القناة.", dropped)
        stalls > 0 && offline -> tr("التوقفات من انقطاع الاتصال بالإنترنت.")
        stalls > 0 && reconnects > 5 -> tr("توقف البث {0} مرة والاتصال بالسيرفر انقطع {1} مرة: غالباً من الواي فاي أو من السيرفر. التطبيق يجمع احتياطياً أكبر تلقائياً، وجرّب «أقصى ثبات» أو صيغة HLS من الإعدادات.", stalls, reconnects)
        stalls > 0 -> tr("توقف البث {0} مرة: الاتصال أو السيرفر لم يرسل البث بسرعة كافية. التطبيق يجمع احتياطياً أكبر تلقائياً.", stalls)
        uneven && !matchOn -> tr("البث {0} إطار والشاشة {1} هرتز فتظهر الحركة متقطعة قليلاً: فعّل «مطابقة معدل الإطارات» من الإعدادات.", fpsText, refresh.toInt())
        uneven && !canMatch -> tr(
            "البث {0} إطار والشاشة {1} هرتز، وتلفزيونك ما يعطي التطبيقات هرتز يناسبه ({2})، فتظهر الحركة متقطعة قليلاً.",
            fpsText, refresh.toInt(), refreshOptions.ifEmpty { listOf(refresh.toInt()) }.joinToString("، ")
        )
        uneven -> tr("البث {0} إطار والشاشة {1} هرتز: «مطابقة معدل الإطارات» بتغيّرها خلال ثواني.", fpsText, refresh.toInt())
        reconnects > 0 -> tr("البث سليم: انقطع الاتصال {0} مرة ورجع على طول بدون توقف.", reconnects)
        else -> tr("البث سليم: لا توقفات ولا إطارات ساقطة.")
    }
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE60B0C10))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(tr("معلومات البث"), style = T.body(14.sp, FontWeight.Bold, RColors.Saffron))
        val lines = listOf(
            tr("الصيغة") to format,
            tr("الصورة") to (if (height > 0) tr("{0}{1} · {2} إطار/ث", if (width > 0) "$width×" else "", height, fpsText) else "—"),
            tr("الشاشة") to (if (refresh > 0f) {
                if (refreshOptions.size > 1) tr("{0} هرتز (يدعم {1})", refresh.toInt(), refreshOptions.joinToString("، ")) else tr("{0} هرتز", refresh.toInt())
            } else {
                "—"
            }),
            tr("الترميز") to tr("{0} · صوت {1}", codec(video), codec(audio)),
            tr("فك الترميز") to (decoder ?: "—"),
            tr("سرعة الاتصال") to (if (bandwidthBps > 0 && bandwidthBps != 12_000_000L) String.format(java.util.Locale.US, tr("%.1f ميجابت"), bandwidthBps / 1_000_000.0) else "—"),
            tr("الاحتياطي") to tr("{0} ث", reserveMs / 1000L) + (if (steadyExtraMs > 0) tr(" (يجمع {0} ث زيادة)", steadyExtraMs / 1000L) else ""),
            tr("التوقفات") to "$stalls",
            tr("إعادة الاتصال") to "$reconnects",
            tr("إطارات ساقطة") to "$dropped",
            tr("تحسين الصورة") to (enhanced ?: tr("لا")),
            tr("الرسوميات") to (gpu ?: "—")
        )
        lines.forEach { (k, v) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(k, style = T.body(12.5.sp, color = RColors.Text2), modifier = Modifier.width(96.dp))
                Text(v, style = T.body(12.5.sp, FontWeight.Bold).copy(textDirection = androidx.compose.ui.text.style.TextDirection.ContentOrLtr))
            }
        }
        Text(
            verdict,
            style = T.body(12.5.sp, FontWeight.Bold, if (verdict.startsWith(tr("البث سليم"))) RColors.Text else RColors.Saffron),
            modifier = Modifier.width(300.dp)
        )
    }
}
