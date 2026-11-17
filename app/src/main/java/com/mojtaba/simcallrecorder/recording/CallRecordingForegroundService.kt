package com.mojtaba.simcallrecorder.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mojtaba.simcallrecorder.MainActivity
import com.mojtaba.simcallrecorder.recording.shizuku.ShizukuAudioRecorderClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CallRecordingForegroundService : Service() {
    @Inject
    lateinit var recorderClient: ShizukuAudioRecorderClient

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startInProgress = false
    private var watchCaptureStatusJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> stopCapture(startId)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        if (startInProgress) return
        startInProgress = true
        startForegroundCompat(buildNotification("Preparing authorized call-audio capture"))

        serviceScope.launch {
            try {
                val output = recorderClient.startRecording()
                updateNotification("Recording active · ${output.name}")
                watchCaptureStatusJob?.cancel()
                watchCaptureStatusJob = serviceScope.launch {
                    recorderClient.captureStatus.collect { status ->
                        if (status.startsWith("Saved ") || status.startsWith("Recording file was not saved:")) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            } catch (exception: Exception) {
                updateNotification("Capture did not start · ${exception.message ?: "unsupported device"}")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                startInProgress = false
            }
        }
    }

    private fun stopCapture(startId: Int) {
        serviceScope.launch {
            runCatching { recorderClient.stopRecording() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CallRecordingForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("SIM Call Recorder · Recording")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop recording", stop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "Call recording",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Visible notification while the user-initiated call-audio capture is active."
                },
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watchCaptureStatusJob?.cancel()
        // A normal user stop reaches ACTION_STOP first. This best-effort cleanup also covers
        // foreground-service teardown so the shell-side AudioRecord is not left running.
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            runCatching { recorderClient.stopRecording() }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mojtaba.simcallrecorder.action.START_CALL_CAPTURE"
        const val ACTION_STOP = "com.mojtaba.simcallrecorder.action.STOP_CALL_CAPTURE"
        private const val NOTIFICATION_CHANNEL = "call_recording"
        private const val NOTIFICATION_ID = 4_201
    }
}
