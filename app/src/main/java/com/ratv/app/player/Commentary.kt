package com.ratv.app.player

import com.ratv.app.i18n.tr
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ratv.app.data.Channel
import com.ratv.app.data.CommentaryGroup
import com.ratv.app.data.foldText
import com.ratv.app.ui.components.ChannelLogo
import com.ratv.app.ui.components.CircleIconButton
import com.ratv.app.ui.components.QualityBadge
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel as Queue

/**
 * Plays the commentary of another channel over the picture of the current one: a second,
 * audio-only player runs beside the main player, whose own sound is turned down.
 * Broadcasters are a few seconds apart, so the viewer moves the sound earlier or later by hand.
 */
@Stable
class MixController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val main: ExoPlayer,
    private val bufferMode: String
) {
    var channel by mutableStateOf<Channel?>(null)
        private set
    /** Positive: the commentary is heard later than before; negative: earlier. */
    var offsetMs by mutableLongStateOf(0L)
        private set
    var message by mutableStateOf<String?>(null)
    var buffering by mutableStateOf(false)
        private set

    private var audio: ExoPlayer? = null
    private var shifting = false
    private val shifts = Queue<Long>(Queue.UNLIMITED)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            buffering = playbackState == Player.STATE_BUFFERING
        }

        override fun onPlayerError(error: PlaybackException) {
            message = tr("انقطع صوت القناة الأخرى، فرجع صوت القناة الحالية.")
            stop()
        }
    }

    init {
        scope.launch {
            for (delta in shifts) applyShift(delta)
        }
    }

    val active: Boolean get() = channel != null

    fun start(ch: Channel, maxConnections: Int?) {
        if (maxConnections != null && maxConnections < 2) {
            message = tr("اشتراكك يسمح باتصال واحد فقط، وسماع معلق قناة أخرى يحتاج اتصالين في نفس الوقت.")
            return
        }
        stop()
        val p = createPlayer(context, bufferMode, bestQuality = false, handleAudioFocus = false, live = true)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        p.addListener(listener)
        p.setMediaItem(mediaItemFor(ch.url, live = true))
        p.prepare()
        p.playWhenReady = main.playWhenReady
        audio = p
        channel = ch
        offsetMs = 0L
        message = null
        main.volume = 0f
    }

    fun stop() {
        audio?.let {
            it.removeListener(listener)
            it.release()
        }
        audio = null
        channel = null
        offsetMs = 0L
        buffering = false
        main.volume = 1f
    }

    fun shift(deltaMs: Long) {
        if (audio == null) return
        offsetMs += deltaMs
        shifts.trySend(deltaMs)
    }

    private suspend fun applyShift(delta: Long) {
        val a = audio ?: return
        shifting = true
        try {
            if (delta > 0) {
                // Hold the commentary back for a moment.
                a.pause()
                delay(delta)
                if (audio === a) a.playWhenReady = main.playWhenReady
            } else {
                // Hold the picture back instead.
                val wasPlaying = main.playWhenReady
                main.pause()
                delay(-delta)
                if (wasPlaying) main.play()
            }
        } finally {
            shifting = false
        }
    }

    /** Keeps the commentary playing or paused together with the picture. */
    fun onMainPlayWhenReady(play: Boolean) {
        if (!shifting) audio?.playWhenReady = play
    }

    fun release() {
        shifts.close()
        stop()
    }
}

fun mixOffsetLabel(offsetMs: Long): String = when {
    offsetMs == 0L -> tr("بدون تقديم أو تأخير")
    offsetMs > 0L -> tr("الصوت متأخر {0} ث", seconds(offsetMs))
    else -> tr("الصوت مقدَّم {0} ث", seconds(offsetMs))
}

