package com.ratv.app.player

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ratv.app.data.Channel
import com.ratv.app.data.MatchLinks
import com.ratv.app.data.Programme
import com.ratv.app.data.ScoreLive
import com.ratv.app.data.ScoreMatch
import com.ratv.app.data.ScoreSchedule
import com.ratv.app.data.ScoreTeam
import com.ratv.app.data.Scores
import com.ratv.app.data.isCalledOff
import com.ratv.app.i18n.I18n
import com.ratv.app.i18n.tr
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.hm
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.ceil

// The scoreboard over a sports channel while it shows a match: both teams with their crests, the
// score and the game clock, all the way through the match. Which match: the one picked by hand for
// this channel, else the one of the link from جدول RA كورة, else the one the programme guide names.
// The score is shown as late as the picture runs behind (so a goal is never told before it is seen).

private const val TAG = "RATVScore"
private const val MIN = 60_000L

/** Matches picked by hand per channel (an empty id hides the board there), until the match is over. */
object ScoreChoices {
    private val picks = HashMap<String, Pair<String, Long>>()
    var version by mutableIntStateOf(0)
        private set

    fun get(channelId: String, now: Long): String? = synchronized(picks) {
        picks[channelId]?.takeIf { it.second > now }?.first
    }

    fun set(channelId: String, matchId: String, until: Long) {
        synchronized(picks) { picks[channelId] = matchId to until }
        version++
    }
}

/** One reading of a match's live state, with the game clock it gives (seconds at [clockAt], running or not). */
private class Reading(val at: Long, val live: ScoreLive, val clockAt: Long, val clockSec: Double, val running: Boolean) {
    fun clockSecAt(t: Long): Double = clockSec + if (running) (t - clockAt) / 1000.0 else 0.0
}

/**
 * The readings of one match over time, so the board can show the match as it was some seconds ago,
 * with a game clock that runs smoothly between readings that only give the minute.
 */
class MatchTimeline {
    private val readings = ArrayList<Reading>()

    /** When the first reading of the final whistle came (elapsed realtime), 0 before. */
    @Volatile
    var overAt = 0L
        private set

    val latest: ScoreLive? get() = synchronized(readings) { readings.lastOrNull()?.live }

    fun add(at: Long, live: ScoreLive): Unit = synchronized(readings) {
        val prev = readings.lastOrNull()
        val running = live.state == "in" && !live.isBreak && !live.isShootout &&
            !live.status.contains("SUSPENDED") && !live.status.contains("DELAYED")
        val w = window(live)
        var clockAt = at
        var clockSec = prev?.clockSecAt(at) ?: 0.0
        if (running && w != null) {
            val samePeriod = prev != null && prev.running && prev.live.period == live.period
            val gap = if (prev != null) (at - prev.at) / 1000.0 else Double.MAX_VALUE
            // The minute just changed (or the half just began): the clock is a few seconds past the turn.
            val turned = prev != null && gap <= 45.0 &&
                (!samePeriod || (prev!!.live.minute ?: 0) < (live.minute ?: 0))
            val estimate = if (samePeriod) prev!!.clockSecAt(at) else Double.NaN
            when {
                turned -> {
                    val anchor = w.first + minOf(gap, 30.0) / 2.0
                    if (!estimate.isNaN() && kotlin.math.abs(estimate - anchor) <= 4.0) {
                        clockAt = prev!!.clockAt
                        clockSec = prev.clockSec
                    } else {
                        clockSec = anchor
                    }
                }
                samePeriod && estimate >= w.first - 2.0 && estimate <= w.second + 5.0 -> {
                    clockAt = prev!!.clockAt
                    clockSec = prev.clockSec
                }
                samePeriod -> clockSec = if (estimate < w.first) w.first else w.second
                else -> clockSec = (w.first + w.second) / 2.0
            }
        }
        readings.add(Reading(at, live, clockAt, clockSec, running && w != null))
        if (live.isOver && overAt == 0L) overAt = at
        // Five minutes of readings are plenty: the longest delay is a minute and a half.
        while (readings.size > 2 && readings[1].at < at - 5 * MIN) readings.removeAt(0)
    }

    /** The reading in effect at [t]: the last one received by then, else the first. */
    private fun readingAt(t: Long): Reading? = synchronized(readings) {
        readings.lastOrNull { it.at <= t } ?: readings.firstOrNull()
    }

    fun liveAt(t: Long): ScoreLive? = readingAt(t)?.live

    /** The game clock at [t] in seconds, null when the source gives none (or the clock stands). */
    fun clockAt(t: Long): Int? {
        val r = readingAt(t) ?: return null
        if (!r.running) return null
        return r.clockSecAt(t).toInt().coerceAtLeast(0)
    }

