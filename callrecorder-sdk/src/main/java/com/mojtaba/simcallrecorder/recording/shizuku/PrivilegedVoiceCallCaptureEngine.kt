package com.mojtaba.simcallrecorder.recording.shizuku

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Only VOICE_CALL represents the full-duplex cellular call mix. UPLINK and DOWNLINK are
 * one-sided sources and must never be accepted as a completed call recording.
 */
internal fun privilegedFullDuplexAudioSources(): List<Int> =
    listOf(MediaRecorder.AudioSource.VOICE_CALL)

internal interface VoiceCallCaptureEngine {
    fun start(
        output: ParcelFileDescriptor,
        sampleRate: Int,
        preferredChannels: Int,
    ): Int

    fun stop()

    val isCapturing: Boolean
    val lastError: String
}

internal interface AudioRecordSession {
    val state: Int
    val recordingState: Int
    fun startRecording()
    fun read(buffer: ByteArray, offset: Int, size: Int, readMode: Int): Int
    fun stop()
    fun release()
}

internal interface AudioRecordSessionFactory {
    fun getMinBufferSize(sampleRate: Int, channelMask: Int, encoding: Int): Int
    fun create(
        audioSource: Int,
        sampleRate: Int,
        channelMask: Int,
        encoding: Int,
        bufferSizeBytes: Int,
    ): AudioRecordSession
}

internal class AndroidAudioRecordSessionFactory : AudioRecordSessionFactory {
    override fun getMinBufferSize(sampleRate: Int, channelMask: Int, encoding: Int): Int =
        AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)

    override fun create(
        audioSource: Int,
        sampleRate: Int,
        channelMask: Int,
        encoding: Int,
        bufferSizeBytes: Int,
    ): AudioRecordSession {
        val record = AudioRecord(
            audioSource,
            sampleRate,
            channelMask,
            encoding,
            bufferSizeBytes,
        )
        return object : AudioRecordSession {
            override val state: Int get() = record.state
            override val recordingState: Int get() = record.recordingState
            override fun startRecording() = record.startRecording()
            override fun read(buffer: ByteArray, offset: Int, size: Int, readMode: Int): Int =
                record.read(buffer, offset, size, readMode)
            override fun stop() = record.stop()
            override fun release() = record.release()
        }
    }
}

/**
 * Only instantiated inside the Shizuku UserService process (normally UID 2000).
 *
 * There is intentionally no MIC / DEFAULT / VOICE_COMMUNICATION fallback. Those sources do not
 * grant cellular call uplink/downlink access. If VOICE_CALL cannot be opened under the authorized
 * shell/root identity, the session fails closed instead of writing a misleading microphone file.
 */
