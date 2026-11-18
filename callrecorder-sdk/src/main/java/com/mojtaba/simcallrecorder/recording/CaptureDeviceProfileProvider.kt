package com.mojtaba.simcallrecorder.recording

import android.Manifest
import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.mojtaba.simcallrecorder.recording.shizuku.ShizukuAudioRecorderClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One authoritative runtime snapshot shared by the UI and foreground service.
 *
 * Shizuku is considered ready only when the live UserService reports an accepted host UID,
 * confirms shell audio attribution, and verifies the protected CAPTURE_AUDIO_OUTPUT permission.
 */
@Singleton
internal class CaptureDeviceProfileProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizukuClient: ShizukuAudioRecorderClient,
) {
    fun snapshot(): CaptureDeviceProfile {
        val hostUid = shizukuClient.authorizedHostUid
        val accessibilityEnabled = runCatching {
            CallAudioAccessibilityService.isEnabled(context)
        }.getOrDefault(false)
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
        ).orEmpty().split(':')
        val expectedListener = ComponentName(context, VoipCallNotificationListenerService::class.java).flattenToString()
        val notificationListenerEnabled = expectedListener in enabledListeners

        return CaptureDeviceProfile(
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            recordAudioGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
            shizukuAuthorized = hostUid == 2_000 || hostUid == 0,
            accessibilityServiceEnabled = accessibilityEnabled,
            notificationListenerEnabled = notificationListenerEnabled,
            voipCallNotificationPresent = VoipCallNotificationRegistry.packageName != null,
        )
    }
}
