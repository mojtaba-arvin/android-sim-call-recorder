package com.mojtaba.simcallrecorder.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
internal class CallRecordingForegroundService : Service() {
    @Inject lateinit var recordingCoordinator: RecordingCoordinator
    @Inject lateinit var captureDeviceProfileProvider: CaptureDeviceProfileProvider
    @Inject lateinit var recorderClient: ShizukuAudioRecorderClient
    @Inject lateinit var diagnosticsRepository: DeviceDiagnosticsRepository
    @Inject lateinit var callRecordRepository: CallRecordRepository
    @Inject lateinit var recorderSettingsRepository: RecorderSettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // WAV finalization must outlive service-scope cancellation during onDestroy().
    private val finalizationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMachine = RecordingServiceStateMachine()
    private val automaticPolicy = AutomaticCallRecordingPolicy()
    private var automaticMonitorJob: Job? = null
    private var permissionMonitorJob: Job? = null
    @Volatile private var automaticArmed = false
    @Volatile private var stopRequestedDuringStart = false
    @Volatile private var crashAfterPromotionForTest = false
    @Volatile private var serviceDestroyed = false
    private var activeMode: RecordingMode? = null
    @Volatile private var selectedFallbackReason: String? = null
    private var activeRecordId: Long? = null
    @Volatile private var pendingAutomaticSimSlotIndex: Int? = null
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
                crashAfterPromotionForTest =
                    applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                        intent.getBooleanExtra(EXTRA_TEST_CRASH_AFTER_PROMOTION, false)
                disarmAutomaticMonitoring()
                when (stateMachine.state) {
                    RecordingServiceStateMachine.State.RECORDING ->
                        stopCapture(startId, "Stopped by user")
                    RecordingServiceStateMachine.State.STARTING -> stopRequestedDuringStart = true
                    RecordingServiceStateMachine.State.STOPPING -> Unit
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
            updateNotification("Automatic ${automaticWaitingLabel()} is already armed")
            return
        }
        if (stateMachine.state != RecordingServiceStateMachine.State.IDLE &&
            stateMachine.state != RecordingServiceStateMachine.State.FAILED
        ) {
            updateNotification("Automatic recording cannot be armed during an active recording session")
            return
        }
        val consent = intent.getBooleanExtra(EXTRA_PARTICIPANT_CONSENT, false)
        val profile = captureDeviceProfileProvider.snapshot()
        val requestedMode = intent.getStringExtra(EXTRA_RECORDING_MODE)
            ?.let { runCatching { RecordingMode.valueOf(it) }.getOrNull() }
        val selection = recordingCoordinator.selectBestWithReason(profile)
        val selectedMode = requestedMode ?: selection?.backend?.mode
        selectedFallbackReason = if (selection?.backend?.mode == selectedMode) {
            selection?.fallbackReason
        } else {
            null
        }
        val notificationsGranted = hasNotificationPermission()
        val strategyAvailable = selectedMode?.let { recordingCoordinator.isAvailable(it, profile) } == true

        if (selectedMode == null || !automaticPolicy.arm(
                enabled = true,
                participantConsentConfirmed = consent,
                selectedMode = selectedMode,
                strategyAvailable = strategyAvailable && notificationsGranted,
                recordAudioGranted = profile.recordAudioGranted,
            )
        ) {
            updateNotification(
                when {
                    !consent -> "Automatic recording not armed · participant authorization and consent are required."
                    !notificationsGranted -> "Automatic recording not armed · notification permission is required."
                    selectedMode == null -> "Automatic recording not armed · no eligible capture strategy is available."
                    else -> "Automatic recording not armed · selected strategy is unavailable or lacks permission."
                },
            )
            stopSelf()
            return
        }

