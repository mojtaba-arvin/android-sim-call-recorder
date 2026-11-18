package com.mojtaba.simcallrecorder.recording

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.annotation.SuppressLint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.max

/**
 * Process-wide diagnostic snapshot for the currently active app-UID microphone capture.
 * An unsilenced configuration is not evidence that remote call audio is present.
 */
object AudioRecordingDiagnostics {
    private val _status = MutableStateFlow("No microphone capture active")
    val status: StateFlow<String> = _status.asStateFlow()

    internal fun update(value: String) {
        _status.value = value
    }
}

class SpeakerphoneMicCallCaptureStrategy @Inject constructor(
    @ApplicationContext context: Context,
) : CallAudioRecordingBackend {
    private val session = MicrophoneCaptureSession(
        context = context,
        mode = RecordingMode.SPEAKERPHONE_MIC,
        displayName = "Speakerphone + MIC",
        audioSource = MediaRecorder.AudioSource.MIC,
        audioSources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
        ),
        routeToSpeaker = true,
        useCommunicationMode = true,
    )

    override val mode get() = session.mode
    override val displayName get() = session.displayName
    override val audioSourceLabel get() = session.audioSourceLabel
    override suspend fun start(request: RecordingStartRequest): File = session.start(request)
    override suspend fun stop(): FinalizedAudioCapture? = session.stop()
}

class OemVoiceRecognitionCaptureStrategy @Inject constructor(
    @ApplicationContext context: Context,
) : CallAudioRecordingBackend {
    private val session = MicrophoneCaptureSession(
        context = context,
        mode = RecordingMode.OEM_VOICE_RECOGNITION,
        displayName = "OEM experiment · VOICE_RECOGNITION",
        audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
        routeToSpeaker = false,
        useCommunicationMode = true,
    )

    override val mode get() = session.mode
    override val displayName get() = session.displayName
    override val audioSourceLabel = "VOICE_RECOGNITION + MODE_IN_COMMUNICATION (microphone input)"
    override suspend fun start(request: RecordingStartRequest): File = session.start(request)
    override suspend fun stop(): FinalizedAudioCapture? = session.stop()
}

class AccessibilityVoiceRecognitionCaptureStrategy @Inject constructor(
    @ApplicationContext private val context: Context,
) : CallAudioRecordingBackend {
    private val session = MicrophoneCaptureSession(
        context = context,
        mode = RecordingMode.ACCESSIBILITY_VOICE_RECOGNITION,
        displayName = "Accessibility · VOICE_RECOGNITION",
        audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
        routeToSpeaker = true,
        useCommunicationMode = true,
    )

    override val mode get() = session.mode
    override val displayName get() = session.displayName
    override val audioSourceLabel = "Accessibility VOICE_RECOGNITION + acoustic speaker path"

    override suspend fun start(request: RecordingStartRequest): File {
        check(CallAudioAccessibilityService.isEnabled(context)) {
            "Enable the SIM Call Recorder accessibility service in Android Settings before selecting this strategy."
        }
        return session.start(request)
    }

    override suspend fun stop(): FinalizedAudioCapture? = session.stop()
}

/**
 * App-UID microphone recorder used only by explicitly selected experimental strategies.
 * It applies available platform AEC/NS/AGC effects, writes PCM into the same validated WAV writer,
 * and restores the previous audio mode/communication route when the session ends.
 */
