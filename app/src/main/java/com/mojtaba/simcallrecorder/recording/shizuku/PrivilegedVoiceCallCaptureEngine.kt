package com.mojtaba.simcallrecorder.recording.shizuku

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

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

/**
 * Only instantiated inside the Shizuku UserService process (normally UID 2000).
 *
 * There is intentionally no MIC / DEFAULT / VOICE_COMMUNICATION fallback. Those sources do not
 * grant cellular call uplink/downlink access. If VOICE_CALL cannot be opened under the authorized
 * shell/root identity, the session fails closed instead of writing a misleading microphone file.
 */
internal class AndroidVoiceCallCaptureEngine : VoiceCallCaptureEngine {
    private val lock = Any()

    @Volatile
    private var active = false

    @Volatile
    private var currentRecord: AudioRecord? = null

    @Volatile
    private var writerThread: Thread? = null

    @Volatile
    private var stopRequested = AtomicBoolean(false)

    @Volatile
    override var lastError: String = ""
        private set

    override val isCapturing: Boolean
        get() = active

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

            for ((channelMask, channelCount) in candidates) {
                val minBufferBytes = runCatching {
                    AudioRecord.getMinBufferSize(
                        sampleRate,
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                }.getOrElse {
                    lastError = "Could not query AudioRecord buffer size: ${it.message}"
                    continue
                }

                if (minBufferBytes <= 0) {
                    lastError = "AudioRecord rejected the requested sample rate/channel format."
                    continue
                }

                val record = runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_CALL,
                        sampleRate,
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        max(minBufferBytes * 4, sampleRate * channelCount),
                    )
                }.getOrElse {
                    lastError = "VOICE_CALL AudioRecord creation failed: ${it.javaClass.simpleName}: ${it.message}"
                    continue
                }

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    runCatching { record.release() }
                    lastError = "VOICE_CALL AudioRecord did not initialize for ${channelCount}-channel PCM."
                    continue
                }

                val sink = runCatching {
                    ParcelFileDescriptor.dup(output.fileDescriptor)
                }.getOrElse {
                    runCatching { record.release() }
                    lastError = "Could not duplicate the capture output pipe: ${it.message}"
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
                    lastError = "VOICE_CALL start failed: ${exception.javaClass.simpleName}: ${exception.message}"
                    continue
                }

                closeQuietly(output)
                stopRequested = AtomicBoolean(false)
                currentRecord = record
                active = true
                lastError = ""

                writerThread = Thread(
                    { drainAudioRecord(record, sink) },
                    "privileged-voice-call-capture",
                ).apply {
                    isDaemon = true
                    start()
                }

                return channelCount
            }

            closeQuietly(output)
            if (lastError.isBlank()) {
                lastError = "No supported VOICE_CALL AudioRecord configuration was available."
            }
            return 0
        }
    }

    private fun drainAudioRecord(
        record: AudioRecord,
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

        state = State.STARTING
        policyError = ""
        val channels = runCatching {
            engine.start(output, sampleRate, preferredChannels)
        }.getOrElse {
            policyError = "Capture initialization threw ${it.javaClass.simpleName}: ${it.message}"
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
        runCatching { engine.stop() }
            .onFailure { policyError = "Capture stop failed: ${it.message}" }
        state = State.IDLE
    }

    private companion object {
        const val SHELL_UID = 2_000
        const val ROOT_UID = 0
    }
}
