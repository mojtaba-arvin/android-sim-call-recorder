package com.mojtaba.simcallrecorder.recording

import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.recording.shizuku.ShizukuAudioRecorderClient
import java.io.File
import javax.inject.Inject

enum class RecordingMode {
    STANDARD,
    SHIZUKU_VOICE_CALL,
}

data class RecordingStartRequest(
    val mode: RecordingMode,
    val callState: CallState,
    val simSlotIndex: Int,
    val participantConsentConfirmed: Boolean,
)

interface CallAudioRecordingBackend {
    val mode: RecordingMode
    suspend fun start(request: RecordingStartRequest): File
    suspend fun stop(): FinalizedAudioCapture?
}

/**
 * The standard Android app UID cannot receive cellular call audio while a voice call owns the
 * input. This backend intentionally refuses carrier-call recording instead of toggling a global
 * audio route and writing silence/one-sided microphone audio under a call-recording label.
 *
 * A separate microphone-only voice-note feature would need its own product label and must not
 * appear as a SIM-call recording.
 */
class StandardCallRecordingBackend @Inject constructor() : CallAudioRecordingBackend {
    override val mode: RecordingMode = RecordingMode.STANDARD

    override suspend fun start(request: RecordingStartRequest): File {
        throw UnsupportedOperationException(
            "Standard Android apps cannot capture cellular call audio. " +
                "Speakerphone automation does not change Android's call-input ownership. " +
                "Use the explicitly authorized Shizuku backend on a compatible device.",
        )
    }

    override suspend fun stop(): FinalizedAudioCapture? = null
}

class ShizukuVoiceCallRecordingBackend @Inject constructor(
    private val client: ShizukuAudioRecorderClient,
) : CallAudioRecordingBackend {
    override val mode: RecordingMode = RecordingMode.SHIZUKU_VOICE_CALL

    override suspend fun start(request: RecordingStartRequest): File {
        require(request.mode == mode) { "Recording mode does not match the Shizuku backend." }
        return client.startRecording()
    }

    override suspend fun stop(): FinalizedAudioCapture? = client.stopRecording()
}

/**
 * Central policy gate for all recording starts. It deliberately has no implicit fallback:
 * a failed privileged capture must never be retried as ordinary microphone audio.
 */
class RecordingCoordinator(
    backends: Set<CallAudioRecordingBackend>,
    private val shizukuCaptureEnabled: Boolean,
) {
    private val backendsByMode = backends.associateBy { it.mode }

    suspend fun start(request: RecordingStartRequest): File {
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

        val backend = backendsByMode[request.mode]
            ?: throw UnsupportedOperationException("The selected recording backend is not installed.")
        return backend.start(request)
    }

    suspend fun stop(mode: RecordingMode): FinalizedAudioCapture? =
        backendsByMode[mode]?.stop()
}
