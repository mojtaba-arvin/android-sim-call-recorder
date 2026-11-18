package com.mojtaba.simcallrecorder.sdk

import android.content.Context
import android.content.Intent
import com.mojtaba.simcallrecorder.core.permissions.CallPermissionManager
import com.mojtaba.simcallrecorder.core.permissions.PermissionPurpose
import com.mojtaba.simcallrecorder.domain.model.AudioRoute
import com.mojtaba.simcallrecorder.domain.model.CallRecord
import com.mojtaba.simcallrecorder.domain.model.CallRecordFilter
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent
import com.mojtaba.simcallrecorder.domain.model.RecorderSettings
import com.mojtaba.simcallrecorder.domain.model.SimSlot
import com.mojtaba.simcallrecorder.domain.repository.CallRecordRepository
import com.mojtaba.simcallrecorder.domain.repository.DeviceDiagnosticsRepository
import com.mojtaba.simcallrecorder.domain.repository.RecorderSettingsRepository
import com.mojtaba.simcallrecorder.recording.AudioRecordingDiagnostics
import com.mojtaba.simcallrecorder.recording.CallRecordingForegroundService
import com.mojtaba.simcallrecorder.recording.CaptureDeviceProfile
import com.mojtaba.simcallrecorder.recording.CaptureDeviceProfileProvider
import com.mojtaba.simcallrecorder.recording.RecordingCoordinator
import com.mojtaba.simcallrecorder.recording.RecordingMode
import com.mojtaba.simcallrecorder.recording.shizuku.ShizukuAudioRecorderClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supported integration facade for the call-recorder engine.
 *
 * Obtain this singleton from the host application's Hilt graph. Engine services, telephony
 * observation, capture strategies, Shizuku bindings, Room persistence and storage remain owned
 * by the SDK. All exposed streams are read-only.
 */
@Singleton
class SimCallRecorderSdk @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callRecords: CallRecordRepository,
    private val diagnostics: DeviceDiagnosticsRepository,
    private val settingsRepository: RecorderSettingsRepository,
    private val permissions: CallPermissionManager,
    private val profileProvider: CaptureDeviceProfileProvider,
    private val coordinator: RecordingCoordinator,
    private val shizukuClient: ShizukuAudioRecorderClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val recordingStatus: StateFlow<String> = CallRecordingForegroundService.serviceStatus
    val automaticRecordingArmed: StateFlow<Boolean> = CallRecordingForegroundService.automaticMonitoringState
    val audioInputDiagnostics: StateFlow<String> = AudioRecordingDiagnostics.status
    val shizukuConnectionStatus: StateFlow<String> = shizukuClient.connectionStatus

    /** Recent telephony events, retained as a bounded, read-only state stream. */
    val callStates: StateFlow<List<CallStateEvent>> = diagnostics.observeCallStateEvents()
        .scan(emptyList()) { events, event -> (events + event).takeLast(100) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Default recordings stream; use [observeRecordings] when filtering is required. */
    val recordedItems: StateFlow<List<CallRecord>> = callRecords.observeRecords()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<RecorderSettings> = settingsRepository.settings
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), RecorderSettings())

    fun observeRecordings(filter: CallRecordFilter = CallRecordFilter()): Flow<List<CallRecord>> =
        callRecords.observeRecords(filter)

    fun observeCallStateEvents(): Flow<CallStateEvent> = diagnostics.observeCallStateEvents()
    suspend fun activeSimSlots(): List<SimSlot> = diagnostics.getActiveSimSlots()
    fun audioRoutes(): List<AudioRoute> = diagnostics.getAudioRoutes()
    suspend fun deleteRecording(recordId: Long): Boolean = callRecords.deleteRecording(recordId)
    suspend fun markInterruptedRecordings(): Int = callRecords.markActiveRecordingsInterrupted()
    suspend fun updateStorageLimit(megabytes: Int) = settingsRepository.updateStorageLimit(megabytes)
    suspend fun updateRecordingMode(modeName: String) = settingsRepository.updateRecordingMode(modeName)

    fun missingPermissions(purpose: PermissionPurpose): List<String> = permissions.missingPermissions(purpose)
    fun permissionsFor(purpose: PermissionPurpose): List<String> = permissions.permissionsFor(purpose)
    fun hasCallStatePermission(): Boolean = permissions.hasCallStatePermission()

    fun captureProfile(): CaptureDeviceProfile = profileProvider.snapshot()
    fun selectBestRecordingMode(profile: CaptureDeviceProfile = captureProfile()): RecordingMode? =
        coordinator.selectBest(profile)?.mode
    fun isRecordingModeAvailable(mode: RecordingMode, profile: CaptureDeviceProfile = captureProfile()): Boolean =
        coordinator.isAvailable(mode, profile)
    fun audioSourceLabel(mode: RecordingMode): String = coordinator.audioSourceLabel(mode)

    fun refreshShizukuConnection() = shizukuClient.refreshConnectionStatus()
    fun connectShizuku() = shizukuClient.connectOrRequestPermission()

    fun createArmAutomaticRecordingIntent(mode: RecordingMode, participantConsentConfirmed: Boolean): Intent =
        Intent(context, CallRecordingForegroundService::class.java)
            .setAction(CallRecordingForegroundService.ACTION_ARM_AUTOMATIC)
            .putExtra(CallRecordingForegroundService.EXTRA_RECORDING_MODE, mode.name)
            .putExtra(CallRecordingForegroundService.EXTRA_PARTICIPANT_CONSENT, participantConsentConfirmed)

    fun createStopRecordingIntent(): Intent =
        Intent(context, CallRecordingForegroundService::class.java)
            .setAction(CallRecordingForegroundService.ACTION_STOP)
}
