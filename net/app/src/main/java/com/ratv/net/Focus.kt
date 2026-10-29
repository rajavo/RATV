package com.ratv.net

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What focus mode is doing, shared between the service and the screen (same process). */
object Focus {
    private val _foreground = MutableStateFlow<String?>(null)

    /** The app on the screen now (package name), as last seen. */
    val foreground: StateFlow<String?> = _foreground.asStateFlow()

    /** The app that was on the screen before it. */
    @Volatile
    var previous: String? = null
        private set

    val running = MutableStateFlow(false)

    /** Apps that may use the internet right now. */
    val allowed = MutableStateFlow<Set<String>>(emptySet())

    /** Blocked connection attempts per app (Linux uid; -1 when the owner is unknown). */
    val blocked = MutableStateFlow<Map<Int, Int>>(emptyMap())

    /** Something the viewer should know (focus could not start, or stopped by itself). */
    val message = MutableStateFlow<String?>(null)

    @Volatile
    var accessibilityConnected = false

    fun report(pkg: String) {
        val now = _foreground.value
        if (pkg == now) return
        previous = now
        _foreground.value = pkg
    }

    fun countBlocked(uid: Int) {
        blocked.update { it + (uid to (it[uid] ?: 0) + 1) }
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ranet", Context.MODE_PRIVATE)

    /** Focus mode should be on (also after the TV restarts). */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(value) = sp.edit().putBoolean("enabled", value).apply()

    /** Apps that keep the internet even when they are not on the screen. */
    var alwaysAllowed: Set<String>
        get() = sp.getStringSet("always", null)?.toSet() ?: DEFAULT_ALLOWED
        set(value) = sp.edit().putStringSet("always", HashSet(value)).apply()

    /** Factory apps removed from the «خفّف الشاشة» page, so they can be brought back. */
    var removed: Set<String>
        get() = sp.getStringSet("removed", null)?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet("removed", HashSet(value)).apply()

    companion object {
        /**
         * Google Play services (sign-in, casting), the Chromecast receiver and voice search (the Google app),
         * unless the viewer turns them off: they use little and are needed the moment the viewer asks.
         */
        val DEFAULT_ALLOWED = setOf(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.apps.mediashell",
            "com.google.android.katniss",
            "com.google.android.googlequicksearchbox"
        )

        /** The system itself (clock, network checks): never blocked. */
        const val SYSTEM = "android"
    }
}

/** The two ways RA Net can know the app on the screen; either one is enough. */
object Detect {
    fun usageGranted(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun accessibilityOn(context: Context): Boolean {
        if (Focus.accessibilityConnected) return true
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, FocusAccessibilityService::class.java)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun ready(context: Context): Boolean = Focus.accessibilityConnected || usageGranted(context)
}

/** Reads from Android's usage history which apps came to the screen. */
class UsageWatcher(context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)

    // The first look goes back a day, to find the app already on the screen.
    private var since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L

    /** Apps that came to the screen since the last call, oldest first (the last one is on the screen). */
    fun poll(): List<String> {
        val now = System.currentTimeMillis()
        val out = ArrayList<String>()
        val events = try {
            usm?.queryEvents(since, now)
        } catch (e: Exception) {
            null
        } ?: return out
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            val pkg = e.packageName ?: continue
            if (out.lastOrNull() != pkg) out.add(pkg)
        }
        // Look a little back each time: events are sometimes written late.
        since = now - 2_000L
        return if (out.size > 3) out.subList(out.size - 3, out.size).toList() else out
    }
}

/** A system property set from adb (tests only), e.g. debug.ranet.grace. */
fun debugProperty(name: String): String? = try {
    val c = Class.forName("android.os.SystemProperties")
    (c.getMethod("get", String::class.java).invoke(null, name) as? String)?.takeIf { it.isNotBlank() }
} catch (e: Exception) {
    null
}
