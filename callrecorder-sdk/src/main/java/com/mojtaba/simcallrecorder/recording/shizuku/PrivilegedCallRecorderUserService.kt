package com.mojtaba.simcallrecorder.recording.shizuku

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep

/**
 * Runs only as the user-authorized Shizuku UserService identity (shell UID 2000 or root UID 0).
 * This is not an exported Android Service and is never started automatically by the app.
 */
@Keep
internal class PrivilegedCallRecorderUserService : IPrivilegedCallRecorder.Stub {
    private val shellAttributionReady = ShizukuShellAttribution.detachFromApplication()

    @Volatile
    private var voiceCallPermissionGranted = Process.myUid() == ROOT_UID

    private val controller = RecorderServiceController(
        engine = AndroidVoiceCallCaptureEngine(),
        hostUid = { Process.myUid() },
        captureReady = {
            shellAttributionReady &&
                hasVoiceCallCapturePermission(Process.myUid(), voiceCallPermissionGranted)
        },
    )

    constructor() : super()

    @Keep
    constructor(context: Context) : this() {
        val uid = Process.myUid()
        val captureAudioOutputGranted = hasPermission(
            context = context,
            permission = Manifest.permission.CAPTURE_AUDIO_OUTPUT,
            uid = uid,
        )
        // Android 16+ AudioPolicy checks CALL_AUDIO_INTERCEPTION first for VOICE_CALL,
        // then retains CAPTURE_AUDIO_OUTPUT as a legacy compatibility fallback.
        val callAudioInterceptionGranted = hasPermission(
            context = context,
            permission = CALL_AUDIO_INTERCEPTION_PERMISSION,
            uid = uid,
        )
        voiceCallPermissionGranted = hasVoiceCallCapturePermission(
            uid = uid,
            captureAudioOutputGranted = captureAudioOutputGranted,
            callAudioInterceptionGranted = callAudioInterceptionGranted,
        )
    }

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


private fun hasPermission(context: Context, permission: String, uid: Int): Boolean =
    runCatching {
        context.checkPermission(permission, Process.myPid(), uid) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

/**
 * Root is accepted explicitly. Shell must actually hold at least one protected permission
 * accepted by the platform's VOICE_CALL input policy:
 * CALL_AUDIO_INTERCEPTION (current Android) or CAPTURE_AUDIO_OUTPUT (legacy fallback).
 */
internal fun hasVoiceCallCapturePermission(
    uid: Int,
    captureAudioOutputGranted: Boolean,
    callAudioInterceptionGranted: Boolean,
): Boolean = uid == ROOT_UID ||
    (uid == SHELL_UID && (captureAudioOutputGranted || callAudioInterceptionGranted))

private const val SHELL_UID = 2_000
private const val ROOT_UID = 0
private const val CALL_AUDIO_INTERCEPTION_PERMISSION = "android.permission.CALL_AUDIO_INTERCEPTION"
