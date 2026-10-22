package com.ratv.app.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ratv.app.data.LoadState
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.components.BrandLogo
import com.ratv.app.ui.components.OutlineButton
import com.ratv.app.ui.components.PrimaryButton
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.SloganText
import com.ratv.app.ui.components.rememberIsTv
import com.ratv.app.ui.components.TvInitialFocus
import com.ratv.app.ui.components.screenBackground
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onNeedLogin: () -> Unit, onReady: () -> Unit, onAutoPlay: (String) -> Unit) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (repo.playlist.value == null) {
            delay(700)
            onNeedLogin()
        } else {
            repo.start()
        }
    }

    LaunchedEffect(state) {
        if (state is LoadState.Ready) {
            delay(250)
            val last = repo.prefs.lastChannelId
            if (repo.prefs.autoplayLast && last != null && repo.channel(last) != null) onAutoPlay(last) else onReady()
        }
    }

    val tv = rememberIsTv()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, tween(750, easing = FastOutSlowInEasing), label = "appear")
    // The light blooms once and stays: an endless animation would keep a weak TV busy while it loads.
    val bloom by animateFloatAsState(if (shown) 1f else 0f, tween(1400, delayMillis = 150, easing = FastOutSlowInEasing), label = "bloom")

    Box(
        Modifier
            .fillMaxSize()
            .screenBackground(),
        contentAlignment = Alignment.Center
    ) {
        // A soft light behind the logo.
        Box(
            Modifier
                .size(if (tv) 460.dp else 340.dp)
                .graphicsLayer {
                    alpha = bloom
                    scaleX = 0.7f + 0.3f * bloom
                    scaleY = 0.7f + 0.3f * bloom
                }
                .background(Brush.radialGradient(listOf(RColors.Saffron.copy(alpha = 0.34f), Color.Transparent)), CircleShape)
        )
        Column(
            Modifier.graphicsLayer {
                alpha = appear
                scaleX = 0.86f + 0.14f * appear
                scaleY = 0.86f + 0.14f * appear
            },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BrandLogo(if (tv) 200.dp else 156.dp)
            Spacer(Modifier.height(18.dp))
            SloganText(if (tv) 30.sp else 22.sp)
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 32.dp, end = 32.dp, bottom = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val current = state
            if (current is LoadState.Error) {
                Text(current.message, style = T.body(14.sp, color = RColors.Text2), textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                val retryFocus = remember { FocusRequester() }
                TvInitialFocus(retryFocus, key = current.message)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        "إعادة المحاولة",
                        onClick = { repo.refresh() },
                        icon = RIcons.Refresh,
                        height = 48.dp,
                        modifier = Modifier.focusRequester(retryFocus)
                    )
                    OutlineButton("تغيير الاشتراك", onClick = onNeedLogin, icon = RIcons.Swap)
                }
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .width(132.dp)
                        .height(3.dp),
                    color = RColors.Saffron,
                    trackColor = RColors.Surface2
                )
                val message = (current as? LoadState.Loading)?.message
                Spacer(Modifier.height(12.dp))
                Text(message ?: " ", style = T.caption)
            }
        }
    }
}
