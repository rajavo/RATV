package com.ratv.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ratv.app.data.Updater
import com.ratv.app.ui.components.OutlineButton
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/** The schedule app, جدول RA كورة: 30 days of matches, each one opening here on its channels. */
object KoraApp {
    const val PACKAGE = "com.ratv.kora"
    const val APK = "https://github.com/rajavo/RATV/releases/download/kora-latest/RAKora.apk"

    /** Opens it; false when it is not installed. */
    fun open(context: Context): Boolean {
        val pm = context.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(PACKAGE) ?: pm.getLaunchIntentForPackage(PACKAGE) ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}

/** "جدول 30 يوم": opens the schedule app, or offers to install it. */
@Composable
fun KoraButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var ask by remember { mutableStateOf(false) }
    OutlineButton("جدول 30 يوم", { if (!KoraApp.open(context)) ask = true }, modifier, icon = RIcons.Calendar, height = 44.dp)
    if (ask) KoraInstallDialog { ask = false }
}

@Composable
private fun KoraInstallDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun install(f: File) {
        needsPermission = !Updater.install(context, f)
        if (!needsPermission) onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        confirmButton = {
            TextButton(
                enabled = progress == null,
                onClick = {
                    val ready = file
                    if (ready != null) {
                        install(ready)
                        return@TextButton
                    }
                    error = null
                    progress = 0f
                    scope.launch {
                        try {
                            val f = Updater.downloadApk(context, KoraApp.APK, "RAKora.apk") { progress = it }
                            file = f
                            progress = null
                            install(f)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            progress = null
                            error = "تعذّر تنزيل التطبيق. تحقّق من الاتصال وحاول مرة أخرى."
                        }
                    }
                }
            ) {
                Text(if (file != null) "تثبيت" else "ثبّت التطبيق", color = if (progress == null) RColors.Saffron else RColors.Text3)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = progress == null) { Text("لاحقاً", color = RColors.Text2) }
        },
        title = { Text("جدول RA كورة", style = T.h2) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "جدول مباريات 30 يوم من الدوريات العربية والأوروبية، يتحدث تلقائياً، ومن أي مباراة تضغط «شاهد على RA TV» ويفتح لك القنوات اللي تعرضها.",
                    style = T.body(14.sp, color = RColors.Text2)
                )
                progress?.let { p ->
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        color = RColors.Saffron,
                        trackColor = RColors.Surface3
                    )
                    Text("جارٍ التنزيل… ${(p * 100).toInt()}%", style = T.caption, modifier = Modifier.padding(top = 8.dp))
                }
                if (needsPermission) {
                    Text(
                        "اسمح لتطبيق RATV بتثبيت التطبيقات في الصفحة التي فُتحت، ثم ارجع واضغط «تثبيت».",
                        style = T.body(14.sp, color = RColors.Saffron),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                error?.let { Text(it, style = T.body(14.sp, color = RColors.Live), modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        containerColor = RColors.Surface
    )
}
