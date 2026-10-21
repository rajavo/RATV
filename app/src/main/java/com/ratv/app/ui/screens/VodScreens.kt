package com.ratv.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ratv.app.data.Category
import com.ratv.app.data.Keys
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.Navigator
import com.ratv.app.ui.components.Chip
import com.ratv.app.ui.components.CircleIconButton
import com.ratv.app.ui.components.EmptyState
import com.ratv.app.ui.components.PosterImage
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.ScreenHeader
import com.ratv.app.ui.components.TvInitialFocus
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T

data class CatalogItem(
    val id: String,
    val title: String,
    val image: String?,
    val rating: String?,
    val year: String?,
    val added: Long,
    val categoryId: String,
    val favoriteKey: String
)

private const val ALL = "__all__"
private const val FAV = "__fav__"
private const val NEW = "__new__"

@Composable
fun MoviesScreen(nav: Navigator) {
    val repo = LocalRepo.current
    val movies by repo.movies.collectAsStateWithLifecycle()
    val categories by repo.vodCategories.collectAsStateWithLifecycle()
    val loading by repo.libraryLoading.collectAsStateWithLifecycle()
    val entries = remember(movies) {
        movies.map { CatalogItem(it.id, it.name, it.poster, it.rating, it.year, it.added, it.categoryId, Keys.movie(it.id)) }
    }
    CatalogScreen(
        title = "الأفلام",
        entries = entries,
        categories = categories,
        loading = loading,
        emptyTitle = "لا توجد أفلام",
        onSearch = { nav.search() },
        onOpen = { nav.movie(it) }
    )
}

@Composable
fun SeriesScreen(nav: Navigator) {
    val repo = LocalRepo.current
    val series by repo.series.collectAsStateWithLifecycle()
    val categories by repo.seriesCategories.collectAsStateWithLifecycle()
    val loading by repo.libraryLoading.collectAsStateWithLifecycle()
    val entries = remember(series) {
        series.map { CatalogItem(it.id, it.name, it.cover, it.rating, it.year, it.added, it.categoryId, Keys.series(it.id)) }
    }
    CatalogScreen(
        title = "المسلسلات",
        entries = entries,
        categories = categories,
        loading = loading,
        emptyTitle = "لا توجد مسلسلات",
        onSearch = { nav.search() },
        onOpen = { nav.show(it) }
    )
}

@Composable
private fun CatalogScreen(
    title: String,
    entries: List<CatalogItem>,
    categories: List<Category>,
    loading: Boolean,
    emptyTitle: String,
    onSearch: () -> Unit,
    onOpen: (String) -> Unit
) {
    val repo = LocalRepo.current
    val favorites by repo.favorites.collectAsStateWithLifecycle()
    var selected by rememberSaveable(title) { mutableStateOf(ALL) }

    val visible = remember(entries, favorites, selected) {
        when (selected) {
            ALL -> entries
            FAV -> entries.filter { it.favoriteKey in favorites }
            NEW -> entries.sortedByDescending { it.added }.take(60)
            else -> entries.filter { it.categoryId == selected }
        }
    }

    val firstId = visible.firstOrNull()?.id
    val firstFocus = remember { FocusRequester() }
    TvInitialFocus(firstFocus, key = firstId)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(RColors.Ink),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScreenHeader(title, Modifier.statusBarsPadding(), horizontalPadding = 0.dp) {
                CircleIconButton(RIcons.Search, "بحث", onSearch)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Chip("الكل", selected == ALL, { selected = ALL }) }
                item { Chip("أُضيف حديثاً", selected == NEW, { selected = NEW }) }
                item { Chip("المفضلة", selected == FAV, { selected = FAV }) }
                items(categories, key = { it.id }) { cat ->
                    Chip(cat.name, selected == cat.id, { selected = cat.id })
                }
            }
        }

        if (visible.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (loading) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = RColors.Saffron, strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp))
                        Text("جارٍ تحميل المكتبة…", style = T.body(14.sp, color = RColors.Text2))
                    }
                } else if (selected == FAV) {
                    EmptyState(RIcons.Heart, "لا توجد عناصر مفضلة", "اضغط على القلب في صفحة أي عنصر لإضافته هنا.")
                } else {
                    EmptyState(RIcons.Film, emptyTitle, "لا يحتوي اشتراكك على عناصر في هذا القسم.")
                }
            }
        }

        items(visible, key = { it.id }) { item ->
            Column(
                Modifier
                    .then(if (item.id == firstId) Modifier.focusRequester(firstFocus) else Modifier)
                    .clip(RoundedCornerShape(12.dp))
                    .tvClickable(RoundedCornerShape(12.dp)) { onOpen(item.id) },
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PosterImage(
                    item.title,
                    item.image,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp)),
                    titleSize = 13.sp
                )
                Text(item.title, style = T.body(13.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.rating != null) {
                        Icon(RIcons.StarFilled, contentDescription = null, tint = RColors.Saffron, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.size(3.dp))
                        Text(item.rating, style = T.body(11.5.sp, FontWeight.Bold))
                    }
                    if (item.year != null) {
                        Text(
                            (if (item.rating != null) " · " else "") + item.year,
                            style = T.body(11.5.sp, color = RColors.Text3)
                        )
                    }
                    if (item.rating == null && item.year == null) Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}
