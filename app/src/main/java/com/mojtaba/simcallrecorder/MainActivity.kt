package com.mojtaba.simcallrecorder

import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.mojtaba.simcallrecorder.domain.model.AudioRoute
import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent
import com.mojtaba.simcallrecorder.domain.model.SimSlot
import com.mojtaba.simcallrecorder.domain.model.CallRecord
import com.mojtaba.simcallrecorder.domain.model.CallRecordFilter
import com.mojtaba.simcallrecorder.domain.model.RecorderSettings
import com.mojtaba.simcallrecorder.sdk.SdkRecordingMode
import com.mojtaba.simcallrecorder.sdk.CaptureStrategySnapshot
import com.mojtaba.simcallrecorder.sdk.SdkPermissionPurpose
import com.mojtaba.simcallrecorder.sdk.SimCallRecorderSdk
import com.mojtaba.simcallrecorder.ui.RecorderRootScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var recorderSdk: SimCallRecorderSdk

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private var monitorJob: Job? = null
    private var pendingMicrophoneStart: Boolean = false
    private var pendingAutomaticArm: Boolean = false
    private var pendingAutomaticNotification: Boolean = false
    private var pendingAutomaticMode: SdkRecordingMode? = null

    private lateinit var secureRecordingStateStore: SecureRecordingStateStore
    private var missingPermissions by mutableStateOf(emptyList<String>())
    private var activeSims by mutableStateOf(emptyList<SimSlot>())
    private var callEvents by mutableStateOf(emptyList<CallStateEvent>())
    private var audioRoutes by mutableStateOf(emptyList<AudioRoute>())
    private var monitoring by mutableStateOf(false)
    private var diagnosticsError by mutableStateOf<String?>(null)
    private var recorderConnectionStatus by mutableStateOf("Checking Shizuku status…")
    private var recorderServiceUid by mutableStateOf<Int?>(null)
    private var recorderCaptureStatus by mutableStateOf("Idle")
    private var speakerphoneConsentConfirmed by mutableStateOf(false)
    private var shizukuConsentConfirmed by mutableStateOf(false)
    private var automaticRecordingArmed by mutableStateOf(false)
    // Hilt injects recorderSdk after Activity construction; never dereference it in a property initializer.
    private var audioInputDiagnostics by mutableStateOf("No microphone capture active")
    private var callRecords by mutableStateOf(emptyList<CallRecord>())
    private var selectedRecord by mutableStateOf<CallRecord?>(null)
    private var recorderSettings by mutableStateOf(RecorderSettings())
    private var captureStrategySnapshot by mutableStateOf(CaptureStrategySnapshot())
    private var recordsJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioInputDiagnostics = recorderSdk.audioInputDiagnostics.value
        secureRecordingStateStore = SecureRecordingStateStore(this)
        val persistedRecordingState = secureRecordingStateStore.read()
        speakerphoneConsentConfirmed = persistedRecordingState.speakerphoneConsentConfirmed
        shizukuConsentConfirmed = persistedRecordingState.shizukuConsentConfirmed
        val serviceAlreadyArmed = recorderSdk.automaticRecordingArmed.value
        if (persistedRecordingState.armRequested && !serviceAlreadyArmed) {
            recorderCaptureStatus = "Previous automatic monitoring was interrupted. Review consent and arm again."
        }
        // Never restore monitoring automatically after process death or force-stop.
        secureRecordingStateStore.setArmRequested(serviceAlreadyArmed)

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            automaticRecordingArmed = recorderSdk.automaticRecordingArmed.value
            refreshPermissionState()
            refreshDiagnostics()
            recorderSdk.refreshShizukuConnection()
            if (pendingMicrophoneStart) {
                pendingMicrophoneStart = false
                if (grants[android.Manifest.permission.RECORD_AUDIO] == true) {
                    requestStartRecording()
                } else {
                    recorderCaptureStatus = "Microphone permission was denied."
                }
            }
            if (pendingAutomaticArm) {
                pendingAutomaticArm = false
                val callStateGranted = recorderSdk.hasCallStatePermission()
                val microphoneWasRequested = grants.containsKey(android.Manifest.permission.RECORD_AUDIO)
                val microphoneDenied = microphoneWasRequested &&
                    grants[android.Manifest.permission.RECORD_AUDIO] != true
                if (!callStateGranted) {
                    recorderCaptureStatus = "Automatic recording requires call-state permission."
                } else if (microphoneDenied) {
                    if (recorderSdk.isRecordingModeAvailable(SdkRecordingMode.SHIZUKU_VOICE_CALL)) {
                        requestArmAutomaticRecording()
                    } else {
                        recorderCaptureStatus = "Microphone permission was denied and no ready Shizuku capture path is available."
                    }
                } else {
                    requestArmAutomaticRecording()
                }
            }
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                if (pendingAutomaticNotification) {
                    pendingAutomaticNotification = false
                    val mode = pendingAutomaticMode
                    pendingAutomaticMode = null
                    if (mode != null) launchAutomaticMonitoringService(mode)
                    else recorderCaptureStatus = "Automatic recording mode could not be resolved."
                } else {
                    launchRecordingForegroundService()
                }
            } else {
                pendingAutomaticNotification = false
                pendingAutomaticMode = null
                recorderCaptureStatus = "Notification permission is required before recording."
            }
        }

        automaticRecordingArmed = recorderSdk.automaticRecordingArmed.value
        refreshPermissionState()
        refreshDiagnostics()
        recorderSdk.refreshShizukuConnection()
        lifecycleScope.launch {
            recorderSdk.shizukuConnectionStatus.collect { status ->
                recorderConnectionStatus = status
                recorderServiceUid = recorderSdk.shizukuHostUid
            }
        }
        lifecycleScope.launch {
            recorderSdk.recordingStatus.collect { recorderCaptureStatus = it }
        }
        lifecycleScope.launch {
            recorderSdk.audioInputDiagnostics.collect { audioInputDiagnostics = it }
        }
        lifecycleScope.launch {
            recorderSdk.automaticRecordingArmed.collect {
                automaticRecordingArmed = it
                secureRecordingStateStore.setArmRequested(it)
            }
        }

        lifecycleScope.launch {
            recorderSdk.settings.collect { recorderSettings = it }
        }
        refreshRecords(CallRecordFilter())

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RecorderRootScreen(
                        records = callRecords,
                        settings = recorderSettings,
                        selectedRecord = selectedRecord,
                        captureStrategySnapshot = captureStrategySnapshot,
                        onFilterChanged = ::refreshRecords,
                        onOpenRecord = { selectedRecord = it },
                        onDeleteRecord = { record ->
                            lifecycleScope.launch {
                                recorderSdk.deleteRecording(record.id)
                                selectedRecord = null
                                refreshRecords(CallRecordFilter())
                            }
                        },
                        onStorageLimitChanged = { limit ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                recorderSdk.updateStorageLimit(limit)
                            }
                        },
                        onRecordingModeChanged = { mode ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                recorderSdk.updateRecordingMode(mode.name)
                            }
                        },
                        onRequestMicrophonePermission = {
                            permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                        },
                        diagnosticsContent = {
                            DiagnosticsScreen(
                                missingPermissions = missingPermissions,
                                activeSims = activeSims,
                                callEvents = callEvents,
                                audioRoutes = audioRoutes,
                                monitoring = monitoring,
                                diagnosticsError = diagnosticsError,
                                recorderConnectionStatus = recorderConnectionStatus,
                                recorderCaptureStatus = recorderCaptureStatus,
                                audioInputDiagnostics = audioInputDiagnostics,
                                recorderConnected = recorderServiceUid == 2_000 || recorderServiceUid == 0,
                                recorderActive = recorderCaptureStatus.startsWith("Recording active"),
                                activeCallSlotIndex = activeCallSlotIndex(),
                                speakerphoneConsentConfirmed = speakerphoneConsentConfirmed,
                                shizukuConsentConfirmed = shizukuConsentConfirmed,
                                manualRecordingConsentConfirmed = hasConsentFor(recorderSettings.recordingMode),
                                automaticRecordingArmed = automaticRecordingArmed,
                                onSpeakerphoneConsentChanged = { confirmed ->
                                    speakerphoneConsentConfirmed = confirmed
                                    secureRecordingStateStore.setConsent(
                                        com.mojtaba.simcallrecorder.domain.model.RecordingMode.SPEAKERPHONE_MIC,
                                        confirmed,
                                    )
                                    if (!confirmed && consentProtectedCaptureActive()) stopForConsentWithdrawal()
                                },
                                onShizukuConsentChanged = { confirmed ->
                                    shizukuConsentConfirmed = confirmed
                                    secureRecordingStateStore.setConsent(
                                        com.mojtaba.simcallrecorder.domain.model.RecordingMode.SHIZUKU_VOICE_CALL,
                                        confirmed,
                                    )
                                    if (!confirmed && consentProtectedCaptureActive()) stopForConsentWithdrawal()
                                },
                                onArmAutomaticRecording = ::requestArmAutomaticRecording,
                                onDisarmAutomaticRecording = ::disarmAutomaticRecording,
                                onRequestPermissions = {
                                    permissionLauncher.launch(
                                        recorderSdk.permissionsFor(SdkPermissionPurpose.CALL_STATE)
                                            .toTypedArray(),
                                    )
                                },
                                onRefresh = ::refreshDiagnostics,
                                onStartMonitoring = ::startMonitoring,
                                onStopMonitoring = ::stopMonitoring,
                                onConnectRecorder = recorderSdk::connectShizuku,
                                onStartRecording = ::requestStartRecording,
                                onStopRecording = ::stopRecording,
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::permissionLauncher.isInitialized) {
            refreshPermissionState()
            refreshDiagnostics()
            recorderSdk.refreshShizukuConnection()
        }
    }

    override fun onDestroy() {
        stopMonitoring()
        recordsJob?.cancel()
        super.onDestroy()
    }

    private fun refreshRecords(filter: CallRecordFilter) {
        recordsJob?.cancel()
        recordsJob = lifecycleScope.launch {
            recorderSdk.observeRecordings(filter).collect { callRecords = it }
        }
    }

    private fun refreshPermissionState() {
        missingPermissions = recorderSdk.missingPermissions(SdkPermissionPurpose.CALL_STATE)
        captureStrategySnapshot = recorderSdk.captureStrategySnapshot()
    }

    private fun refreshDiagnostics() {
        audioRoutes = recorderSdk.audioRoutes()
        captureStrategySnapshot = recorderSdk.captureStrategySnapshot()
        if (!recorderSdk.hasCallStatePermission()) {
            activeSims = emptyList()
            return
        }

        lifecycleScope.launch {
            try {
                activeSims = recorderSdk.activeSimSlots()
                diagnosticsError = null
            } catch (exception: Exception) {
                diagnosticsError = exception.message ?: "Could not inspect SIM subscriptions."
            }
        }
    }

    private fun startMonitoring() {
        if (!recorderSdk.hasCallStatePermission()) {
            permissionLauncher.launch(
                recorderSdk.permissionsFor(SdkPermissionPurpose.CALL_STATE).toTypedArray(),
            )
            return
        }

        monitorJob?.cancel()
        callEvents = emptyList()
        diagnosticsError = null
        monitorJob = lifecycleScope.launch {
            monitoring = true
            try {
                activeSims = recorderSdk.activeSimSlots()
                recorderSdk.observeCallStateEvents().collect { event ->
                    callEvents = (callEvents + event).takeLast(MAX_VISIBLE_EVENTS)
                }
            } catch (exception: Exception) {
                diagnosticsError = exception.message ?: "Call-state monitoring failed."
            } finally {
                monitoring = false
            }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        monitoring = false
    }

    private fun requestArmAutomaticRecording() {
        if (!recorderSdk.hasCallStatePermission()) {
            pendingAutomaticArm = true
            permissionLauncher.launch(
                recorderSdk.permissionsFor(SdkPermissionPurpose.CALL_STATE).toTypedArray(),
            )
            return
        }

        val selectedMode = recorderSdk.selectBestRecordingMode()
        if (selectedMode == null) {
            if (!recorderSdk.hasMicrophonePermission()) {
                pendingAutomaticArm = true
                permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
            } else {
                recorderCaptureStatus = "No eligible automatic capture strategy is available on this device/build."
            }
            return
        }
        if (!hasConsentFor(selectedMode)) {
            recorderCaptureStatus = "Confirm the separate consent for ${automaticModeLabel(selectedMode)} before arming."
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAutomaticMode = selectedMode
            pendingAutomaticNotification = true
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchAutomaticMonitoringService(selectedMode)
    }

    private fun launchAutomaticMonitoringService(mode: SdkRecordingMode) {
        val intent = recorderSdk.createArmAutomaticRecordingIntent(mode, hasConsentFor(mode))
        try {
            secureRecordingStateStore.setArmRequested(true)
            ContextCompat.startForegroundService(this, intent)
            automaticRecordingArmed = true
            recorderCaptureStatus = "Automatic ${automaticModeLabel(mode)} is being armed."
        } catch (exception: Exception) {
            secureRecordingStateStore.setArmRequested(false)
            automaticRecordingArmed = false
            recorderCaptureStatus = "Automatic recording could not start: ${exception.message}"
        }
    }

    private fun hasConsentFor(mode: SdkRecordingMode): Boolean = when (mode) {
        SdkRecordingMode.SPEAKERPHONE_MIC -> speakerphoneConsentConfirmed
        SdkRecordingMode.SHIZUKU_VOICE_CALL -> shizukuConsentConfirmed
        else -> false
    }

    private fun consentProtectedCaptureActive(): Boolean =
        automaticRecordingArmed ||
            recorderCaptureStatus.startsWith("Recording") ||
            recorderCaptureStatus.startsWith("Starting") ||
            recorderCaptureStatus.startsWith("Finalizing") ||
            recorderCaptureStatus.startsWith("Preparing")

    private fun stopForConsentWithdrawal() {
        startService(recorderSdk.createStopRecordingIntent())
        automaticRecordingArmed = false
        secureRecordingStateStore.setArmRequested(false)
        recorderCaptureStatus = "Recording stopped because the applicable consent acknowledgement was withdrawn."
    }

    private fun automaticModeLabel(mode: SdkRecordingMode): String = when (mode) {
        SdkRecordingMode.SHIZUKU_VOICE_CALL -> "Shizuku VOICE_CALL"
        SdkRecordingMode.SPEAKERPHONE_MIC -> "Speakerphone + MIC acoustic capture"
        else -> mode.name
    }

    private fun disarmAutomaticRecording() {
        startService(
            recorderSdk.createStopRecordingIntent(),
        )
        automaticRecordingArmed = false
        secureRecordingStateStore.setArmRequested(false)
        recorderCaptureStatus = "Automatic recording disabled."
    }

    private fun requestStartRecording() {
        if (!hasConsentFor(recorderSettings.recordingMode)) {
            recorderCaptureStatus = "Confirm the separate consent for ${recorderSettings.recordingMode.name} before recording."
            return
        }
        val microphoneStrategies = setOf(
            SdkRecordingMode.SPEAKERPHONE_MIC,
            SdkRecordingMode.OEM_VOICE_RECOGNITION,
            SdkRecordingMode.ACCESSIBILITY_VOICE_RECOGNITION,
        )
        if (recorderSettings.recordingMode in microphoneStrategies &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingMicrophoneStart = true
            permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
            return
        }
        if (activeCallSlotIndex() == null) {
            recorderCaptureStatus = "No active OFFHOOK SIM call. Start call-state monitoring and try again during a call."
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchRecordingForegroundService()
    }

    private fun launchRecordingForegroundService() {
        val slotIndex = activeCallSlotIndex() ?: run {
            recorderCaptureStatus = "No active SIM call was detected."
            return
        }
        val intent = recorderSdk.createStartRecordingIntent(
            mode = recorderSettings.recordingMode,
            simSlotIndex = slotIndex,
            callState = CallState.OFFHOOK,
            participantConsentConfirmed = hasConsentFor(recorderSettings.recordingMode),
        )
        ContextCompat.startForegroundService(this, intent)
    }

    private fun activeCallSlotIndex(): Int? = callEvents
        .groupBy { it.simSlotIndex }
        .values
        .mapNotNull { events -> events.maxByOrNull { it.observedAtEpochMillis } }
        .filter { it.state == CallState.OFFHOOK && it.simSlotIndex >= 0 }
        .maxByOrNull { it.observedAtEpochMillis }
        ?.simSlotIndex

    private fun stopRecording() {
        startService(
            recorderSdk.createStopRecordingIntent(),
        )
    }

    private companion object {
        const val MAX_VISIBLE_EVENTS = 40
    }
}

@Composable
private fun DiagnosticsScreen(
    missingPermissions: List<String>,
    activeSims: List<SimSlot>,
    callEvents: List<CallStateEvent>,
    audioRoutes: List<AudioRoute>,
    monitoring: Boolean,
    diagnosticsError: String?,
    recorderConnectionStatus: String,
    recorderCaptureStatus: String,
    audioInputDiagnostics: String,
    recorderConnected: Boolean,
    recorderActive: Boolean,
    activeCallSlotIndex: Int?,
    speakerphoneConsentConfirmed: Boolean,
    shizukuConsentConfirmed: Boolean,
    manualRecordingConsentConfirmed: Boolean,
    automaticRecordingArmed: Boolean,
    onSpeakerphoneConsentChanged: (Boolean) -> Unit,
    onShizukuConsentChanged: (Boolean) -> Unit,
    onArmAutomaticRecording: () -> Unit,
    onDisarmAutomaticRecording: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onConnectRecorder: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "SIM Call Recorder",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = if (recorderActive) "Device diagnostics · call-audio capture active" else "Device diagnostics · no call-audio capture",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = {
                val report = buildPrivacySafeDiagnosticsReport(
                    generatedAtEpochMillis = System.currentTimeMillis(),
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    androidRelease = Build.VERSION.RELEASE,
                    apiLevel = Build.VERSION.SDK_INT,
                    buildDisplay = Build.DISPLAY,
                    securityPatch = Build.VERSION.SECURITY_PATCH,
                    missingPermissions = missingPermissions,
                    activeSimSlots = activeSims,
                    callEvents = callEvents,
                    audioRoutes = audioRoutes,
                    monitoring = monitoring,
                    recorderConnected = recorderConnected,
                    recorderActive = recorderActive,
                    automaticRecordingArmed = automaticRecordingArmed,
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share privacy-safe diagnostics"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export privacy-safe diagnostics")
        }

        DiagnosticCard(title = "Device profile") {
            DiagnosticLine("Manufacturer", Build.MANUFACTURER)
            DiagnosticLine("Model", Build.MODEL)
            DiagnosticLine(
                "Android",
                Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
            )
            DiagnosticLine("Build", Build.DISPLAY)
            DiagnosticLine(
                "Security patch",
                Build.VERSION.SECURITY_PATCH.ifBlank { "Unavailable" },
            )
        }

        DiagnosticCard(title = "SIM subscriptions") {
            if (missingPermissions.isNotEmpty()) {
                Text("READ_PHONE_STATE is required to inspect active SIM slots.")
                Button(onClick = onRequestPermissions) {
                    Text("Grant call-state permission")
                }
            } else if (activeSims.isEmpty()) {
                Text("No active SIM subscriptions are currently visible.")
            } else {
                activeSims.forEach { sim ->
                    val state = callEvents.lastOrNull {
                        it.simSlotIndex == sim.slotIndex
                    }?.state
                    DiagnosticLine(sim.label, state?.name ?: "WAITING FOR CALL EVENT")
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (monitoring) {
                OutlinedButton(onClick = onStopMonitoring) {
                    Text("Stop monitoring")
                }
            } else {
                Button(onClick = onStartMonitoring) {
                    Text("Start call-state monitoring")
                }
            }
            Text(
                "Diagnostics monitoring runs while this screen is open. During recording, the foreground service " +
                    "keeps a SIM-scoped listener and stops the capture when the selected SIM returns to IDLE.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        DiagnosticCard(title = "Recent call-state events") {
            if (callEvents.isEmpty()) {
                Text("No call-state transitions captured in this session.")
            } else {
                callEvents.asReversed().forEach { event ->
                    val label = if (event.simSlotIndex >= 0) {
                        "SIM " + (event.simSlotIndex + 1)
                    } else {
                        "Unknown SIM slot"
                    }
                    DiagnosticLine(
                        label,
                        event.state.name + " · " + formatTime(event.observedAtEpochMillis),
                    )
                }
            }
        }

        DiagnosticCard(title = "Available audio devices") {
            if (audioRoutes.isEmpty()) {
                Text("No audio input/output devices were reported.")
            } else {
                audioRoutes.forEach { route ->
                    val selected = if (route.selectedForCommunication) {
                        " · communication route"
                    } else {
                        ""
                    }
                    DiagnosticLine(
                        route.direction + " · " + route.deviceType,
                        route.displayName + selected,
                    )
                }
            }
            Text(
                "This is an inventory of audio endpoints, not proof that a device exposes " +
                    "cellular call audio. The selected communication route is reported separately.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        DiagnosticCard(title = "Automatic best-available SIM-call recording") {
            Text(
                "Arm once from this visible screen before a call. The app selects Shizuku VOICE_CALL when its live " +
                    "UserService, shell UID and audio attribution are ready; otherwise it selects Speakerphone + MIC " +
                    "if microphone permission is granted. The selected mode stays fixed until you disarm. A Shizuku " +
                    "failure never downgrades to microphone capture. Re-arm after reboot or force-stop.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Shizuku VOICE_CALL is privileged digital capture but still device-dependent. Speakerphone + MIC is " +
                    "acoustic capture and may miss the remote participant. Neither is marked two-way verified without device evidence.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Microphone input diagnostics: $audioInputDiagnostics", style = MaterialTheme.typography.bodySmall)
            Text(
                "clientSilenced=false only means Android did not report framework silencing for this capture session; " +
                    "it does not prove that the remote participant is present in the recording.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (automaticRecordingArmed) {
                OutlinedButton(onClick = onDisarmAutomaticRecording) {
                    Text("Disable automatic recording")
                }
            } else {
                Button(
                    onClick = onArmAutomaticRecording,
                    enabled = speakerphoneConsentConfirmed || shizukuConsentConfirmed,
                ) {
                    Text("Arm best available automatic recording")
                }
            }
        }

        DiagnosticCard(title = "Call-audio recording modes") {
            Text("Standard Android mode")
            Text(
                "Legacy Standard app-UID digital capture is unavailable for cellular-call audio and fails closed. " +
                    "The separate, explicitly armed Speakerphone + MIC mode is acoustic capture; it is not a guaranteed " +
                    "digital downlink and may be one-sided on some devices.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Advanced mode · Shizuku")
            Text("Connection: $recorderConnectionStatus")
            Text("Capture: $recorderCaptureStatus")
            DiagnosticLine("Call state", activeCallSlotIndex?.let { "OFFHOOK · SIM ${it + 1}" } ?: "No active call detected")
            androidx.compose.foundation.layout.Row {
                Checkbox(
                    checked = speakerphoneConsentConfirmed,
                    onCheckedChange = onSpeakerphoneConsentChanged,
                )
                Text(
                    "I am authorized to use Speakerphone + MIC acoustic recording and have obtained all required participant consent.",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            androidx.compose.foundation.layout.Row {
                Checkbox(
                    checked = shizukuConsentConfirmed,
                    onCheckedChange = onShizukuConsentChanged,
                )
                Text(
                    "I am authorized to use privileged Shizuku call-audio capture and have obtained all required participant consent.",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onConnectRecorder) {
                Text("Connect / authorize Shizuku")
            }
            if (recorderActive) {
                OutlinedButton(onClick = onStopRecording) {
                    Text("Stop recording")
                }
            } else {
                Button(
                    onClick = onStartRecording,
                    enabled = recorderConnected &&
                        activeCallSlotIndex != null &&
                        manualRecordingConsentConfirmed &&
                        !automaticRecordingArmed &&
                        !recorderCaptureStatus.startsWith("Preparing"),
                ) {
                    Text("Start visible call recording")
                }
            }
            Text(
                "This path requires Android 8.0+ and a running, explicitly authorized Shizuku/Sui server. " +
                    "Starting Shizuku through on-device Wireless Debugging requires Android 11+; Android 8–10 " +
                    "need root or computer-based ADB setup. It opens AudioSource.VOICE_CALL only inside the shell/root UserService. " +
                    "It does not switch on speakerphone or fall back to the microphone.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "The manual Start button below is for isolated strategy testing while the monitored SIM reports OFFHOOK. " +
                    "For hands-off per-call start, use the best-available automatic arm control above. A foreground " +
                    "notification stays visible and the service stops when that SIM returns to IDLE. A non-silent WAV " +
                    "is not proof that both parties were audible; OEM/modem behavior remains device-specific.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        DiagnosticCard(title = "Hardware validation") {
            Text("Automatic best-eligible capture can be armed explicitly; digital two-way support remains unverified until physical-device tests.")
            Text(
                "Robolectric verifies telephony listeners and recording policy. It cannot prove that a particular modem/audio HAL delivers both call directions.",
            )
            Text(
                "Two-way audio remains unverified until a lawful capture path is tested on that " +
                    "exact device and both independently spoken test phrases are audible in the output.",
            )
            OutlinedButton(onClick = onRefresh) {
                Text("Refresh SIM and audio inventory")
            }
        }

        if (diagnosticsError != null) {
            DiagnosticCard(title = "Diagnostic warning") {
                Text(diagnosticsError, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DiagnosticCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(epochMillis))
