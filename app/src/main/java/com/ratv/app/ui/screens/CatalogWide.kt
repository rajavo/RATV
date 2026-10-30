package com.ratv.app.ui.screens

import com.ratv.app.i18n.tr
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ratv.app.data.Category
import com.ratv.app.data.foldText
import com.ratv.app.data.stripTags
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.components.EmptyState
import com.ratv.app.ui.components.PosterImage
import com.ratv.app.ui.components.QualityBadge
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.focusRing
import com.ratv.app.ui.components.formatCount
import com.ratv.app.ui.components.rememberIsTv
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.components.screenBackground
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val C_CONTINUE = "__continue__"
private const val C_ALL = "__all__"
private const val C_FAV = "__fav__"
private const val C_NEW = "__new__"

private val sortOptions get() = listOf(
    "added" to tr("الأحدث إضافة"),
    "name" to tr("الاسم (أ - ي)"),
    "name_desc" to tr("الاسم (ي - أ)"),
    "rating" to tr("الأعلى تقييماً"),
    "year" to tr("الأحدث إصداراً")
)

private fun sortItems(list: List<CatalogItem>, key: String): List<CatalogItem> = when (key) {
    "name", "name_desc" -> {
        // Sort by the title itself, not by provider tags such as "AR-SUBS:".
        val keyed = list.map { it to foldText(stripTags(it.title)) }
        val sorted = if (key == "name") keyed.sortedBy { it.second } else keyed.sortedByDescending { it.second }
        sorted.map { it.first }
    }
    "rating" -> list.sortedByDescending { it.rating?.toDoubleOrNull() ?: -1.0 }
    "year" -> list.sortedByDescending { it.year?.toIntOrNull() ?: 0 }
    else -> list.sortedByDescending { it.added }
}

/** Films or series on a TV screen: sections with counts, sorting, and a poster wall. */
@Composable
fun CatalogWide(
    title: String,
    entries: List<CatalogItem>,
    categories: List<Category>,
    loading: Boolean,
    continueIds: List<String>,
    sortKey: String,
    onSort: (String) -> Unit,
    emptyTitle: String,
    badgeOf: (CatalogItem) -> String?,
    onOpen: (String) -> Unit,
    onSearch: () -> Unit
) {
    val repo = LocalRepo.current
    val tv = rememberIsTv()
    val scope = rememberCoroutineScope()
    val favorites by repo.favorites.collectAsStateWithLifecycle()
    var selected by rememberSaveable(title) { mutableStateOf(C_ALL) }

    val counts = remember(entries) { entries.groupingBy { it.categoryId }.eachCount() }
    val favoriteCount = remember(entries, favorites) { entries.count { it.favoriteKey in favorites } }
    val byId = remember(entries) { entries.associateBy { it.id } }
    val continueItems = remember(continueIds, byId) { continueIds.mapNotNull { byId[it] } }
    val sideEntries = remember(categories, counts, favoriteCount, continueItems, entries) {
        buildList {
            if (continueItems.isNotEmpty()) add(SideEntry(C_CONTINUE, tr("متابعة المشاهدة"), continueItems.size, RIcons.Clock))
            add(SideEntry(C_ALL, tr("الكل"), entries.size, RIcons.Film))
            add(SideEntry(C_FAV, tr("المفضلة"), favoriteCount, RIcons.Heart))
            add(SideEntry(C_NEW, tr("أُضيف حديثاً"), minOf(60, entries.size), RIcons.Star))
            categories.forEach { add(SideEntry(it.id, it.name, counts[it.id] ?: 0)) }
        }
    }
    // Starts empty (with the spinner) so the first poster on screen is already the sorted one.
    var sorting by remember { mutableStateOf(true) }
    val visible by produceState(emptyList<CatalogItem>(), entries, favorites, selected, sortKey, continueItems) {
        sorting = true
        value = withContext(Dispatchers.Default) {
            when (selected) {
                C_CONTINUE -> continueItems
                C_NEW -> entries.sortedByDescending { it.added }.take(60)
                C_ALL -> sortItems(entries, sortKey)
                C_FAV -> sortItems(entries.filter { it.favoriteKey in favorites }, sortKey)
                else -> sortItems(entries.filter { it.categoryId == selected }, sortKey)
            }
        }
        sorting = false
    }
    val sectionName = sideEntries.firstOrNull { it.key == selected }?.name ?: title

    val gridState = rememberLazyGridState()
    val firstFocus = remember { FocusRequester() }
    // On TV the remote lands on the first poster when the section opens. The list can be
    // rebuilt right after (sorting, favourites), which drops the focus back to the top bar,
    // so keep asking until the focus is really inside the screen, for a few seconds only.
    var contentFocused by remember { mutableStateOf(false) }
    var userMoved by remember { mutableStateOf(false) }
    val openedAt = remember { SystemClock.uptimeMillis() }
    LaunchedEffect(visible.isNotEmpty(), sorting) {
        if (!tv || visible.isEmpty() || sorting) return@LaunchedEffect
        // Until the viewer presses a key, put the focus back on the first poster whenever a
        // rebuilt list takes it away (for the first few seconds only).
        while (!userMoved && SystemClock.uptimeMillis() - openedAt < 6_000L) {
            if (!contentFocused) runCatching { firstFocus.requestFocus() }
            delay(150L)
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .onFocusChanged { contentFocused = it.hasFocus }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) userMoved = true
                false
            }
            .screenBackground()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // The posters come first: in Arabic (right to left) they sit on the right, and the sections on the left.
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Start (on the right in Arabic): the section and how many titles it holds.
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(sectionName, style = T.h2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(formatCount(visible.size) + tr(" عنوان"), style = T.caption)
                    if (sorting) CircularProgressIndicator(color = RColors.Saffron, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
                // The middle: the search, the same in every section.
                CatalogSearchPill(onSearch)
                // End (on the left in Arabic): the sort order.
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (selected != C_CONTINUE && selected != C_NEW) SortMenu(sortKey, onSort)
                }
            }

            if (visible.isEmpty()) {
                when {
                    loading || sorting -> Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = RColors.Saffron, strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp))
                        Text(tr("جارٍ تحميل المكتبة…"), style = T.body(14.sp, color = RColors.Text2))
                    }
                    selected == C_FAV -> EmptyState(RIcons.Heart, tr("لا توجد عناصر مفضلة"), tr("اضغط على القلب في صفحة أي عنوان لإضافته هنا."))
                    else -> EmptyState(RIcons.Film, emptyTitle, tr("لا يحتوي هذا القسم على عناوين."))
                }
            } else {
                val firstId = visible.first().id
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(visible, key = { it.id }) { item ->
                        val badge = remember(item.id) { badgeOf(item) }
                        PosterCard(
                            item = item,
                            badge = badge,
                            focusRequester = if (item.id == firstId) firstFocus else null,
                            onOpen = { onOpen(item.id) }
                        )
                    }
                }
            }
        }

        SectionColumn(
            entries = sideEntries,
            selected = selected,
            onSelect = { key ->
                if (key != selected) {
                    selected = key
                    scope.launch { gridState.scrollToItem(0) }
                }
            },
            onEnter = {
                scope.launch {
                    gridState.scrollToItem(0)
                    delay(120L)
                    runCatching { firstFocus.requestFocus() }
                }
            },
            modifier = Modifier
                .width(270.dp)
                .fillMaxHeight()
        )
    }
}

