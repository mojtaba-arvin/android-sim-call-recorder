package com.mojtaba.simcallrecorder.recording

import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent

/**
 * Deterministic policy for a user-armed automatic SIM-call strategy.
 *
 * The selected mode is fixed at arm time. Shizuku and Standard acoustic capture are separate
 * backends; a Shizuku failure is never downgraded to microphone capture.
 */
class AutomaticCallRecordingPolicy {
    enum class Decision { NONE, START, STOP }

    @Volatile
    var isArmed: Boolean = false
        private set

    @Volatile
    var activeSimSlotIndex: Int? = null
        private set

    @Volatile
    var selectedMode: RecordingMode? = null
        private set

    @Synchronized
    fun arm(
        enabled: Boolean,
        participantConsentConfirmed: Boolean,
        selectedMode: RecordingMode,
        strategyAvailable: Boolean,
        recordAudioGranted: Boolean,
    ): Boolean {
        if (!enabled || !participantConsentConfirmed || !strategyAvailable) return false
        if (selectedMode !in setOf(RecordingMode.SPEAKERPHONE_MIC, RecordingMode.SHIZUKU_VOICE_CALL)) {
            return false
        }
        if (selectedMode == RecordingMode.SPEAKERPHONE_MIC && !recordAudioGranted) return false
        isArmed = true
        activeSimSlotIndex = null
        this.selectedMode = selectedMode
        return true
    }

    @Synchronized
    fun onCallState(event: CallStateEvent): Decision {
        if (!isArmed || event.simSlotIndex < 0) return Decision.NONE
        return when (event.state) {
            CallState.OFFHOOK -> {
                if (activeSimSlotIndex == null) {
                    activeSimSlotIndex = event.simSlotIndex
                    Decision.START
                } else {
                    Decision.NONE
                }
            }
            CallState.IDLE -> {
                if (activeSimSlotIndex == event.simSlotIndex) {
                    activeSimSlotIndex = null
                    Decision.STOP
                } else {
                    Decision.NONE
                }
            }
            CallState.RINGING, CallState.UNKNOWN -> Decision.NONE
        }
    }

    @Synchronized
    fun disarm() {
        isArmed = false
        activeSimSlotIndex = null
        selectedMode = null
    }
}