/** Commentators of this stream, the same game on other channels, and other versions of this channel. */
@Composable
internal fun SourcesPanel(
    audio: List<TrackOption>,
    sameEvent: List<Channel>?,
    siblings: List<Channel>,
    sports: List<Channel>,
    mix: MixController,
    epgLookup: (Channel) -> String?,
    onPickAudio: (TrackOption) -> Unit,
    onMix: (Channel) -> Unit,
    onSwitch: (Channel) -> Unit,
    onClose: () -> Unit,
    tv: Boolean,
    canMix: Boolean,
    otherName: String?,
    otherGroups: List<CommentaryGroup>?,
    otherError: String?,
    onMixOther: (Channel) -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    // The search box of the commentators' list (the other subscription's channels).
    var otherQuery by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        if (tv) {
            delay(150L)
            runCatching { firstFocus.requestFocus() }
        }
    }
    // Other versions of this very channel get their own section; the same game elsewhere comes first.
    val shown = HashSet<String>()
    val versions = siblings.filter { shown.add(it.id) }
    val event = sameEvent.orEmpty().filter { shown.add(it.id) }
    val others = sports.filter { shown.add(it.id) }.take(150)

    // The remote starts on the first thing that can be picked.
    val focusKey = when {
        mix.channel != null -> "mix"
        audio.size > 1 -> "audio"
        event.isNotEmpty() -> "e" + event.first().id
        versions.isNotEmpty() -> "v" + versions.first().id
        others.isNotEmpty() -> "s" + others.first().id
        else -> ""
    }
    fun focusIf(key: String): Modifier = if (key == focusKey) Modifier.focusRequester(firstFocus) else Modifier

    SidePanel(
        title = tr("المعلق والجودة"),
        subtitle = if (canMix) tr("اختر المعلق، أو اسمع معلق قناة أخرى على هذه الصورة") else tr("اختر المعلق من هذا البث، أو انتقل لقناة أخرى على نفس المباراة"),
        onClose = onClose,
        width = 430.dp
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            val mixing = mix.channel
            if (mixing != null) {
                item {
                    PanelCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(RIcons.Mic, contentDescription = null, tint = RColors.Saffron, modifier = Modifier.size(20.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tr("تسمع الآن معلق"), style = T.caption)
                                Text(mixing.name, style = T.body(15.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (mix.buffering) {
                                CircularProgressIndicator(color = RColors.Saffron, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            }
                        }
                        Text(mixOffsetLabel(mix.offsetMs), style = T.body(13.sp, FontWeight.Medium, RColors.SaffronLight))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ActionChip("−5", { mix.shift(-5_000L) }, modifier = focusIf("mix"))
                            ActionChip("−½", { mix.shift(-500L) })
                            ActionChip("+½", { mix.shift(500L) })
                            ActionChip("+5", { mix.shift(5_000L) })
                        }
                        Text(
                            tr("إذا سبق الصوتُ الصورة اضغط +، وإذا تأخر اضغط −"),
                            style = T.caption
                        )
                        ActionChip(tr("إيقاف وإرجاع صوت القناة"), { mix.stop() }, icon = RIcons.Close)
                    }
                }
            }
            val note = mix.message
            if (note != null) {
                item { PanelNote(note, RColors.Danger) }
            }

            if (audio.size > 1) {
                item { PanelHeader(tr("المعلق في هذا البث")) }
                itemsIndexed(audio) { index, option ->
                    SourceRow(
                        title = option.label,
                        subtitle = null,
                        badge = null,
                        selected = option.selected && mixing == null,
                        icon = RIcons.Mic,
                        modifier = if (index == 0) focusIf("audio") else Modifier,
                        onClick = { onPickAudio(option) }
                    )
                }
            }

            // The commentators of another saved subscription: that subscription has its own connection for them.
            if (otherName != null) {
                item { PanelHeader(tr("معلقو {0}", otherName)) }
                val groups = otherGroups
                when {
                    otherError != null -> item { PanelNote(otherError, RColors.Danger) }
                    groups == null -> item {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(color = RColors.Saffron, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Text(tr("جارٍ تحميل قنوات اشتراك المعلق…"), style = T.caption)
                        }
                    }
                    groups.isEmpty() -> item { PanelNote(tr("لم نجد قنوات في اشتراك المعلق.")) }
                    else -> {
                        item(key = "q-search") { CommentarySearch(otherQuery) { otherQuery = it } }
                        val q = foldText(otherQuery.trim())
                        val shown = if (q.isEmpty()) groups else groups.mapNotNull { g ->
                            val hits = g.channels.filter { foldText(it.name).contains(q) }
                            if (hits.isEmpty()) null else CommentaryGroup(g.name, hits)
                        }
                        if (shown.isEmpty()) item { PanelNote(tr("لا توجد قناة بهذا الاسم في اشتراك المعلق.")) }
                        shown.forEachIndexed { gi, g ->
                            item(key = "g$gi") { PanelHeader(g.name.ifBlank { tr("قنوات أخرى") }) }
                            items(g.channels, key = { "o" + it.id }) { ch ->
                                SourceRow(
                                    title = ch.name,
                                    subtitle = null,
                                    badge = null,
                                    selected = mixing?.url == ch.url,
                                    icon = RIcons.Mic,
                                    modifier = Modifier,
                                    onClick = { onMixOther(ch) }
                                )
                            }
                        }
                    }
                }
            }

            item { PanelHeader(tr("تنقل نفس المباراة الآن")) }
            when {
                sameEvent == null -> item {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(color = RColors.Saffron, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text(tr("جارٍ البحث في دليل البرامج…"), style = T.caption)
                    }
                }
                event.isEmpty() -> item {
                    PanelNote(tr("لم نجد في دليل البرامج قناة أخرى تعرض نفس المباراة الآن. اختر من القنوات الرياضية بالأسفل."))
                }
                else -> items(event, key = { "e" + it.id }) { ch ->
                    ChannelChoiceRow(ch, epgLookup(ch), mixing?.id == ch.id, focusIf("e" + ch.id), onMix, onSwitch, canMix)
                }
            }

            if (versions.isNotEmpty()) {
                item { PanelHeader(tr("نسخ أخرى من القناة")) }
                items(versions, key = { "v" + it.id }) { ch ->
                    ChannelChoiceRow(ch, epgLookup(ch), mixing?.id == ch.id, focusIf("v" + ch.id), onMix, onSwitch, canMix)
                }
            }

            if (others.isNotEmpty()) {
                item { PanelHeader(tr("قنوات رياضية أخرى")) }
                items(others, key = { "s" + it.id }) { ch ->
                    ChannelChoiceRow(ch, epgLookup(ch), mixing?.id == ch.id, focusIf("s" + ch.id), onMix, onSwitch, canMix)
                }
            }
        }
    }
}

