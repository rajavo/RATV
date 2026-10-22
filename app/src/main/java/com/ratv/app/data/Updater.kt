package com.ratv.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ratv.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Updates the app from its GitHub release: the release carries RATV.apk and version.json
 * ({"versionCode": 10, "versionName": "1.0.10"}). Customers update from inside the app.
 *
 * Tests point it at another server with: adb shell setprop debug.ratv.update http://host:port/path
 */
object Updater {

    class Info(val versionCode: Int, val versionName: String, val apkUrl: String)

    private const val RELEASE = "https://github.com/rajavo/RATV/releases/download/latest"
    private const val CHECK_EVERY = 6L * 60L * 60L * 1000L

    private val _available = MutableStateFlow<Info?>(null)

    /** A newer version, once a check found one. */
    val available: StateFlow<Info?> = _available.asStateFlow()

    private var lastCheck = 0L

    private fun base(): String = debugProperty("debug.ratv.update") ?: RELEASE

    /** Looks for a newer version on GitHub; null when this one is the latest. Throws when GitHub cannot be reached. */
    suspend fun check(): Info? = withContext(Dispatchers.IO) {
        val o = JSONObject(Http.getString(base() + "/version.json"))
        val code = o.optInt("versionCode")
        lastCheck = System.currentTimeMillis()
        val info = if (code > BuildConfig.VERSION_CODE) {
            Info(code, o.optString("versionName").ifBlank { "1.0.$code" }, base() + "/" + o.optString("apk").ifBlank { "RATV.apk" })
        } else {
            null
        }
        _available.value = info
        info
    }

    /** Checks quietly now and then (at start-up), so the home screen can offer the update. */
    suspend fun checkIfDue() {
        if (System.currentTimeMillis() - lastCheck < CHECK_EVERY) return
        runCatching { check() }
    }

    /** Downloads the new APK, reporting progress from 0 to 1 on the main thread. */
    suspend fun download(context: Context, info: Info, onProgress: (Float) -> Unit): File =
        downloadApk(context, info.apkUrl, "RATV-${info.versionCode}.apk", onProgress)

    /** Downloads an APK (this app's update, or another RA app) into the folder Android's installer reads from. */
    suspend fun downloadApk(context: Context, url: String, name: String, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, name)
        dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
        val part = File(dir, file.name + ".part")
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code)
            val body = response.body ?: throw IOException("Empty response")
            val total = body.contentLength()
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var reported = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - reported >= 512 * 1024) {
                            reported = done
                            val f = done.toFloat() / total
                            withContext(Dispatchers.Main) { onProgress(f) }
                        }
                    }
                }
            }
        }
        if (file.exists()) file.delete()
        if (!part.renameTo(file)) throw IOException("Could not save the download")
        withContext(Dispatchers.Main) { onProgress(1f) }
        file
    }

    /**
     * Opens Android's installer for [file]. Returns false when Android must first allow RATV to
     * install apps: that settings page is opened instead, and the viewer comes back to install.
     */
    fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
