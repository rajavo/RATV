package com.ratv.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ratv.app.data.Playlist
import com.ratv.app.data.SpeedTest
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import java.util.Locale

/** Measures the internet speed and says which quality it is enough for. */
@Composable
fun SpeedTestDialog(playlist: Playlist?, onDismiss: () -> Unit) {
    var round by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var mbps by remember { mutableDoubleStateOf(0.0) }
    var ping by remember { mutableStateOf<Long?>(null) }
    var serverPing by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(round) {
        running = true
        failed = false
        mbps = 0.0
        ping = null
        serverPing = null
        val result = SpeedTest.run(SpeedTest.serverOf(playlist)) { mbps = it }
        if (result == null) {
            failed = true
        } else {
            mbps = result.mbps
            ping = result.pingMs
            serverPing = result.serverPingMs
        }
        running = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { round++ }, enabled = !running) {
                Text("إعادة القياس", color = if (running) RColors.Text3 else RColors.Saffron)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق", color = RColors.Text2) } },
        title = { Text("قياس سرعة النت", style = T.h2) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    String.format(Locale.US, if (mbps >= 100) "%.0f" else "%.1f", mbps),
                    style = T.body(46.sp, FontWeight.Bold, RColors.Saffron)
                )
                Text("ميجابت في الثانية", style = T.body(13.sp, color = RColors.Text2))
                if (running) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        color = RColors.Saffron,
                        trackColor = RColors.Surface3
                    )
                    Text("جارٍ القياس…", style = T.caption, modifier = Modifier.padding(top = 8.dp))
                } else if (failed) {
                    Text(
                        "تعذّر القياس. تأكّد من اتصال الجهاز بالإنترنت ثم أعد المحاولة.",
                        style = T.body(14.sp, color = RColors.Text2),
                        modifier = Modifier.padding(top = 14.dp)
                    )
                } else {
                    Text(
                        SpeedTest.verdict(mbps),
                        style = T.body(15.sp, FontWeight.Bold),
                        modifier = Modifier.padding(top = 14.dp)
                    )
                }
                Column(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoLine("زمن الاستجابة", ping?.let { "$it ملي ثانية" } ?: "—")
                    if (playlist != null) {
                        InfoLine("استجابة سيرفر الاشتراك", serverPing?.let { "$it ملي ثانية" } ?: if (running) "—" else "لا يستجيب")
                    }
                }
            }
        },
        containerColor = RColors.Surface
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = T.body(14.sp, color = RColors.Text2))
        Text(value, style = T.body(14.sp, FontWeight.Bold))
    }
}