internal class AndroidVoiceCallCaptureEngine(
    private val audioRecordFactory: AudioRecordSessionFactory = AndroidAudioRecordSessionFactory(),
) : VoiceCallCaptureEngine {
    private val lock = Any()

    @Volatile
    private var active = false

    @Volatile
    private var currentRecord: AudioRecordSession? = null

    @Volatile
    private var writerThread: Thread? = null

    @Volatile
    private var stopRequested = AtomicBoolean(false)

    @Volatile
    override var lastError: String = ""
        private set

    override val isCapturing: Boolean
        get() = active

    /**
     * This capture engine is instantiated only after Shizuku authorization in a shell/root
     * UserService. Permission checks must therefore be evaluated for that process identity,
     * not for the app UID; Android may still reject the protected VOICE_CALL source, which is
     * handled below as a normal capture failure.
     */
    @SuppressLint("MissingPermission")
    override fun start(
        output: ParcelFileDescriptor,
        sampleRate: Int,
        preferredChannels: Int,
    ): Int {
        synchronized(lock) {
            if (active) {
                closeQuietly(output)
                lastError = "A capture session is already active."
                return 0
            }

            if (sampleRate !in 8_000..96_000 || preferredChannels !in 1..2) {
                closeQuietly(output)
                lastError = "Unsupported PCM format requested."
                return 0
            }

            val candidates = if (preferredChannels == 2) {
                listOf(
                    AudioFormat.CHANNEL_IN_STEREO to 2,
                    AudioFormat.CHANNEL_IN_MONO to 1,
                )
            } else {
                listOf(AudioFormat.CHANNEL_IN_MONO to 1)
            }

            // Shell/root identity is not a permission bypass. Only VOICE_CALL is accepted here:
            // isolated UPLINK or DOWNLINK would create a one-sided WAV and violate the product contract.
            val sourceCandidates = privilegedFullDuplexAudioSources()
            for (audioSource in sourceCandidates) {
                for ((channelMask, channelCount) in candidates) {
                    val minBufferBytes = try {
                        audioRecordFactory.getMinBufferSize(
                            sampleRate,
                            channelMask,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                    } catch (exception: Exception) {
                        lastError = "Could not query AudioRecord buffer size: ${exception.message}"
                        continue
                    }

                    if (minBufferBytes <= 0) {
                        lastError = "AudioRecord rejected the requested sample rate/channel format."
                        continue
                    }

                    val record = try {
                        audioRecordFactory.create(
                            audioSource,
                            sampleRate,
                            channelMask,
                            AudioFormat.ENCODING_PCM_16BIT,
                            max(minBufferBytes * 4, sampleRate * channelCount),
                        )
                    } catch (exception: Exception) {
                        lastError = "AudioSource $audioSource AudioRecord creation failed: ${exception.javaClass.simpleName}: ${exception.message}"
                        continue
                    }

                    if (record.state != AudioRecord.STATE_INITIALIZED) {
                        runCatching { record.release() }
                        lastError = "AudioSource $audioSource did not initialize for ${channelCount}-channel PCM."
                        continue
                    }

                    val sink = try {
                        ParcelFileDescriptor.dup(output.fileDescriptor)
                    } catch (exception: Exception) {
                        runCatching { record.release() }
                        lastError = "Could not duplicate the capture output pipe: ${exception.message}"
                        continue
                    }

                    try {
                        record.startRecording()
                        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            throw IllegalStateException("AudioRecord did not enter RECORDSTATE_RECORDING.")
                        }
                    } catch (exception: Exception) {
                        closeQuietly(sink)
                        runCatching { record.release() }
                        lastError = "AudioSource $audioSource start failed: ${exception.javaClass.simpleName}: ${exception.message}"
                        continue
                    }

                    closeQuietly(output)
                    stopRequested = AtomicBoolean(false)
                    currentRecord = record
                    active = true
                    lastError = ""

                    val thread = try {
                        Thread(
                            { drainAudioRecord(record, sink) },
                            "privileged-voice-call-capture",
                        ).apply { isDaemon = true }
                    } catch (exception: Exception) {
                        releaseUnstartedCapture(record, sink)
                        lastError = "Could not create the AudioRecord writer thread: ${exception.javaClass.simpleName}: ${exception.message}"
                        return 0
                    }

                    writerThread = thread
                    try {
                        thread.start()
                    } catch (exception: Exception) {
                        releaseUnstartedCapture(record, sink)
                        lastError = "Could not start the AudioRecord writer thread: ${exception.javaClass.simpleName}: ${exception.message}"
                        return 0
                    }

                    return channelCount
                }
            }

            closeQuietly(output)
            if (lastError.isBlank()) {
                lastError = "No supported privileged call-audio AudioRecord source/format was available."
            }
            return 0
        }
    }

    /**
     * Releases resources when the writer thread could not be created or started. In this path
     * there is no drain thread to own AudioRecord/sink cleanup.
     */
    private fun releaseUnstartedCapture(record: AudioRecordSession, sink: ParcelFileDescriptor) {
        stopRequested.set(true)
        runCatching {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        }
        runCatching { record.release() }
        closeQuietly(sink)
        if (currentRecord === record) currentRecord = null
        writerThread = null
        active = false
    }

    private fun drainAudioRecord(
        record: AudioRecordSession,
        sink: ParcelFileDescriptor,
    ) {
        val buffer = ByteArray(16 * 1024)
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(sink).use { output ->
                while (!stopRequested.get()) {
                    val count = record.read(
                        buffer,
                        0,
                        buffer.size,
                        AudioRecord.READ_BLOCKING,
                    )
                    when {
                        count > 0 -> output.write(buffer, 0, count)
                        count == 0 -> Thread.yield()
                        count < 0 -> {
                            if (!stopRequested.get()) {
                                lastError = "AudioRecord read failed with code $count."
                            }
                            break
                        }
                    }
                }
                output.flush()
            }
        } catch (exception: IOException) {
            if (!stopRequested.get()) {
                lastError = "Audio capture output pipe failed: ${exception.message}"
            }
        } catch (exception: Exception) {
            if (!stopRequested.get()) {
                lastError = "Audio capture stopped unexpectedly: ${exception.message}"
            }
        } finally {
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }
            runCatching { record.release() }
            synchronized(lock) {
                if (currentRecord === record) {
                    currentRecord = null
                    writerThread = null
                    active = false
                }
            }
        }
    }

    override fun stop() {
        val record: AudioRecord?
        val thread: Thread?
        synchronized(lock) {
            if (!active) return
            stopRequested.set(true)
            record = currentRecord
            thread = writerThread
        }

        runCatching {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        }
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_JOIN_TIMEOUT_MS) }
            if (thread.isAlive) {
                lastError = "AudioRecord writer thread did not terminate within ${STOP_JOIN_TIMEOUT_MS} ms."
            }
        }
    }

    private fun closeQuietly(descriptor: ParcelFileDescriptor) {
        runCatching { descriptor.close() }
    }

    private companion object {
        const val STOP_JOIN_TIMEOUT_MS = 2_000L
    }
}

