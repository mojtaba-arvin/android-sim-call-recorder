package com.mojtaba.simcallrecorder.recording

import com.mojtaba.simcallrecorder.domain.model.CallState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingCoordinatorTest {

    @Test
    fun standardModeFailsClosedWithoutCallingAnyMicFallback() = runBlocking {
        val standard = StandardCallRecordingBackend()
        val shizuku = FakeBackend(RecordingMode.SHIZUKU_VOICE_CALL)
        val coordinator = RecordingCoordinator(setOf(standard, shizuku), shizukuCaptureEnabled = true)

        val failure = runCatching {
            coordinator.start(request(RecordingMode.STANDARD))
        }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
        assertTrue(failure?.message.orEmpty().contains("cannot capture cellular call audio"))
        assertEquals(0, shizuku.startCount)
    }

    @Test
    fun shizukuModeRequiresExplicitParticipantConsent() = runBlocking {
        val shizuku = FakeBackend(RecordingMode.SHIZUKU_VOICE_CALL)
        val coordinator = RecordingCoordinator(setOf(shizuku), shizukuCaptureEnabled = true)

        val failure = runCatching {
            coordinator.start(request(RecordingMode.SHIZUKU_VOICE_CALL, consent = false))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, shizuku.startCount)
    }

    @Test
    fun shizukuModeRequiresAnActiveCallAndKnownSimSlot() = runBlocking {
        val shizuku = FakeBackend(RecordingMode.SHIZUKU_VOICE_CALL)
        val coordinator = RecordingCoordinator(setOf(shizuku), shizukuCaptureEnabled = true)

        val idleFailure = runCatching {
            coordinator.start(request(RecordingMode.SHIZUKU_VOICE_CALL, callState = CallState.IDLE))
        }.exceptionOrNull()
        val unknownSlotFailure = runCatching {
            coordinator.start(request(RecordingMode.SHIZUKU_VOICE_CALL, slot = -1))
        }.exceptionOrNull()

        assertTrue(idleFailure is IllegalArgumentException)
        assertTrue(unknownSlotFailure is IllegalArgumentException)
        assertEquals(0, shizuku.startCount)
    }

    @Test
    fun enabledShizukuModeDelegatesOnlyAfterAllGatesPass() = runBlocking {
        val shizuku = FakeBackend(RecordingMode.SHIZUKU_VOICE_CALL)
        val coordinator = RecordingCoordinator(setOf(shizuku), shizukuCaptureEnabled = true)

        val output = coordinator.start(request(RecordingMode.SHIZUKU_VOICE_CALL))
        coordinator.stop(RecordingMode.SHIZUKU_VOICE_CALL)

        assertEquals(File("test-recording.wav"), output)
        assertEquals(1, shizuku.startCount)
        assertEquals(1, shizuku.stopCount)
    }

    @Test
    fun featureFlagDisablesShizukuModeWithoutTryingStandardFallback() = runBlocking {
        val standard = StandardCallRecordingBackend()
        val shizuku = FakeBackend(RecordingMode.SHIZUKU_VOICE_CALL)
        val coordinator = RecordingCoordinator(setOf(standard, shizuku), shizukuCaptureEnabled = false)

        val failure = runCatching {
            coordinator.start(request(RecordingMode.SHIZUKU_VOICE_CALL))
        }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
        assertTrue(failure?.message.orEmpty().contains("disabled"))
        assertEquals(0, shizuku.startCount)
    }

    private fun request(
        mode: RecordingMode,
        callState: CallState = CallState.OFFHOOK,
        slot: Int = 1,
        consent: Boolean = true,
    ) = RecordingStartRequest(
        mode = mode,
        callState = callState,
        simSlotIndex = slot,
        participantConsentConfirmed = consent,
    )

    private class FakeBackend(
        override val mode: RecordingMode,
    ) : CallAudioRecordingBackend {
        var startCount = 0
        var stopCount = 0
        override suspend fun start(request: RecordingStartRequest): File {
            startCount++
            return File("test-recording.wav")
        }
        override suspend fun stop(): FinalizedAudioCapture? {
            stopCount++
            return null
        }
    }
}
