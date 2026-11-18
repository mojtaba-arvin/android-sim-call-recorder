package com.mojtaba.simcallrecorder.recording.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.annotation.MainThread
import com.mojtaba.simcallrecorder.recording.FinalizedAudioCapture
import com.mojtaba.simcallrecorder.recording.WavCaptureResult
import com.mojtaba.simcallrecorder.recording.WavPcmFileWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShizukuAudioRecorderClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionStatus = MutableStateFlow("Not connected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    /**
     * Non-null only while the actual Shizuku UserService binder is alive and reports an
     * explicitly accepted shell/root identity. Shizuku permission alone is not capture readiness.
     */
    val authorizedHostUid: Int?
        get() {
            val service = remote ?: return null
            if (!runCatching { service.asBinder().isBinderAlive }.getOrDefault(false)) return null
            return runCatching { service.hostUid }
                .getOrNull()
                ?.takeIf { it == SHELL_UID || it == ROOT_UID }
        }

    private val _captureStatus = MutableStateFlow("Idle")
    val captureStatus: StateFlow<String> = _captureStatus.asStateFlow()

    @Volatile private var remote: IPrivilegedCallRecorder? = null
    private var serviceConnection: ServiceConnection? = null
    private var copyJob: Job? = null
    private var activeReadPipe: ParcelFileDescriptor? = null
    private var activeFile: File? = null
    private var lastCaptureResult: WavCaptureResult? = null

    @Volatile
    private var lastCaptureChannels: Int = 0

    @Volatile
    private var activeCaptureStartedAtEpochMillis: Long? = null

    @Volatile
    private var activeCaptureStartedElapsedRealtime: Long? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context, PrivilegedCallRecorderUserService::class.java),
        )
            .daemon(false)
            .processNameSuffix("voice-call-capture")
            .version(USER_SERVICE_VERSION)
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshConnectionStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        val staleConnection = serviceConnection
        remote = null
        serviceConnection = null
        if (staleConnection != null) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, staleConnection, true) }
        }
        _connectionStatus.value = "Shizuku stopped. Restart Shizuku and reconnect."
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindUserService()
                } else {
                    _connectionStatus.value = "Shizuku permission was denied."
                }
            }
        }

    init {
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener) }
        runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
        runCatching { Shizuku.addRequestPermissionResultListener(permissionResultListener) }
    }

    fun refreshConnectionStatus() {
        val existing = remote
        if (existing != null && runCatching { existing.asBinder().isBinderAlive }.getOrDefault(false)) {
            val uid = runCatching { existing.hostUid }.getOrDefault(-1)
            if (uid == SHELL_UID || uid == ROOT_UID) {
                _connectionStatus.value = if (uid == ROOT_UID) {
                    "Connected · root UID 0"
                } else {
                    "Connected · shell UID 2000"
                }
                return
            }
            remote = null
            serviceConnection = null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _connectionStatus.value = "Shizuku capture requires Android 11 or newer."
            return
        }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            _connectionStatus.value = "Shizuku is not running. Start it, then reconnect here."
            return
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            _connectionStatus.value = "This Shizuku server version is not supported."
            return
        }
        val permission = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED)
        _connectionStatus.value = if (permission == PackageManager.PERMISSION_GRANTED) {
            "Shizuku is ready. Tap Connect to start the authorized capture service."
        } else {
            "Shizuku authorization is required."
        }
    }

    @MainThread
    fun connectOrRequestPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _connectionStatus.value = "Shizuku capture requires Android 11 or newer."
            return
        }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            _connectionStatus.value = "Shizuku is not running. Start it, then tap Connect again."
            return
        }
        val permission = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            _connectionStatus.value = "Waiting for Shizuku authorization…"
            runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST) }
                .onFailure { _connectionStatus.value = "Could not request Shizuku permission: ${it.message}" }
            return
        }
        bindUserService()
    }

    @MainThread
    private fun bindUserService() {
        if (remote != null || serviceConnection != null) return
        _connectionStatus.value = "Connecting to the Shizuku recorder process…"

        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null || !binder.isBinderAlive) {
                    remote = null
                    serviceConnection = null
                    _connectionStatus.value = "Shizuku returned an invalid recorder binder."
                    return
                }
                val api = IPrivilegedCallRecorder.Stub.asInterface(binder)
                val uid = runCatching { api.hostUid }.getOrDefault(-1)
                if (uid != SHELL_UID && uid != ROOT_UID) {
                    remote = null
                    serviceConnection = null
                    _connectionStatus.value = "Recorder process has unexpected UID $uid; capture is disabled."
                    runCatching { Shizuku.unbindUserService(userServiceArgs, this, true) }
                    return
                }
                remote = api
                _connectionStatus.value = if (uid == ROOT_UID) {
                    "Connected · root UID 0"
                } else {
                    "Connected · shell UID 2000"
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                remote = null
                serviceConnection = null
                _connectionStatus.value = "Shizuku recorder process disconnected."
            }
        }

        serviceConnection = newConnection
        runCatching {
            Shizuku.bindUserService(userServiceArgs, newConnection)
        }.onFailure {
            serviceConnection = null
            _connectionStatus.value = "Could not bind Shizuku recorder: ${it.message}"
        }
    }

    suspend fun startRecording(): File = withContext(Dispatchers.IO) {
        check(copyJob?.isActive != true) { "A recording is already active." }
        val api = remote ?: error("Connect and authorize the Shizuku recorder first.")

        _captureStatus.value = "Preparing VOICE_CALL capture…"
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val destination = createRecordingFile()
        val startEpochMillis = System.currentTimeMillis()
        val startElapsedRealtime = SystemClock.elapsedRealtime()

        val channels = try {
            api.startVoiceCallCapture(writeEnd, SAMPLE_RATE, PREFERRED_CHANNELS)
        } catch (exception: Exception) {
            runCatching { readEnd.close() }
            _captureStatus.value = "Capture request failed: ${exception.message}"
            activeCaptureStartedAtEpochMillis = null
            activeCaptureStartedElapsedRealtime = null
            throw exception
        } finally {
            runCatching { writeEnd.close() }
        }

        if (channels !in 1..2) {
            runCatching { readEnd.close() }
            val reason = runCatching { api.lastError }.getOrNull().orEmpty()
            _captureStatus.value = "Capture failed: ${reason.ifBlank { "VOICE_CALL is unavailable on this device." }}"
            activeCaptureStartedAtEpochMillis = null
            activeCaptureStartedElapsedRealtime = null
            error(reason.ifBlank { "VOICE_CALL capture could not be started." })
        }

        activeReadPipe = readEnd
        activeFile = destination
        activeCaptureStartedAtEpochMillis = startEpochMillis
        activeCaptureStartedElapsedRealtime = startElapsedRealtime
        lastCaptureChannels = channels
        lastCaptureResult = null
        _captureStatus.value = "Recording active · ${channels}-channel VOICE_CALL · tap Stop to finish"

        copyJob = scope.launch {
            runCatching {
                WavPcmFileWriter.copyPcm(
                    input = ParcelFileDescriptor.AutoCloseInputStream(readEnd),
                    destination = destination,
                    sampleRate = SAMPLE_RATE,
                    channels = channels,
                )
            }.onSuccess { result ->
                lastCaptureResult = result
                val audioAssessment = if (result.isSilent) {
                    "The WAV contains only digital silence; this device did not deliver usable call audio."
                } else {
                    "Peak=${result.peakSample}, RMS=${result.rmsSample}. Both call directions still require device-level verification."
                }
                _captureStatus.value = "Saved ${result.file.name} · ${result.dataBytes} PCM bytes. $audioAssessment"
            }.onFailure { error ->
                _captureStatus.value = "Recording file was not saved: ${error.message}"
            }
        }
        destination
    }

    suspend fun stopRecording(): FinalizedAudioCapture? = withContext(Dispatchers.IO) {
        val startedAtEpochMillis = activeCaptureStartedAtEpochMillis
        val startedAtElapsedRealtime = activeCaptureStartedElapsedRealtime
        val api = remote
        val remoteStopSucceeded = api != null && runCatching {
            api.stopVoiceCallCapture()
        }.onFailure {
            _captureStatus.value = "Capture stop request failed: ${it.message}"
        }.isSuccess

        val reader = activeReadPipe
        val writerJob = copyJob
        // On a successful remote stop, let the pipe reach EOF so the copy coroutine can drain
        // every buffered PCM byte before closing the reader. Close early only if the binder died,
        // the stop transaction failed, or the writer did not finish within the bounded drain time.
        if (!remoteStopSucceeded) {
            reader?.let { runCatching { it.close() } }
        }
        val drained = writerJob?.let { job ->
            runCatching { withTimeout(PIPE_DRAIN_TIMEOUT_MS) { job.join() } }.isSuccess
        } ?: true
        if (!drained) {
            reader?.let { runCatching { it.close() } }
            writerJob?.let { job ->
                runCatching { withTimeout(PIPE_CLOSE_TIMEOUT_MS) { job.join() } }
            }
        }
        activeReadPipe = null
        copyJob = null

        val wav = lastCaptureResult
        val finalized = if (wav != null && startedAtEpochMillis != null && startedAtElapsedRealtime != null) {
            FinalizedAudioCapture(
                file = wav.file,
                startedAtEpochMillis = startedAtEpochMillis,
                durationMillis = (SystemClock.elapsedRealtime() - startedAtElapsedRealtime).coerceAtLeast(0L),
                sampleRate = SAMPLE_RATE,
                channelCount = if (wav.dataBytes > 0L) lastCaptureChannels else 0,
                dataBytes = wav.dataBytes,
                peakSample = wav.peakSample,
                rmsSample = wav.rmsSample,
                isSilent = wav.isSilent,
            )
        } else {
            null
        }

        activeCaptureStartedAtEpochMillis = null
        activeCaptureStartedElapsedRealtime = null
        if (finalized == null && _captureStatus.value.startsWith("Recording active")) {
            _captureStatus.value = "Capture stopped. No finalized WAV result was reported."
        }
        finalized
    }

    fun currentCaptureFile(): File? = lastCaptureResult?.file ?: activeFile

    fun currentCaptureStartedAtEpochMillis(): Long? = activeCaptureStartedAtEpochMillis

    fun currentCaptureChannels(): Int = lastCaptureChannels

    private fun createRecordingFile(): File {
        val folder = File(context.filesDir, "recordings")
        check(folder.exists() || folder.mkdirs()) { "Could not create the private recordings directory." }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        return File(folder, "call-$stamp.wav")
    }

    private companion object {
        const val SHIZUKU_PERMISSION_REQUEST = 5_713
        const val USER_SERVICE_VERSION = 1
        const val SHELL_UID = 2_000
        const val ROOT_UID = 0
        const val SAMPLE_RATE = 48_000
        const val PREFERRED_CHANNELS = 2
        const val PIPE_DRAIN_TIMEOUT_MS = 5_000L
        const val PIPE_CLOSE_TIMEOUT_MS = 2_000L
    }
}
