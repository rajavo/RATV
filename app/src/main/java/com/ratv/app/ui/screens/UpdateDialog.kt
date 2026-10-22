package com.ratv.app.ui.screens

import com.ratv.app.i18n.tr
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
import com.ratv.app.BuildConfig
import com.ratv.app.data.Updater
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/** Downloads the new version from GitHub and opens Android's installer. */
@Composable
fun UpdateDialog(info: Updater.Info, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun install(f: File) {
        needsPermission = !Updater.install(context, f)
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
                            val f = Updater.download(context, info) { progress = it }
                            file = f
                            progress = null
                            install(f)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            progress = null
                            error = tr("تعذّر تنزيل التحديث. تحقّق من الاتصال وحاول مرة أخرى.")
                        }
                    }
                }
            ) {
                Text(if (file != null) tr("تثبيت") else tr("تحديث الآن"), color = if (progress == null) RColors.Saffron else RColors.Text3)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = progress == null) { Text(tr("لاحقاً"), color = RColors.Text2) }
        },
        title = { Text(tr("تحديث جديد متاح"), style = T.h2) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    tr("الإصدار {0} جاهز (عندك {1}). يُنزَّل من صفحة RATV على GitHub، وتبقى اشتراكاتك وإعداداتك كما هي.", info.versionName, BuildConfig.VERSION_NAME),
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
                    Text(tr("جارٍ التنزيل… {0}%", (p * 100).toInt()), style = T.caption, modifier = Modifier.padding(top = 8.dp))
                }
                if (needsPermission) {
                    Text(
                        tr("اسمح لتطبيق RATV بتثبيت التطبيقات في الصفحة التي فُتحت، ثم ارجع واضغط «تثبيت»."),
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
