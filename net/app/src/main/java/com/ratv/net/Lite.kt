package com.ratv.net

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException

class ExtraApp(val pkg: String, val label: String, val icon: ImageBitmap?, val suggested: Boolean)

/**
 * Making the TV lighter. An app alone may not remove the apps that came with the TV, so RA Net talks to
 * the TV's own debugging service (adb, on this TV only) once the viewer turns it on and allows RA Net.
 * Apps are removed for the viewer only (pm uninstall --user 0): they come back from here, or with a factory reset.
 */
object Lite {
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    /** Bumped after every change, so the lists on the screen are read again. */
    val changes = MutableStateFlow(0)

    private const val HOST = "127.0.0.1"
    private const val PORT = 5555
    private const val MATCH_UNINSTALLED = 0x2000 // PackageManager.MATCH_UNINSTALLED_PACKAGES

    /** Removing them is safe and most viewers never open them; they start ticked. */
    private val SUGGESTED = setOf(
        "com.google.android.videos",
        "com.google.android.youtube.tvmusic",
        "com.google.android.youtube.tvkids",
        "com.google.android.play.games",
        "com.google.android.feedback",
        "com.google.android.marvin.talkback",
        "com.android.printspooler",
        "com.tcl.waterfall.overseas",
        "tv.pluto.android",
        "com.tubitv",
        "com.plexapp.android",
        "com.hulu.livingroomplus",
        "com.wbd.stream",
        "com.peacocktv.peacockandroid",
        "com.apple.atve.androidtv.appletv"
    )

    /** The TV needs these (home screen, settings, store, sources and inputs, remote, updates): never listed. */
    private val KEEP = setOf(
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.katniss",
        "com.google.android.googlequicksearchbox",
        "com.google.android.tv",
        "com.google.android.apps.mediashell"
    )
    private val KEEP_WORDS = listOf(
        "settings", "launcher", "setup", "input", "livetv", "home", "keyboard", "remote", "update", "ota",
        "bluetooth", "installer", "permission", "systemui", "media", "player", "source", "provision"
    )

    fun keep(pkg: String): Boolean {
        if (pkg in KEEP || pkg == "com.tcl.tv" || pkg.startsWith("com.tcl.tv.")) return true
        val p = pkg.lowercase()
        return KEEP_WORDS.any { it in p }
    }

    fun supported() = Build.VERSION.SDK_INT >= 26

