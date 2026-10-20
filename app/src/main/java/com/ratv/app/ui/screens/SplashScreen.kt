package com.ratv.app.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ratv.app.data.LoadState
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.components.AppIcon
import com.ratv.app.ui.components.OutlineButton
import com.ratv.app.ui.components.PrimaryButton
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.RatvLogo
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

    Box(
        Modifier
            .fillMaxSize()
            .background(RColors.Ink),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val unit = 1.dp.toPx()
            drawCircle(RColors.Surface, radius = 280f * unit, center = c, style = Stroke(width = unit))
            drawCircle(RColors.Surface2, radius = 200f * unit, center = c, style = Stroke(width = unit))
            drawCircle(RColors.Surface3, radius = 128f * unit, center = c, style = Stroke(width = unit))
            drawCircle(RColors.Saffron, radius = 4f * unit, center = Offset(c.x + 90.5f * unit, c.y - 90.5f * unit))
        }

        AppIcon(124.dp)

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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("إعادة المحاولة", onClick = { repo.refresh() }, icon = RIcons.Refresh, height = 48.dp)
                    OutlineButton("تغيير الاشتراك", onClick = onNeedLogin, icon = RIcons.Swap)
                }
            } else {
                RatvLogo(30.sp)
                Spacer(Modifier.height(12.dp))
                Text("كل قنواتك في مكان واحد", style = T.body(15.sp, color = RColors.Text2))
                Spacer(Modifier.height(24.dp))
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
