package com.ratv.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ratv.app.data.Channel
import com.ratv.app.data.Movie
import com.ratv.app.data.SeriesItem
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.Navigator
import com.ratv.app.ui.components.ChannelLogo
import com.ratv.app.ui.components.CircleIconButton
import com.ratv.app.ui.components.EmptyState
import com.ratv.app.ui.components.PosterImage
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private class SearchResults(
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<SeriesItem> = emptyList()
) {
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

@Composable
fun SearchScreen(nav: Navigator) {
    val repo = LocalRepo.current
    val channels by repo.channels.collectAsStateWithLifecycle()
    val movies by repo.movies.collectAsStateWithLifecycle()
    val series by repo.series.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(SearchResults()) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (e: IllegalStateException) {
            // The field is not attached yet.
        }
    }

    LaunchedEffect(query, channels, movies, series) {
        val q = query.trim().lowercase()
        if (q.length < 2) {
            results = SearchResults()
            return@LaunchedEffect
        }
        delay(250)
        results = withContext(Dispatchers.Default) {
            SearchResults(
                channels = channels.asSequence().filter { it.name.lowercase().contains(q) }.take(30).toList(),
                movies = movies.asSequence().filter { it.name.lowercase().contains(q) }.take(30).toList(),
                series = series.asSequence().filter { it.name.lowercase().contains(q) }.take(30).toList()
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RColors.Ink)
            .statusBarsPadding()
            .imePadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircleIconButton(RIcons.Back, "رجوع", { nav.back() })
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
                placeholder = { Text("ابحث عن قناة أو فيلم أو مسلسل", style = T.body(14.sp, color = RColors.Text3)) },
                leadingIcon = { Icon(RIcons.Search, contentDescription = null, tint = RColors.Text3, modifier = Modifier.size(20.dp)) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        CircleIconButton(RIcons.Close, "مسح", { query = "" }, background = RColors.Surface2, size = 32.dp, iconSize = 16.dp)
                    }
                } else {
                    null
                },
                singleLine = true,
                textStyle = T.body(15.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
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

        val r = results
        when {
            query.trim().length < 2 -> EmptyState(RIcons.Search, "ابحث في كل اشتراكك", "اكتب حرفين على الأقل للبحث في القنوات والأفلام والمسلسلات.")
            r.isEmpty -> EmptyState(RIcons.Search, "لا توجد نتائج", "جرّب كلمة أخرى أو اسماً أقصر.")
            else -> LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
                if (r.channels.isNotEmpty()) {
                    item { ResultHeader("القنوات", r.channels.size) }
                    items(r.channels, key = { "c" + it.id }) { ch ->
                        ResultRow(
                            title = ch.name,
                            subtitle = repo.liveCategoryName(ch.categoryId) ?: "قناة مباشرة",
                            onClick = { nav.playLive(ch.id) }
                        ) { ChannelLogo(ch.name, ch.logo, 48.dp) }
                    }
                }
                if (r.movies.isNotEmpty()) {
                    item { ResultHeader("الأفلام", r.movies.size) }
                    items(r.movies, key = { "m" + it.id }) { m ->
                        ResultRow(
                            title = m.name,
                            subtitle = listOfNotNull("فيلم", m.year, m.rating?.let { "★ $it" }).joinToString(" · "),
                            onClick = { nav.movie(m.id) }
                        ) { PosterImage(m.name, m.poster, Modifier.width(48.dp).height(68.dp).clip(RoundedCornerShape(8.dp)), showTitle = false) }
                    }
                }
                if (r.series.isNotEmpty()) {
                    item { ResultHeader("المسلسلات", r.series.size) }
                    items(r.series, key = { "s" + it.id }) { s ->
                        ResultRow(
                            title = s.name,
                            subtitle = listOfNotNull("مسلسل", s.year).joinToString(" · "),
                            onClick = { nav.show(s.id) }
                        ) { PosterImage(s.name, s.cover, Modifier.width(48.dp).height(68.dp).clip(RoundedCornerShape(8.dp)), showTitle = false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultHeader(title: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = T.h2)
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), style = T.caption)
    }
}

@Composable
private fun ResultRow(title: String, subtitle: String, onClick: () -> Unit, leading: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = T.body(15.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = T.caption, maxLines = 1)
        }
    }
}
