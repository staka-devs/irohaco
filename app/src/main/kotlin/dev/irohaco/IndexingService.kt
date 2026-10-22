package dev.irohaco

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import dev.irohaco.core.contracts.IndexJobProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Keeps one indexing job running while the app is in the background, within the system's limits
 * (M2-03, ARCHITECTURE §11). Started only from a user's action; a stop by the system pauses the job
 * at its last checkpoint, and the user resumes it.
 */
class IndexingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val jobId = intent?.getStringExtra(EXTRA_JOB_ID)
        // One job at a time: a second start while one runs is ignored.
        if (jobId == null || running?.isActive == true) return START_NOT_STICKY
        val jobs = (application as IrohacoApplication).container.indexJobs
        running = scope.launch {
            val progress = launch {
                jobs.progress(jobId).filterNotNull().conflate().collect {
                    // Without the permission the job still runs; only the progress line is not updated.
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(it))
                    }
                    delay(NOTIFY_INTERVAL_MS) // the system drops updates that come faster
                }
            }
            try {
                jobs.run(jobId)
            } finally {
                progress.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // Android 15+ limits dataSync time per day; the job pauses at its last checkpoint (AC-012).
    override fun onTimeout(startId: Int, fgsType: Int) {
        running?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(progress: IndexJobProgress?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.indexing_channel), NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.indexing_title))
            .setOngoing(true)
            .apply {
                if (progress != null) {
                    val done = progress.committed + progress.failed + progress.skipped
                    setContentText(getString(R.string.indexing_progress, done, progress.total))
                    setProgress(progress.total, done, false)
                }
            }
            .build()
    }

    companion object {
        private const val EXTRA_JOB_ID = "jobId"
        private const val CHANNEL = "indexing"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFY_INTERVAL_MS = 1_000L

        /** Runs [jobId] in the foreground; call it from the user's action, as the system requires. */
        fun start(context: Context, jobId: String) {
            context.startForegroundService(Intent(context, IndexingService::class.java).putExtra(EXTRA_JOB_ID, jobId))
        }
    }
}
