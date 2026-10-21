package com.ratv.net

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?)

/** Names and icons of the installed apps. */
object Apps {
    private val labels = ConcurrentHashMap<String, String>()
    private val icons = ConcurrentHashMap<String, ImageBitmap>()

    fun label(context: Context, pkg: String): String = labels.getOrPut(pkg) {
        val pm = context.packageManager
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    fun icon(context: Context, pkg: String): ImageBitmap? = icons[pkg] ?: try {
        context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap().also { icons[pkg] = it }
    } catch (e: Exception) {
        null
    }

    fun packageForUid(context: Context, uid: Int): String? =
        if (uid < 0) null else context.packageManager.getPackagesForUid(uid)?.firstOrNull()

    fun labelForUid(context: Context, uid: Int): String {
        if (uid < 0) return "تطبيق غير معروف"
        val pkg = packageForUid(context, uid) ?: return context.packageManager.getNameForUid(uid) ?: "uid $uid"
        return label(context, pkg)
    }

    /** The apps the viewer can open (phone and TV launchers), plus the helpers allowed by default. */
    fun forSettings(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val pkgs = LinkedHashSet<String>()
        for (category in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                .forEach { pkgs.add(it.activityInfo.packageName) }
        }
        for (pkg in Prefs.DEFAULT_ALLOWED) {
            if (runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess) pkgs.add(pkg)
        }
        pkgs.remove(context.packageName)
        return pkgs.map { AppInfo(it, label(context, it), icon(context, it)) }.sortedBy { it.label.lowercase() }
    }
}
