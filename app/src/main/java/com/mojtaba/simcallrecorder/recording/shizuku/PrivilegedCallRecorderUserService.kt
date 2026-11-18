package com.mojtaba.simcallrecorder.recording.shizuku

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep

/**
 * Runs only as the user-authorized Shizuku UserService identity (shell UID 2000 or root UID 0).
 * This is not an exported Android Service and is never started automatically by the app.
 */
@Keep
class PrivilegedCallRecorderUserService : IPrivilegedCallRecorder.Stub {
    private val shellAttributionReady = ShizukuShellAttribution.detachFromApplication()

    private val controller = RecorderServiceController(
        engine = AndroidVoiceCallCaptureEngine(),
        hostUid = { Process.myUid() },
        captureReady = { shellAttributionReady },
    )

    constructor() : super()

    @Keep
    constructor(context: Context) : this()

    override fun getHostUid(): Int = Process.myUid()

    override fun startVoiceCallCapture(
        output: ParcelFileDescriptor,
        sampleRate: Int,
        preferredChannels: Int,
    ): Int {
        // AIDL enters this method with the app UID as Binder.getCallingUid(). AudioRecord must
        // perform permission/AppOps checks as the actual Shizuku shell/root process instead.
        val identity = Binder.clearCallingIdentity()
        return try {
            controller.start(output, sampleRate, preferredChannels)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun stopVoiceCallCapture() {
        val identity = Binder.clearCallingIdentity()
        try {
            controller.stop()
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun isCapturing(): Boolean = controller.isRecording

    override fun getLastError(): String = controller.lastError

    override fun isCaptureReady(): Boolean = controller.isCaptureReady

    override fun destroy() {
        controller.stop()
        System.exit(0)
    }
}
