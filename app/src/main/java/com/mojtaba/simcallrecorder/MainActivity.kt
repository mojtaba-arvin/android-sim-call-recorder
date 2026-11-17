package com.mojtaba.simcallrecorder

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import com.mojtaba.simcallrecorder.core.permissions.PermissionPurpose
import com.mojtaba.simcallrecorder.domain.model.AudioRoute
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent
import com.mojtaba.simcallrecorder.domain.model.SimSlot
import com.mojtaba.simcallrecorder.domain.repository.DeviceDiagnosticsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
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

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private var monitorJob: Job? = null

    private var missingPermissions by mutableStateOf(emptyList<String>())
    private var activeSims by mutableStateOf(emptyList<SimSlot>())
    private var callEvents by mutableStateOf(emptyList<CallStateEvent>())
    private var audioRoutes by mutableStateOf(emptyList<AudioRoute>())
    private var monitoring by mutableStateOf(false)
    private var diagnosticsError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            refreshPermissionState()
            refreshDiagnostics()
        }

        refreshPermissionState()
        refreshDiagnostics()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DiagnosticsScreen(
                        missingPermissions = missingPermissions,
                        activeSims = activeSims,
                        callEvents = callEvents,
                        audioRoutes = audioRoutes,
                        monitoring = monitoring,
                        diagnosticsError = diagnosticsError,
                        onRequestPermissions = {
                            permissionLauncher.launch(
                                permissionManager.permissionsFor(PermissionPurpose.CALL_STATE)
                                    .toTypedArray(),
                            )
                        },
                        onRefresh = ::refreshDiagnostics,
                        onStartMonitoring = ::startMonitoring,
                        onStopMonitoring = ::stopMonitoring,
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
        }
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
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
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
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
            text = "Device diagnostics · No audio is being recorded",
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
                "Monitoring runs only while this screen's process is alive. It is a diagnostic " +
                    "tool, not a background receiver or recording service.",
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

        DiagnosticCard(title = "Hardware validation") {
            Text("This build does not start an audio recorder.")
            Text(
                "Place a test call on each SIM and check the RINGING, OFFHOOK, and IDLE events above.",
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
