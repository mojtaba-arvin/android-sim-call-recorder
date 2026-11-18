package com.mojtaba.simcallrecorder.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.mojtaba.simcallrecorder.recording.shizuku.ShizukuAudioRecorderClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One authoritative runtime snapshot shared by the UI and foreground service.
 *
 * Shizuku is considered ready only when the live UserService reports an accepted host UID and
 * confirms that shell audio attribution was successfully established.
 */
@Singleton
class CaptureDeviceProfileProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizukuClient: ShizukuAudioRecorderClient,
) {
    fun snapshot(): CaptureDeviceProfile {
        val hostUid = shizukuClient.authorizedHostUid
        return CaptureDeviceProfile(
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            recordAudioGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
            shizukuAuthorized = hostUid == 2_000 || hostUid == 0,
            accessibilityServiceEnabled = CallAudioAccessibilityService.isEnabled(context),
            notificationListenerEnabled = false,
            voipCallNotificationPresent = false,
        )
    }
}
