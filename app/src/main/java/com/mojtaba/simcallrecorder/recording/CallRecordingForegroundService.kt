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
import com.mojtaba.simcallrecorder.domain.model.CallRecord
import com.mojtaba.simcallrecorder.domain.model.CallRecordCompletion
import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent
import com.mojtaba.simcallrecorder.domain.repository.CallRecordRepository
import com.mojtaba.simcallrecorder.domain.repository.DeviceDiagnosticsRepository
import com.mojtaba.simcallrecorder.domain.repository.RecorderSettingsRepository
import com.mojtaba.simcallrecorder.recording.shizuku.ShizukuAudioRecorderClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CallRecordingForegroundService : Service() {
    @Inject lateinit var recordingCoordinator: RecordingCoordinator
    @Inject lateinit var recorderClient: ShizukuAudioRecorderClient
    @Inject lateinit var diagnosticsRepository: DeviceDiagnosticsRepository
    @Inject lateinit var callRecordRepository: CallRecordRepository
    @Inject lateinit var recorderSettingsRepository: RecorderSettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMachine = RecordingServiceStateMachine()
    private val automaticPolicy = AutomaticCallRecordingPolicy()
    private var automaticMonitorJob: Job? = null
    private var automaticArmed = false
    private var stopRequestedDuringStart = false
    private var activeMode: RecordingMode? = null
    private var activeRecordId: Long? = null
    private var watchCaptureStatusJob: Job? = null
    private var watchCallEndJob: Job? = null
    private var latestStartId: Int = 0
    private var finalizationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_ARM_AUTOMATIC -> armAutomaticMonitoring(intent)
            ACTION_STOP -> {
                disarmAutomaticMonitoring()
                when (stateMachine.state) {
                    RecordingServiceStateMachine.State.RECORDING ->
                        stopCapture(startId, "Stopped by user")
                    RecordingServiceStateMachine.State.STARTING -> stopRequestedDuringStart = true
                    else -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun armAutomaticMonitoring(intent: Intent) {
        if (automaticArmed) {
            updateNotification("Automatic acoustic recording is already armed")
            return
        }
        if (stateMachine.state != RecordingServiceStateMachine.State.IDLE &&
            stateMachine.state != RecordingServiceStateMachine.State.FAILED
        ) {
            updateNotification("Automatic recording cannot be armed during an active recording session")
            return
        }
        val consent = intent.getBooleanExtra(EXTRA_PARTICIPANT_CONSENT, false)
        val microphoneGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!automaticPolicy.arm(
                enabled = true,
                participantConsentConfirmed = consent,
                selectedMode = RecordingMode.SPEAKERPHONE_MIC,
                recordAudioGranted = microphoneGranted && notificationsGranted,
            )
        ) {
            updateNotification(
                "Automatic recording not armed · explicit consent, microphone and notification permissions are required.",
            )
            stopSelf()
            return
        }

        automaticArmed = true
        automaticMonitoringArmed = true
        try {
            // The app starts this microphone FGS from a visible activity. It remains visible and
            // user-stoppable while waiting for OFFHOOK; no background FGS start is attempted.
            startForegroundCompat(
                buildNotification("Automatic acoustic recording armed · waiting for a SIM call"),
                RecordingMode.SPEAKERPHONE_MIC,
            )
        } catch (exception: Exception) {
            disarmAutomaticMonitoring()
            updateNotification("Automatic recording could not start · ${exception.message}")
            stopSelf()
            return
        }

        automaticMonitorJob?.cancel()
        automaticMonitorJob = serviceScope.launch {
            try {
                diagnosticsRepository.observeCallStateEvents().collect { event ->
                    when (automaticPolicy.onCallState(event)) {
                        AutomaticCallRecordingPolicy.Decision.START -> {
                            val startIntent = Intent(this@CallRecordingForegroundService, CallRecordingForegroundService::class.java)
                                .setAction(ACTION_START)
                                .putExtra(EXTRA_RECORDING_MODE, RecordingMode.SPEAKERPHONE_MIC.name)
                                .putExtra(EXTRA_CALL_STATE, CallState.OFFHOOK.name)
                                .putExtra(EXTRA_SIM_SLOT_INDEX, event.simSlotIndex)
                                .putExtra(EXTRA_PARTICIPANT_CONSENT, true)
                            startCapture(startIntent)
                        }
                        AutomaticCallRecordingPolicy.Decision.STOP -> {
                            when (stateMachine.state) {
                                RecordingServiceStateMachine.State.RECORDING ->
                                    stopCapture(latestStartId, "Call ended")
                                RecordingServiceStateMachine.State.STARTING ->
                                    stopRequestedDuringStart = true
                                else -> Unit
                            }
                        }
                        AutomaticCallRecordingPolicy.Decision.NONE -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                disarmAutomaticMonitoring()
                updateNotification("Automatic call monitoring failed · ${exception.message}")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun disarmAutomaticMonitoring() {
        automaticArmed = false
        automaticMonitoringArmed = false
        automaticPolicy.disarm()
        automaticMonitorJob?.cancel()
        automaticMonitorJob = null
    }

    private fun startCapture(intent: Intent) {
        if (stateMachine.state != RecordingServiceStateMachine.State.IDLE &&
            stateMachine.state != RecordingServiceStateMachine.State.FAILED
        ) return

        val request = try {
            val mode = RecordingMode.valueOf(
                intent.getStringExtra(EXTRA_RECORDING_MODE) ?: RecordingMode.SHIZUKU_VOICE_CALL.name,
            )
            val callState = CallState.valueOf(intent.getStringExtra(EXTRA_CALL_STATE).orEmpty())
            RecordingStartRequest(
                mode = mode,
                callState = callState,
                simSlotIndex = intent.getIntExtra(EXTRA_SIM_SLOT_INDEX, -1),
                participantConsentConfirmed = intent.getBooleanExtra(EXTRA_PARTICIPANT_CONSENT, false),
            )
        } catch (exception: Exception) {
            rejectStart("Invalid recording request: ${exception.message}")
            return
        }

        startForegroundCompat(
            buildNotification("Preparing ${request.mode.name} capture"),
            request.mode,
        )

        if (!stateMachine.beginStart(request)) {
            rejectStart("Recording requires an active SIM call and explicit participant-consent confirmation.")
            return
        }

        serviceScope.launch {
            var startedMode: RecordingMode? = null
            var insertedRecordId: Long? = null
            try {
                val output = recordingCoordinator.start(request, captureDeviceProfile())
                startedMode = request.mode
                activeMode = request.mode

                val startedAt = if (request.mode == RecordingMode.SHIZUKU_VOICE_CALL) {
                    recorderClient.currentCaptureStartedAtEpochMillis() ?: System.currentTimeMillis()
                } else {
                    System.currentTimeMillis()
                }
                insertedRecordId = callRecordRepository.createActiveRecording(
                    CallRecord(
                        startedAtEpochMillis = startedAt,
                        durationMillis = null,
                        phoneNumber = null,
                        simSlotIndex = request.simSlotIndex,
                        filePath = output.absolutePath,
                        recordingMode = request.mode.name,
                        audioSource = recordingCoordinator.audioSourceLabel(request.mode),
                        sampleRate = 48_000,
                        channelCount = 0,
                        dataBytes = 0L,
                        peakSample = 0,
                        rmsSample = 0,
                        isSilent = null,
                        status = STATUS_RECORDING,
                        failureReason = null,
                    ),
                )
                activeRecordId = insertedRecordId
                check(stateMachine.recordingStarted()) { "Recording state transition was rejected." }
                updateNotification("Recording active · ${output.name} · SIM ${request.simSlotIndex + 1}")
                if (stopRequestedDuringStart) {
                    stopRequestedDuringStart = false
                    stopCapture(latestStartId, "Stopped by user")
                    return@launch
                }

                watchCaptureStatusJob?.cancel()
                watchCaptureStatusJob = if (request.mode == RecordingMode.SHIZUKU_VOICE_CALL) {
                    serviceScope.launch {
                        recorderClient.captureStatus.collect { status ->
                            if (status.startsWith("Saved ") || status.startsWith("Recording file was not saved:")) {
                                stopCapture(latestStartId, "Capture stream finished")
                            }
                        }
                    }
                } else {
                    null
                }
                watchCallEnd(request.simSlotIndex)
            } catch (exception: Exception) {
                if (startedMode != null) {
                    val finalized = runCatching { recordingCoordinator.stop(startedMode) }.getOrNull()
                    if (insertedRecordId != null) {
                        if (finalized != null) {
                            runCatching {
                                callRecordRepository.completeRecording(insertedRecordId, finalized.toCompletion())
                            }
                        } else {
                            runCatching {
                                callRecordRepository.markRecordingFailed(insertedRecordId, exception.message ?: "Capture setup failed.")
                            }
                        }
                    } else {
                        finalized?.file?.delete()
                    }
                }
                activeMode = null
                activeRecordId = null
                stateMachine.startFailed()
                updateNotification(
                    if (automaticArmed) {
                        "Automatic capture failed · ${exception.message ?: "unsupported device"} · waiting for next call"
                    } else {
                        "Capture did not start · ${exception.message ?: "unsupported device"}"
                    },
                )
                if (!automaticArmed) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun captureDeviceProfile(): CaptureDeviceProfile {
        val helperUid = recorderClient.authorizedHostUid
        return CaptureDeviceProfile(
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            recordAudioGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
            shizukuAuthorized = helperUid == 2_000 || helperUid == 0,
            accessibilityServiceEnabled = CallAudioAccessibilityService.isEnabled(this),
            notificationListenerEnabled = false,
            voipCallNotificationPresent = false,
        )
    }

    private fun watchCallEnd(simSlotIndex: Int) {
        watchCallEndJob?.cancel()
        watchCallEndJob = serviceScope.launch {
            diagnosticsRepository.observeCallStateEvents().collect { event ->
                if (event.simSlotIndex == simSlotIndex && stateMachine.shouldStopFor(event)) {
                    stopCapture(latestStartId, "Call ended")
                }
            }
        }
    }

    private fun stopCapture(startId: Int, reason: String) {
        if (!stateMachine.beginStop()) return
        val mode = activeMode ?: run {
            stateMachine.stopCompleted()
            if (automaticArmed && reason == "Call ended") {
                updateNotification("Automatic acoustic recording armed · waiting for a SIM call")
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return
        }

        watchCallEndJob?.cancel()
        watchCallEndJob = null
        watchCaptureStatusJob?.cancel()
        watchCaptureStatusJob = null
        updateNotification("Finalizing recording…")

        finalizationJob = serviceScope.launch {
            finalizeSession(mode, reason)
            activeMode = null
            activeRecordId = null
            stateMachine.stopCompleted()
            if (automaticArmed && reason == "Call ended") {
                updateNotification("Automatic acoustic recording armed · waiting for a SIM call")
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
    }

    private suspend fun finalizeSession(mode: RecordingMode, reason: String) {
        val recordId = activeRecordId
        try {
            val finalized = recordingCoordinator.stop(mode)
            if (recordId != null) {
                if (finalized == null) {
                    callRecordRepository.markRecordingFailed(recordId, "Capture ended without a valid finalized WAV file.")
                    updateNotification("Recording was not finalized")
                } else {
                    val saved = callRecordRepository.completeRecording(recordId, finalized.toCompletion())
                    if (saved) {
                        val limitMb = recorderSettingsRepository.settings.first().storageLimitMb
                        val removed = callRecordRepository.enforceStorageLimit(limitMb.toLong() * 1024L * 1024L)
                        val signal = if (finalized.isSilent) " · digital silence" else ""
                        val retention = if (removed > 0) " · removed $removed older file(s)" else ""
                        updateNotification("Saved ${finalized.file.name}$signal$retention")
                    } else {
                        updateNotification("Audio finalized, but metadata update failed")
                    }
                }
            }
        } catch (exception: Exception) {
            if (recordId != null) {
                runCatching {
                    callRecordRepository.markRecordingFailed(recordId, exception.message ?: "Could not finalize recording.")
                }
            }
            updateNotification("Finalization failed · ${exception.message ?: "unknown error"}")
        }
        if (reason == "Call ended" || reason == "Stopped by user") updateNotification(reason)
    }

    private fun FinalizedAudioCapture.toCompletion() = CallRecordCompletion(
        durationMillis = durationMillis,
        dataBytes = dataBytes,
        peakSample = peakSample,
        rmsSample = rmsSample,
        isSilent = isSilent,
    )

    private fun rejectStart(message: String) {
        updateNotification("Capture refused · $message")
        if (!automaticArmed) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundCompat(notification: Notification, mode: RecordingMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val serviceType = if (mode == RecordingMode.SHIZUKU_VOICE_CALL) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
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
            .setContentTitle(
                if (automaticArmed && stateMachine.state == RecordingServiceStateMachine.State.IDLE) {
                    "SIM Call Recorder · Automatic monitoring"
                } else {
                    "SIM Call Recorder · Recording"
                },
            )
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(
                automaticArmed ||
                    stateMachine.state == RecordingServiceStateMachine.State.STARTING ||
                    stateMachine.state == RecordingServiceStateMachine.State.RECORDING ||
                    stateMachine.state == RecordingServiceStateMachine.State.STOPPING,
            )
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
        automaticMonitorJob?.cancel()
        automaticPolicy.disarm()
        automaticMonitoringArmed = false
        watchCaptureStatusJob?.cancel()
        watchCallEndJob?.cancel()
        val mode = activeMode
        val recordId = activeRecordId
        if (mode != null && stateMachine.beginStop()) {
            // onDestroy must not block the main thread. If the OS kills the process before this
            // detached IO cleanup finishes, launch recovery will retain the record as INTERRUPTED.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { finalizeSession(mode, "Service destroyed") }
                    .onFailure { error ->
                        if (recordId != null) {
                            runCatching {
                                callRecordRepository.markRecordingFailed(
                                    recordId,
                                    error.message ?: "Service destroyed before finalization.",
                                )
                            }
                        }
                    }
            }
            activeMode = null
            activeRecordId = null
            stateMachine.stopCompleted()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mojtaba.simcallrecorder.action.START_CALL_CAPTURE"
        const val ACTION_ARM_AUTOMATIC = "com.mojtaba.simcallrecorder.action.ARM_AUTOMATIC_CAPTURE"
        const val ACTION_STOP = "com.mojtaba.simcallrecorder.action.STOP_CALL_CAPTURE"

        @Volatile
        var automaticMonitoringArmed: Boolean = false
            private set
        const val EXTRA_RECORDING_MODE = "com.mojtaba.simcallrecorder.extra.RECORDING_MODE"
        const val EXTRA_CALL_STATE = "com.mojtaba.simcallrecorder.extra.CALL_STATE"
        const val EXTRA_SIM_SLOT_INDEX = "com.mojtaba.simcallrecorder.extra.SIM_SLOT_INDEX"
        const val EXTRA_PARTICIPANT_CONSENT = "com.mojtaba.simcallrecorder.extra.PARTICIPANT_CONSENT"

        private const val NOTIFICATION_CHANNEL = "call_recording"
        private const val NOTIFICATION_ID = 4_201
        private const val STATUS_RECORDING = "RECORDING"
    }
}
