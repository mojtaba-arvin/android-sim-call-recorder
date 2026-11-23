package com.mojtaba.simcallrecorder.recording.shizuku

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidVoiceCallCaptureEngineTest {

    @Test
    fun stereoBufferRejectionFallsBackToMonoWithoutChangingAudioSource() {
        val session = BlockingSession()
        val factory = FakeFactory(
            minBuffer = { _, channelMask, _ ->
                if (channelMask == AudioFormat.CHANNEL_IN_STEREO) AudioRecord.ERROR_BAD_VALUE else 4_096
            },
            sessions = listOf(session),
        )
        val engine = AndroidVoiceCallCaptureEngine(factory)
        val pipe = ParcelFileDescriptor.createPipe()

        assertEquals(1, engine.start(pipe[1], 48_000, 2))
        assertEquals(
            listOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO),
            factory.requestedChannelMasks,
        )
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_CALL), factory.requestedSources)
        engine.stop()

        assertFalse(engine.isCapturing)
        assertEquals(1, session.releaseCount)
        pipe[0].close()
    }

    @Test
    fun invalidBufferSizesFailClosedBeforeConstructingAudioRecord() {
        val factory = FakeFactory(minBuffer = { _, _, _ -> AudioRecord.ERROR_BAD_VALUE })
        val engine = AndroidVoiceCallCaptureEngine(factory)
        val pipe = ParcelFileDescriptor.createPipe()

        assertEquals(0, engine.start(pipe[1], 48_000, 2))
        assertTrue(engine.lastError.contains("rejected"))
        assertTrue(factory.requestedSources.isEmpty())
        assertFalse(engine.isCapturing)
        pipe[0].close()
    }

    @Test
    fun uninitializedStereoRecordIsReleasedBeforeMonoRetry() {
        val uninitialized = BlockingSession(initialized = false)
        val mono = BlockingSession()
        val factory = FakeFactory(
            minBuffer = { _, _, _ -> 4_096 },
            sessions = listOf(uninitialized, mono),
        )
        val engine = AndroidVoiceCallCaptureEngine(factory)
        val pipe = ParcelFileDescriptor.createPipe()

        assertEquals(1, engine.start(pipe[1], 48_000, 2))
        assertEquals(2, factory.requestedChannelMasks.size)
        assertEquals(1, uninitialized.releaseCount)
        assertEquals(0, mono.releaseCount)
        engine.stop()
        assertEquals(1, mono.releaseCount)
        pipe[0].close()
    }

    @Test
    fun startRecordingFailureReleasesRecordAndDoesNotBecomeActive() {
        val broken = BlockingSession(startFailure = IllegalStateException("HAL start denied"))
        val factory = FakeFactory(
            minBuffer = { _, _, _ -> 4_096 },
            sessions = listOf(broken),
        )
        val engine = AndroidVoiceCallCaptureEngine(factory)
        val pipe = ParcelFileDescriptor.createPipe()

        assertEquals(0, engine.start(pipe[1], 48_000, 1))
        assertTrue(engine.lastError.contains("HAL start denied"))
        assertFalse(engine.isCapturing)
        assertEquals(1, broken.releaseCount)
        assertFalse(broken.readEntered)
        pipe[0].close()
    }

    @Test
    fun unsupportedPcmRequestDoesNotCallAudioRecordFactory() {
        val factory = FakeFactory(minBuffer = { _, _, _ -> 4_096 })
        val engine = AndroidVoiceCallCaptureEngine(factory)
        val pipe = ParcelFileDescriptor.createPipe()

        assertEquals(0, engine.start(pipe[1], 7_999, 2))
        assertTrue(engine.lastError.contains("Unsupported PCM"))
        assertTrue(factory.requestedChannelMasks.isEmpty())
        pipe[0].close()
    }

    @Test
    fun audioRecordReadFailuresKeepDistinctHalErrorClasses() {
        assertTrue(describeAudioRecordReadFailure(AudioRecord.ERROR_DEAD_OBJECT).contains("ERROR_DEAD_OBJECT"))
        assertTrue(describeAudioRecordReadFailure(AudioRecord.ERROR_INVALID_OPERATION).contains("ERROR_INVALID_OPERATION"))
        assertTrue(describeAudioRecordReadFailure(AudioRecord.ERROR_BAD_VALUE).contains("ERROR_BAD_VALUE"))
        assertTrue(describeAudioRecordReadFailure(AudioRecord.ERROR).contains("generic read failure"))
        assertTrue(describeAudioRecordReadFailure(-99).contains("unknown error code -99"))
    }

    @Test
    fun unexpectedDeadObjectReadFailureClosesThePipeAndMarksCaptureStopped() {
        val session = FailingReadSession(AudioRecord.ERROR_DEAD_OBJECT)
        val factory = FakeFactory(
            minBuffer = { _, _, _ -> 4_096 },
            sessions = listOf(session),
        )
        val engine = AndroidVoiceCallCaptureEngine(factory)
        val pipe = ParcelFileDescriptor.createPipe()

        assertEquals(1, engine.start(pipe[1], 48_000, 1))
        assertTrue(session.readFinished.await(2, TimeUnit.SECONDS))
        val eof = ByteArray(1)
        assertEquals(-1, ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).read(eof))
        assertFalse(engine.isCapturing)
        assertTrue(engine.lastError.contains("ERROR_DEAD_OBJECT"))
    }

    private class FakeFactory(
        private val minBuffer: (Int, Int, Int) -> Int,
        private val sessions: List<AudioRecordSession> = emptyList(),
    ) : AudioRecordSessionFactory {
        val requestedChannelMasks = mutableListOf<Int>()
        val requestedSources = mutableListOf<Int>()
        private var nextSession = 0

        override fun getMinBufferSize(sampleRate: Int, channelMask: Int, encoding: Int): Int {
            requestedChannelMasks += channelMask
            return minBuffer(sampleRate, channelMask, encoding)
        }

        override fun create(
            audioSource: Int,
            sampleRate: Int,
            channelMask: Int,
            encoding: Int,
            bufferSizeBytes: Int,
        ): AudioRecordSession {
            requestedSources += audioSource
            return sessions[nextSession++]
        }
    }

    private class FailingReadSession(
        private val errorCode: Int,
    ) : AudioRecordSession {
        val readFinished = CountDownLatch(1)
        @Volatile private var recording = false
        override val state: Int get() = AudioRecord.STATE_INITIALIZED
        override val recordingState: Int
            get() = if (recording) AudioRecord.RECORDSTATE_RECORDING else AudioRecord.RECORDSTATE_STOPPED

        override fun startRecording() {
            recording = true
        }

        override fun read(buffer: ByteArray, offset: Int, size: Int, readMode: Int): Int {
            readFinished.countDown()
            return errorCode
        }

        override fun stop() {
            recording = false
        }

        override fun release() {
            recording = false
        }
    }

    private class BlockingSession(
        private val initialized: Boolean = true,
        private val startFailure: RuntimeException? = null,
    ) : AudioRecordSession {
        private val stopSignal = CountDownLatch(1)
        @Volatile private var recording = false
        @Volatile var readEntered = false
            private set
        var releaseCount = 0
            private set

        override val state: Int
            get() = if (initialized) AudioRecord.STATE_INITIALIZED else AudioRecord.STATE_UNINITIALIZED

        override val recordingState: Int
            get() = if (recording) AudioRecord.RECORDSTATE_RECORDING else AudioRecord.RECORDSTATE_STOPPED

        override fun startRecording() {
            startFailure?.let { throw it }
            recording = true
        }

        override fun read(buffer: ByteArray, offset: Int, size: Int, readMode: Int): Int {
            readEntered = true
            stopSignal.await(2, TimeUnit.SECONDS)
            return -6
        }

        override fun stop() {
            recording = false
            stopSignal.countDown()
        }

        override fun release() {
            releaseCount++
            stopSignal.countDown()
        }
    }
}
