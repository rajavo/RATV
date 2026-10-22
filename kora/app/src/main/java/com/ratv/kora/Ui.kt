package com.ratv.kora

import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

// ------------------------------------------------------------------ look

object C {
    val Ink = Color(0xFF0B0C10)
    val Surface = Color(0xFF14161C)
    val Surface2 = Color(0xFF1C1F27)
    val Surface3 = Color(0xFF262A34)
    val Text = Color(0xFFF4F2EE)
    val Text2 = Color(0xFFB4B0A8)
    val Text3 = Color(0xFF7D7A73)
    val Saffron = Color(0xFFFF9F1C)
    val OnSaffron = Color(0xFF1A1205)
    val Live = Color(0xFFFF4D5E)
    val LiveRow = Color(0xFF221419)
    val Notice = Color(0x40D92B3A)
}

private val Tajawal = FontFamily(Font(R.font.tajawal_regular, FontWeight.Normal), Font(R.font.tajawal_bold, FontWeight.Bold))

private fun t(size: Float, bold: Boolean = false, color: Color = C.Text, align: TextAlign = TextAlign.Unspecified) = TextStyle(
    fontFamily = Tajawal,
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    color = color,
    lineHeight = (size * 1.35f).sp,
    textAlign = align
)

/** Clickable with the remote: a saffron ring shows the selected item. */
@Composable
private fun Modifier.tvClick(shape: Shape, onFocus: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    if (focused && onFocus != null) LaunchedEffect(Unit) { onFocus() }
    return this
        .clip(shape)
        .border(BorderStroke(3.dp, if (focused) C.Saffron else Color.Transparent), shape)
        .clickable(interactionSource = source, indication = ripple(), onClick = onClick)
}

@Composable
private fun rememberIsTv(): Boolean {
    val context = LocalContext.current
    return remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}

// ------------------------------------------------------------------ screen

