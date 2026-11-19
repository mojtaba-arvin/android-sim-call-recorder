package com.mojtaba.simcallrecorder.recording

import com.mojtaba.simcallrecorder.domain.model.RecordingMode

/**
 * Microphone-backed modes expose AudioRecordingCallback diagnostics.
 * These are routing/capture diagnostics, not proof of remote-party audio.
 */
internal fun RecordingMode.usesMicrophoneRecordingDiagnostics(): Boolean = when (this) {
    RecordingMode.SPEAKERPHONE_MIC,
    RecordingMode.OEM_VOICE_RECOGNITION,
    RecordingMode.ACCESSIBILITY_VOICE_RECOGNITION -> true
    RecordingMode.STANDARD,
    RecordingMode.SHIZUKU_VOICE_CALL -> false
}
