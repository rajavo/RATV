package com.ratv.net.probe

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/** Test helper: while it runs, calls the test server every second and logs "ok" or "fail". */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "RANet Probe"
            textSize = 40f
            gravity = Gravity.CENTER
        })
        val service = Intent(this, ProbeService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
    }
}

class ProbeService : Service() {
    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("probe", "probe", NotificationManager.IMPORTANCE_LOW))
            startForeground(
                1,
                Notification.Builder(this, "probe")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("probe")
                    .build()
            )
        }
        if (!running) {
            running = true
            Thread(::loop, "probe").start()
        }
        return START_STICKY
    }

    private fun loop() {
        // A new connection every time, so each call shows what the app may do now.
        System.setProperty("http.keepAlive", "false")
        var n = 0
        while (running) {
            n++
            val result = try {
                val c = URL("http://10.0.2.2:8000/probe.txt?n=$n").openConnection() as HttpURLConnection
                c.connectTimeout = 1500
                c.readTimeout = 1500
                c.setRequestProperty("Connection", "close")
                val code = c.responseCode
                c.inputStream.use { it.readBytes() }
                c.disconnect()
                "ok $code"
            } catch (e: Exception) {
                "fail " + e.javaClass.simpleName
            }
            Log.i("RANetProbe", "$n $result")
            Thread.sleep(1000)
        }
    }
}
