package com.mojtaba.simcallrecorder.core.permissions

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Permission groups are requested only when the corresponding capability is entered.
 * Holding RECORD_AUDIO or POST_NOTIFICATIONS is not required just to observe call state.
 */
internal enum class PermissionPurpose {
    CALL_STATE,
    MICROPHONE_CAPTURE,
    FOREGROUND_NOTIFICATIONS,
}

@Singleton
internal class CallPermissionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun permissionsFor(purpose: PermissionPurpose): List<String> =
        when (purpose) {
            PermissionPurpose.CALL_STATE ->
                listOf(Manifest.permission.READ_PHONE_STATE)

            PermissionPurpose.MICROPHONE_CAPTURE ->
                listOf(Manifest.permission.RECORD_AUDIO)

            PermissionPurpose.FOREGROUND_NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    listOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyList()
                }
        }

    fun missingPermissions(purpose: PermissionPurpose): List<String> =
        permissionsFor(purpose).filterNot(::isGranted)

    fun hasCallStatePermission(): Boolean =
        isGranted(Manifest.permission.READ_PHONE_STATE)

    fun hasMicrophonePermission(): Boolean {
        if (!isGranted(Manifest.permission.RECORD_AUDIO)) return false
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return when (
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_RECORD_AUDIO,
                context.applicationInfo.uid,
                context.packageName,
            )
        ) {
            AppOpsManager.MODE_ALLOWED,
            AppOpsManager.MODE_DEFAULT,
            AppOpsManager.MODE_FOREGROUND -> true
            else -> false
        }
    }

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(Manifest.permission.POST_NOTIFICATIONS)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