    companion object {
        /**
         * ESPN turns to minute N about 28 seconds after the game clock reaches N−1:00 (football counts
         * 22:10 as the 23rd minute, and its answers come late): measured against the kick-off its own
         * goals and cards give.
         */
        private const val MINUTE_LAG_SEC = 28.0

        /** Where the game clock can be while a reading shows its minute: from, to (seconds). */
        internal fun window(live: ScoreLive): Pair<Double, Double>? {
            val minute = live.minute ?: return null
            if (minute <= 0) return null
            val base = when (live.period) {
                2 -> 2700.0
                3 -> 5400.0
                4 -> 6300.0
                else -> 0.0
            }
            val low = maxOf((minute - 1) * 60.0 + MINUTE_LAG_SEC, base)
            val high = maxOf(minute * 60.0 + MINUTE_LAG_SEC, low + 1.0)
            return low to high
        }
    }
}

/** What the board shows at one moment. */
class BoardView(
    val match: ScoreMatch,
    val home: ScoreTeam,
    val away: ScoreTeam,
    val homeScore: Int?,
    val awayScore: Int?,
    val homePens: Int?,
    val awayPens: Int?,
    /** The game clock ("34:12"), or what is going on instead ("استراحة", "انتهت", "تبدأ 21:00"). */
    val pill: String,
    val clock: Boolean,
    val live: Boolean,
    /** Every other second, for the live dot. */
    val blink: Boolean = false
) {
    fun describe(arabic: Boolean): String {
        val score = if (homeScore != null && awayScore != null) "$homeScore - $awayScore" else "-"
        return tr("لوحة النتيجة: {0} {1} {2}، {3}", home.name(arabic), score, away.name(arabic), pill)
    }
}

/** The board of the channel playing: its match, and the matches that can be picked for it. */
@Stable
class ScoreBoardState {
    var schedule by mutableStateOf<ScoreSchedule?>(null)
        internal set
    var match by mutableStateOf<ScoreMatch?>(null)
        internal set

    /** How [match] was found: "hand", "link" (from جدول RA كورة) or "guide" (the programme guide). */
    var source by mutableStateOf("")
        internal set

    /** True when the viewer hid the board on this channel. */
    var hidden by mutableStateOf(false)
        internal set

    internal var timeline = MatchTimeline()

    /** The matches a viewer may be watching now, for picking one by hand. */
    fun candidates(now: Long): List<ScoreMatch> = schedule?.around(now).orEmpty()
}

/** True when a programme title reads like a match ("A × B", "A vs B", "A - B"). */
private val MATCH_TITLE = Regex("""\s(×|x|X|vs\.?|VS|Vs|v|V|ضد|-|–)\s""")

/** Finds the match shown on [channel] and follows its live state, while [enabled]. */
@Composable
fun rememberScoreBoard(enabled: Boolean, channel: Channel?, programme: Programme?, isSports: Boolean): ScoreBoardState {
    val context = LocalContext.current
    val state = remember { ScoreBoardState() }
    val choices = ScoreChoices.version
    val linked = MatchLinks.watching?.takeIf { it.first == channel?.id }
    val wanted = enabled && channel != null &&
        (isSports || linked != null || programme?.title?.let { MATCH_TITLE.containsMatchIn(it) } == true ||
            ScoreChoices.get(channel.id, System.currentTimeMillis()) != null)

    // The schedule, while a channel that may show a match is on (it is kept 3 hours on the device).
    LaunchedEffect(wanted) {
        if (!wanted) return@LaunchedEffect
        while (isActive) {
            Scores.schedule(context)?.let { state.schedule = it }
            delay(20 * MIN)
        }
    }

    // Which match: picked by hand, from the link, or named by the guide; looked at again every half minute.
    val currentProgramme by rememberUpdatedState(programme)
    LaunchedEffect(wanted, state.schedule, channel?.id, programme?.title, programme?.start, choices) {
        while (isActive) {
            val s = state.schedule
            val ch = channel
            if (!wanted || s == null || ch == null) {
                state.match = null
                state.hidden = false
                return@LaunchedEffect
            }
            val now = System.currentTimeMillis()
            fun showing(m: ScoreMatch?) = m != null && now >= m.time - 20 * MIN && now <= m.time + 180 * MIN
            val picked = ScoreChoices.get(ch.id, now)
            state.hidden = picked == ""
            val p = currentProgramme
            val found: Pair<ScoreMatch, String>? = when {
                picked == "" -> null
                picked != null -> s.match(picked)?.let { it to "hand" }
                else -> linked?.let { Scores.matchFor(s, it.second) }?.takeIf { showing(it) }?.let { it to "link" }
                    ?: p?.let { guideMatch(s, it) }?.takeIf { showing(it) }?.let { it to "guide" }
            }
            if (found?.first?.id != state.match?.id) {
                Log.i(TAG, "board on ${ch.name}: ${found?.first?.id ?: "none"} (${found?.second ?: "-"})")
                state.timeline = MatchTimeline()
            }
            state.match = found?.first
            state.source = found?.second.orEmpty()
            delay(30_000L)
        }
    }

    // The live state of that match, read again and again while it is on.
    val match = state.match
    LaunchedEffect(match?.id, enabled) {
        val m = match ?: return@LaunchedEffect
        if (!enabled) return@LaunchedEffect
        val timeline = state.timeline
        var failures = 0
        while (isActive) {
            val wait = m.time - 20 * MIN - System.currentTimeMillis()
            if (wait > 0) {
                delay(minOf(wait, 5 * MIN))
                continue
            }
            val live = Scores.live(m)
            if (live != null) {
                timeline.add(SystemClock.elapsedRealtime(), live)
                failures = 0
            } else {
                failures++
            }
            if (timeline.overAt > 0L && SystemClock.elapsedRealtime() - timeline.overAt > 15 * MIN) break
            delay(
                when {
                    live == null -> minOf(20_000L * failures, 120_000L)
                    live.state == "in" -> if (m.fromEspn) 20_000L else 40_000L
                    live.state == "pre" -> 45_000L
                    else -> 90_000L
                }
            )
        }
    }
    return state
}