@Composable
fun KoraApp() {
    val context = LocalContext.current
    val schedule by Kora.schedule.collectAsStateWithLifecycle()
    val live by Kora.live.collectAsStateWithLifecycle()
    val loading by Kora.loading.collectAsStateWithLifecycle()
    val error by Kora.error.collectAsStateWithLifecycle()
    val update by SelfUpdate.available.collectAsStateWithLifecycle()
    val progress by Installer.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val tv = rememberIsTv()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // While the app is on the screen: the saved schedule at once, a new one every half hour,
    // live scores every minute.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            Kora.loadSaved(context)
            launch { SelfUpdate.checkIfDue() }
            while (isActive) {
                now = System.currentTimeMillis()
                Kora.refresh(context)
                Kora.refreshLive()
                now = System.currentTimeMillis()
                delay(60_000)
            }
        }
    }

    val today = Fmt.startOfDay(now)
    val days = remember(today) { (-1 until 30).map { Fmt.addDays(today, it) } }
    var day by rememberSaveable { mutableLongStateOf(today) }
    LaunchedEffect(days) { if (day !in days) day = today }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var askInstall by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(9_000)
            message = null
        }
    }

    val s = schedule
    val counts = remember(s, today) { s?.matches?.groupingBy { Fmt.startOfDay(it.time) }?.eachCount() ?: emptyMap() }
    val dayMatches = remember(s, live, day) {
        val end = Fmt.addDays(day, 1)
        s?.matches?.filter { it.time >= day && it.time < end }?.map { it.withLive(live[it.id]) } ?: emptyList()
    }
    val liveNow = dayMatches.filter { statusOf(it, now).phase == Phase.LIVE }
    val leaguesOfDay = remember(dayMatches, s) {
        dayMatches.map { it.league }.distinct().sortedBy { s?.leagueOrder(it) ?: 0 }
    }
    val shownFilter = when {
        filter == "live" && liveNow.isNotEmpty() -> "live"
        filter != null && filter in leaguesOfDay -> filter
        else -> null
    }
    val shown = when (shownFilter) {
        null -> dayMatches
        "live" -> liveNow
        else -> dayMatches.filter { it.league == shownFilter }
    }

    val dayFocus = remember { FocusRequester() }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    var restoreId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(day, shownFilter) { listState.scrollToItem(0) }
    LaunchedEffect(s != null) {
        if (tv && s != null) {
            delay(150)
            runCatching { dayFocus.requestFocus() }
        }
    }
    LaunchedEffect(openId) {
        val back = restoreId
        if (openId == null && back != null) {
            delay(80)
            runCatching { rowFocus[back]?.requestFocus() }
            restoreId = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(C.Ink)
            .safeDrawingPadding()
            .padding(horizontal = if (tv) 36.dp else 12.dp, vertical = if (tv) 18.dp else 8.dp)
    ) {
        Header(s, loading, now, tv) {
            scope.launch {
                if (!Kora.refresh(context, force = true)) message = "ما قدرنا نحدّث الجدول الحين، تأكد من النت."
                Kora.refreshLive()
            }
        }
        update?.let { info ->
            Spacer(Modifier.height(10.dp))
            UpdateBanner(info.versionName, progress) {
                scope.launch {
                    try {
                        val f = Installer.download(context, info.apkUrl, "RAKora-${info.versionCode}.apk")
                        if (!Installer.install(context, f)) {
                            message = "اسمح لـ «جدول RA كورة» يثبّت التطبيقات، وارجع اضغط «حدّث» مرة ثانية."
                        }
                    } catch (e: Exception) {
                        message = "ما قدرنا نحمّل التحديث، تأكد من النت وجرّب مرة ثانية."
                    }
                }
            }
        }
        message?.let {
            Spacer(Modifier.height(10.dp))
            Notice(it)
        }
        if (s == null) {
            EmptyState(loading, error) { scope.launch { Kora.refresh(context, force = true) } }
            return@Column
        }
        Spacer(Modifier.height(if (tv) 14.dp else 10.dp))
        DayStrip(days, day, today, counts, tv, dayFocus) { day = it }
        Spacer(Modifier.height(10.dp))
        LeagueStrip(s, leaguesOfDay, dayMatches, liveNow.size, shownFilter, tv) { filter = it }
        Spacer(Modifier.height(10.dp))
        MatchList(shown, s, now, tv, listState, rowFocus, empty = when {
            dayMatches.isEmpty() -> "ما في مباريات في هذا اليوم."
            else -> "ما في مباريات بهذا الاختيار."
        }) { m ->
            restoreId = m.id
            openId = m.id
        }
    }

    val open = openId?.let { id -> s?.matches?.firstOrNull { it.id == id }?.withLive(live[id]) }
    if (open != null && s != null) {
        MatchDialog(open, s, now, tv, onWatch = {
            when (RaTv.watch(context, open, s)) {
                RaTv.Result.OPENED -> openId = null
                RaTv.Result.OLD_VERSION -> {
                    openId = null
                    message = "حدّث RA TV لآخر نسخة (من داخل RA TV) عشان يفتح المباراة على طول."
                }
                RaTv.Result.NOT_INSTALLED -> askInstall = true
            }
        }, onClose = { openId = null })
    }
    if (askInstall) {
        InstallDialog(progress, onInstall = {
            scope.launch {
                try {
                    val f = Installer.download(context, RaTv.APK, "RATV.apk")
                    if (!Installer.install(context, f)) {
                        message = "اسمح لـ «جدول RA كورة» يثبّت التطبيقات، وارجع اضغط «ثبّت RA TV» مرة ثانية."
                    }
                    askInstall = false
                } catch (e: Exception) {
                    message = "ما قدرنا نحمّل RA TV، تأكد من النت وجرّب مرة ثانية."
                }
            }
        }, onClose = { askInstall = false })
    }
}

@Composable
private fun Header(s: Schedule?, loading: Boolean, now: Long, tv: Boolean, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier
                .size(if (tv) 50.dp else 42.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("جدول RA كورة", style = t(if (tv) 27f else 22f, true))
            Text(
                when {
                    s == null -> "مباريات 30 يوم، تتحدث تلقائياً"
                    else -> "محدّث ${Fmt.ago(s.updated, now)} · ${Fmt.matches(s.matches.size)} في 30 يوم"
                },
                style = t(if (tv) 15f else 13f, color = C.Text2),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Pill(if (loading) "جاري التحديث…" else "تحديث", primary = false, tv = tv, onClick = onRefresh)
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = t(15f, true),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.Notice)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
private fun UpdateBanner(version: String, progress: Float?, onUpdate: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface2)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("في نسخة جديدة من الجدول ($version)", style = t(15f, true), modifier = Modifier.weight(1f))
        Pill(progress?.let { "جاري التحميل ${(it * 100).toInt()}%" } ?: "حدّث الحين", primary = true, tv = false, onClick = onUpdate)
    }
}

