package com.mojtaba.simcallrecorder.recording

import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingServiceStateMachineTest {

    @Test
    fun startRequiresOffhookKnownSimAndConsent() {
        val machine = RecordingServiceStateMachine()

        assertFalse(machine.beginStart(request(consent = false)))
        assertFalse(machine.beginStart(request(callState = CallState.IDLE)))
        assertFalse(machine.beginStart(request(slot = -1)))
        assertEquals(RecordingServiceStateMachine.State.IDLE, machine.state)
    }

    @Test
    fun onlyTheSelectedSimReturningToIdleStopsTheSession() {
        val machine = RecordingServiceStateMachine()
        assertTrue(machine.beginStart(request(slot = 1)))
        assertTrue(machine.recordingStarted())

        assertFalse(machine.shouldStopFor(event(slot = 0, state = CallState.IDLE)))
        assertFalse(machine.shouldStopFor(event(slot = 1, state = CallState.RINGING)))
        assertFalse(machine.shouldStopFor(event(slot = 1, state = CallState.OFFHOOK)))
        assertTrue(machine.shouldStopFor(event(slot = 1, state = CallState.IDLE)))
        assertTrue(machine.beginStop())
        assertFalse(machine.beginStop())
        machine.stopCompleted()

        assertEquals(RecordingServiceStateMachine.State.IDLE, machine.state)
        assertEquals(null, machine.activeSimSlotIndex)
    }

    @Test
    fun failedStartCanBeRetried() {
        val machine = RecordingServiceStateMachine()
        assertTrue(machine.beginStart(request()))
        machine.startFailed()
        assertEquals(RecordingServiceStateMachine.State.FAILED, machine.state)

        assertTrue(machine.beginStart(request()))
        assertTrue(machine.recordingStarted())
    }

    private fun request(
        slot: Int = 0,
        callState: CallState = CallState.OFFHOOK,
        consent: Boolean = true,
    ) = RecordingStartRequest(
        mode = RecordingMode.SHIZUKU_VOICE_CALL,
        callState = callState,
        simSlotIndex = slot,
        participantConsentConfirmed = consent,
    )

    private fun event(slot: Int, state: CallState) = CallStateEvent(
        simSlotIndex = slot,
        state = state,
        observedAtEpochMillis = 1L,
    )
}