private class MicrophoneCaptureSession(
    private val context: Context,
    val mode: RecordingMode,
    val displayName: String,
    private val audioSource: Int,
    private val audioSources: List<Int> = listOf(audioSource),
    private val routeToSpeaker: Boolean,
    private val useCommunicationMode: Boolean,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var writerThread: Thread? = null
    @Volatile private var pipeOutput: PipedOutputStream? = null
    @Volatile private var wavResult: WavCaptureResult? = null
    @Volatile private var writerFailure: Throwable? = null
    @Volatile private var startedAtEpochMillis: Long = 0L
    @Volatile private var activeAudioSource: Int = audioSource
    @Volatile private var sourceFallbackReason: String? = null
    @Volatile private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null

    val audioSourceLabel: String
        get() {
            val fallback = sourceFallbackReason?.let { " · fallback: $it" }.orEmpty()
            return "${audioSourceName(activeAudioSource)} + speaker route request (acoustic; not digital downlink)$fallback"
        }
    @Volatile private var previousMode: Int? = null
    @Volatile private var previousSpeakerphone: Boolean? = null
    @Volatile private var previousCommunicationDevice: AudioDeviceInfo? = null
    private var effects: List<AudioEffect> = emptyList()

    @SuppressLint("MissingPermission")
    suspend fun start(request: RecordingStartRequest): File = withContext(Dispatchers.IO) {
        require(request.mode == mode) { "Recording mode does not match this capture strategy." }
        require(request.callState.name == "OFFHOOK") { "An active call is required." }
        require(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) { "Microphone permission is required for this experimental strategy." }
        check(running.compareAndSet(false, true)) { "This capture strategy is already active." }

        val sampleRate = 16_000
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            running.set(false)
            throw IllegalStateException("AudioRecord rejected 16 kHz mono PCM on this device.")
        }

        val outputDir = File(context.filesDir, "recordings").apply {
            check(exists() || mkdirs()) { "Could not create the private recordings directory." }
        }
        val outputFile = File(outputDir, "capture_${System.currentTimeMillis()}_${mode.name.lowercase()}.wav")
        var selectedRecord: AudioRecord? = null
        try {
            applyAudioRoute()
            val failures = mutableListOf<String>()
            for (source in audioSources.distinct()) {
                val candidate = try {
                    AudioRecord(
                        source,
                        sampleRate,
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        max(minBufferBytes * 4, sampleRate),
                    )
                } catch (error: Throwable) {
                    failures += "${audioSourceName(source)}: ${error.message ?: error::class.java.simpleName}"
                    continue
                }

                if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                    failures += "${audioSourceName(source)}: initialization rejected"
                    runCatching { candidate.release() }
                    continue
                }

                val started = runCatching {
                    candidate.startRecording()
                    candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING
                }.getOrDefault(false)
                if (started) {
                    selectedRecord = candidate
                    activeAudioSource = source
                    sourceFallbackReason = if (source == audioSources.firstOrNull()) {
                        null
                    } else {
                        failures.joinToString("; ")
                    }
                    break
                }

                failures += "${audioSourceName(source)}: startRecording was rejected"
                runCatching { candidate.stop() }
                runCatching { candidate.release() }
            }

            val record = selectedRecord
                ?: throw IllegalStateException(
                    "No microphone source could start for $displayName. ${failures.joinToString("; ")}",
                )
            recorder = record
            registerAudioRecordingDiagnostics(record)
            startedAtEpochMillis = System.currentTimeMillis()
            wavResult = null
            writerFailure = null

            val input = PipedInputStream(64 * 1024)
            val output = PipedOutputStream(input)
            pipeOutput = output
            writerThread = Thread(
                {
                    try {
                        wavResult = WavPcmFileWriter.copyPcm(input, outputFile, sampleRate, 1)
                    } catch (failure: Throwable) {
                        writerFailure = failure
                    }
                },
                "call-mic-wav-writer",
            ).apply {
                isDaemon = true
                start()
            }

            val effectsList = mutableListOf<AudioEffect>()
            if (AcousticEchoCanceler.isAvailable()) {
                runCatching { AcousticEchoCanceler.create(record.audioSessionId) }
                    .getOrNull()?.also { effect -> runCatching { effect.enabled = true }; effectsList += effect }
            }
            if (NoiseSuppressor.isAvailable()) {
                runCatching { NoiseSuppressor.create(record.audioSessionId) }
                    .getOrNull()?.also { effect -> runCatching { effect.enabled = true }; effectsList += effect }
            }
            if (AutomaticGainControl.isAvailable()) {
                runCatching { AutomaticGainControl.create(record.audioSessionId) }
                    .getOrNull()?.also { effect -> runCatching { effect.enabled = true }; effectsList += effect }
            }
            effects = effectsList

            captureThread = Thread(
                {
                    val buffer = ByteArray(8 * 1024)
                    try {
                        while (running.get()) {
                            val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                            if (count > 0) {
                                output.write(buffer, 0, count)
                            } else if (count < 0 && running.get()) {
                                throw IllegalStateException("AudioRecord.read failed with code $count.")
                            }
                        }
                    } catch (failure: Throwable) {
                        if (running.get()) writerFailure = failure
                    } finally {
                        runCatching { output.flush() }
                        runCatching { output.close() }
                    }
                },
                "call-mic-audio-capture",
            ).apply {
                isDaemon = true
                start()
            }
            outputFile
        } catch (failure: Throwable) {
            running.set(false)
            selectedRecord?.let { record ->
                runCatching { record.stop() }
                runCatching { record.release() }
            }
            runCatching { pipeOutput?.close() }
            unregisterAudioRecordingDiagnostics()
            restoreAudioRoute()
            throw failure
        }
    }

    suspend fun stop(): FinalizedAudioCapture? = withContext(Dispatchers.IO) {
        if (!running.getAndSet(false)) return@withContext null

        unregisterAudioRecordingDiagnostics()
        val record = recorder
        if (record != null) {
            runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
            runCatching { record.release() }
        }
        captureThread?.join(3_000L)
        runCatching { pipeOutput?.close() }
        writerThread?.join(5_000L)
        effects.forEach { effect -> runCatching { effect.release() } }
        effects = emptyList()
        restoreAudioRoute()

        check(captureThread?.isAlive != true) { "Microphone capture thread did not stop cleanly." }
        check(writerThread?.isAlive != true) { "WAV writer did not finish before the stop timeout." }
        writerFailure?.let { throw IllegalStateException("Microphone capture failed: ${it.message}", it) }
        val result = wavResult ?: return@withContext null
        recorder = null
        captureThread = null
        writerThread = null
        pipeOutput = null

        FinalizedAudioCapture(
            file = result.file,
            startedAtEpochMillis = startedAtEpochMillis,
            durationMillis = (System.currentTimeMillis() - startedAtEpochMillis).coerceAtLeast(0L),
            sampleRate = 16_000,
            channelCount = 1,
            dataBytes = result.dataBytes,
            peakSample = result.peakSample,
            rmsSample = result.rmsSample,
            isSilent = result.isSilent,
        )
    }

    private fun registerAudioRecordingDiagnostics(record: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            AudioRecordingDiagnostics.update("AudioRecordingCallback requires Android 10 or newer.")
            return
        }
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val configuration = configs.firstOrNull {
                    it.clientAudioSessionId == record.audioSessionId
                }
                if (configuration == null) {
                    AudioRecordingDiagnostics.update(
                        "No active framework recording configuration for session ${record.audioSessionId}.",
                    )
                    return
                }
                val device = configuration.audioDevice?.let {
                    "${it.productName} (type=${it.type})"
                } ?: "unknown input device"
                AudioRecordingDiagnostics.update(
                    "session=${configuration.clientAudioSessionId}; " +
                        "clientSource=${audioSourceName(configuration.clientAudioSource)}; " +
                        "activeSource=${audioSourceName(configuration.audioSource)}; " +
                        "device=$device; " +
                        "clientFormat=${configuration.clientFormat.sampleRate}Hz/" +
                        "${configuration.clientFormat.channelCount}ch; " +
                        "deviceFormat=${configuration.format.sampleRate}Hz/" +
                        "${configuration.format.channelCount}ch; " +
                        "clientSilenced=${configuration.isClientSilenced}",
                )
            }
        }
        AudioRecordingDiagnostics.update("Waiting for framework recording configuration · session ${record.audioSessionId}")
        audioRecordingCallback = callback
        runCatching {
            audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        }.onFailure {
            audioRecordingCallback = null
            AudioRecordingDiagnostics.update("Could not register AudioRecordingCallback: ${it.message}")
        }
    }

    private fun unregisterAudioRecordingDiagnostics() {
        val callback = audioRecordingCallback ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { audioManager.unregisterAudioRecordingCallback(callback) }
        }
        audioRecordingCallback = null
        AudioRecordingDiagnostics.update("No microphone capture active")
    }

    private fun audioSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        else -> "AudioSource($source)"
    }

    private fun applyAudioRoute() {
        previousMode = audioManager.mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            previousCommunicationDevice = audioManager.communicationDevice
        } else {
            @Suppress("DEPRECATION")
            previousSpeakerphone = audioManager.isSpeakerphoneOn
        }

        if (useCommunicationMode) {
            runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        }
        if (routeToSpeaker) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && it.isSink
                }
                if (speaker != null) runCatching { audioManager.setCommunicationDevice(speaker) }
            } else {
                @Suppress("DEPRECATION")
                runCatching { audioManager.isSpeakerphoneOn = true }
            }
        }
    }

    private fun restoreAudioRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val previous = previousCommunicationDevice
                if (previous != null) audioManager.setCommunicationDevice(previous)
                else audioManager.clearCommunicationDevice()
            }
        } else {
            previousSpeakerphone?.let { old ->
                @Suppress("DEPRECATION")
                runCatching { audioManager.isSpeakerphoneOn = old }
            }
        }
        previousMode?.let { old -> runCatching { audioManager.mode = old } }
        previousMode = null
        previousSpeakerphone = null
        previousCommunicationDevice = null
    }
}
