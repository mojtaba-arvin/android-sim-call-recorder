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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.mojtaba.simcallrecorder.core.permissions.CallPermissionManager
import com.mojtaba.simcallrecorder.recording.CallRecordingForegroundService
import com.mojtaba.simcallrecorder.recording.shizuku.ShizukuAudioRecorderClient
import com.mojtaba.simcallrecorder.core.permissions.PermissionPurpose
import com.mojtaba.simcallrecorder.domain.model.AudioRoute
import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent
import com.mojtaba.simcallrecorder.domain.model.SimSlot
import com.mojtaba.simcallrecorder.domain.repository.DeviceDiagnosticsRepository
import com.mojtaba.simcallrecorder.domain.repository.CallRecordRepository
import com.mojtaba.simcallrecorder.domain.repository.RecorderSettingsRepository
import com.mojtaba.simcallrecorder.domain.model.CallRecord
import com.mojtaba.simcallrecorder.domain.model.CallRecordFilter
import com.mojtaba.simcallrecorder.domain.model.RecorderSettings
import com.mojtaba.simcallrecorder.recording.RecordingMode
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
    lateinit var permissionManager: CallPermissionManager

    @Inject
    lateinit var diagnosticsRepository: DeviceDiagnosticsRepository

    @Inject
    lateinit var callRecordRepository: CallRecordRepository

    @Inject
    lateinit var recorderClient: ShizukuAudioRecorderClient

    @Inject
    lateinit var recorderSettingsRepository: RecorderSettingsRepository

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private var monitorJob: Job? = null
    private var pendingMicrophoneStart: Boolean = false

    private var missingPermissions by mutableStateOf(emptyList<String>())
    private var activeSims by mutableStateOf(emptyList<SimSlot>())
    private var callEvents by mutableStateOf(emptyList<CallStateEvent>())
    private var audioRoutes by mutableStateOf(emptyList<AudioRoute>())
    private var monitoring by mutableStateOf(false)
    private var diagnosticsError by mutableStateOf<String?>(null)
    private var recorderConnectionStatus by mutableStateOf("Checking Shizuku status…")
    private var recorderCaptureStatus by mutableStateOf("Idle")
    private var participantConsentConfirmed by mutableStateOf(false)
    private var callRecords by mutableStateOf(emptyList<CallRecord>())
    private var selectedRecord by mutableStateOf<CallRecord?>(null)
    private var recorderSettings by mutableStateOf(RecorderSettings())
    private var recordsJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            refreshPermissionState()
            refreshDiagnostics()
            recorderClient.refreshConnectionStatus()
            if (pendingMicrophoneStart) {
                pendingMicrophoneStart = false
                if (grants[android.Manifest.permission.RECORD_AUDIO] == true) {
                    requestStartRecording()
                } else {
                    recorderCaptureStatus = "Microphone permission was denied."
                }
            }
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) launchRecordingForegroundService()
            else recorderCaptureStatus = "Notification permission is required before recording."
        }

        refreshPermissionState()
        refreshDiagnostics()
        lifecycleScope.launch(Dispatchers.IO) {
            callRecordRepository.markActiveRecordingsInterrupted()
        }
        recorderClient.refreshConnectionStatus()
        lifecycleScope.launch {
            recorderClient.connectionStatus.collect { recorderConnectionStatus = it }
        }
        lifecycleScope.launch {
            recorderClient.captureStatus.collect { recorderCaptureStatus = it }
        }

        lifecycleScope.launch {
            recorderSettingsRepository.settings.collect { recorderSettings = it }
        }
        refreshRecords(CallRecordFilter())

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RecorderRootScreen(
                        records = callRecords,
                        settings = recorderSettings,
                        selectedRecord = selectedRecord,
                        onFilterChanged = ::refreshRecords,
                        onOpenRecord = { selectedRecord = it },
                        onDeleteRecord = { record ->
                            lifecycleScope.launch {
                                callRecordRepository.deleteRecording(record.id)
                                selectedRecord = null
                                refreshRecords(CallRecordFilter())
                            }
                        },
                        onStorageLimitChanged = { limit ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                recorderSettingsRepository.updateStorageLimit(limit)
                            }
                        },
                        onRecordingModeChanged = { mode ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                recorderSettingsRepository.updateRecordingMode(mode.name)
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
                                recorderConnected = recorderConnectionStatus.startsWith("Connected"),
                                recorderActive = recorderCaptureStatus.startsWith("Recording active"),
                                activeCallSlotIndex = activeCallSlotIndex(),
                                participantConsentConfirmed = participantConsentConfirmed,
                                onParticipantConsentChanged = { participantConsentConfirmed = it },
                                onRequestPermissions = {
                                    permissionLauncher.launch(
                                        permissionManager.permissionsFor(PermissionPurpose.CALL_STATE)
                                            .toTypedArray(),
                                    )
                                },
                                onRefresh = ::refreshDiagnostics,
                                onStartMonitoring = ::startMonitoring,
                                onStopMonitoring = ::stopMonitoring,
                                onConnectRecorder = recorderClient::connectOrRequestPermission,
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
            recorderClient.refreshConnectionStatus()
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
            callRecordRepository.observeRecords(filter).collect { callRecords = it }
        }
    }

    private fun refreshPermissionState() {
        missingPermissions = permissionManager.missingPermissions(PermissionPurpose.CALL_STATE)
    }

    private fun refreshDiagnostics() {
        audioRoutes = diagnosticsRepository.getAudioRoutes()
        if (!permissionManager.hasCallStatePermission()) {
            activeSims = emptyList()
            return
        }

        lifecycleScope.launch {
            try {
                activeSims = diagnosticsRepository.getActiveSimSlots()
                diagnosticsError = null
            } catch (exception: Exception) {
                diagnosticsError = exception.message ?: "Could not inspect SIM subscriptions."
            }
        }
    }

    private fun startMonitoring() {
        if (!permissionManager.hasCallStatePermission()) {
            permissionLauncher.launch(
                permissionManager.permissionsFor(PermissionPurpose.CALL_STATE).toTypedArray(),
            )
            return
        }

        monitorJob?.cancel()
        callEvents = emptyList()
        diagnosticsError = null
        monitorJob = lifecycleScope.launch {
            monitoring = true
            try {
                activeSims = diagnosticsRepository.getActiveSimSlots()
                diagnosticsRepository.observeCallStateEvents().collect { event ->
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

    private fun requestStartRecording() {
        if (!participantConsentConfirmed) {
            recorderCaptureStatus = "Confirm participant consent before starting a recording."
            return
        }
        val microphoneStrategies = setOf(
            RecordingMode.SPEAKERPHONE_MIC,
            RecordingMode.OEM_VOICE_RECOGNITION,
            RecordingMode.ACCESSIBILITY_VOICE_RECOGNITION,
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
        val intent = Intent(this, CallRecordingForegroundService::class.java)
            .setAction(CallRecordingForegroundService.ACTION_START)
            .putExtra(
                CallRecordingForegroundService.EXTRA_RECORDING_MODE,
                recorderSettings.recordingMode.name,
            )
            .putExtra(CallRecordingForegroundService.EXTRA_CALL_STATE, CallState.OFFHOOK.name)
            .putExtra(CallRecordingForegroundService.EXTRA_SIM_SLOT_INDEX, slotIndex)
            .putExtra(CallRecordingForegroundService.EXTRA_PARTICIPANT_CONSENT, participantConsentConfirmed)
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
            Intent(this, CallRecordingForegroundService::class.java)
                .setAction(CallRecordingForegroundService.ACTION_STOP),
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
    recorderConnected: Boolean,
    recorderActive: Boolean,
    activeCallSlotIndex: Int?,
    participantConsentConfirmed: Boolean,
    onParticipantConsentChanged: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onConnectRecorder: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
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

        DiagnosticCard(title = "Call-audio recording modes") {
            Text("Standard Android mode")
            Text(
                "Unavailable for cellular-call audio. During a voice call Android gives the input to the call; " +
                    "a normal app's MIC source cannot be used as a guaranteed call-recording fallback. " +
                    "The app does not force speakerphone or save one-sided audio as a call recording.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Advanced mode · Shizuku")
            Text("Connection: $recorderConnectionStatus")
            Text("Capture: $recorderCaptureStatus")
            DiagnosticLine("Call state", activeCallSlotIndex?.let { "OFFHOOK · SIM ${it + 1}" } ?: "No active call detected")
            androidx.compose.foundation.layout.Row {
                Checkbox(
                    checked = participantConsentConfirmed,
                    onCheckedChange = onParticipantConsentChanged,
                )
                Text(
                    "I confirm I am authorized to record this call and have obtained all consent required from the participants.",
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
                        participantConsentConfirmed &&
                        !recorderCaptureStatus.startsWith("Preparing"),
                ) {
                    Text("Start visible call recording")
                }
            }
            Text(
                "This optional path requires Android 11+, a running Shizuku/Sui server, and explicit " +
                    "authorization. It opens AudioSource.VOICE_CALL only inside the shell/root UserService. " +
                    "It does not switch on speakerphone or fall back to the microphone.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Recording is manually started only while the monitored SIM reports OFFHOOK. A foreground notification " +
                    "stays visible, offers Stop, and the service stops when that SIM returns to IDLE. " +
                    "A non-silent WAV is not proof that both parties were audible; OEM/modem behavior remains device-specific.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        DiagnosticCard(title = "Hardware validation") {
            Text("Call-audio capture is explicit and optional. It is not automatically started when a call arrives.")
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
