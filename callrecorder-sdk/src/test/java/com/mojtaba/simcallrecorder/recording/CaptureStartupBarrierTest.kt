package com.mojtaba.simcallrecorder.recording

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

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
                startBackend = {
                    backendStarted = true
                },
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