    /** Factory apps still on the TV: the ones with an icon, plus the hidden ones known to be extra. */
    fun extras(context: Context): List<ExtraApp> {
        val pm = context.packageManager
        val pkgs = LinkedHashSet<String>()
        for (category in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                .forEach { pkgs.add(it.activityInfo.packageName) }
        }
        pkgs.addAll(SUGGESTED)
        val home = homeApps(context)
        return pkgs.mapNotNull { pkg ->
            if (pkg == context.packageName || pkg in home || keep(pkg)) return@mapNotNull null
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            if ((info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) return@mapNotNull null
            ExtraApp(pkg, Apps.label(context, pkg), Apps.icon(context, pkg), pkg in SUGGESTED)
        }.sortedWith(compareBy({ !it.suggested }, { it.label.lowercase() }))
    }

    /** Apps removed from here that can come back. */
    fun removed(context: Context, prefs: Prefs): List<ExtraApp> {
        val pm = context.packageManager
        return prefs.removed.mapNotNull { pkg ->
            if (runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess) return@mapNotNull null // back already
            @Suppress("WrongConstant")
            val info = runCatching { pm.getApplicationInfo(pkg, MATCH_UNINSTALLED) }.getOrNull() ?: return@mapNotNull null
            val icon = runCatching { info.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            ExtraApp(pkg, info.loadLabel(pm).toString(), icon, false)
        }.sortedBy { it.label.lowercase() }
    }

    private fun homeApps(context: Context): Set<String> {
        @Suppress("DEPRECATION")
        return context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
    }

    // ------------------------------------------------------------- RA Home as the home screen

    const val RA_HOME = "com.ratv.home"
    const val RA_HOME_LINK = "github.com/rajavo/RATV/releases/download/home-latest/RAHome.apk"

    fun homeInstalled(context: Context) = runCatching { context.packageManager.getApplicationInfo(RA_HOME, 0) }.isSuccess

    /** The app the remote's home button opens now. */
    fun currentHome(context: Context): String? {
        @Suppress("DEPRECATION")
        return context.packageManager
            .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    /**
     * Makes RA Home the home screen: it is set as the preferred one, and the TV's own home screens are
     * turned off (Google TV does not let the viewer choose another). The settings' fallback home stays.
     */
    fun makeHome(context: Context, prefs: Prefs) = onTv(context, null) { tv ->
        if (!homeInstalled(context)) {
            message.value = "ثبّت RA Home أول، وبعدين ارجع هنا."
            return@onTv
        }
        tv.shell("cmd package set-home-activity $RA_HOME/.MainActivity")
        val off = ArrayList<String>()
        for (pkg in homeApps(context) - RA_HOME) {
            if ("settings" in pkg.lowercase() || pkg == "android") continue
            val out = tv.shell("pm disable-user --user 0 $pkg").allOutput
            if (out.contains("disabled", true)) off.add(pkg)
        }
        prefs.homeDisabled = prefs.homeDisabled + off
        message.value = if (currentHome(context) == RA_HOME || off.isNotEmpty()) {
            "صارت RA Home الواجهة الأساسية. اضغط زر البيت في الريموت وبتطلع لك."
        } else {
            "ما قدرت أغيّر الواجهة في هذي الشاشة."
        }
    }

    /** Turns the TV's own home screens back on. */
    fun restoreHome(context: Context, prefs: Prefs) = onTv(context, "رجعت واجهة الشاشة الأصلية. اضغط زر البيت في الريموت.") { tv ->
        for (pkg in prefs.homeDisabled) tv.shell("pm enable $pkg")
        prefs.homeDisabled = emptySet()
    }

    fun animationsFast(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) < 1f

    /** Quicker animations, background apps closed, caches emptied and the always-on network scans stopped. */
    fun lighten(context: Context) = onTv(
        context,
        "خفّت الشاشة: الحركة أسرع، والتطبيقات اللي بالخلفية انقفلت، والملفات المؤقتة انمسحت، والبحث الدائم عن الشبكات والبلوتوث وقف."
    ) { tv ->
        scale(tv, "0.5")
        tv.shell("am kill-all")
        tv.shell("pm trim-caches 999G")
        // Scans the TV keeps doing in the background even with Wi-Fi or Bluetooth not in use.
        tv.shell("settings put global wifi_scan_always_enabled 0")
        tv.shell("settings put global ble_scan_always_enabled 0")
    }

    // ------------------------------------------------------------- the old ads blocker

    /** Older RA Net could send the TV's name lookups to AdGuard DNS; only turning that off is left. */
    const val AD_DNS = "dns.adguard-dns.com"

    fun adsSupported() = Build.VERSION.SDK_INT >= 28

    fun adsBlocked(context: Context): Boolean {
        if (!adsSupported()) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching { cm.getLinkProperties(cm.activeNetwork)?.privateDnsServerName == AD_DNS }.getOrDefault(false)
    }

    fun allowAds(context: Context) = onTv(context, "انطفى حاجب الإعلانات، ورجع النت مثل ما كان.") { tv -> allowAdsOn(tv) }

    private fun allowAdsOn(tv: Dadb) {
        tv.shell("settings put global private_dns_mode opportunistic")
        tv.shell("settings delete global private_dns_specifier")
    }

    // ------------------------------------------------------------- eye comfort

    /** Turns on RA Net's accessibility service (which draws «راحة العين»), keeping the ones already on. */
    fun turnOnComfort(context: Context, level: Int) = onTv(
        context,
        "اشتغلت راحة العين: ألوان الشاشة صارت أدفى وأهدى. غيّر القوة أو طفّها من هنا."
    ) { tv ->
        val me = ComponentName(context, FocusAccessibilityService::class.java).flattenToString()
        val now = tv.shell("settings get secure enabled_accessibility_services").output.trim()
        Comfort.set(context, level)
        tv.shell("settings put secure enabled_accessibility_services '" + withService(now, me) + "'")
        tv.shell("settings put secure accessibility_enabled 1")
    }

    /** The list of accessibility services with [me] in it once. */
    fun withService(list: String, me: String): String {
        val items = list.split(':').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }
        return (if (me in items) items else items + me).joinToString(":")
    }

    fun openAccessibility(context: Context) {
        for (action in listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS)) {
            if (runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    fun normalAnimations(context: Context) = onTv(context, "رجعت الحركة لسرعتها الأصلية.") { tv -> scale(tv, "1.0") }

    private fun scale(tv: Dadb, value: String) {
        for (key in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) {
            tv.shell("settings put global $key $value")
        }
    }

    fun remove(context: Context, prefs: Prefs, pkgs: List<String>) = onTv(context, null) { tv ->
        var done = 0
        val failed = ArrayList<String>()
        for (pkg in pkgs) {
            val out = tv.shell("pm uninstall -k --user 0 $pkg").allOutput
            if (out.contains("Success")) {
                done++
                prefs.removed = prefs.removed + pkg
            } else {
                failed.add(Apps.label(context, pkg))
            }
        }
        message.value = when {
            failed.isEmpty() -> "انحذف $done تطبيق. تقدر ترجع أي واحد منها من تحت."
            done == 0 -> "ما قدرت أحذف: " + failed.joinToString("، ")
            else -> "انحذف $done تطبيق، وما قدرت أحذف: " + failed.joinToString("، ")
        }
    }

    fun restore(context: Context, prefs: Prefs, pkg: String, label: String) = onTv(context, null) { tv ->
        val out = tv.shell("cmd package install-existing $pkg").allOutput
        message.value = if (out.contains("installed for user", true) || out.contains("Success", true)) {
            prefs.removed = prefs.removed - pkg
            "رجع «$label»."
        } else {
            "ما قدرت أرجّع «$label»: " + out.trim().take(120)
        }
    }

    // ------------------------------------------------------------- packing the apps

    /**
     * Packs every app the viewer can open, one after the other: each is compiled ahead of time from how it is
     * used (quicker to open, less work for the TV), then the caches of all apps are emptied.
     */
    fun packAll(context: Context) = onTv(context, null) { tv ->
        val before = freeBytes()
        val pkgs = Apps.forSettings(context).map { it.pkg }
        pkgs.forEachIndexed { i, pkg ->
            message.value = "يضغط ${i + 1} من ${pkgs.size}: ${Apps.label(context, pkg)}"
            tv.shell("cmd package compile -m speed-profile $pkg")
        }
        message.value = "يمسح الملفات المؤقتة…"
        tv.shell("pm trim-caches 999G")
        val freed = (freeBytes() - before).coerceAtLeast(0) / (1024 * 1024)
        message.value = "خلص: انضغط ${pkgs.size} تطبيق، وفضّى $freed ميجا من المساحة."
    }

    /** Empties the caches of all apps, without a word on the screen (the automatic clean). True when it ran. */
    fun cleanQuietly(context: Context): Boolean {
        if (!supported()) return false
        return try {
            Dadb.create(HOST, PORT, keys(context), 3_000, 60_000).use { tv -> tv.shell("pm trim-caches 999G") }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun freeBytes(): Long = android.os.Environment.getDataDirectory().usableSpace

    /** Runs [block] on this TV's debugging service, and puts what happened in [message]. */
    private fun onTv(context: Context, done: String?, block: (Dadb) -> Unit) {
        if (!supported()) {
            message.value = "هذي الخاصية تحتاج أندرويد ٨ أو أحدث."
            return
        }
        busy.value = true
        message.value = "لو طلعت رسالة «السماح بتصحيح الأخطاء»، اختر «السماح دائماً» ثم موافق."
        try {
            Dadb.create(HOST, PORT, keys(context), 3_000, 180_000).use { tv ->
                block(tv)
            }
            if (done != null) message.value = done
        } catch (e: Exception) {
            message.value = explain(e)
        } finally {
            busy.value = false
            changes.value++
        }
    }

    private fun explain(e: Throwable): String {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is ConnectException) {
                return "تصحيح الأخطاء طافي في هذا التلفزيون. فعّل «تصحيح USB» من «خيارات المطوّرين» (الخطوة فوق) وجرّب مرة ثانية."
            }
            if (cause is SocketTimeoutException) {
                return "ما وصلت الموافقة. لما تطلع رسالة «السماح بتصحيح الأخطاء» اختر «السماح دائماً» ثم موافق، وجرّب مرة ثانية."
            }
            cause = cause.cause
        }
        return "صار خطأ: " + (e.message ?: e.javaClass.simpleName) +
            ". تأكد إن «تصحيح USB» شغّال، وإنك وافقت على رسالة التلفزيون."
    }

    private fun keys(context: Context): AdbKeyPair {
        val dir = File(context.filesDir, "adb")
        val privateKey = File(dir, "adbkey")
        val publicKey = File(dir, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists()) {
            dir.mkdirs()
            AdbKeyPair.generate(privateKey, publicKey)
        }
        return AdbKeyPair.read(privateKey, publicKey)
    }

    fun openDeveloperOptions(context: Context): Boolean {
        val pm = context.packageManager
        for (action in listOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS, Settings.ACTION_SETTINGS)) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            @Suppress("DEPRECATION")
            if (pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
