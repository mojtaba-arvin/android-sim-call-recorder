package com.mojtaba.simcallrecorder.recording.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep

/**
 * Runs only as the user-authorized Shizuku UserService identity (shell UID 2000 or root UID 0).
 * This is not an exported Android Service and is never started automatically by the app.
 */
@Keep
class PrivilegedCallRecorderUserService : IPrivilegedCallRecorder.Stub {
    private val controller = RecorderServiceController(
        engine = AndroidVoiceCallCaptureEngine(),
        hostUid = { Process.myUid() },
    )

    constructor() : super()

    @Keep
    constructor(context: Context) : this()

    override fun getHostUid(): Int = Process.myUid()

    override fun startVoiceCallCapture(
        output: ParcelFileDescriptor,
        sampleRate: Int,
        preferredChannels: Int,
    ): Int = controller.start(output, sampleRate, preferredChannels)

    override fun stopVoiceCallCapture() {
        controller.stop()
    }

    override fun isCapturing(): Boolean = controller.isRecording

    override fun getLastError(): String = controller.lastError

    override fun destroy() {
        controller.stop()
        System.exit(0)
    }
}
