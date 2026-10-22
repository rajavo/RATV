package com.ratv.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ratv.app.data.Keys
import com.ratv.app.data.MovieInfo
import com.ratv.app.data.qualityOf
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.Navigator
import com.ratv.app.ui.components.CircleIconButton
import com.ratv.app.ui.components.EmptyState
import com.ratv.app.ui.components.PosterImage
import com.ratv.app.ui.components.PrimaryButton
import com.ratv.app.ui.components.QualityBadge
import com.ratv.app.ui.components.TvInitialFocus
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.artSeed
import com.ratv.app.ui.components.formatClock
import com.ratv.app.ui.components.monogramOf
import com.ratv.app.ui.components.screenBackground
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T

private val avatarColors = listOf(
    Color(0xFF3A2A1C), Color(0xFF2A2F3E), Color(0xFF2E3A2C), Color(0xFF3E2A36), Color(0xFF233A3E)
)

private val BackdropScrim = Brush.verticalGradient(
    0f to Color(0x730B0C10),
    0.3f to Color.Transparent,
    0.5f to Color(0x330B0C10),
    1f to RColors.Ink
)

@Composable
fun MovieDetailScreen(movieId: String, nav: Navigator) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val movies by repo.movies.collectAsStateWithLifecycle()
    val favorites by repo.favorites.collectAsStateWithLifecycle()
    val history by repo.history.collectAsStateWithLifecycle()
    val movie = remember(movieId, movies) { repo.movie(movieId) }

    if (movie == null) {
        MissingItem("الفيلم غير متاح", nav)
        return
    }

    val info by produceState<MovieInfo?>(initialValue = null, movieId) { value = repo.movieInfo(movieId) }
    val favKey = Keys.movie(movie.id)
    val isFavorite = favKey in favorites
    val resume = history.firstOrNull { it.key == favKey }
    val similar = remember(movies, movie) {
        movies.filter { it.categoryId == movie.categoryId && it.id != movie.id }.take(12)
    }
    val quality = remember(movie) { repo.movieQuality(movie) }
    val versions = remember(movie, movies) { repo.movieVersions(movie) }
    val better = remember(versions, quality) {
        if (quality == "4K") null else versions.firstOrNull { repo.movieQuality(it) == "4K" }
    }
    val backdrop = info?.backdrop ?: info?.cover ?: movie.poster
    val rating = info?.rating ?: movie.rating
    val meta = listOfNotNull(info?.year ?: movie.year, info?.genre, info?.duration).joinToString(" · ")
    val playFocus = remember { FocusRequester() }
    TvInitialFocus(playFocus, key = movie.id)

    Column(
        Modifier
            .fillMaxSize()
            .screenBackground()
            .verticalScroll(rememberScrollState())
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(380.dp)
        ) {
            PosterImage(movie.name, backdrop, Modifier.fillMaxSize(), showTitle = false)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(BackdropScrim)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CircleIconButton(RIcons.Back, "رجوع", { nav.back() }, background = Color(0x8C0B0C10))
                CircleIconButton(
                    if (isFavorite) RIcons.HeartFilled else RIcons.Heart,
                    if (isFavorite) "إزالة من المفضلة" else "إضافة إلى المفضلة",
                    { repo.toggleFavorite(favKey) },
                    background = Color(0x8C0B0C10),
                    tint = if (isFavorite) RColors.Saffron else RColors.Text
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(movie.name, style = T.display(28.sp, FontWeight.ExtraBold), maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (meta.isNotBlank()) Text(meta, style = T.body(13.sp, color = RColors.Text2))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (rating != null) {
                        Icon(RIcons.StarFilled, contentDescription = null, tint = RColors.Saffron, modifier = Modifier.size(15.dp))
                        Text(rating, style = T.body(14.sp, FontWeight.Bold))
                        Text("/10", style = T.caption)
                        Spacer(Modifier.width(4.dp))
                    }
                    quality?.let { QualityBadge(it, color = if (it == "4K") RColors.SaffronLight else RColors.Text2) }
                }
            }
        }

        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    text = if (resume != null) "متابعة من ${formatClock(resume.position)}" else "تشغيل",
                    onClick = { nav.playMovie(movie.id) },
                    icon = RIcons.Play,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(playFocus)
                )
                if (better != null) {
                    UpgradeButton(onClick = { nav.playMovie(better.id) })
                }
                val trailer = info?.trailer
                if (!trailer.isNullOrBlank()) {
                    Box(
                        Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(RColors.Surface)
                            .border(1.dp, RColors.Surface3, RoundedCornerShape(16.dp))
                            .tvClickable(RoundedCornerShape(16.dp), onClickLabel = "المقطع الدعائي") {
                                val url = if (trailer.startsWith("http")) trailer else "https://www.youtube.com/watch?v=$trailer"
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                } catch (e: Exception) {
                                    // No app can open the trailer link.
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(RIcons.PlayCircle, contentDescription = "المقطع الدعائي", tint = RColors.Text, modifier = Modifier.size(22.dp))
                    }
                }
            }

            val plot = info?.plot
            if (!plot.isNullOrBlank()) {
                Text(plot, style = T.body(14.5.sp, color = RColors.Text2).copy(lineHeight = 25.sp))
            }

            if (versions.isNotEmpty()) {
                Text("نسخ أخرى من الفيلم في اشتراكك", style = T.h2)
                versions.take(6).forEach { v ->
                    VersionRow(
                        quality = repo.movieQuality(v),
                        title = v.name,
                        subtitle = listOfNotNull(repo.vodCategoryName(v.categoryId), v.year).joinToString(" · ").ifBlank { null },
                        onClick = { nav.playMovie(v.id) }
                    )
                }
            }

            val director = info?.director
            if (!director.isNullOrBlank()) InfoLine("إخراج", director)
            repo.vodCategoryName(movie.categoryId)?.let { InfoLine("القسم", it) }

            val cast = info?.cast.orEmpty()
            if (cast.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("طاقم التمثيل", style = T.h2)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    cast.forEach { name -> CastMember(name) }
                }
            }

            if (similar.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("أفلام مشابهة", style = T.h2)
            }
        }

        if (similar.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            PosterRow(similar.map { PosterData(it.id, it.name, it.poster, it.year ?: "") }) { nav.movie(it) }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** "4K متاح": the same title in a higher quality elsewhere in the subscription. */
@Composable
fun UpgradeButton(onClick: () -> Unit) {
    Row(
        Modifier
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(RColors.SaffronSoft)
            .border(1.5.dp, RColors.Saffron, RoundedCornerShape(16.dp))
            .tvClickable(RoundedCornerShape(16.dp), onClickLabel = "شاهد نسخة 4K", ringColor = Color.White, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(RIcons.Play, contentDescription = null, tint = RColors.Saffron, modifier = Modifier.size(16.dp))
        Text("4K متاح", style = T.body(15.sp, FontWeight.Bold, RColors.Saffron))
    }
}

/** Another copy of the same film or series (quality, section). */
@Composable
fun VersionRow(quality: String?, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(RColors.Surface)
            .tvClickable(RoundedCornerShape(14.dp), onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QualityBadge(quality ?: "—", color = if (quality == "4K") RColors.SaffronLight else RColors.Text2)
        Column(Modifier.weight(1f)) {
            Text(title, style = T.body(14.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = T.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(RIcons.ChevronLeft, contentDescription = null, tint = RColors.Text3, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = T.body(13.sp, color = RColors.Text3), modifier = Modifier.width(56.dp))
        Text(value, style = T.body(13.sp))
    }
}

@Composable
fun CastMember(name: String) {
    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(avatarColors[artSeed(name) % avatarColors.size]),
            contentAlignment = Alignment.Center
        ) {
            Text(monogramOf(name), style = T.display(20.sp, FontWeight.Bold))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            style = T.body(12.sp, FontWeight.Bold),
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun MissingItem(title: String, nav: Navigator) {
    Column(
        Modifier
            .fillMaxSize()
            .screenBackground()
            .statusBarsPadding()
    ) {
        Row(Modifier.padding(16.dp)) { CircleIconButton(RIcons.Back, "رجوع", { nav.back() }) }
        EmptyState(RIcons.Film, title, "ربما حُذف من اشتراكك أو لم يكتمل تحميل المكتبة بعد.")
    }
}
