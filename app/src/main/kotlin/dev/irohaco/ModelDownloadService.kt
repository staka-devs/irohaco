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
import dev.irohaco.core.contracts.ModelInstallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a model download going while the app is in the background (M2-02). The settings screen pauses
 * or cancels it through ManageModels, which stops the download here; the service then stops itself.
 */
class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val id = intent?.getStringExtra(EXTRA_MODEL_ID)
        // One download at a time: a second start while one runs is ignored.
        if (id == null || running?.isActive == true) return START_NOT_STICKY
        val models = (application as IrohacoApplication).container.manageModels
        running = scope.launch {
            val progress = launch {
                models.states.map { it[id] }.conflate().collect { state ->
                    if (state is ModelInstallState.Downloading &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    ) {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                    }
                    delay(NOTIFY_INTERVAL_MS) // the system drops updates that come faster
                }
            }
            try {
                models.download(id)
            } finally {
                progress.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // Android 15+ limits dataSync time per day; the download keeps its part and shows as paused.
    override fun onTimeout(startId: Int, fgsType: Int) {
        running?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: ModelInstallState.Downloading?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.model_download_channel), NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.model_download_title))
            .setOngoing(true)
            .apply {
                if (state != null && state.totalBytes > 0) {
                    setProgress(PERCENT, (PERCENT * state.downloadedBytes / state.totalBytes).toInt(), false)
                }
            }
            .build()
    }

    companion object {
        private const val EXTRA_MODEL_ID = "modelId"
        private const val CHANNEL = "model_download"
        private const val NOTIFICATION_ID = 2
        private const val NOTIFY_INTERVAL_MS = 1_000L
        private const val PERCENT = 100

        /** Downloads [modelId] in the foreground; call it from the user's action, as the system requires. */
        fun start(context: Context, modelId: String) {
            context.startForegroundService(Intent(context, ModelDownloadService::class.java).putExtra(EXTRA_MODEL_ID, modelId))
        }
    }
}
