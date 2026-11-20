package com.mojtaba.simcallrecorder.recording

import android.Manifest
import android.app.AppOpsManager
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
            "enabled_notification_listeners",
        ).orEmpty().split(':')
        val expectedListener = ComponentName(context, VoipCallNotificationListenerService::class.java).flattenToString()
        val notificationListenerEnabled = expectedListener in enabledListeners
        val recordAudioGranted = runCatching {
            val permissionGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val appOpMode = context.getSystemService(AppOpsManager::class.java).checkOpNoThrow(
                AppOpsManager.OPSTR_RECORD_AUDIO,
                context.applicationInfo.uid,
                context.packageName,
            )
            permissionGranted && appOpMode != AppOpsManager.MODE_IGNORED &&
                appOpMode != AppOpsManager.MODE_ERRORED
        }.getOrDefault(false)

        return CaptureDeviceProfile(
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            recordAudioGranted = recordAudioGranted,
            shizukuAuthorized = hostUid == 2_000 || hostUid == 0,
            accessibilityServiceEnabled = accessibilityEnabled,
            notificationListenerEnabled = notificationListenerEnabled,
            voipCallNotificationPresent = VoipCallNotificationRegistry.packageName != null,
        )
    }
}
