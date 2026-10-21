package com.ratv.net

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Focus mode: the app on the screen keeps the whole internet connection, the others lose it.
 *
 * Android lets an app route other apps into a local VPN. Here every app goes into it except the
 * app on the screen, the apps the viewer always allows, the system and the app that just left
 * the screen (for a minute, so a quick trip to the settings does not cut a film). What reaches
 * the VPN is dropped: nothing is forwarded and there is no server, so the allowed apps use the
 * line directly at full speed. When another app comes to the screen the VPN is rebuilt.
 */
class FocusVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var generation = 0

    /** Set when focus is turned off, so a rebuild already under way does not bring the VPN back. */
    @Volatile
    private var stopped = false
    private var applied: Set<String>? = null
    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(NotificationChannel(CHANNEL, "التركيز", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.enabled = false
            shutDown()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTE_ID,
            notification(Focus.foreground.value),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        stopped = false
        if (job?.isActive != true) job = scope.launch { watch() }
        return START_STICKY
    }

    private suspend fun watch() {
        val grace = (debugProperty("debug.ranet.grace")?.toLongOrNull() ?: GRACE_SECONDS) * 1000L
        val leaving = HashMap<String, Long>() // apps that left the screen, allowed until then
        var usage: UsageWatcher? = null
        var waiting = 0
        var first = true
        var shown: String? = null
        applied = null
        Focus.message.value = null
        Focus.blocked.value = emptyMap()
        Focus.running.value = true
        while (currentCoroutineContext().isActive) {
            val instant = Focus.accessibilityConnected
            if (!instant) {
                if (Detect.usageGranted(this)) {
                    val w = usage ?: UsageWatcher(this).also { usage = it }
                    w.poll().forEach { Focus.report(it) }
                } else {
                    // Without knowing the app on the screen every app would lose the internet: stop instead.
                    if (++waiting > 25) {
                        stopWith("انطفى التركيز: RA Net ما يقدر يعرف التطبيق اللي على الشاشة. فعّل الإذن من الخطوة الأولى.")
                        return
                    }
                    delay(200)
                    continue
                }
            }
            waiting = 0
            val now = SystemClock.elapsedRealtime()
            val fg = Focus.foreground.value
            if (first) {
                // The app the viewer came from (often the one they want to go back to) keeps its internet a minute.
                val before = Focus.previous
                if (before != null && before != fg) leaving[before] = now + grace
                first = false
            }
            if (fg != shown) {
                val left = shown
                if (left != null) leaving[left] = now + grace
                shown = fg
                updateNotification(fg)
            }
            leaving.entries.removeAll { it.value <= now || it.key == fg }
            val allowed = buildSet {
                add(packageName)
                add(Prefs.SYSTEM)
                addAll(prefs.alwaysAllowed)
                if (fg != null) add(fg)
                addAll(leaving.keys)
            }
            if (allowed != applied && !establish(allowed)) {
                stopWith("انطفى التركيز: الموافقة على الاتصال (VPN) انسحبت. شغّله من جديد ووافق على الطلب.")
                return
            }
            delay(if (instant) 250L else 400L)
        }
    }

    /** Builds the VPN that catches every app except [allowed], then drops the previous one. */
    private fun establish(allowed: Set<String>): Boolean {
        if (stopped) return true
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(TUN_V4, 32)
            .addAddress(TUN_V6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(DNS_V4)
            .setMtu(1500)
            .setBlocking(false)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        for (pkg in allowed) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                // Not installed on this TV.
            }
        }
        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            null
        } ?: return false
        if (stopped) {
            try {
                fd.close()
            } catch (e: Exception) {
                // Already closed.
            }
            return true
        }
        val gen = ++generation
        val old = tun
        tun = fd
        Thread({ drain(fd, gen) }, "ranet-tun").apply { isDaemon = true }.start()
        try {
            old?.close()
        } catch (e: Exception) {
            // Already closed.
        }
        applied = allowed
        Focus.allowed.value = allowed
        return true
    }

    /** Reads and drops what the blocked apps send, counting their attempts to connect. */
    private fun drain(fd: ParcelFileDescriptor, gen: Int) {
        val cm = getSystemService(ConnectivityManager::class.java)
        val seen = HashMap<String, Long>()
        val buf = ByteArray(32767)
        // The stream does not own the descriptor: closing the interface (fd.close()) is what ends it.
        val input = FileInputStream(fd.fileDescriptor)
        try {
            while (generation == gen) {
                val n = input.read(buf)
                if (n <= 0) {
                    Thread.sleep(40)
                    continue
                }
                val flow = Packets.parse(buf, n) ?: continue
                if (!flow.opens) continue
                val now = SystemClock.elapsedRealtime()
                val key = flow.key()
                val at = seen[key]
                if (at != null && now - at < 30_000L) continue // the same attempt, sent again
                if (seen.size > 4096) seen.clear()
                seen[key] = now
                Focus.countBlocked(ownerUid(cm, flow))
            }
        } catch (e: Exception) {
            // The interface was closed: a newer one took over, or focus stopped.
        }
    }

    private fun ownerUid(cm: ConnectivityManager?, f: Packets.Flow): Int {
        if (Build.VERSION.SDK_INT < 29 || cm == null) return -1
        return try {
            cm.getConnectionOwnerUid(
                f.protocol,
                InetSocketAddress(InetAddress.getByAddress(f.src), f.srcPort),
                InetSocketAddress(InetAddress.getByAddress(f.dst), f.dstPort)
            )
        } catch (e: Exception) {
            -1
        }
    }

    private fun stopWith(message: String) {
        Focus.message.value = message
        prefs.enabled = false
        shutDown()
    }

    private fun shutDown() {
        stopped = true
        job?.cancel()
        job = null
        generation++
        try {
            tun?.close()
        } catch (e: Exception) {
            // Already closed.
        }
        tun = null
        applied = null
        Focus.running.value = false
        Focus.allowed.value = emptySet()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // Another VPN app started, or the viewer turned it off in the settings.
        stopWith("انطفى التركيز لأن تطبيق VPN ثاني اشتغل أو انطفى من الإعدادات.")
    }

    override fun onDestroy() {
        scope.cancel()
        generation++
        try {
            tun?.close()
        } catch (e: Exception) {
            // Already closed.
        }
        tun = null
        Focus.running.value = false
        Focus.allowed.value = emptySet()
        super.onDestroy()
    }

    private fun notification(fg: String?) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(if (fg == null) "التركيز شغّال" else "النت الحين لـ " + Apps.label(this, fg))
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .addAction(
            0,
            "إيقاف",
            PendingIntent.getService(
                this, 1, Intent(this, FocusVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    @SuppressLint("MissingPermission")
    private fun updateNotification(fg: String?) {
        try {
            NotificationManagerCompat.from(this).notify(NOTE_ID, notification(fg))
        } catch (e: Exception) {
            // Notifications not allowed: focus works the same.
        }
    }

    companion object {
        const val ACTION_STOP = "com.ratv.net.STOP"
        private const val CHANNEL = "focus"
        private const val NOTE_ID = 1
        private const val GRACE_SECONDS = 60L
        private const val TUN_V4 = "10.215.173.1"
        private const val DNS_V4 = "10.215.173.2"
        private const val TUN_V6 = "fd00:7261:6e65:7400::1"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FocusVpnService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FocusVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
