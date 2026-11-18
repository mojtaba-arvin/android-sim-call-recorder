package com.mojtaba.simcallrecorder.recording

import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.recording.shizuku.ShizukuAudioRecorderClient
import java.io.File
import javax.inject.Inject

/** Internal engine alias to the stable public SDK recording-mode contract. */
internal typealias RecordingMode = com.mojtaba.simcallrecorder.domain.model.RecordingMode

/**
 * A capture strategy is a selectable implementation, not a promise that a device's modem or audio
 * HAL exposes both call directions. Implementations must report their actual source accurately.
 */
internal interface CallCaptureStrategy {
    val mode: RecordingMode
    val displayName: String get() = mode.name
    val audioSourceLabel: String get() = mode.name
    suspend fun start(request: RecordingStartRequest): File
    suspend fun stop(): FinalizedAudioCapture?
}

/** Compatibility name retained for the existing Hilt multibindings and tests. */
internal interface CallAudioRecordingBackend : CallCaptureStrategy

internal data class RecordingStartRequest(
    val mode: RecordingMode,
    val callState: CallState,
    val simSlotIndex: Int,
    val participantConsentConfirmed: Boolean,
)

/**
 * Standard is deliberately retained as a selectable diagnostic state for older preferences, but
 * it refuses rather than recording ordinary app microphone audio as a SIM-call recording.
 */
internal class StandardCallRecordingBackend @Inject constructor() : CallAudioRecordingBackend {
    override val mode: RecordingMode = RecordingMode.STANDARD
    override val displayName: String = "Standard Android (unavailable)"
    override val audioSourceLabel: String = "NONE"

    override suspend fun start(request: RecordingStartRequest): File {
        throw UnsupportedOperationException(
            "The standard Android app UID cannot capture cellular call uplink/downlink. " +
                "Choose an explicitly labelled experimental strategy or the authorized Shizuku path.",
        )
    }

    override suspend fun stop(): FinalizedAudioCapture? = null
}

internal class ShizukuVoiceCallRecordingBackend @Inject constructor(
    private val client: ShizukuAudioRecorderClient,
) : CallAudioRecordingBackend {
    override val mode: RecordingMode = RecordingMode.SHIZUKU_VOICE_CALL
    override val displayName: String = "Shizuku · privileged VOICE_CALL"
    override val audioSourceLabel: String = "VOICE_CALL (privileged; device-dependent)"

    override suspend fun start(request: RecordingStartRequest): File {
        require(request.mode == mode) { "Recording mode does not match the Shizuku backend." }
        return client.startRecording()
    }

    override suspend fun stop(): FinalizedAudioCapture? = client.stopRecording()
}

/**
 * Resolves only the strategy explicitly selected by the user. Recommendation/fallback is exposed
 * separately through CallCaptureManager; a capture failure is never silently retried with a
 * different source because that could turn a call recording into a one-sided mic recording.
 */
internal class RecordingCoordinator(
    backends: Set<CallAudioRecordingBackend>,
    private val shizukuCaptureEnabled: Boolean,
) {
    private val captureManager = CallCaptureManager(backends, shizukuCaptureEnabled)

    suspend fun start(request: RecordingStartRequest, profile: CaptureDeviceProfile): File {
        require(request.participantConsentConfirmed) {
            "Confirm that you are authorized to record and have obtained all required participant consent."
        }
        require(request.callState == CallState.OFFHOOK) {
            "Recording can only be started while the selected SIM is in an active call (OFFHOOK)."
        }
        require(request.simSlotIndex >= 0) {
            "The active call could not be associated with a SIM slot."
        }
        if (request.mode == RecordingMode.SHIZUKU_VOICE_CALL && !shizukuCaptureEnabled) {
            throw UnsupportedOperationException("The Shizuku call-capture feature is disabled in this build.")
        }

        if (request.mode != RecordingMode.STANDARD) {
            val candidate = captureManager.availability(profile).firstOrNull { it.mode == request.mode }
            if (candidate?.available != true) {
                throw UnsupportedOperationException(
                    candidate?.reason ?: "The selected recording strategy is unavailable or not installed.",
                )
            }
        }

        val backend = captureManager.strategyFor(request.mode)
            ?: throw UnsupportedOperationException("The selected recording strategy is unavailable or not installed.")
        return backend.start(request)
    }

    fun selectBest(
        profile: CaptureDeviceProfile,
        preferred: RecordingMode? = null,
    ): CallAudioRecordingBackend? = captureManager.selectBest(profile, preferred)

    fun isAvailable(mode: RecordingMode, profile: CaptureDeviceProfile): Boolean =
        captureManager.availability(profile).any { it.mode == mode && it.available } &&
            captureManager.strategyFor(mode) != null

    fun audioSourceLabel(mode: RecordingMode): String =
        captureManager.strategyFor(mode)?.audioSourceLabel ?: mode.name

    suspend fun stop(mode: RecordingMode): FinalizedAudioCapture? =
        captureManager.strategyFor(mode)?.stop()
}