/** The search box above the commentators' list: it narrows the channels by name. */
@Composable
private fun CommentarySearch(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        placeholder = { Text(tr("ابحث عن قناة"), style = T.body(14.sp, color = RColors.Text3)) },
        leadingIcon = { Icon(RIcons.Search, contentDescription = null, tint = RColors.Text3, modifier = Modifier.size(20.dp)) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                CircleIconButton(RIcons.Close, tr("مسح"), { onQuery("") }, background = RColors.Surface2, size = 32.dp, iconSize = 16.dp)
            }
        } else {
            null
        },
        singleLine = true,
        textStyle = T.body(15.sp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        shape = RoundedCornerShape(22.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = RColors.Saffron,
            unfocusedBorderColor = RColors.Surface3,
            focusedContainerColor = RColors.Surface,
            unfocusedContainerColor = RColors.Surface,
            cursorColor = RColors.Saffron,
            focusedTextColor = RColors.Text,
            unfocusedTextColor = RColors.Text
        )
    )
}

@Composable
private fun ChannelChoiceRow(
    ch: Channel,
    programme: String?,
    mixing: Boolean,
    actionModifier: Modifier,
    onMix: (Channel) -> Unit,
    onSwitch: (Channel) -> Unit,
    canMix: Boolean
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ChannelLogo(ch.name, ch.logo, 36.dp, corner = 9.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    ch.name,
                    style = T.body(13.5.sp, FontWeight.Bold, if (mixing) RColors.Saffron else RColors.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                ch.quality?.let { QualityBadge(it, color = if (it == "4K") RColors.SaffronLight else RColors.Text2) }
            }
            if (programme != null) Text(programme, style = T.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (canMix) {
            ActionChip(if (mixing) tr("تسمعه الآن") else tr("سماع معلقها"), { onMix(ch) }, modifier = actionModifier, selected = mixing, icon = RIcons.Mic)
            ActionChip(tr("انتقال"), { onSwitch(ch) }, icon = RIcons.Tv)
        } else {
            // One connection: a second channel cannot play beside this one, so its commentator is heard by switching to it.
            ActionChip(tr("انتقال"), { onSwitch(ch) }, modifier = actionModifier, icon = RIcons.Tv)
        }
    }
    Spacer(Modifier.size(2.dp))
}
