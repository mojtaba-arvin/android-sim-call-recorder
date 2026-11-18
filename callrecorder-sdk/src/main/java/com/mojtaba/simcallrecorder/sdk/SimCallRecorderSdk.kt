package com.mojtaba.simcallrecorder.sdk

import android.content.Context
import android.content.Intent
import com.mojtaba.simcallrecorder.core.permissions.CallPermissionManager
import com.mojtaba.simcallrecorder.domain.model.AudioRoute
import com.mojtaba.simcallrecorder.domain.model.CallRecord
import com.mojtaba.simcallrecorder.domain.model.CallRecordFilter
import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent
import com.mojtaba.simcallrecorder.domain.model.RecorderSettings
import com.mojtaba.simcallrecorder.domain.model.SimSlot
import com.mojtaba.simcallrecorder.domain.repository.CallRecordRepository
import com.mojtaba.simcallrecorder.domain.repository.DeviceDiagnosticsRepository
import com.mojtaba.simcallrecorder.domain.repository.RecorderSettingsRepository
import com.mojtaba.simcallrecorder.recording.AudioRecordingDiagnostics
import com.mojtaba.simcallrecorder.recording.CallRecordingForegroundService
import com.mojtaba.simcallrecorder.recording.CaptureDeviceProfileProvider
import com.mojtaba.simcallrecorder.recording.RecordingCoordinator
import com.mojtaba.simcallrecorder.recording.CaptureStrategySelector
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
 * Immutable public snapshot for the sample UI and SDK consumers. No capture backend or Android
 * service implementation types cross the SDK boundary.
 */
data class CaptureStrategyOption(
    val mode: SdkRecordingMode,
    val displayName: String,
    val available: Boolean,
    val recommended: Boolean,
    val capability: String,
    val reason: String,
)

data class CaptureStrategySnapshot(
    val options: List<CaptureStrategyOption> = emptyList(),
    val microphonePermissionGranted: Boolean = false,
    val notificationListenerEnabled: Boolean = false,
    val voipCallNotificationPresent: Boolean = false,
)

/**
 * Supported integration facade for the call-recorder engine.
 *
 * Obtain this singleton from the host application's Hilt graph. Engine services, telephony
 * observation, capture strategies, Shizuku bindings, Room persistence and storage remain owned
 * by the SDK. All exposed streams are read-only.
 */
@Singleton
class SimCallRecorderSdk @Inject internal constructor(
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
        .scan(emptyList<CallStateEvent>()) { events, event -> (events + event).takeLast(100) }
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

    fun missingPermissions(purpose: SdkPermissionPurpose): List<String> =
        permissions.missingPermissions(purpose.toInternal())
    fun permissionsFor(purpose: SdkPermissionPurpose): List<String> =
        permissions.permissionsFor(purpose.toInternal())
    fun hasCallStatePermission(): Boolean = permissions.hasCallStatePermission()
    fun hasMicrophonePermission(): Boolean = permissions.hasMicrophonePermission()

    val shizukuHostUid: Int? get() = shizukuClient.authorizedHostUid

    fun captureStrategySnapshot(): CaptureStrategySnapshot {
        val profile = profileProvider.snapshot()
        return CaptureStrategySnapshot(
            options = CaptureStrategySelector.evaluate(profile).map { option ->
                CaptureStrategyOption(
                    mode = option.mode,
                    displayName = option.displayName,
                    available = option.available,
                    recommended = option.recommended,
                    capability = option.capability,
                    reason = option.reason,
                )
            },
            microphonePermissionGranted = profile.recordAudioGranted,
            notificationListenerEnabled = profile.notificationListenerEnabled,
            voipCallNotificationPresent = profile.voipCallNotificationPresent,
        )
    }

    fun selectBestRecordingMode(): SdkRecordingMode? =
        coordinator.selectBest(profileProvider.snapshot())?.mode

    fun isRecordingModeAvailable(mode: SdkRecordingMode): Boolean =
        coordinator.isAvailable(mode, profileProvider.snapshot())
    fun audioSourceLabel(mode: SdkRecordingMode): String = coordinator.audioSourceLabel(mode)

    fun refreshShizukuConnection() = shizukuClient.refreshConnectionStatus()
    fun connectShizuku() = shizukuClient.connectOrRequestPermission()

    fun createStartRecordingIntent(
        mode: SdkRecordingMode,
        simSlotIndex: Int,
        callState: CallState,
        participantConsentConfirmed: Boolean,
    ): Intent = Intent(context, CallRecordingForegroundService::class.java)
        .setAction(CallRecordingForegroundService.ACTION_START)
        .putExtra(CallRecordingForegroundService.EXTRA_RECORDING_MODE, mode.name)
        .putExtra(CallRecordingForegroundService.EXTRA_CALL_STATE, callState.name)
        .putExtra(CallRecordingForegroundService.EXTRA_SIM_SLOT_INDEX, simSlotIndex)
        .putExtra(CallRecordingForegroundService.EXTRA_PARTICIPANT_CONSENT, participantConsentConfirmed)

    fun createArmAutomaticRecordingIntent(mode: SdkRecordingMode, participantConsentConfirmed: Boolean): Intent =
        Intent(context, CallRecordingForegroundService::class.java)
            .setAction(CallRecordingForegroundService.ACTION_ARM_AUTOMATIC)
            .putExtra(CallRecordingForegroundService.EXTRA_RECORDING_MODE, mode.name)
            .putExtra(CallRecordingForegroundService.EXTRA_PARTICIPANT_CONSENT, participantConsentConfirmed)

    fun createStopRecordingIntent(): Intent =
        Intent(context, CallRecordingForegroundService::class.java)
            .setAction(CallRecordingForegroundService.ACTION_STOP)
}

/** Permission groups exposed by the SDK without exposing its permission-manager implementation. */
enum class SdkPermissionPurpose {
    CALL_STATE,
    MICROPHONE_CAPTURE,
    FOREGROUND_NOTIFICATIONS,
}

private fun SdkPermissionPurpose.toInternal(): com.mojtaba.simcallrecorder.core.permissions.PermissionPurpose =
    when (this) {
        SdkPermissionPurpose.CALL_STATE -> com.mojtaba.simcallrecorder.core.permissions.PermissionPurpose.CALL_STATE
        SdkPermissionPurpose.MICROPHONE_CAPTURE -> com.mojtaba.simcallrecorder.core.permissions.PermissionPurpose.MICROPHONE_CAPTURE
        SdkPermissionPurpose.FOREGROUND_NOTIFICATIONS -> com.mojtaba.simcallrecorder.core.permissions.PermissionPurpose.FOREGROUND_NOTIFICATIONS
    }

/**
 * Hilt entry point for host applications that integrate the SDK without depending on its
 * implementation graph. The application must install its own @HiltAndroidApp Application.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface SimCallRecorderSdkEntryPoint {
    fun simCallRecorderSdk(): SimCallRecorderSdk
}