        setAutomaticMonitoringArmed(true)
        val modeLabel = automaticModeLabel(selectedMode)
        try {
            // The app starts the mode-specific foreground service from the visible activity.
            // Shizuku uses its special-use type; acoustic mode uses microphone type.
            startForegroundCompat(
                buildNotification("Automatic $modeLabel armed · waiting for a SIM call"),
                selectedMode,
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
                                .putExtra(EXTRA_RECORDING_MODE, automaticPolicy.selectedMode?.name ?: return@collect)
                                .putExtra(EXTRA_CALL_STATE, CallState.OFFHOOK.name)
                                .putExtra(EXTRA_SIM_SLOT_INDEX, event.simSlotIndex)
                                .putExtra(EXTRA_PARTICIPANT_CONSENT, true)
                                .putExtra(EXTRA_AUTOMATIC_TRIGGER, true)
                            if (stateMachine.state == RecordingServiceStateMachine.State.IDLE ||
                                stateMachine.state == RecordingServiceStateMachine.State.FAILED
                            ) {
                                startCapture(startIntent)
                            } else {
                                // A second SIM can enter OFFHOOK after the first SIM ended but
                                // while its WAV is still finalizing. Queue it rather than losing
                                // the event against the STOPPING state machine.
                                pendingAutomaticSimSlotIndex = event.simSlotIndex
                            }
                        }
                        AutomaticCallRecordingPolicy.Decision.STOP -> {
                            if (pendingAutomaticSimSlotIndex == event.simSlotIndex) {
                                pendingAutomaticSimSlotIndex = null
                            }
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
        permissionMonitorJob?.cancel()
        permissionMonitorJob = serviceScope.launch {
            while (automaticArmed) {
                kotlinx.coroutines.delay(PERMISSION_RECHECK_INTERVAL_MILLIS)
                enforceAutomaticArmPermissions()
            }
        }
    }

    private fun setAutomaticMonitoringArmed(armed: Boolean) {
        automaticArmed = armed
        automaticMonitoringArmed = armed
        _automaticMonitoringState.value = armed
    }

    private fun disarmAutomaticMonitoring() {
        setAutomaticMonitoringArmed(false)
        automaticPolicy.disarm()
        selectedFallbackReason = null
        pendingAutomaticSimSlotIndex = null
        automaticMonitorJob?.cancel()
        automaticMonitorJob = null
        permissionMonitorJob?.cancel()
        permissionMonitorJob = null
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val permissionGranted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val appOpMode = getSystemService(AppOpsManager::class.java).checkOpNoThrow(
            AppOpsManager.OPSTR_POST_NOTIFICATION,
            applicationInfo.uid,
            packageName,
        )
        return permissionGranted && appOpMode != AppOpsManager.MODE_IGNORED &&
            appOpMode != AppOpsManager.MODE_ERRORED
    }

    private fun enforceAutomaticArmPermissions() {
        if (!automaticArmed) return
        val mode = automaticPolicy.selectedMode ?: return
        val notificationsGranted = hasNotificationPermission()
        val callStateGranted = checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        val strategyAvailable = recordingCoordinator.isAvailable(mode, captureDeviceProfile())
        if (notificationsGranted && callStateGranted && strategyAvailable) return

        val reason = when {
            !notificationsGranted -> "notification permission was revoked"
            !callStateGranted -> "call-state permission was revoked"
            else -> "selected capture strategy is no longer authorized"
        }
        disarmAutomaticMonitoring()
        updateNotification("Automatic recording disabled · $reason")
        if (stateMachine.state == RecordingServiceStateMachine.State.RECORDING) {
            stopCapture(latestStartId, "Permission revoked")
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(latestStartId)
        }
    }

    private fun startCapture(intent: Intent) {
        val automaticTrigger = intent.getBooleanExtra(EXTRA_AUTOMATIC_TRIGGER, false)
        if (automaticTrigger && !automaticArmed) return
        if (automaticArmed && !automaticTrigger) {
            rejectStart("Automatic ${automaticWaitingLabel()} is armed; disable it before manual capture.")
            return
        }
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

        try {
            startForegroundCompat(
                buildNotification("Preparing ${automaticModeLabel(request.mode)} capture"),
                request.mode,
            )
        } catch (exception: Exception) {
            if (automaticArmed) disarmAutomaticMonitoring()
            rejectStart("Foreground service start was denied: ${exception.message ?: exception::class.java.simpleName}")
            return
        }

        if (!stateMachine.beginStart(request)) {
            rejectStart("Recording requires an active SIM call and explicit participant-consent confirmation.")
            return
        }

        // A stop request received during a previous failed STARTING attempt must not
        // leak into this new session and immediately stop a successful retry.
        stopRequestedDuringStart = false

        serviceScope.launch {
            var startedMode: RecordingMode? = null
            var insertedRecordId: Long? = null
            try {
                // Recovery must finish before profile selection or any backend side effect.
                val (runtimeProfile, output) = CaptureStartupBarrier.start(
                    prepareForCapture = { callRecordRepository.prepareForCapture() },
                    startBackend = {
                        val profile = captureDeviceProfile()
                        profile to recordingCoordinator.start(request, profile)
                    },
                )
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
                        sampleRate = if (request.mode == RecordingMode.SHIZUKU_VOICE_CALL) 48_000 else 16_000,
                        channelCount = if (request.mode == RecordingMode.SHIZUKU_VOICE_CALL) {
                            recorderClient.currentCaptureChannels()
                        } else {
                            1
                        },
                        dataBytes = 0L,
                        peakSample = 0,
                        rmsSample = 0,
                        isSilent = null,
                        status = STATUS_RECORDING,
                        failureReason = null,
                        captureProfile = listOf(
                            "api=${runtimeProfile.sdkInt}",
                            "device=${runtimeProfile.manufacturer} ${runtimeProfile.model}",
                            "recordAudio=${runtimeProfile.recordAudioGranted}",
                            "shizukuAuthorized=${runtimeProfile.shizukuAuthorized}",
                            "accessibilityEnabled=${runtimeProfile.accessibilityServiceEnabled}",
                        ).joinToString("; "),
                        captureDiagnostics = buildList {
                            add("[${System.currentTimeMillis()}] strategy=${request.mode.name}")
                            add("source=${recordingCoordinator.audioSourceLabel(request.mode)}")
                            selectedFallbackReason?.let { add("fallbackReason=$it") }
                        }.joinToString("; "),
                    ),
                )
                activeRecordId = insertedRecordId
                check(stateMachine.recordingStarted()) { "Recording state transition was rejected." }
                val fallbackLabel = selectedFallbackReason?.let { " · fallback: $it" }.orEmpty()
                updateNotification(
                    "Recording active · ${automaticModeLabel(request.mode)} · ${output.name} · SIM ${request.simSlotIndex + 1}$fallbackLabel",
                )
                if (stopRequestedDuringStart) {
                    stopRequestedDuringStart = false
                    stopCapture(
                        latestStartId,
                        if (automaticArmed) "Call ended" else "Stopped by user",
                    )
                    return@launch
                }

                watchCaptureStatusJob?.cancel()
                watchCaptureStatusJob = when (request.mode) {
                    RecordingMode.SHIZUKU_VOICE_CALL -> serviceScope.launch {
                        recorderClient.captureStatus.collect { status ->
                            activeRecordId?.let { recordId ->
                                callRecordRepository.appendCaptureDiagnostic(recordId, "[${System.currentTimeMillis()}] $status")
                            }
                            if (status.startsWith("Saved ") || status.startsWith("Recording file was not saved:")) {
                                stopCapture(latestStartId, "Capture stream finished")
                            }
                        }
                    }
                    else -> if (request.mode.usesMicrophoneRecordingDiagnostics()) {
                        serviceScope.launch {
                            AudioRecordingDiagnostics.status.collect { status ->
                                activeRecordId?.let { recordId ->
                                    callRecordRepository.appendCaptureDiagnostic(recordId, "[${System.currentTimeMillis()}] $status")
                                }
                            }
                        }
                    } else {
                        null
                    }
                }
                // Instrumentation can exercise Service/Activity lifecycle with a synthetic OFFHOOK
                // request on a non-telephony emulator. This hook is honored only by debuggable
                // builds; release builds always retain the real SIM call-end watcher.
                val disableCallEndWatchForTest =
                    applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                        intent.getBooleanExtra(EXTRA_TEST_DISABLE_CALL_END_WATCH, false)
                if (!disableCallEndWatchForTest) watchCallEnd(request.simSlotIndex)
            } catch (cancelled: CancellationException) {
                // Backend start may have crossed its native/binder side-effect boundary before the
                // coroutine was cancelled. Stop the requested backend even when start() did not
                // return a File yet, and do cleanup outside the cancelled service scope.
                // STARTING has not transferred ownership to the normal stop path. Once the
                // state reaches RECORDING, compete for the same STOPPING transition used by
                // onDestroy()/user stop so exactly one path owns backend finalization.
                val ownsCleanup = stateMachine.beginFailureCleanup()
                if (startedMode == null || ownsCleanup) {
                    val cleanupMode = startedMode ?: request.mode
                    val finalized = withContext(NonCancellable) {
                        runCatching { recordingCoordinator.stop(cleanupMode) }.getOrNull()
                    }
                    if (insertedRecordId != null) {
                        if (finalized != null) {
                            runCatching {
                                withContext(NonCancellable) {
                                    completeRecordOrFail(insertedRecordId, finalized)
                                }
                            }
                        } else {
                            runCatching {
                                withContext(NonCancellable) {
                                    callRecordRepository.markRecordingFailed(
                                        insertedRecordId,
                                        "Capture start was cancelled before setup completed.",
                                    )
                                }
                            }
                        }
                    } else {
                        finalized?.file?.delete()
                    }
                    activeMode = null
                    activeRecordId = null
                    when (stateMachine.state) {
                        RecordingServiceStateMachine.State.STARTING -> stateMachine.startFailed()
                        RecordingServiceStateMachine.State.STOPPING -> stateMachine.stopCompleted()
                        else -> Unit
                    }
                }
                throw cancelled
            } catch (exception: Exception) {
                // A failure after recordingStarted() must claim the same STOPPING transition
                // as user/call-end teardown. If another path already owns STOPPING, leave its
                // backend finalization and Room commit untouched.
                val ownsCleanup = stateMachine.beginFailureCleanup()
                if (ownsCleanup) {
                    if (startedMode != null) {
                        val finalized = runCatching { recordingCoordinator.stop(startedMode) }.getOrNull()
                        if (insertedRecordId != null) {
                            if (finalized != null) {
                                runCatching {
                                    completeRecordOrFail(insertedRecordId, finalized)
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
                    when (stateMachine.state) {
                        RecordingServiceStateMachine.State.STARTING -> stateMachine.startFailed()
                        RecordingServiceStateMachine.State.STOPPING -> stateMachine.stopCompleted()
                        else -> Unit
                    }
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
    }

    private fun automaticModeLabel(mode: RecordingMode): String = when (mode) {
        RecordingMode.SHIZUKU_VOICE_CALL -> "Shizuku privileged VOICE_CALL"
        RecordingMode.SPEAKERPHONE_MIC -> "Speakerphone + MIC acoustic capture"
        else -> mode.name
    }

    private fun captureDeviceProfile(): CaptureDeviceProfile = captureDeviceProfileProvider.snapshot()

    private fun automaticWaitingLabel(): String {
        val mode = automaticPolicy.selectedMode?.let(::automaticModeLabel) ?: "call recording"
        return selectedFallbackReason?.let { "$mode · fallback: $it" } ?: mode
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
                updateNotification("Automatic ${automaticWaitingLabel()} armed · waiting for a SIM call")
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

        finalizationJob = finalizationScope.launch {
            finalizeSession(mode, reason)
            activeMode = null
            activeRecordId = null
            stateMachine.stopCompleted()
            if (!serviceDestroyed) {
                if (automaticArmed && reason == "Call ended") {
                    val nextSimSlot = pendingAutomaticSimSlotIndex
                    if (nextSimSlot != null) {
                        pendingAutomaticSimSlotIndex = null
                        val nextIntent = Intent(
                            this@CallRecordingForegroundService,
                            CallRecordingForegroundService::class.java,
                        )
                            .setAction(ACTION_START)
                            .putExtra(EXTRA_RECORDING_MODE, automaticPolicy.selectedMode?.name)
                            .putExtra(EXTRA_CALL_STATE, CallState.OFFHOOK.name)
                            .putExtra(EXTRA_SIM_SLOT_INDEX, nextSimSlot)
                            .putExtra(EXTRA_PARTICIPANT_CONSENT, true)
                            .putExtra(EXTRA_AUTOMATIC_TRIGGER, true)
                        serviceScope.launch(Dispatchers.Main.immediate) {
                            startCapture(nextIntent)
                        }
                    } else {
                        updateNotification("Automatic ${automaticWaitingLabel()} armed · waiting for a SIM call")
                    }
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
    }

    private suspend fun finalizeSession(mode: RecordingMode, reason: String) {
        val recordId = activeRecordId
        try {
            val finalized = recordingCoordinator.stop(mode)
            if (crashAfterPromotionForTest && finalized != null &&
                applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            ) {
                crashAfterPromotionForTest = false
                android.util.Log.i("CallRecorderProcessDeath", "WINDOW2_KILL_AFTER_WAV_PROMOTION")
                android.os.Process.killProcess(android.os.Process.myPid())
                return
            }
            if (recordId != null) {
                if (finalized == null) {
                    callRecordRepository.markRecordingFailed(recordId, "Capture ended without a valid finalized WAV file.")
                    updateNotification("Recording was not finalized")
                } else {
                    val saved = completeRecordOrFail(recordId, finalized)
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

    private suspend fun completeRecordOrFail(
        recordId: Long,
        finalized: FinalizedAudioCapture,
    ): Boolean {
        val completed = callRecordRepository.completeRecording(recordId, finalized.toCompletion())
        if (!completed) {
            callRecordRepository.markRecordingFailed(
                recordId,
                "Finalized WAV failed integrity validation or the recording was no longer active.",
            )
        }
        return completed
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
        if (serviceDestroyed) return
        _serviceStatus.value = message
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CallRecordingForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopLabel = if (
            automaticArmed && stateMachine.state == RecordingServiceStateMachine.State.IDLE
        ) {
            "Disable monitoring"
        } else {
            "Stop recording"
        }
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
            .addAction(android.R.drawable.ic_media_pause, stopLabel, stop)
            .build()
    }

    private fun createNotificationChannel() {
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceDestroyed = true
        automaticMonitorJob?.cancel()
        automaticPolicy.disarm()
        setAutomaticMonitoringArmed(false)
        _serviceStatus.value = "Service stopped"
        watchCaptureStatusJob?.cancel()
        watchCallEndJob?.cancel()
        val mode = activeMode
        val recordId = activeRecordId
        val cleanupJob = if (mode != null && stateMachine.beginStop()) {
            // Keep cleanup independent from serviceScope: onDestroy cancels monitoring jobs,
            // but an active recording still needs its WAV header and Room row finalized.
            finalizationScope.launch {
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
                activeMode = null
                activeRecordId = null
                stateMachine.stopCompleted()
            }
        } else {
            // A user/call-end stop may already be finalizing when Android destroys the service.
            finalizationJob
        }
        if (cleanupJob == null) {
            finalizationScope.cancel()
        } else {
            cleanupJob.invokeOnCompletion { finalizationScope.cancel() }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mojtaba.simcallrecorder.action.START_CALL_CAPTURE"
        const val ACTION_ARM_AUTOMATIC = "com.mojtaba.simcallrecorder.action.ARM_AUTOMATIC_CAPTURE"
        const val ACTION_STOP = "com.mojtaba.simcallrecorder.action.STOP_CALL_CAPTURE"

        private val _automaticMonitoringState = MutableStateFlow(false)
        val automaticMonitoringState: StateFlow<Boolean> = _automaticMonitoringState.asStateFlow()

        private val _serviceStatus = MutableStateFlow("Idle")
        val serviceStatus: StateFlow<String> = _serviceStatus.asStateFlow()

        @Volatile
        var automaticMonitoringArmed: Boolean = false
            private set
        const val EXTRA_RECORDING_MODE = "com.mojtaba.simcallrecorder.extra.RECORDING_MODE"
        const val EXTRA_CALL_STATE = "com.mojtaba.simcallrecorder.extra.CALL_STATE"
        const val EXTRA_SIM_SLOT_INDEX = "com.mojtaba.simcallrecorder.extra.SIM_SLOT_INDEX"
        const val EXTRA_PARTICIPANT_CONSENT = "com.mojtaba.simcallrecorder.extra.PARTICIPANT_CONSENT"
        const val EXTRA_AUTOMATIC_TRIGGER = "com.mojtaba.simcallrecorder.extra.AUTOMATIC_TRIGGER"
        const val EXTRA_TEST_DISABLE_CALL_END_WATCH = "com.mojtaba.simcallrecorder.extra.TEST_DISABLE_CALL_END_WATCH"
        const val EXTRA_TEST_CRASH_AFTER_PROMOTION = "com.mojtaba.simcallrecorder.extra.TEST_CRASH_AFTER_PROMOTION"

        private const val PERMISSION_RECHECK_INTERVAL_MILLIS = 1_000L
        private const val NOTIFICATION_CHANNEL = "call_recording"
        private const val NOTIFICATION_ID = 4_201
        private const val STATUS_RECORDING = "RECORDING"
    }
}
