package com.mojtaba.simcallrecorder.domain.model

/**
 * Recording strategies exposed as a domain contract. Availability is device/build dependent;
 * selecting a mode does not guarantee that both cellular call directions are accessible.
 */
enum class RecordingMode {
    STANDARD,
    SPEAKERPHONE_MIC,
    OEM_VOICE_RECOGNITION,
    ACCESSIBILITY_VOICE_RECOGNITION,
    SHIZUKU_VOICE_CALL,
}