@Composable
private fun EmptyState(loading: Boolean, error: String?, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            if (error != null && !loading) error else "جاري تحميل الجدول…",
            style = t(18f, true, align = TextAlign.Center)
        )
        if (error != null && !loading) Pill("جرّب مرة ثانية", primary = true, tv = false, onClick = onRetry)
    }
}

@Composable
private fun Pill(label: String, primary: Boolean, tv: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        style = t(if (tv) 16f else 14f, true, if (primary) C.OnSaffron else C.Text),
        modifier = modifier
            .tvClick(RoundedCornerShape(14.dp), onClick = onClick)
            .background(if (primary) C.Saffron else C.Surface3)
            .padding(horizontal = 18.dp, vertical = 9.dp)
    )
}

// ------------------------------------------------------------------ days and competitions

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DayStrip(
    days: List<Long>,
    selected: Long,
    today: Long,
    counts: Map<Long, Int>,
    tv: Boolean,
    selectedFocus: FocusRequester,
    onSelect: (Long) -> Unit
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (days.indexOf(selected) - 1).coerceAtLeast(0))
    LazyRow(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            // Coming back to the days with the remote lands on the chosen day, not on its neighbour.
            .focusProperties {
                enter = {
                    if (state.layoutInfo.visibleItemsInfo.any { it.key == selected }) selectedFocus else FocusRequester.Default
                }
            }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        items(days, key = { it }) { d ->
            val chosen = d == selected
            val count = counts[d] ?: 0
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier
                    .then(if (chosen) Modifier.focusRequester(selectedFocus) else Modifier)
                    // With the remote the list follows the selected day; on a phone a tap chooses it.
                    .tvClick(shape, onFocus = if (tv) ({ onSelect(d) }) else null) { onSelect(d) }
                    .background(if (chosen) C.Saffron else C.Surface)
                    .width(if (tv) 104.dp else 86.dp)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val fg = if (chosen) C.OnSaffron else C.Text
                Text(Fmt.dayName(d, today), style = t(if (tv) 17f else 15f, true, fg), maxLines = 1)
                Text(Fmt.date(d), style = t(if (tv) 13.5f else 12f, color = if (chosen) C.OnSaffron else C.Text2), maxLines = 1)
                Text(
                    Fmt.matches(count),
                    style = t(if (tv) 12f else 11f, color = if (chosen) C.OnSaffron else C.Text3),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun LeagueStrip(
    s: Schedule,
    leagues: List<String>,
    dayMatches: List<Match>,
    liveCount: Int,
    selected: String?,
    tv: Boolean,
    onSelect: (String?) -> Unit
) {
    if (dayMatches.isEmpty()) return
    val perLeague = remember(dayMatches) { dayMatches.groupingBy { it.league }.eachCount() }
    LazyRow(
        Modifier
            .fillMaxWidth()
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp)
    ) {
        item(key = "all") { LeagueChip("الكل (${dayMatches.size})", null, selected == null, tv) { onSelect(null) } }
        if (liveCount > 0) {
            item(key = "live") { LeagueChip("مباشر الحين ($liveCount)", null, selected == "live", tv, live = true) { onSelect("live") } }
        }
        items(leagues, key = { it }) { id ->
            val lg = s.league(id)
            LeagueChip("${lg.short} (${perLeague[id] ?: 0})", lg.logo, selected == id, tv) { onSelect(id) }
        }
    }
}

@Composable
private fun LeagueChip(label: String, logo: String?, chosen: Boolean, tv: Boolean, live: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .tvClick(shape, onClick = onClick)
            .background(if (chosen) C.Saffron else C.Surface)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (live) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (chosen) C.OnSaffron else C.Live)
            )
            Spacer(Modifier.width(7.dp))
        } else if (!logo.isNullOrBlank()) {
            Logo(logo, label, if (tv) 20.dp else 18.dp)
            Spacer(Modifier.width(7.dp))
        }
        Text(label, style = t(if (tv) 15f else 13.5f, true, if (chosen) C.OnSaffron else C.Text), maxLines = 1)
    }
}

// ------------------------------------------------------------------ matches