/** The search, in the middle of the header on TV: it works the same in every section. */
@Composable
private fun CatalogSearchPill(onSearch: () -> Unit) {
    val shape = RoundedCornerShape(21.dp)
    Row(
        Modifier
            .height(42.dp)
            .clip(shape)
            .background(RColors.Surface)
            .border(1.dp, RColors.LineStrong, shape)
            .tvClickable(shape, onClickLabel = tr("بحث"), onClick = onSearch)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(RIcons.Search, contentDescription = null, tint = RColors.Text2, modifier = Modifier.size(16.dp))
        Text(tr("بحث"), style = T.body(13.sp, FontWeight.Medium), maxLines = 1)
    }
}

@Composable
private fun SortMenu(sortKey: String, onSort: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = sortOptions.firstOrNull { it.first == sortKey }?.second ?: sortOptions.first().second
    val shape = RoundedCornerShape(21.dp)
    Box {
        Row(
            Modifier
                .height(42.dp)
                .clip(shape)
                .background(RColors.Surface)
                .border(1.dp, RColors.LineStrong, shape)
                .tvClickable(shape, onClickLabel = tr("تغيير الترتيب")) { open = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(RIcons.Sliders, contentDescription = null, tint = RColors.Text2, modifier = Modifier.size(16.dp))
            Text(tr("الترتيب: {0}", label), style = T.body(13.sp, FontWeight.Medium), maxLines = 1)
            Icon(RIcons.ChevronDown, contentDescription = null, tint = RColors.Text2, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(RColors.Surface2)
        ) {
            sortOptions.forEach { (key, name) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            name,
                            style = T.body(14.sp, if (key == sortKey) FontWeight.Bold else FontWeight.Normal, if (key == sortKey) RColors.Saffron else RColors.Text)
                        )
                    },
                    onClick = {
                        open = false
                        onSort(key)
                    },
                    trailingIcon = if (key == sortKey) {
                        { Icon(RIcons.Check, contentDescription = null, tint = RColors.Saffron, modifier = Modifier.size(16.dp)) }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun PosterCard(item: CatalogItem, badge: String?, focusRequester: FocusRequester?, onOpen: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "poster-scale")
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(RColors.Surface2)
            .focusRing(focused, shape, width = 3.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onOpen)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
        ) {
            PosterImage(item.title, item.image, Modifier.fillMaxSize(), titleSize = 14.sp)
            if (badge != null) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xD90B0C10))
                        .padding(2.dp)
                ) {
                    QualityBadge(badge, color = if (badge == "4K") RColors.SaffronLight else RColors.Text)
                }
            }
            val rating = item.rating
            if (rating != null) {
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xD90B0C10))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(RIcons.StarFilled, contentDescription = null, tint = RColors.Saffron, modifier = Modifier.size(11.dp))
                    Text(rating, style = T.body(11.sp, FontWeight.Bold))
                }
            }
        }
        Text(
            item.title,
            style = T.body(13.sp, FontWeight.Bold, if (focused) RColors.Saffron else RColors.Text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
    }
}
