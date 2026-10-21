package com.ratv.app.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.ratv.app.data.Channel
import com.ratv.app.data.Episode
import com.ratv.app.data.Http
import com.ratv.app.data.Keys
import com.ratv.app.data.Movie
import com.ratv.app.data.Repository
import java.util.Locale

/** Something the player can play: a live channel, a movie or an episode. */
data class Playable(
    val kind: String,
    val id: String,
    val url: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val channel: Channel? = null,
    val seriesId: String? = null,
    val startPosition: Long = 0L
) {
    val isLive: Boolean get() = kind == KIND_LIVE

    companion object {
        const val KIND_LIVE = "live"
        const val KIND_MOVIE = "movie"
        const val KIND_EPISODE = "episode"
        const val KIND_RESUME = "resume"
    }
}

class Resolved(val playable: Playable, val episodes: List<Episode>)

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun liveToPlayable(ch: Channel) = Playable(
    kind = Playable.KIND_LIVE,
    id = ch.id,
    url = ch.url,
    title = ch.name,
    subtitle = null,
    image = ch.logo,
    channel = ch
)

suspend fun resolvePlayable(repo: Repository, kind: String, id: String, seriesId: String?): Resolved? {
    when (kind) {
        Playable.KIND_LIVE -> {
            val ch = repo.channel(id) ?: return null
            return Resolved(liveToPlayable(ch), emptyList())
        }
        Playable.KIND_MOVIE -> {
            val m = repo.movie(id) ?: return null
            val start = repo.historyEntry(Keys.movie(m.id))?.position ?: 0L
            return Resolved(moviePlayable(m, start), emptyList())
        }
        Playable.KIND_EPISODE -> {
            val sid = seriesId ?: return null
            return resolveEpisode(repo, sid, id)
        }
        Playable.KIND_RESUME -> {
            val entry = repo.historyEntry(id) ?: return null
            if (entry.kind == Playable.KIND_EPISODE && entry.seriesId != null) {
                resolveEpisode(repo, entry.seriesId, entry.itemId)?.let { return it }
            }
            return Resolved(
                Playable(
                    kind = entry.kind,
                    id = entry.itemId,
                    url = entry.url,
                    title = entry.title,
                    subtitle = entry.subtitle,
                    image = entry.image,
                    seriesId = entry.seriesId,
                    startPosition = entry.position
                ),
                emptyList()
            )
        }
    }
    return null
}

private suspend fun resolveEpisode(repo: Repository, seriesId: String, episodeId: String): Resolved? {
    val info = repo.seriesInfo(seriesId) ?: return null
    val all = info.episodes.toSortedMap().values.flatten()
    val ep = all.firstOrNull { it.id == episodeId } ?: return null
    val item = repo.seriesItem(seriesId)
    val start = repo.historyEntry(Keys.episode(ep.id))?.position ?: 0L
    return Resolved(episodePlayable(ep, seriesId, item?.name, item?.cover, start), all)
}

fun moviePlayable(m: Movie, start: Long = 0L) =
    Playable(Playable.KIND_MOVIE, m.id, m.url, m.name, null, m.poster, startPosition = start)

