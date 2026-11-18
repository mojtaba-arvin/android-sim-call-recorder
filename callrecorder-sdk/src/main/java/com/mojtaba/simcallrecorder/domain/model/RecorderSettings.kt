package com.mojtaba.simcallrecorder.domain.model

import com.mojtaba.simcallrecorder.recording.RecordingMode

enum class RecordingTrigger {
    MANUAL,
}

data class RecorderSettings(
    val storageLimitMb: Int = 512,
    val recordingMode: RecordingMode = RecordingMode.SHIZUKU_VOICE_CALL,
    val recordingTrigger: RecordingTrigger = RecordingTrigger.MANUAL,
)