/** The match a programme shows: named in its title, or else in its description ("الدوري الإنجليزي" over "ليفربول × أرسنال"). */
private fun guideMatch(s: ScoreSchedule, p: Programme): ScoreMatch? =
    // Put there by RA TV's own sports guide: its very match.
    p.matchId?.let { s.match(it) }
        ?: Scores.matchFor(s, p.title, p.start, p.end)
        ?: p.desc?.takeIf { it.isNotBlank() }?.let { Scores.matchFor(s, p.title + " " + it.take(240), p.start, p.end) }

/** What the board shows at [at] (elapsed realtime, already moved back by the delay), null for nothing. */
private fun boardView(s: ScoreSchedule, m: ScoreMatch, timeline: MatchTimeline, at: Long, blink: Boolean): BoardView? {
    val live = timeline.liveAt(at)
    val now = System.currentTimeMillis()
    if (live != null && live.status.isCalledOff()) return null
    // Ten minutes after the final whistle the board goes.
    if (live?.isOver == true && timeline.overAt > 0L && SystemClock.elapsedRealtime() - timeline.overAt > 10 * MIN) return null
    if (live == null && now > m.time + 180 * MIN) return null
    val clock = if (live != null) timeline.clockAt(at) else null
    val pill = when {
        live == null || live.state == "pre" -> {
            val minutes = ceil((m.time - now) / 60_000.0).toInt()
            when {
                live == null && now > m.time -> tr("مباشر")
                minutes > 60 -> tr("تبدأ {0}", hm(m.time))
                minutes > 0 -> tr("تبدأ بعد {0} د", minutes)
                else -> tr("على وشك البداية")
            }
        }
        live.isOver -> if (live.homePens != null && live.awayPens != null) tr("انتهت بركلات الترجيح") else tr("انتهت")
        live.isShootout -> tr("ركلات الترجيح")
        live.isBreak -> tr("استراحة")
        live.status.contains("SUSPENDED") || live.status.contains("DELAYED") -> tr("متوقفة")
        clock != null -> formatGameClock(clock)
        else -> tr("مباشر")
    }
    return BoardView(
        match = m,
        home = s.team(m.home),
        away = s.team(m.away),
        homeScore = live?.homeScore,
        awayScore = live?.awayScore,
        homePens = live?.homePens,
        awayPens = live?.awayPens,
        pill = pill,
        clock = clock != null,
        live = live?.state == "in",
        blink = blink
    )
}

/** 34:07, 90:00, 105:30. */
fun formatGameClock(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}

// ------------------------------------------------------------ drawing

/**
 * The board at the top of the picture, [top] from the edge (lower while the controls are up).
 * [delayMs] is how late to show the match: as late as the picture runs behind the real game.
 */
