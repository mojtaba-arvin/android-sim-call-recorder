package com.mojtaba.simcallrecorder.recording

/**
 * Runtime facts used to rank capture strategies. Manufacturer/model are descriptive inputs only:
 * no OEM is marked compatible without physical test evidence for that exact build and carrier.
 */
data class CaptureDeviceProfile(
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val recordAudioGranted: Boolean,
    val shizukuAuthorized: Boolean,
    val accessibilityServiceEnabled: Boolean,
    val notificationListenerEnabled: Boolean,
    val voipCallNotificationPresent: Boolean,
)

data class CaptureStrategyAvailability(
    val mode: RecordingMode,
    val displayName: String,
    val available: Boolean,
    val recommended: Boolean,
    val capability: String,
    val reason: String,
)

object CaptureStrategySelector {
    fun evaluate(profile: CaptureDeviceProfile): List<CaptureStrategyAvailability> {
        val candidates = listOf(
            CaptureStrategyAvailability(
                mode = RecordingMode.SHIZUKU_VOICE_CALL,
                displayName = "Shizuku · privileged VOICE_CALL",
                available = profile.sdkInt >= 24 && profile.shizukuAuthorized,
                recommended = false,
                capability = "Potential full-duplex cellular call mix (unverified)",
                reason = when {
                    profile.sdkInt < 24 -> "The Shizuku API/UserService requires Android 7.0 (API 24) or newer."
                    !profile.shizukuAuthorized -> "Start Shizuku, authorize this app, and connect its recorder UserService."
                    else -> "Privileged source access is available; the modem/audio HAL still needs device testing."
                },
            ),
            CaptureStrategyAvailability(
                mode = RecordingMode.SPEAKERPHONE_MIC,
                displayName = "Speakerphone + MIC",
                available = profile.recordAudioGranted,
                recommended = false,
                capability = "Acoustic capture only; not digital call downlink",
                reason = if (profile.recordAudioGranted) {
                    "Explicit acoustic mode: requests speaker routing and records microphone input. Echo, clipping, Bluetooth and OEM routing can affect results."
                } else {
                    "Grant the microphone permission."
                },
            ),
            CaptureStrategyAvailability(
                mode = RecordingMode.ACCESSIBILITY_VOICE_RECOGNITION,
                displayName = "Accessibility · VOICE_RECOGNITION (experimental)",
                available = profile.sdkInt >= 29 &&
                    profile.recordAudioGranted && profile.accessibilityServiceEnabled,
                recommended = false,
                capability = "Accessibility-priority microphone input; speaker acoustics may supply remote speech",
                reason = when {
                    profile.sdkInt < 29 -> "The Android 10+ audio-input sharing policy is required for this path."
                    !profile.recordAudioGranted -> "Grant the microphone permission."
                    !profile.accessibilityServiceEnabled -> "Enable the app's explicitly described accessibility service in Android Settings."
                    profile.voipCallNotificationPresent -> "An ongoing call notification was detected; direct playback capture is not implied."
                    else -> "Experimental Accessibility-priority microphone input; not a digital call downlink and not eligible for automatic selection."
                },
            ),
            CaptureStrategyAvailability(
                mode = RecordingMode.OEM_VOICE_RECOGNITION,
                displayName = "Platform microphone experiment · VOICE_RECOGNITION",
                available = profile.recordAudioGranted,
                recommended = false,
                capability = "Generic platform microphone input; not vendor-specific",
                reason = if (profile.recordAudioGranted) {
                    "Generic VOICE_RECOGNITION microphone candidate on ${profile.manufacturer} ${profile.model}; no OEM compatibility is inferred."
                } else {
                    "Grant the microphone permission."
                },
            ),
        )
        val recommendedMode = candidates.firstOrNull { it.available }?.mode
        return candidates.map { it.copy(recommended = it.available && it.mode == recommendedMode) }
    }

    fun recommendedMode(profile: CaptureDeviceProfile): RecordingMode? =
        evaluate(profile).firstOrNull { it.recommended }?.mode
}

class CallCaptureManager(
    strategies: Set<CallAudioRecordingBackend>,
    private val shizukuCaptureEnabled: Boolean,
) {
    private val byMode = strategies.associateBy { it.mode }

    fun strategyFor(mode: RecordingMode): CallAudioRecordingBackend? {
        if (mode == RecordingMode.SHIZUKU_VOICE_CALL && !shizukuCaptureEnabled) return null
        return byMode[mode]
    }

    fun availability(profile: CaptureDeviceProfile): List<CaptureStrategyAvailability> =
        CaptureStrategySelector.evaluate(profile).map { option ->
            if (option.mode == RecordingMode.SHIZUKU_VOICE_CALL &&
                (!shizukuCaptureEnabled || byMode[option.mode] == null)
            ) {
                option.copy(
                    available = false,
                    recommended = false,
                    reason = "Shizuku capture is disabled or not included in this build.",
                )
            } else if (byMode[option.mode] == null) {
                option.copy(
                    available = false,
                    recommended = false,
                    reason = "This strategy is not installed in the current build.",
                )
            } else {
                option
            }
        }

    fun selectBest(
        profile: CaptureDeviceProfile,
        preferred: RecordingMode? = null,
    ): CallAudioRecordingBackend? {
        val options = availability(profile)
        // A failed privileged selection must never become an implicit microphone recording.
        if (preferred == RecordingMode.SHIZUKU_VOICE_CALL) {
            val privileged = options.firstOrNull {
                it.mode == RecordingMode.SHIZUKU_VOICE_CALL && it.available
            }
            return privileged?.let { byMode[it.mode] }
        }
        val selected = preferred?.let { mode -> options.firstOrNull { it.mode == mode && it.available } }
            ?: options.firstOrNull { it.recommended }
        return selected?.let { byMode[it.mode] }
    }
}
