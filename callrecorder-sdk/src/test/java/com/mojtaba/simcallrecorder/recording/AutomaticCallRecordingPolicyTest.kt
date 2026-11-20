package com.mojtaba.simcallrecorder.recording

import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticCallRecordingPolicyTest {

    @Test
    fun armingRequiresExplicitEnablementConsentAvailabilityAndModePermissions() {
        val policy = AutomaticCallRecordingPolicy()

        assertFalse(policy.arm(false, true, RecordingMode.SPEAKERPHONE_MIC, true, true))
        assertFalse(policy.arm(true, false, RecordingMode.SPEAKERPHONE_MIC, true, true))
        assertFalse(policy.arm(true, true, RecordingMode.SPEAKERPHONE_MIC, false, true))
        assertFalse(policy.arm(true, true, RecordingMode.SPEAKERPHONE_MIC, true, false))
        assertFalse(policy.isArmed)
    }

    @Test
    fun automaticPolicyAcceptsOnlyShizukuOrStandardAcousticWhenAvailable() {
        val policy = AutomaticCallRecordingPolicy()

        assertFalse(policy.arm(true, true, RecordingMode.ACCESSIBILITY_VOICE_RECOGNITION, true, true))
        assertFalse(policy.arm(true, true, RecordingMode.SHIZUKU_VOICE_CALL, false, false))
        assertTrue(policy.arm(true, true, RecordingMode.SHIZUKU_VOICE_CALL, true, false))
        assertEquals(RecordingMode.SHIZUKU_VOICE_CALL, policy.selectedMode)

        policy.disarm()
        assertTrue(policy.arm(true, true, RecordingMode.SPEAKERPHONE_MIC, true, true))
        assertEquals(RecordingMode.SPEAKERPHONE_MIC, policy.selectedMode)
    }

    @Test
    fun startsOnlyOnOffhookAndStopsOnlyWhenTheSameSimReturnsToIdle() {
        val policy = armedPolicy()

        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(0, CallState.RINGING)))
        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(0, CallState.UNKNOWN)))
        assertEquals(AutomaticCallRecordingPolicy.Decision.START, policy.onCallState(event(1, CallState.OFFHOOK)))
        assertEquals(1, policy.activeSimSlotIndex)
        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(0, CallState.IDLE)))
        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(0, CallState.OFFHOOK)))
        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(1, CallState.OFFHOOK)))
        assertEquals(AutomaticCallRecordingPolicy.Decision.STOP, policy.onCallState(event(1, CallState.IDLE)))
        assertEquals(null, policy.activeSimSlotIndex)
    }

    @Test
    fun duplicateOffhookAndConcurrentSimEventsDoNotStartAnotherSession() {
        val policy = armedPolicy()
        assertEquals(AutomaticCallRecordingPolicy.Decision.START, policy.onCallState(event(0, CallState.OFFHOOK)))
        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(0, CallState.OFFHOOK)))
        // A second SIM entering OFFHOOK while the first SIM is active must not replace its session.
        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(1, CallState.OFFHOOK)))
        assertEquals(0, policy.activeSimSlotIndex)
        // A call-waiting RINGING event cannot start a second recording.
        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(0, CallState.RINGING)))
        assertEquals(AutomaticCallRecordingPolicy.Decision.STOP, policy.onCallState(event(0, CallState.IDLE)))
        // A stale duplicate IDLE cannot stop a later call.
        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(0, CallState.IDLE)))
        // Once the first SIM has ended, a subsequent call on the other SIM is eligible.
        assertEquals(AutomaticCallRecordingPolicy.Decision.START, policy.onCallState(event(1, CallState.OFFHOOK)))
        assertEquals(1, policy.activeSimSlotIndex)
        assertEquals(AutomaticCallRecordingPolicy.Decision.STOP, policy.onCallState(event(1, CallState.IDLE)))
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
        assertEquals(null, policy.selectedMode)
        assertEquals(AutomaticCallRecordingPolicy.Decision.NONE, policy.onCallState(event(0, CallState.IDLE)))
    }

    private fun armedPolicy() = AutomaticCallRecordingPolicy().apply {
        check(arm(true, true, RecordingMode.SPEAKERPHONE_MIC, true, true))
    }

    private fun event(slot: Int, state: CallState) = CallStateEvent(
        simSlotIndex = slot,
        state = state,
        observedAtEpochMillis = 1L,
    )
}
