package com.mojtaba.simcallrecorder.recording

import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent

/**
 * Small deterministic state machine shared by the foreground service and its JVM tests.
 * It only controls orchestration; it does not pretend to keep an Android process alive.
 */
class RecordingServiceStateMachine {
    enum class State { IDLE, STARTING, RECORDING, STOPPING, FAILED }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var activeSimSlotIndex: Int? = null
        private set

    private var hasObservedOffhook = false

    @Synchronized
    fun beginStart(request: RecordingStartRequest): Boolean {
        if (state != State.IDLE && state != State.FAILED) return false
        if (!request.participantConsentConfirmed) return false
        if (request.callState != CallState.OFFHOOK || request.simSlotIndex < 0) return false

        activeSimSlotIndex = request.simSlotIndex
        hasObservedOffhook = true
        state = State.STARTING
        return true
    }

    @Synchronized
    fun recordingStarted(): Boolean {
        if (state != State.STARTING) return false
        state = State.RECORDING
        return true
    }

    @Synchronized
    fun startFailed() {
        if (state == State.STARTING) {
            state = State.FAILED
            activeSimSlotIndex = null
            hasObservedOffhook = false
        }
    }

    @Synchronized
    fun shouldStopFor(event: CallStateEvent): Boolean {
        if (state != State.RECORDING || event.simSlotIndex != activeSimSlotIndex) return false
        if (event.state == CallState.OFFHOOK) {
            hasObservedOffhook = true
            return false
        }
        return event.state == CallState.IDLE && hasObservedOffhook
    }

    @Synchronized
    fun beginStop(): Boolean {
        if (state != State.RECORDING) return false
        state = State.STOPPING
        return true
    }

    @Synchronized
    fun stopCompleted() {
        if (state == State.STOPPING || state == State.FAILED) {
            state = State.IDLE
            activeSimSlotIndex = null
            hasObservedOffhook = false
        }
    }
}
