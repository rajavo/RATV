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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.ratv.app.data.Channel
import com.ratv.app.data.Episode
import com.ratv.app.data.Http
import com.ratv.app.data.Keys
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
            return Resolved(
                Playable(Playable.KIND_MOVIE, m.id, m.url, m.name, null, m.poster, startPosition = start),
                emptyList()
            )
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
fun createPlayer(context: Context, bufferMode: String): ExoPlayer {
    val durations = when (bufferMode) {
        "small" -> intArrayOf(5_000, 20_000, 1_000, 2_000)
        "large" -> intArrayOf(30_000, 90_000, 3_000, 6_000)
        else -> intArrayOf(15_000, 50_000, 2_000, 4_000)
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
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setLoadControl(loadControl)
        .setAudioAttributes(audioAttributes, true)
        .setHandleAudioBecomingNoisy(true)
        .build()
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
