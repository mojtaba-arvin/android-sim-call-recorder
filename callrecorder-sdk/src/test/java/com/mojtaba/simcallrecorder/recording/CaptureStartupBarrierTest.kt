package com.mojtaba.simcallrecorder.recording

import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.model.RecordingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CaptureStartupBarrierTest {
    @Test
    fun backendCannotStartUntilProcessRecoveryCompletes() = runBlocking {
        val recoveryStarted = CompletableDeferred<Unit>()
        val allowRecoveryToFinish = CompletableDeferred<Unit>()
        var backendStarted = false
        val startup = async {
            CaptureStartupBarrier.start(
                prepareForCapture = {
                    recoveryStarted.complete(Unit)
                    allowRecoveryToFinish.await()
                },
                startBackend = {
                    backendStarted = true
                    "capture-output"
                },
            )
        }
        recoveryStarted.await()
        assertFalse("Backend must remain untouched while recovery is suspended.", backendStarted)
        assertFalse("Startup must remain suspended behind the recovery barrier.", startup.isCompleted)
        allowRecoveryToFinish.complete(Unit)
        assertEquals("capture-output", startup.await())
        assertTrue(backendStarted)
    }

    @Test
    fun concurrentStartContendersCannotBypassRecoveryOrStartTwoBackends() = runBlocking {
        val machine = RecordingServiceStateMachine()
        val contendersReady = CountDownLatch(2)
        val releaseContenders = CountDownLatch(1)
        val recoveryStarted = CompletableDeferred<Unit>()
        val allowRecoveryToFinish = CompletableDeferred<Unit>()
        val accepted = Collections.synchronizedList(mutableListOf<Boolean>())
        val backendCalls = AtomicInteger()

        val contenders = (0..1).map { slot ->
            async(Dispatchers.Default) {
                contendersReady.countDown()
                check(releaseContenders.await(5, TimeUnit.SECONDS))
                val ownsStart = machine.beginStart(
                    RecordingStartRequest(
                        mode = RecordingMode.SHIZUKU_VOICE_CALL,
                        callState = CallState.OFFHOOK,
                        simSlotIndex = slot,
                        participantConsentConfirmed = true,
                    ),
                )
                accepted += ownsStart
                if (ownsStart) {
                    CaptureStartupBarrier.start(
                        prepareForCapture = {
                            recoveryStarted.complete(Unit)
                            allowRecoveryToFinish.await()
                        },
                        startBackend = {
                            backendCalls.incrementAndGet()
                            "capture-output"
                        },
                    )
                }
            }
        }

        assertTrue("Both start contenders must be ready before releasing the race.", contendersReady.await(5, TimeUnit.SECONDS))
        releaseContenders.countDown()
        recoveryStarted.await()

        assertEquals("Only one simultaneous start request may own STARTING.", 1, accepted.count { it })
        assertEquals("No backend may start while process recovery is suspended.", 0, backendCalls.get())
        assertEquals(RecordingServiceStateMachine.State.STARTING, machine.state)

        allowRecoveryToFinish.complete(Unit)
        contenders.awaitAll()

        assertEquals("Exactly one backend may start after recovery.", 1, backendCalls.get())
        assertTrue(machine.recordingStarted())
        assertEquals(RecordingServiceStateMachine.State.RECORDING, machine.state)
    }

    @Test
    fun cancellationWhileRecoveryIsPendingNeverInvokesBackend() = runBlocking {
        val recoveryStarted = CompletableDeferred<Unit>()
        val holdRecovery = CompletableDeferred<Unit>()
        var backendStarted = false
        val startup = launch {
            CaptureStartupBarrier.start(
                prepareForCapture = {
                    recoveryStarted.complete(Unit)
                    holdRecovery.await()
                },
                startBackend = { backendStarted = true },
            )
        }
        recoveryStarted.await()
        startup.cancelAndJoin()
        assertFalse("Cancelling a pending recovery barrier must not start the backend.", backendStarted)
    }

    @Test
    fun backendFailureIsPropagatedAfterRecoveryWithoutRepeatingTheBarrier() = runBlocking {
        val backendFailure = IllegalStateException("Backend startup failed")
        var recoveryCalls = 0
        var backendCalls = 0
        val thrown = runCatching {
            CaptureStartupBarrier.start(
                prepareForCapture = { recoveryCalls++ },
                startBackend = {
                    backendCalls++
                    throw backendFailure
                },
            )
        }.exceptionOrNull()
        assertSame(backendFailure, thrown)
        assertEquals(1, recoveryCalls)
        assertEquals(1, backendCalls)
    }

    @Test
    fun recoveryFailureFailsClosedWithoutInvokingBackend() = runBlocking {
        val recoveryFailure = IllegalStateException("Room recovery failed")
        var backendStarted = false
        val thrown = runCatching {
            CaptureStartupBarrier.start(
                prepareForCapture = { throw recoveryFailure },
                startBackend = {
                    backendStarted = true
                    "must-not-start"
                },
            )
        }.exceptionOrNull()
        assertSame(recoveryFailure, thrown)
        assertFalse("A recovery failure must prevent all backend startup side effects.", backendStarted)
    }
}
