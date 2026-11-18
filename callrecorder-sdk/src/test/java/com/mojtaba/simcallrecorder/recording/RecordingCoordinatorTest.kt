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
            coordinator.start(request(RecordingMode.STANDARD), profile())
        }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
        assertTrue(failure?.message.orEmpty().contains("cannot capture cellular call uplink/downlink"))
        assertEquals(0, shizuku.startCount)
    }

    @Test
    fun shizukuModeRequiresExplicitParticipantConsent() = runBlocking {
        val shizuku = FakeBackend(RecordingMode.SHIZUKU_VOICE_CALL)
        val coordinator = RecordingCoordinator(setOf(shizuku), shizukuCaptureEnabled = true)

        val failure = runCatching {
            coordinator.start(request(RecordingMode.SHIZUKU_VOICE_CALL, consent = false), profile())
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, shizuku.startCount)
    }

    @Test
    fun shizukuModeRequiresAnActiveCallAndKnownSimSlot() = runBlocking {
        val shizuku = FakeBackend(RecordingMode.SHIZUKU_VOICE_CALL)
        val coordinator = RecordingCoordinator(setOf(shizuku), shizukuCaptureEnabled = true)

        val idleFailure = runCatching {
            coordinator.start(request(RecordingMode.SHIZUKU_VOICE_CALL, callState = CallState.IDLE), profile())
        }.exceptionOrNull()
        val unknownSlotFailure = runCatching {
            coordinator.start(request(RecordingMode.SHIZUKU_VOICE_CALL, slot = -1), profile())
        }.exceptionOrNull()

        assertTrue(idleFailure is IllegalArgumentException)
        assertTrue(unknownSlotFailure is IllegalArgumentException)
        assertEquals(0, shizuku.startCount)
    }

    @Test
    fun enabledShizukuModeDelegatesOnlyAfterAllGatesPass() = runBlocking {
        val shizuku = FakeBackend(RecordingMode.SHIZUKU_VOICE_CALL)
        val coordinator = RecordingCoordinator(setOf(shizuku), shizukuCaptureEnabled = true)

        val output = coordinator.start(request(RecordingMode.SHIZUKU_VOICE_CALL), profile())
        coordinator.stop(RecordingMode.SHIZUKU_VOICE_CALL)

        assertEquals(File("test-recording.wav"), output)
        assertEquals(1, shizuku.startCount)
        assertEquals(1, shizuku.stopCount)
    }

    @Test
    fun shizukuRuntimeFailureNeverStartsMicrophoneFallback() = runBlocking {
        val shizuku = FakeBackend(
            mode = RecordingMode.SHIZUKU_VOICE_CALL,
            startFailure = IllegalStateException("VOICE_CALL source rejected"),
        )
        val microphone = FakeBackend(RecordingMode.SPEAKERPHONE_MIC)
        val coordinator = RecordingCoordinator(setOf(shizuku, microphone), shizukuCaptureEnabled = true)

        val failure = runCatching {
            coordinator.start(request(RecordingMode.SHIZUKU_VOICE_CALL), profile())
        }.exceptionOrNull()

        assertEquals("VOICE_CALL source rejected", failure?.message)
        assertEquals(1, shizuku.startCount)
        assertEquals(0, microphone.startCount)
    }

    @Test
    fun featureFlagDisablesShizukuModeWithoutTryingStandardFallback() = runBlocking {
        val standard = StandardCallRecordingBackend()
        val shizuku = FakeBackend(RecordingMode.SHIZUKU_VOICE_CALL)
        val coordinator = RecordingCoordinator(setOf(standard, shizuku), shizukuCaptureEnabled = false)

        val failure = runCatching {
            coordinator.start(request(RecordingMode.SHIZUKU_VOICE_CALL), profile())
        }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
        assertTrue(failure?.message.orEmpty().contains("disabled"))
        assertEquals(0, shizuku.startCount)
    }

    @Test
    fun shizukuModeRejectsPermissionWithoutLiveAuthorizedUserService() = runBlocking {
        val shizuku = FakeBackend(RecordingMode.SHIZUKU_VOICE_CALL)
        val coordinator = RecordingCoordinator(setOf(shizuku), shizukuCaptureEnabled = true)

        val failure = runCatching {
            coordinator.start(request(RecordingMode.SHIZUKU_VOICE_CALL), profile(shizuku = false))
        }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
        assertTrue(failure?.message.orEmpty().contains("Start Shizuku"))
        assertEquals(0, shizuku.startCount)
    }

    @Test
    fun microphoneStrategyRejectsMissingRecordAudioPermission() = runBlocking {
        val microphone = FakeBackend(RecordingMode.SPEAKERPHONE_MIC)
        val coordinator = RecordingCoordinator(setOf(microphone), shizukuCaptureEnabled = true)

        val failure = runCatching {
            coordinator.start(request(RecordingMode.SPEAKERPHONE_MIC), profile(microphone = false))
        }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
        assertTrue(failure?.message.orEmpty().contains("microphone permission"))
        assertEquals(0, microphone.startCount)
    }

    private fun profile(
        microphone: Boolean = true,
        shizuku: Boolean = true,
    ) = CaptureDeviceProfile(
        sdkInt = 35,
        manufacturer = "Test",
        model = "Test device",
        recordAudioGranted = microphone,
        shizukuAuthorized = shizuku,
        accessibilityServiceEnabled = false,
        notificationListenerEnabled = false,
        voipCallNotificationPresent = false,
    )

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
        private val startFailure: Exception? = null,
    ) : CallAudioRecordingBackend {
        var startCount = 0
        var stopCount = 0
        override suspend fun start(request: RecordingStartRequest): File {
            startCount++
            startFailure?.let { throw it }
            return File("test-recording.wav")
        }
        override suspend fun stop(): FinalizedAudioCapture? {
            stopCount++
            return null
        }
    }
}
