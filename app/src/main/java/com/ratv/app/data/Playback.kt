package com.ratv.app.data

import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** Whether a channel or film is playing, so the app's own downloads and work keep out of its way. */
object Playback {
    @Volatile
    private var activeUntil = 0L

    /** Called about once a second while something plays or loads. */
    fun markActive() {
        activeUntil = SystemClock.elapsedRealtime() + 3_000L
    }

    val active: Boolean
        get() = SystemClock.elapsedRealtime() < activeUntil

    /** While something plays, lists and the guide download at most this fast (about 2 Mbps). */
    const val BACKGROUND_BYTES_PER_SECOND = 250_000L

    /**
     * Threads for lists and the guide, at a lower CPU priority so the player's threads come first on
     * a slow TV. Just above Android's "background" level, which would also move them to the slow cores.
     */
    val gentle: CoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(3) { task ->
            Thread({
                try {
                    Process.setThreadPriority(9)
                } catch (e: Throwable) {
                    // Keep the normal priority.
                }
                task.run()
            }, "ratv-load").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
