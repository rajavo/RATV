package com.ratv.net

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.view.accessibility.AccessibilityEvent

/**
 * Tells RA Net at once which app opened a screen. It listens only to "a new screen opened"
 * events and never reads what is on the screen.
 */
class FocusAccessibilityService : AccessibilityService() {
    private val screens = HashMap<String, Boolean>()

    override fun onServiceConnected() {
        Focus.accessibilityConnected = true
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
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Focus.accessibilityConnected = false
        super.onDestroy()
    }
}

/** Turns focus mode back on after the TV restarts or RA Net is updated, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs(context).enabled) return
        if (VpnService.prepare(context) != null) return // the VPN request must be accepted again on the screen
        try {
            FocusVpnService.start(context)
        } catch (e: Exception) {
            // Started from the screen next time.
        }
    }
}