/** A media item for a stream address (HLS links get their type so they always play as HLS). */
fun mediaItemFor(url: String): MediaItem {
    val builder = MediaItem.Builder().setUri(url)
    if (url.contains(".m3u8", ignoreCase = true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
    return builder.build()
}

fun episodePlayable(ep: Episode, seriesId: String, seriesName: String?, cover: String?, start: Long = 0L) = Playable(
    kind = Playable.KIND_EPISODE,
    id = ep.id,
    url = ep.url,
    title = seriesName ?: ep.title,
    subtitle = "الموسم ${ep.season} · الحلقة ${ep.num}",
    image = ep.image ?: cover,
    seriesId = seriesId,
    startPosition = start
)

@androidx.annotation.OptIn(UnstableApi::class)
fun createPlayer(context: Context, bufferMode: String, bestQuality: Boolean, handleAudioFocus: Boolean = true): ExoPlayer {
    // min buffer, max buffer, needed to start, needed to resume after a stall (ms)
    val durations = when (bufferMode) {
        "small" -> intArrayOf(5_000, 20_000, 1_000, 2_000)
        "medium" -> intArrayOf(15_000, 50_000, 2_000, 4_000)
        "max" -> intArrayOf(50_000, 150_000, 8_000, 12_000)
        else -> intArrayOf(30_000, 90_000, 3_000, 6_000)
    }
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(Http.USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
    val dataSourceFactory = DefaultDataSource.Factory(context, http)
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(durations[0], durations[1], durations[2], durations[3])
        .build()
    val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
    // Retry failed downloads several times before giving up, so short network drops do not stop playback.
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))
    // Live streams keep normal speed instead of rushing back to the live edge after a stall:
    // fewer stalls, and two streams stay in step when one gives the picture and the other the commentary.
    val liveSpeed = DefaultLivePlaybackSpeedControl.Builder()
        .setFallbackMinPlaybackSpeed(1f)
        .setFallbackMaxPlaybackSpeed(1f)
        .build()
    val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(loadControl)
        .setLivePlaybackSpeedControl(liveSpeed)
        .setAudioAttributes(audioAttributes, handleAudioFocus)
        .setHandleAudioBecomingNoisy(handleAudioFocus)
        .build()
    if (bestQuality) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setForceHighestSupportedBitrate(true)
            .build()
    }
    return player
}

/** Network hiccups and server overloads are worth reconnecting for; a refused login is not. */
@androidx.annotation.OptIn(UnstableApi::class)
fun isRetryable(e: PlaybackException): Boolean = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_TIMEOUT,
    PlaybackException.ERROR_CODE_UNSPECIFIED -> true
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        ((e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0) >= 500
    else -> false
}

/** The real height of the picture being shown, as people usually name it. */
fun resolutionLabel(height: Int): String? = when {
    height <= 0 -> null
    height >= 2000 -> "4K"
    height >= 1400 -> "1440p"
    height >= 1000 -> "1080p"
    height >= 700 -> "720p"
    else -> "${height}p"
}

fun describeError(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "رفض الخادم تشغيل البث. قد يكون الاشتراك منتهياً أو مستخدماً على جهاز آخر."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "تعذّر الاتصال بالبث. تحقّق من الإنترنت."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
        "صيغة هذا البث غير مدعومة على جهازك."
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
        "البث غير متاح حالياً."
    else -> "تعذّر تشغيل البث (${e.errorCodeName})."
}

class TrackOption(val label: String, val group: Tracks.Group?, val index: Int, val selected: Boolean)

fun trackOptions(player: Player, type: Int): List<TrackOption> {
    val out = ArrayList<TrackOption>()
    val arabic = Locale("ar")
    var n = 1
    player.currentTracks.groups.forEach { g ->
        if (g.type != type) return@forEach
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            val code = f.language
            val language = if (code.isNullOrBlank() || code == "und") null else {
                val name = Locale(code).getDisplayLanguage(arabic)
                if (name.isNullOrBlank()) code else name
            }
            val parts = listOfNotNull(f.label?.takeIf { it.isNotBlank() }, language).distinct()
            val label = if (parts.isEmpty()) {
                (if (type == C.TRACK_TYPE_AUDIO) "مسار صوتي " else "ترجمة ") + n
            } else {
                parts.joinToString(" · ")
            }
            out.add(TrackOption(label, g, i, g.isTrackSelected(i)))
            n++
        }
    }
    return out
}

fun selectTrack(player: Player, type: Int, option: TrackOption?) {
    val builder = player.trackSelectionParameters.buildUpon()
    val group = option?.group
    if (option == null || group == null) {
        builder.setTrackTypeDisabled(type, true)
    } else {
        builder.setTrackTypeDisabled(type, false)
        builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.index))
    }
    player.trackSelectionParameters = builder.build()
}

fun isTrackTypeDisabled(player: Player, type: Int): Boolean =
    player.trackSelectionParameters.disabledTrackTypes.contains(type)

@Composable
fun rememberIsInPictureInPicture(activity: Activity?): Boolean {
    val componentActivity = activity as? ComponentActivity
    var inPip by remember {
        mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity?.isInPictureInPictureMode == true)
    }
    DisposableEffect(componentActivity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { info -> inPip = info.isInPictureInPictureMode }
        componentActivity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { componentActivity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}
