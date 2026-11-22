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


/**
 * Value-only projection of the framework callback. Keeping this independent of Android framework
 * classes makes session correlation and silence-state reporting deterministic in unit tests.
 * This snapshot is diagnostic metadata only; it cannot establish remote-party audio presence.
 */
internal data class AudioRecordingDiagnosticConfiguration(
    val sessionId: Int,
    val clientSource: String,
    val activeSource: String,
    val inputDeviceType: Int?,
    val clientSampleRate: Int,
    val clientChannelCount: Int,
    val deviceSampleRate: Int,
    val deviceChannelCount: Int,
    val clientSilenced: Boolean,
)

internal fun List<AudioRecordingDiagnosticConfiguration>.forAudioSession(
    sessionId: Int,
): AudioRecordingDiagnosticConfiguration? = firstOrNull { it.sessionId == sessionId }

internal fun AudioRecordingDiagnosticConfiguration.toDiagnosticStatus(): String {
    val device = inputDeviceType?.let { "type=$it" } ?: "unknown input device"
    return "session=$sessionId; clientSource=$clientSource; activeSource=$activeSource; " +
        "device=$device; clientFormat=${clientSampleRate}Hz/${clientChannelCount}ch; " +
        "deviceFormat=${deviceSampleRate}Hz/${deviceChannelCount}ch; " +
        "clientSilenced=$clientSilenced (not proof of two-way call audio)"
}


/**
 * Owns the registration state for one AudioRecordingCallback instance.
 * The platform adapter is supplied by the caller so registration ordering and cleanup can be
 * verified without constructing Android AudioManager objects in local unit tests.
 */
internal class AudioRecordingCallbackRegistration(
    private val registerCallback: () -> Unit,
    private val unregisterCallback: () -> Unit,
) {
    private var registered = false

    @Synchronized
    fun register(): Boolean {
        if (registered) return false
        registerCallback()
        registered = true
        return true
    }

    @Synchronized
    fun unregister(): Boolean {
        if (!registered) return false
        // Keep ownership when the platform rejects unregister so the caller can retry.
        // Clearing this flag on failure would silently orphan a still-registered callback.
        unregisterCallback()
        registered = false
        return true
    }

    @Synchronized
    fun isRegistered(): Boolean = registered
}