@Composable
private fun MatchList(
    matches: List<Match>,
    s: Schedule,
    now: Long,
    tv: Boolean,
    state: LazyListState,
    rowFocus: HashMap<String, FocusRequester>,
    empty: String,
    onOpen: (Match) -> Unit
) {
    if (matches.isEmpty()) {
        Text(
            empty,
            style = t(17f, true, C.Text2, TextAlign.Center),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp)
        )
        return
    }
    val groups = remember(matches, s) {
        matches.groupBy { it.league }.toList().sortedBy { s.leagueOrder(it.first) }
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 24.dp)
    ) {
        groups.forEach { (leagueId, list) ->
            item(key = "league-$leagueId") { LeagueTitle(s.league(leagueId), list.size, tv) }
            items(list, key = { it.id }) { m ->
                val req = rowFocus.getOrPut(m.id) { FocusRequester() }
                MatchRow(m, s, now, tv, Modifier.focusRequester(req)) { onOpen(m) }
            }
        }
    }
}

@Composable
private fun LeagueTitle(league: League, count: Int, tv: Boolean) {
    Row(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (league.logo.isNotBlank()) {
            Logo(league.logo, league.short, if (tv) 24.dp else 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(league.ar, style = t(if (tv) 17f else 15f, true, C.Saffron))
        Spacer(Modifier.width(8.dp))
        Text("$count", style = t(if (tv) 14f else 12.5f, color = C.Text3))
    }
}

@Composable
private fun MatchRow(m: Match, s: Schedule, now: Long, tv: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val home = s.team(m.home)
    val away = s.team(m.away)
    val st = statusOf(m, now)
    val shape = RoundedCornerShape(16.dp)
    val logo = if (tv) 40.dp else 30.dp
    Row(
        modifier
            .fillMaxWidth()
            .tvClick(shape, onClick = onClick)
            .background(if (st.phase == Phase.LIVE) C.LiveRow else C.Surface)
            .padding(horizontal = if (tv) 18.dp else 10.dp, vertical = if (tv) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Right to left: home team, the score or the time, away team.
        TeamName(home.name, TextAlign.End, tv, Modifier.weight(1f))
        Spacer(Modifier.width(if (tv) 12.dp else 8.dp))
        Logo(home.logo, home.name, logo)
        Middle(m, st, tv, Modifier.width(if (tv) 132.dp else 92.dp))
        Logo(away.logo, away.name, logo)
        Spacer(Modifier.width(if (tv) 12.dp else 8.dp))
        TeamName(away.name, TextAlign.Start, tv, Modifier.weight(1f))
    }
}

@Composable
private fun TeamName(name: String, align: TextAlign, tv: Boolean, modifier: Modifier) {
    Text(
        name,
        style = t(if (tv) 18f else 14.5f, true, align = align),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** Two numbers side by side, the home team's on the right. */
@Composable
private fun ScorePair(home: Int, away: Int, style: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$home", style = style)
        Text(" - ", style = style)
        Text("$away", style = style)
    }
}

@Composable
private fun Middle(m: Match, st: StatusView, tv: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val hs = m.homeScore
        val aw = m.awayScore
        val showScore = hs != null && aw != null && m.state != "pre"
        when {
            showScore -> {
                ScorePair(hs!!, aw!!, t(if (tv) 24f else 19f, true))
                if (m.homePens != null && m.awayPens != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ترجيح ", style = t(11.5f, color = C.Text3))
                        ScorePair(m.homePens, m.awayPens, t(11.5f, color = C.Text3))
                    }
                }
                StatusLine(st, tv)
            }
            st.phase == Phase.SOON -> Text(st.text, style = t(if (tv) 20f else 16f, true, C.Saffron), maxLines = 1)
            else -> StatusLine(st, tv, big = true)
        }
    }
}

@Composable
private fun StatusLine(st: StatusView, tv: Boolean, big: Boolean = false) {
    val color = when (st.phase) {
        Phase.LIVE -> C.Live
        Phase.OFF -> C.Text2
        else -> C.Text3
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (st.phase == Phase.LIVE) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(C.Live)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(Fmt.ltr(st.text), style = t(if (big) (if (tv) 16f else 14f) else (if (tv) 13.5f else 12f), true, color), maxLines = 1)
    }
}

@Composable
private fun Logo(url: String, name: String, size: Dp) {
    var failed by remember(url) { mutableStateOf(url.isBlank()) }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (failed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(C.Surface3),
                contentAlignment = Alignment.Center
            ) {
                Text(initial(name), style = t(size.value * 0.42f, true, C.Text2))
            }
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true }
            )
        }
    }
}

