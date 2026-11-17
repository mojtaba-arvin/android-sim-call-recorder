package com.mojtaba.simcallrecorder.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime permissions used by the initial call-state and microphone foundation.
 *
 * These permissions do not grant access to privileged cellular call audio sources.
 * READ_CALL_LOG is intentionally excluded.
 */
@Singleton
class CallPermissionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun requestablePermissions(): List<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun missingRuntimePermissions(): List<String> =
        requestablePermissions().filterNot(::isGranted)

    fun hasCallStatePermission(): Boolean =
        isGranted(Manifest.permission.READ_PHONE_STATE)

    fun hasMicrophonePermission(): Boolean =
        isGranted(Manifest.permission.RECORD_AUDIO)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
