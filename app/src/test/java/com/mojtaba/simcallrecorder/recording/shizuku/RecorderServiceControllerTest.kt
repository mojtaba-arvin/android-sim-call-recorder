package com.mojtaba.simcallrecorder.recording.shizuku

import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecorderServiceControllerTest {

    @Test
    fun shellUid_canStartAndStopCapture() {
        val engine = FakeCaptureEngine()
        val controller = RecorderServiceController(engine, hostUid = { 2_000 })
        val pipe = ParcelFileDescriptor.createPipe()

        val channels = controller.start(pipe[1], 48_000, 2)

        assertEquals(2, channels)
        assertEquals(RecorderServiceController.State.RECORDING, controller.state)
        assertTrue(controller.isRecording)

        controller.stop()

        assertEquals(RecorderServiceController.State.IDLE, controller.state)
        assertFalse(controller.isRecording)
        assertEquals(1, engine.stopCount)
        pipe[0].close()
    }

    @Test
    fun rootUid_canUseTheExplicitPrivilegedBackend() {
        val engine = FakeCaptureEngine()
        val controller = RecorderServiceController(engine, hostUid = { 0 })
        val pipe = ParcelFileDescriptor.createPipe()

        assertEquals(2, controller.start(pipe[1], 48_000, 2))

        controller.stop()
        pipe[0].close()
    }

    @Test
    fun ordinaryApplicationUid_isRejectedBeforeAudioRecordIsOpened() {
        val engine = FakeCaptureEngine()
        val controller = RecorderServiceController(engine, hostUid = { 10_123 })
        val pipe = ParcelFileDescriptor.createPipe()

        assertEquals(0, controller.start(pipe[1], 48_000, 2))
        assertEquals(0, engine.startCount)
        assertEquals(RecorderServiceController.State.FAILED, controller.state)
        assertTrue(controller.lastError.contains("UID: 10123"))
        pipe[0].close()
    }

    @Test
    fun duplicateStart_isRejected_andStopAllowsANewSession() {
        val engine = FakeCaptureEngine()
        val controller = RecorderServiceController(engine, hostUid = { 2_000 })
        val firstPipe = ParcelFileDescriptor.createPipe()
        val secondPipe = ParcelFileDescriptor.createPipe()

        assertEquals(2, controller.start(firstPipe[1], 48_000, 2))
        assertEquals(0, controller.start(secondPipe[1], 48_000, 2))
        assertEquals(1, engine.startCount)
        assertTrue(controller.lastError.contains("already in progress"))

        controller.stop()
        assertEquals(2, controller.start(secondPipe[1], 48_000, 2))
        assertEquals(2, engine.startCount)

        controller.stop()
        firstPipe[0].close()
        secondPipe[0].close()
    }

    @Test
    fun failedEngineStart_canBeRetriedAfterTheFailure() {
        val engine = FakeCaptureEngine(startResult = 0, error = "permission denied")
        val controller = RecorderServiceController(engine) { 2_000 }
        val firstPipe = ParcelFileDescriptor.createPipe()

        assertEquals(0, controller.start(firstPipe[1], 48_000, 2))
        assertEquals(RecorderServiceController.State.FAILED, controller.state)
        assertTrue(controller.lastError.contains("permission denied"))
        firstPipe[0].close()

        engine.startResult = 1
        val secondPipe = ParcelFileDescriptor.createPipe()
        assertEquals(1, controller.start(secondPipe[1], 48_000, 1))
        controller.stop()
        secondPipe[0].close()
    }

    private class FakeCaptureEngine(
        var startResult: Int = 2,
        private val error: String = "",
    ) : VoiceCallCaptureEngine {
        var startCount = 0
        var stopCount = 0
        override var isCapturing: Boolean = false
            private set
        override val lastError: String
            get() = error

        override fun start(
            output: ParcelFileDescriptor,
            sampleRate: Int,
            preferredChannels: Int,
        ): Int {
            startCount++
            output.close()
            isCapturing = startResult > 0
            return startResult
        }

        override fun stop() {
            stopCount++
            isCapturing = false
        }
    }
}