/** The letter shown when a team has no logo: "الأهلي" → "أ", not the "ا" of "ال". */
private fun initial(name: String): String {
    val n = name.trim()
    val core = if (n.startsWith("ال") && n.length > 3) n.substring(2) else n
    return core.take(1)
}

// ------------------------------------------------------------------ dialogs

@Composable
private fun MatchDialog(m: Match, s: Schedule, now: Long, tv: Boolean, onWatch: () -> Unit, onClose: () -> Unit) {
    val home = s.team(m.home)
    val away = s.team(m.away)
    val league = s.league(m.league)
    val st = statusOf(m, now)
    val first = remember { FocusRequester() }
    val canWatch = st.phase == Phase.SOON || st.phase == Phase.LIVE
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                Modifier
                    .padding(20.dp)
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(C.Surface)
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (league.logo.isNotBlank()) {
                        Logo(league.logo, league.short, 26.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(league.ar, style = t(16f, true, C.Saffron))
                    m.note?.let { Text("  ·  $it", style = t(14f, color = C.Text2), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BigTeam(home, tv, Modifier.weight(1f))
                    Column(Modifier.width(if (tv) 170.dp else 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val hs = m.homeScore
                        val aw = m.awayScore
                        if (hs != null && aw != null && m.state != "pre") {
                            ScorePair(hs, aw, t(if (tv) 38f else 30f, true))
                            if (m.homePens != null && m.awayPens != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("ترجيح ", style = t(13f, color = C.Text3))
                                    ScorePair(m.homePens, m.awayPens, t(13f, color = C.Text3))
                                }
                            }
                            StatusLine(st, tv)
                        } else if (st.phase == Phase.SOON) {
                            Text(st.text, style = t(if (tv) 28f else 22f, true, C.Saffron))
                        } else {
                            StatusLine(st, tv, big = true)
                        }
                    }
                    BigTeam(away, tv, Modifier.weight(1f))
                }
                Text(
                    Fmt.fullDate(m.time) + (if (m.tbd) " · الوقت لاحقاً" else " · " + Fmt.time(m.time)),
                    style = t(16f, color = C.Text2)
                )
                listOfNotNull(m.venue, m.city).distinct().joinToString("، ").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = t(14f, color = C.Text3, align = TextAlign.Center))
                }
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (canWatch) {
                        Pill("شاهد على RA TV", primary = true, tv = tv, modifier = Modifier.focusRequester(first), onClick = onWatch)
                        Pill("رجوع", primary = false, tv = tv, onClick = onClose)
                    } else {
                        Pill("رجوع", primary = true, tv = tv, modifier = Modifier.focusRequester(first), onClick = onClose)
                    }
                }
                Text(
                    if (canWatch) "يفتح RA TV ويدوّر على القنوات اللي تعرض المباراة في دليل البرامج."
                    else "المباراة خلصت.",
                    style = t(13f, color = C.Text3, align = TextAlign.Center)
                )
            }
        }
        LaunchedEffect(Unit) {
            delay(60)
            runCatching { first.requestFocus() }
        }
    }
}

@Composable
private fun BigTeam(team: Team, tv: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Logo(team.logo, team.name, if (tv) 76.dp else 60.dp)
        Text(team.name, style = t(if (tv) 19f else 16f, true, align = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InstallDialog(progress: Float?, onInstall: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(C.Surface)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("RA TV مو مثبت على هذا الجهاز", style = t(20f, true, align = TextAlign.Center))
                Text(
                    "ثبّت RA TV، وبعدها ارجع هنا واضغط «شاهد على RA TV» مرة ثانية.",
                    style = t(15f, color = C.Text2, align = TextAlign.Center)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pill(
                        progress?.let { String.format(Locale.US, "جاري التحميل %d%%", (it * 100).toInt()) } ?: "ثبّت RA TV",
                        primary = true,
                        tv = false,
                        modifier = Modifier.focusRequester(first),
                        onClick = { if (progress == null) onInstall() }
                    )
                    Pill("رجوع", primary = false, tv = false, onClick = onClose)
                }
            }
        }
        LaunchedEffect(Unit) {
            delay(60)
            runCatching { first.requestFocus() }
        }
    }
}
