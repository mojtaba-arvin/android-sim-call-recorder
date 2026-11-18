package com.mojtaba.simcallrecorder.domain.model


enum class RecordingTrigger {
    MANUAL,
}

data class RecorderSettings(
    val storageLimitMb: Int = 512,
    val recordingMode: RecordingMode = RecordingMode.SHIZUKU_VOICE_CALL,
    val recordingTrigger: RecordingTrigger = RecordingTrigger.MANUAL,
)