@Composable
fun BoxScope.ScoreBoardOverlay(state: ScoreBoardState, top: Dp, tv: Boolean, delayMs: () -> Long) {
    val match = state.match
    val schedule = state.schedule
    val tick by produceState(SystemClock.elapsedRealtime(), match?.id) {
        while (isActive) {
            value = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }
    val view = if (match == null || schedule == null) null else boardView(schedule, match, state.timeline, tick - delayMs(), (tick / 1000L) % 2L == 1L)
    val topAnimated by animateDpAsState(top, label = "boardTop")
    // The last board stays drawn while it fades out.
    val last = remember { arrayOfNulls<BoardView>(1) }
    if (view != null) last[0] = view
    AnimatedVisibility(
        visible = view != null,
        enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { -it / 2 },
        exit = fadeOut(tween(300)),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = topAnimated)
    ) {
        last[0]?.let { ScoreBoardCard(it, tv) }
    }
}

@Composable
private fun ScoreBoardCard(b: BoardView, tv: Boolean) {
    val arabic = I18n.lang == "ar"
    // A goal: the scorer's box lights up and the pill says so, for 8 seconds.
    val before = remember(b.match.id) { arrayOf(b.homeScore, b.awayScore) }
    var goalSide by remember(b.match.id) { mutableIntStateOf(0) }
    LaunchedEffect(b.match.id, b.homeScore, b.awayScore) {
        val (h, a) = before
        val scored = when {
            h != null && b.homeScore != null && b.homeScore > h -> 1
            a != null && b.awayScore != null && b.awayScore > a -> 2
            else -> 0
        }
        before[0] = b.homeScore
        before[1] = b.awayScore
        if (scored != 0) {
            goalSide = scored
            delay(8_000L)
            goalSide = 0
        }
    }
    val goalNow = goalSide != 0

    val crest = if (tv) 30.dp else 26.dp
    val nameWidth = if (tv) 128.dp else 96.dp
    val barHeight = if (tv) 46.dp else 40.dp
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.semantics { contentDescription = b.describe(arabic) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier
                .shadow(12.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xF51D2029), Color(0xF50B0C10))))
                .border(1.dp, Color(0x2EFFFFFF), shape)
                .height(barHeight)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Crest(b.home, crest)
            TeamName(b.home.name(arabic), nameWidth, tv)
            ScoreBox(b.homeScore, goalNow && goalSide == 1, tv)
            Spacer(Modifier.width(4.dp))
            ScoreBox(b.awayScore, goalNow && goalSide == 2, tv)
            TeamName(b.away.name(arabic), nameWidth, tv)
            Crest(b.away, crest)
        }
        val pillShape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
        Row(
            Modifier
                .offset(y = (-1).dp)
                .clip(pillShape)
                .background(if (goalNow) RColors.AccentBrush else SolidColor(Color(0xF50B0C10)))
                .padding(horizontal = 14.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (b.live && !goalNow) LiveDot(dim = b.blink)
            val pens = if (b.homePens != null && b.awayPens != null && !goalNow) {
                // Home first in reading order: on the right in Arabic.
                if (arabic) " (${b.awayPens}-${b.homePens})" else " (${b.homePens}-${b.awayPens})"
            } else {
                ""
            }
            Text(
                if (goalNow) tr("هدف!") else b.pill + pens,
                style = T.display(if (tv) 13.sp else 12.sp, FontWeight.Bold, if (goalNow) RColors.OnSaffron else RColors.Text)
                    .copy(lineHeight = 18.sp, textDirection = if (b.clock && !goalNow) TextDirection.Ltr else TextDirection.Content),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun Crest(team: ScoreTeam, size: Dp) {
    var failed by remember(team.logo) { mutableStateOf(team.logo.isBlank()) }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (failed) {
            val initial = team.name(I18n.lang == "ar").firstOrNull { it.isLetter() }?.toString().orEmpty()
            Box(
                Modifier
                    .size(size * 0.86f)
                    .clip(CircleShape)
                    .background(RColors.Surface3),
                contentAlignment = Alignment.Center
            ) {
                Text(initial, style = T.display(12.sp, FontWeight.Bold, RColors.Text2), maxLines = 1)
            }
        } else {
            AsyncImage(
                model = team.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
                onError = { failed = true }
            )
        }
    }
}

@Composable
private fun TeamName(name: String, width: Dp, tv: Boolean) {
    val size = when {
        name.length > 16 -> if (tv) 11.5.sp else 10.5.sp
        name.length > 11 -> if (tv) 12.5.sp else 11.5.sp
        else -> if (tv) 14.sp else 12.5.sp
    }
    Text(
        name,
        style = T.display(size, FontWeight.Bold, RColors.Text).copy(lineHeight = size * 1.2f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 6.dp)
    )
}

@Composable
private fun ScoreBox(score: Int?, goal: Boolean, tv: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    val pulse by animateFloatAsState(if (goal) 1.12f else 1f, tween(300), label = "pulse")
    val textColor by animateColorAsState(if (goal) RColors.SaffronDeep else RColors.OnSaffron, label = "scoreText")
    Box(
        Modifier
            .scale(pulse)
            .size(width = if (tv) 32.dp else 28.dp, height = if (tv) 34.dp else 30.dp)
            .clip(shape)
            .background(if (goal) SolidColor(Color.White) else RColors.AccentBrush),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = score,
            transitionSpec = {
                (slideInVertically(tween(350)) { it } + fadeIn(tween(350))) togetherWith
                    (slideOutVertically(tween(350)) { -it } + fadeOut(tween(250)))
            },
            label = "score"
        ) { value ->
            Text(
                value?.toString() ?: "–",
                style = T.display(if (tv) 20.sp else 17.sp, FontWeight.ExtraBold, textColor).copy(lineHeight = 22.sp),
                maxLines = 1
            )
        }
    }
}

/** The red live dot, blinking with the clock (once a second, not an endless animation: weak TVs draw less). */
@Composable
private fun LiveDot(dim: Boolean) {
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(RColors.Live.copy(alpha = if (dim) 0.4f else 1f))
    )
}

