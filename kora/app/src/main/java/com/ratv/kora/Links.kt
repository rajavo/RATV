package com.ratv.kora

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Opening a match in RA TV. */
object RaTv {
    const val PACKAGE = "com.ratv.app"
    const val APK = "https://github.com/rajavo/RATV/releases/download/latest/RATV.apk"

    enum class Result { OPENED, OLD_VERSION, NOT_INSTALLED }

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** ratv://match?home=…&away=…&homeAr=…&awayAr=…&start=ms&league=… */
    fun link(m: Match, s: Schedule): Uri {
        val home = s.team(m.home)
        val away = s.team(m.away)
        val league = s.league(m.league)
        return Uri.Builder().scheme("ratv").authority("match")
            .appendQueryParameter("home", home.en)
            .appendQueryParameter("away", away.en)
            .appendQueryParameter("homeAr", home.ar.orEmpty())
            .appendQueryParameter("awayAr", away.ar.orEmpty())
            .appendQueryParameter("start", m.time.toString())
            .appendQueryParameter("league", league.ar)
            .appendQueryParameter("leagueEn", league.en)
            .appendQueryParameter("id", m.id)
            .build()
    }

    /** RA TV finds the channels showing the match in its programme guide. */
    fun watch(context: Context, m: Match, s: Schedule): Result {
        val uri = link(m, s)
        val result = try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result.OPENED
        } catch (e: ActivityNotFoundException) {
            if (installed(context)) {
                // A version of RA TV from before this link: open it as it is, its own update brings the link.
                val pm = context.packageManager
                val launch = pm.getLeanbackLaunchIntentForPackage(PACKAGE) ?: pm.getLaunchIntentForPackage(PACKAGE)
                launch?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                Result.OLD_VERSION
            } else {
                Result.NOT_INSTALLED
            }
        }
        Log.i("RAKora", "watch $result: $uri")
        return result
    }
}

/** Downloads an app from a GitHub release and opens Android's installer for it. */
object Installer {
    /** Download progress from 0 to 1, or null when nothing is downloading. */
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    suspend fun download(context: Context, url: String, name: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "apks").apply { mkdirs() }
        val file = File(dir, name)
        dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
        val part = File(dir, "$name.part")
        _progress.value = 0f
        try {
            Kora.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body ?: throw IOException("empty answer")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) _progress.value = done.toFloat() / total
                        }
                    }
                }
            }
            if (file.exists()) file.delete()
            if (!part.renameTo(file)) throw IOException("could not save $name")
            file
        } finally {
            _progress.value = null
        }
    }

    /**
     * Opens the installer. False when Android must first allow this app to install apps: that
     * settings page opens instead, and the viewer comes back and presses install again.
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
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}

/** New versions of this app, from its GitHub release (RAKora.apk and version.json). */
object SelfUpdate {
    class Info(val versionCode: Int, val versionName: String, val apkUrl: String)

    private const val RELEASE = "https://github.com/rajavo/RATV/releases/download/kora-latest"
    private const val CHECK_EVERY = 6L * 60L * 60L * 1000L
    private var lastCheck = 0L

    private val _available = MutableStateFlow<Info?>(null)
    val available: StateFlow<Info?> = _available.asStateFlow()

    private fun base() = debugProperty("debug.kora.update") ?: RELEASE

    suspend fun checkIfDue() = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - lastCheck < CHECK_EVERY) return@withContext
        lastCheck = System.currentTimeMillis()
        try {
            val o = JSONObject(Kora.get(base() + "/version.json"))
            val code = o.optInt("versionCode")
            _available.value = if (code > BuildConfig.VERSION_CODE) {
                Info(code, o.optString("versionName").ifBlank { "1.0.$code" }, base() + "/" + o.optString("apk").ifBlank { "RAKora.apk" })
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w("RAKora", "update check: $e")
        }
    }
}
