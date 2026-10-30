package com.ratv.home

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

class Tile(val pkg: String, val label: String, val banner: ImageBitmap?, val icon: ImageBitmap?)

/** The apps on the TV, as tiles: their wide TV banner when they have one, else their icon. */
object Apps {
    /** The RA apps come first, then the rest by name. */
    private val FIRST = listOf("com.ratv.app", "com.ratv.kora", "com.ratv.net")

    fun load(context: Context): List<Tile> {
        val pm = context.packageManager
        val seen = LinkedHashMap<String, Tile>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            @Suppress("DEPRECATION")
            val found = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            for (ri in found) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || pkg in seen) continue
                val banner = runCatching {
                    (ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm))
                        ?.toBitmap(320, 180)?.asImageBitmap()
                }.getOrNull()
                val icon = if (banner != null) null else runCatching { ri.loadIcon(pm).toBitmap(128, 128).asImageBitmap() }.getOrNull()
                seen[pkg] = Tile(pkg, ri.loadLabel(pm).toString(), banner, icon)
            }
        }
        return seen.values.sortedWith(compareBy({ FIRST.indexOf(it.pkg).let { i -> if (i < 0) FIRST.size else i } }, { it.label.lowercase() }))
    }

    fun open(context: Context, pkg: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
