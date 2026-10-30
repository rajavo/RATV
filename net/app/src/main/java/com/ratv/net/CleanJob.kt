package com.ratv.net

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** The automatic clean: every six hours, while it is on, the caches of all apps are emptied. */
class CleanJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            Lite.cleanQuietly(this)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 7301
        private const val EVERY_MS = 6 * 3600_000L

        fun schedule(context: Context, on: Boolean) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (!on) {
                js.cancel(ID)
                return
            }
            js.schedule(
                JobInfo.Builder(ID, ComponentName(context, CleanJob::class.java))
                    .setPeriodic(EVERY_MS)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}
