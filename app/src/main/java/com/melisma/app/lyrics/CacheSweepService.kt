package com.melisma.app.lyrics

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Deletes cached lyrics that have outlived their time, once a day, with or without the app open.
 *
 * A limit that is only checked when a track is looked up is no limit for a track nobody plays
 * again. Spicy Lyrics' terms cap a cached copy at thirty days so that a contributor withdrawing a
 * sync actually removes it, and that has to hold for a phone whose owner has moved on to other
 * songs, or stopped opening the app. Hence a job Android runs for us rather than a check on launch.
 */
class CacheSweepService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        running = scope.launch {
            LyricsCache(applicationContext).sweep()
            jobFinished(params, false)
        }
        return true
    }

    /** Cut short by Android: run again at the next opportunity rather than waiting a day. */
    override fun onStopJob(params: JobParameters?): Boolean {
        running?.cancel()
        return true
    }

    companion object {
        internal const val JOB_ID = 2801

        /** The process the manifest runs this in, as the suffix Android gives its name. */
        const val PROCESS = ":sweep"

        private val INTERVAL_MS = TimeUnit.DAYS.toMillis(1)

        /**
         * Ask Android to run the sweep daily, from now on and across restarts.
         *
         * Left alone when it is already scheduled: scheduling again would restart the day's wait,
         * so an app opened every morning would put the sweep off forever.
         */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            runCatching {
                scheduler.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(context, CacheSweepService::class.java))
                        .setPeriodic(INTERVAL_MS)
                        .setPersisted(true)
                        .build(),
                )
            }
        }
    }
}
