package com.mojtaba.simcallrecorder.sdk

/**
 * Recording strategies exposed to SDK consumers.
 *
 * Availability is device/build dependent. Selecting a mode does not guarantee that Android,
 * the modem, or the OEM audio HAL will provide both cellular call directions.
 */
enum class SdkRecordingMode {
    STANDARD,
    SPEAKERPHONE_MIC,
    OEM_VOICE_RECOGNITION,
    ACCESSIBILITY_VOICE_RECOGNITION,
    SHIZUKU_VOICE_CALL,
}
