package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.engine.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Downloads Ivy's models in a foreground service so the ~1.5 GB download keeps going when Ivy is closed
 * or the screen is off. Progress is shown in a notification and in [state] (the setup wizard reads it).
 * A dropped connection is retried a few times; the download itself resumes where it stopped.
 */
class ModelDownloadService : Service() {
    data class State(val fraction: Float = 0f, val running: Boolean = false, val done: Boolean = false, val error: String = "")

    companion object {
        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state
        private const val CHANNEL = "model_download"
        private const val NOTIFY_ID = 7
        private const val TRIES = 12

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, ModelDownloadService::class.java))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model download", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIFY_ID, notification(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive == true) return START_NOT_STICKY
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ivy:model-download").apply { acquire(6 * 60 * 60 * 1000L) }
        _state.value = State(running = true)
        job = scope.launch {
            var lastNotify = 0L
            var ok = false
            for (attempt in 1..TRIES) {
                try {
                    ModelStore.install(this@ModelDownloadService) { f ->
                        _state.update { it.copy(fraction = f) }
                        val now = System.currentTimeMillis()
                        if (now - lastNotify > 1000) {
                            lastNotify = now
                            nm.notify(NOTIFY_ID, notification((f * 100).toInt()))
                        }
                    }
                    ok = true
                    break
                } catch (e: Exception) {
                    delay(3000L * attempt)
                }
            }
            _state.update {
                if (ok) it.copy(fraction = 1f, running = false, done = true, error = "")
                else it.copy(running = false, error = "No connection. Check the internet and tap Retry.")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15 caps data-sync services at 6 h; the download resumes on the next Retry. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        _state.update { it.copy(running = false, error = "Download paused. Tap Retry.") }
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification(percent: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Ivy is downloading its speech model")
            .setContentText("$percent%. You can leave the app; it keeps going.")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
}