// ------------------------------------------------------------ picking the match by hand

/** The matches being played now, to pick the one this channel shows (or none). */
@Composable
internal fun ScorePickPanel(
    state: ScoreBoardState,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    tv: Boolean
) {
    val now = remember { System.currentTimeMillis() }
    val s = state.schedule
    val matches = remember(s) { state.candidates(now) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (tv) {
            delay(150L)
            runCatching { firstFocus.requestFocus() }
        }
    }
    val arabic = I18n.lang == "ar"
    val current = state.match?.id
    SidePanel(
        title = tr("لوحة النتيجة"),
        subtitle = tr("اختر المباراة اللي تعرضها هذي القناة، وتظهر نتيجتها فوق طول المباراة"),
        onClose = onClose,
        width = 430.dp
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                SourceRow(
                    title = tr("بدون لوحة"),
                    subtitle = tr("تختفي من هذي القناة لين تخلص المباراة"),
                    badge = null,
                    selected = state.hidden,
                    icon = RIcons.EyeOff,
                    modifier = if (current == null) Modifier.focusRequester(firstFocus) else Modifier
                ) { onPick("") }
            }
            if (s == null) {
                item { PanelNote(tr("جارٍ تحميل جدول المباريات…")) }
            } else if (matches.isEmpty()) {
                item { PanelNote(tr("ما فيه مباريات شغّالة الحين.")) }
            } else {
                item { PanelHeader(tr("المباريات الحين")) }
                items(matches, key = { it.id }) { m ->
                    val league = s.league(m.league)
                    val home = s.team(m.home)
                    val away = s.team(m.away)
                    val started = now >= m.time
                    val auto = current == m.id && state.source == "guide"
                    MatchPickRow(
                        home = home,
                        away = away,
                        title = "${home.name(arabic)} × ${away.name(arabic)}",
                        subtitle = listOf(
                            if (arabic) league.ar else league.en.ifBlank { league.ar },
                            if (started) tr("بدأت {0}", hm(m.time)) else tr("تبدأ {0}", hm(m.time)),
                            if (auto) tr("من دليل البرامج") else ""
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        selected = current == m.id,
                        modifier = if (current == m.id) Modifier.focusRequester(firstFocus) else Modifier
                    ) { onPick(m.id) }
                }
            }
        }
    }
}

@Composable
private fun MatchPickRow(
    home: ScoreTeam,
    away: ScoreTeam,
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) RColors.Surface2 else Color.Transparent)
            .tvClickable(RoundedCornerShape(14.dp), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Crest(home, 24.dp)
            Crest(away, 24.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = T.body(14.sp, FontWeight.Bold, if (selected) RColors.Saffron else RColors.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) Text(subtitle, style = T.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(RIcons.Check, contentDescription = tr("المختار"), tint = RColors.Saffron, modifier = Modifier.size(18.dp))
    }
}

/** Remembers the viewer's pick for [channelId] until the match is surely over. */
internal fun pickScoreMatch(state: ScoreBoardState, channelId: String, matchId: String) {
    val m = if (matchId.isEmpty()) state.match else state.schedule?.match(matchId)
    val until = (m?.time ?: System.currentTimeMillis()) + 4 * 60 * MIN
    ScoreChoices.set(channelId, matchId, until)
}

