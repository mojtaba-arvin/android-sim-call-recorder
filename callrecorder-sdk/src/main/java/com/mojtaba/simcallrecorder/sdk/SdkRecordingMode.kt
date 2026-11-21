package com.mojtaba.simcallrecorder.sdk

/**
 * Stable SDK-facing name for the public recording-mode domain contract.
 *
 * Availability is device/build dependent. Selecting a mode does not guarantee that Android,
 * the modem, or the OEM audio HAL will provide both cellular call directions.
 */
typealias SdkRecordingMode = com.mojtaba.simcallrecorder.domain.model.RecordingMode
