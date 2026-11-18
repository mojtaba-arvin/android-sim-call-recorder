package com.mojtaba.simcallrecorder.recording

import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent

/**
 * Deterministic policy for consent-gated Standard acoustic auto-recording.
 *
 * This policy deliberately supports only Speakerphone + MIC. Privileged Shizuku capture has a
 * separate explicit authorization lifecycle and must never be selected or downgraded here.
 */
class AutomaticCallRecordingPolicy {
    enum class Decision { NONE, START, STOP }

    @Volatile
    var isArmed: Boolean = false
        private set

    @Volatile
    var activeSimSlotIndex: Int? = null
        private set

    @Synchronized
    fun arm(
        enabled: Boolean,
        participantConsentConfirmed: Boolean,
        selectedMode: RecordingMode,
        recordAudioGranted: Boolean,
    ): Boolean {
        if (!enabled || !participantConsentConfirmed) return false
        if (selectedMode != RecordingMode.SPEAKERPHONE_MIC || !recordAudioGranted) return false
        isArmed = true
        activeSimSlotIndex = null
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
            CallState.RINGING -> Decision.NONE
        }
    }

    @Synchronized
    fun disarm() {
        isArmed = false
        activeSimSlotIndex = null
    }
}
