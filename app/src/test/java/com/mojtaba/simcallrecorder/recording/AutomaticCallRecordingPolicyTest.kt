package com.mojtaba.simcallrecorder.recording

import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticCallRecordingPolicyTest {

    @Test
    fun armingRequiresExplicitEnablementConsentAndMicrophonePermission() {
        val policy = AutomaticCallRecordingPolicy()

        assertFalse(policy.arm(false, true, RecordingMode.SPEAKERPHONE_MIC, true))
        assertFalse(policy.arm(true, false, RecordingMode.SPEAKERPHONE_MIC, true))
        assertFalse(policy.arm(true, true, RecordingMode.SPEAKERPHONE_MIC, false))
        assertFalse(policy.isArmed)
    }

    @Test
    fun automaticPolicyAcceptsOnlyTheStandardAcousticStrategy() {
        val policy = AutomaticCallRecordingPolicy()

        assertFalse(policy.arm(true, true, RecordingMode.SHIZUKU_VOICE_CALL, true))
        assertFalse(policy.arm(true, true, RecordingMode.ACCESSIBILITY_VOICE_RECOGNITION, true))
        assertTrue(policy.arm(true, true, RecordingMode.SPEAKERPHONE_MIC, true))
    }

    @Test
    fun startsOnlyOnOffhookAndStopsOnlyWhenTheSameSimReturnsToIdle() {
        val policy = armedPolicy()

        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(0, CallState.RINGING)))
        assertEquals(AutomaticCallRecordingPolicy.Decision.START, policy.onCallState(event(1, CallState.OFFHOOK)))
        assertEquals(1, policy.activeSimSlotIndex)
        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(0, CallState.IDLE)))
        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(0, CallState.OFFHOOK)))
        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(1, CallState.OFFHOOK)))
        assertEquals(AutomaticCallRecordingPolicy.Decision.STOP, policy.onCallState(event(1, CallState.IDLE)))
        assertEquals(null, policy.activeSimSlotIndex)
    }

    @Test
    fun unknownSimSlotNeverStartsAutomaticCapture() {
        val policy = armedPolicy()
        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(-1, CallState.OFFHOOK)))
        assertEquals(null, policy.activeSimSlotIndex)
    }

    @Test
    fun disarmClearsTheActiveSimAndRejectsFurtherEvents() {
        val policy = armedPolicy()
        assertEquals(AutomaticCallRecordingPolicy.Decision.START, policy.onCallState(event(0, CallState.OFFHOOK)))

        policy.disarm()

        assertFalse(policy.isArmed)
        assertEquals(null, policy.activeSimSlotIndex)
        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(0, CallState.IDLE)))
    }

    private fun armedPolicy() = AutomaticCallRecordingPolicy().apply {
        check(arm(true, true, RecordingMode.SPEAKERPHONE_MIC, true))
    }

    private fun event(slot: Int, state: CallState) = CallStateEvent(
        simSlotIndex = slot,
        state = state,
        observedAtEpochMillis = 1L,
    )
}
