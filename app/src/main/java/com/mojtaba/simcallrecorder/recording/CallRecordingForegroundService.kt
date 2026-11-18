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
import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.repository.DeviceDiagnosticsRepository
import com.mojtaba.simcallrecorder.recording.shizuku.ShizukuAudioRecorderClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class CallRecordingForegroundService : Service() {
    @Inject
    lateinit var recordingCoordinator: RecordingCoordinator

    @Inject
    lateinit var recorderClient: ShizukuAudioRecorderClient

    @Inject
    lateinit var diagnosticsRepository: DeviceDiagnosticsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startInProgress = false
    private var activeMode: RecordingMode? = null
    private var watchCaptureStatusJob: Job? = null
    private var watchCallEndJob: Job? = null
    private var latestStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCapture(startId, "Stopped by user")
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (startInProgress || activeMode != null) return
        startInProgress = true
        startForegroundCompat(buildNotification("Preparing authorized call-audio capture"))

        val request = try {
            val mode = RecordingMode.valueOf(
                intent.getStringExtra(EXTRA_RECORDING_MODE) ?: RecordingMode.SHIZUKU_VOICE_CALL.name,
            )
            val state = CallState.valueOf(intent.getStringExtra(EXTRA_CALL_STATE).orEmpty())
            val slot = intent.getIntExtra(EXTRA_SIM_SLOT_INDEX, -1)
            val consent = intent.getBooleanExtra(EXTRA_PARTICIPANT_CONSENT, false)
            RecordingStartRequest(
                mode = mode,
                callState = state,
                simSlotIndex = slot,
                participantConsentConfirmed = consent,
            )
        } catch (exception: Exception) {
            updateNotification("Capture refused · ${exception.message ?: "invalid recording request"}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            startInProgress = false
            return
        }

        serviceScope.launch {
            try {
                val output = recordingCoordinator.start(request)
                activeMode = request.mode
                updateNotification("Recording active · ${output.name} · SIM ${request.simSlotIndex + 1}")

                watchCaptureStatusJob?.cancel()
                watchCaptureStatusJob = serviceScope.launch {
                    recorderClient.captureStatus.collect { status ->
                        if (
                            status.startsWith("Saved ") ||
                            status.startsWith("Recording file was not saved:")
                        ) {
                            stopCapture(latestStartId, "Capture stream finished")
                        }
                    }
                }
                watchCallEnd(request.simSlotIndex)
            } catch (exception: Exception) {
                updateNotification("Capture did not start · ${exception.message ?: "unsupported device"}")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                startInProgress = false
            }
        }
    }

    /**
     * The user-initiated start happens from a visible activity. This service keeps the selected
     * subscription listener alive while recording and finalizes the output when that SIM returns
     * to IDLE; it does not auto-start capture on an incoming call.
     */
    private fun watchCallEnd(simSlotIndex: Int) {
        watchCallEndJob?.cancel()
        watchCallEndJob = serviceScope.launch {
            var sawOffhook = false
            diagnosticsRepository.observeCallStateEvents().collect { event ->
                if (event.simSlotIndex != simSlotIndex) return@collect
                if (event.state == CallState.OFFHOOK) {
                    sawOffhook = true
                } else if (sawOffhook && event.state == CallState.IDLE) {
                    stopCapture(latestStartId, "Call ended")
                    return@launch
                }
            }
        }
    }

    private fun stopCapture(startId: Int, reason: String) {
        val mode = activeMode ?: run {
            if (!startInProgress) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return
        }
        activeMode = null
        watchCallEndJob?.cancel()
        watchCallEndJob = null
        watchCaptureStatusJob?.cancel()
        watchCaptureStatusJob = null

        serviceScope.launch {
            runCatching { recordingCoordinator.stop(mode) }
                .onFailure { updateNotification("Capture stop warning · ${it.message}") }
            updateNotification(reason)
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
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
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
            .setOngoing(activeMode != null || startInProgress)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop recording", stop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "Call recording",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Visible notification while user-authorized call-audio capture is active."
                },
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watchCaptureStatusJob?.cancel()
        watchCallEndJob?.cancel()
        val mode = activeMode
        if (mode != null) {
            runBlocking(Dispatchers.IO) {
                runCatching { recordingCoordinator.stop(mode) }
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mojtaba.simcallrecorder.action.START_CALL_CAPTURE"
        const val ACTION_STOP = "com.mojtaba.simcallrecorder.action.STOP_CALL_CAPTURE"
        const val EXTRA_RECORDING_MODE = "com.mojtaba.simcallrecorder.extra.RECORDING_MODE"
        const val EXTRA_CALL_STATE = "com.mojtaba.simcallrecorder.extra.CALL_STATE"
        const val EXTRA_SIM_SLOT_INDEX = "com.mojtaba.simcallrecorder.extra.SIM_SLOT_INDEX"
        const val EXTRA_PARTICIPANT_CONSENT = "com.mojtaba.simcallrecorder.extra.PARTICIPANT_CONSENT"

        private const val NOTIFICATION_CHANNEL = "call_recording"
        private const val NOTIFICATION_ID = 4_201
    }
}
