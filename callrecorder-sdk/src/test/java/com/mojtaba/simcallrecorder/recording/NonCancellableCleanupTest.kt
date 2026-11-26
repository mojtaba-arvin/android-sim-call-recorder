package com.mojtaba.simcallrecorder.recording

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class NonCancellableCleanupTest {

    @Test
    fun cleanupFinishesBackendStopAndRoomCommitWhenCallerIsCancelled() = runTest {
        val cleanupEntered = CompletableDeferred<Unit>()
        val allowCleanupToFinish = CompletableDeferred<Unit>()
        var backendStopped = false
        var roomCommitted = false

        val cleanupJob = launch {
            NonCancellableCleanup.run {
                cleanupEntered.complete(Unit)
                allowCleanupToFinish.await()
                backendStopped = true
                roomCommitted = true
            }
        }

        cleanupEntered.await()
        cleanupJob.cancel()
        allowCleanupToFinish.complete(Unit)
        cleanupJob.join()

        assertTrue("Backend stop must finish despite caller cancellation.", backendStopped)
        assertTrue("Room commit must finish despite caller cancellation.", roomCommitted)
    }
}