/**
 * State/identity gate used by the actual AIDL UserService and unit-tested without a modem or HAL.
 */
internal class RecorderServiceController(
    private val engine: VoiceCallCaptureEngine,
    private val hostUid: () -> Int,
    private val captureReady: () -> Boolean = { true },
) {
    enum class State { IDLE, STARTING, RECORDING, STOPPING, FAILED }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var policyError: String = ""
        private set

    val isRecording: Boolean
        get() = state == State.RECORDING && engine.isCapturing

    val lastError: String
        get() = policyError.ifBlank { engine.lastError }

    val isCaptureReady: Boolean
        get() = runCatching { captureReady() }.getOrDefault(false)

    @Synchronized
    fun start(
        output: ParcelFileDescriptor,
        sampleRate: Int,
        preferredChannels: Int,
    ): Int {
        if (state == State.RECORDING && !engine.isCapturing) {
            state = State.IDLE
        }
        if (state == State.STARTING || state == State.RECORDING || state == State.STOPPING) {
            runCatching { output.close() }
            policyError = "A recording session is already in progress."
            return 0
        }

        val uid = hostUid()
        if (uid != SHELL_UID && uid != ROOT_UID) {
            runCatching { output.close() }
            state = State.FAILED
            policyError = "Capture is restricted to an explicitly authorized Shizuku shell/root process. Current UID: $uid."
            return 0
        }

        if (!isCaptureReady) {
            runCatching { output.close() }
            state = State.FAILED
            policyError = "The Shizuku process lacks the required protected call-audio permission or could not establish shell audio attribution on this Android build."
            return 0
        }

        state = State.STARTING
        policyError = ""
        val channels = try {
            engine.start(output, sampleRate, preferredChannels)
        } catch (exception: Exception) {
            runCatching { output.close() }
            policyError = "Capture initialization threw ${exception.javaClass.simpleName}: ${exception.message}"
            0
        }

        state = if (channels > 0) State.RECORDING else State.FAILED
        if (channels <= 0 && policyError.isBlank()) {
            policyError = engine.lastError.ifBlank { "VOICE_CALL capture could not be initialized." }
        }
        return channels
    }

    @Synchronized
    fun stop() {
        if (state == State.IDLE) return
        state = State.STOPPING
        val stopFailure = runCatching { engine.stop() }.exceptionOrNull()
        when {
            stopFailure != null -> {
                state = State.FAILED
                policyError = "Capture stop failed: ${stopFailure.message}"
            }
            engine.isCapturing -> {
                state = State.FAILED
                policyError = "Capture engine remained active after stop; refusing to report an idle recorder."
            }
            else -> {
                state = State.IDLE
                policyError = ""
            }
        }
    }

    private companion object {
        const val SHELL_UID = 2_000
        const val ROOT_UID = 0
    }
}