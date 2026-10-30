package com.ratv.store

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** An RA app on this TV: whether it is installed, and which version. */
class Installed(val versionCode: Long, val versionName: String?, val updatedAt: Long, val icon: ImageBitmap?)

object Store {
    fun installed(context: Context, pkg: String): Installed? {
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        val icon = runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        return Installed(code, info.versionName, info.lastUpdateTime, icon)
    }

    /** A newer file than the one installed: by version when the release says it, else by date. */
    fun hasUpdate(r: Release, i: Installed?): Boolean {
        if (i == null) return false
        val code = r.versionCode
        return if (code != null) code > i.versionCode else r.updatedAt > i.updatedAt + 10 * 60_000L
    }

    /** Downloads the app file, reporting the share done (0..100). Network: call off the main thread. */
    fun download(context: Context, r: Release, progress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "apks").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name != r.apk) it.delete() } // only the latest file is kept
        val file = File(dir, r.apk)
        val c = URL(r.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "RAStore")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            var shown = -1
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val p = if (total > 0) (done * 100 / total).toInt() else 0
                        if (p != shown) {
                            shown = p
                            progress(p)
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return file
    }

    /** Hands the file to the TV's installer, which asks the viewer to confirm. */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Android 8 and later want the viewer to allow RA Store to install apps, once. */
    fun mayInstall(context: Context) = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun askToInstall(context: Context) {
        val tries = buildList {
            if (Build.VERSION.SDK_INT >= 26) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (i in tries) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    fun open(context: Context, pkg: String) {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
