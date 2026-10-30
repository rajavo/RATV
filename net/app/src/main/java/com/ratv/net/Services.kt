package com.ratv.net

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.VpnService
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Tells RA Net at once which app opened a screen. It listens only to "a new screen opened"
 * events and never reads what is on the screen. It also draws «راحة العين» over every app.
 */
class FocusAccessibilityService : AccessibilityService() {
    private val screens = HashMap<String, Boolean>()
    private var scope: CoroutineScope? = null
    private var overlay: View? = null

    override fun onServiceConnected() {
        Focus.accessibilityConnected = true
        Comfort.load(this)
        scope?.cancel()
        scope = MainScope().also { s -> s.launch { Comfort.level.collect { showComfort(it) } } }
    }

    /** A see-through warm layer over the whole screen; it takes no presses, the remote goes to the app below. */
    private fun showComfort(level: Int) {
        val wm = getSystemService(WindowManager::class.java) ?: return
        val color = Comfort.color(level)
        if (color == 0) {
            removeComfort()
            return
        }
        val view = overlay ?: View(this).also { v ->
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            try {
                wm.addView(v, params)
            } catch (e: Exception) {
                return
            }
            overlay = v
        }
        view.setBackgroundColor(color)
    }

    private fun removeComfort() {
        val v = overlay ?: return
        overlay = null
        try {
            getSystemService(WindowManager::class.java)?.removeView(v)
        } catch (e: Exception) {
            // Already gone with the service.
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // Whole screens only: a keyboard, a toast or a dialog over an app does not change who has the internet.
        val screen = screens.getOrPut("$pkg/$cls") {
            try {
                packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
                true
            } catch (e: Exception) {
                false
            }
        }
        if (screen) Focus.report(pkg)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        Focus.accessibilityConnected = false
        scope?.cancel()
        scope = null
        removeComfort()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Focus.accessibilityConnected = false
        scope?.cancel()
        scope = null
        removeComfort()
        super.onDestroy()
    }
}

/** Turns focus mode back on after the TV restarts or RA Net is updated, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs(context).autoClean) CleanJob.schedule(context, true)
        if (!Prefs(context).enabled) return
        if (VpnService.prepare(context) != null) return // the VPN request must be accepted again on the screen
        try {
            FocusVpnService.start(context)
        } catch (e: Exception) {
            // Started from the screen next time.
        }
    }
}
