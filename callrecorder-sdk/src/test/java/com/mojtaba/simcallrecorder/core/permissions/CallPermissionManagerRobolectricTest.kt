package com.mojtaba.simcallrecorder.core.permissions

import android.Manifest
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallPermissionManagerRobolectricTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun permissionPurposesRequestOnlyTheCapabilityTheyNeed() {
        val manager = CallPermissionManager(context)

        assertEquals(
            listOf(Manifest.permission.READ_PHONE_STATE),
            manager.permissionsFor(PermissionPurpose.CALL_STATE),
        )
        assertEquals(
            listOf(Manifest.permission.RECORD_AUDIO),
            manager.permissionsFor(PermissionPurpose.MICROPHONE_CAPTURE),
        )
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            manager.permissionsFor(PermissionPurpose.FOREGROUND_NOTIFICATIONS),
        )
    }

    @Test
    fun callStatePermissionRevocationIsObservedOnTheNextCheck() {
        val manager = CallPermissionManager(context)
        val shadow = Shadows.shadowOf(context as android.app.Application)

        shadow.grantPermissions(Manifest.permission.READ_PHONE_STATE)
        assertTrue(manager.hasCallStatePermission())
        assertTrue(manager.missingPermissions(PermissionPurpose.CALL_STATE).isEmpty())

        shadow.denyPermissions(Manifest.permission.READ_PHONE_STATE)
        assertFalse(manager.hasCallStatePermission())
        assertEquals(
            listOf(Manifest.permission.READ_PHONE_STATE),
            manager.missingPermissions(PermissionPurpose.CALL_STATE),
        )
    }

    @Test
    fun microphonePermissionRevocationIsReflectedInMissingPermissions() {
        val manager = CallPermissionManager(context)
        val shadow = Shadows.shadowOf(context as android.app.Application)

        shadow.grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(manager.missingPermissions(PermissionPurpose.MICROPHONE_CAPTURE).isEmpty())

        shadow.denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(
            listOf(Manifest.permission.RECORD_AUDIO),
            manager.missingPermissions(PermissionPurpose.MICROPHONE_CAPTURE),
        )
    }

    @Test
    @Config(sdk = [32])
    fun notificationPermissionIsNotRequiredBeforeAndroid13() {
        val manager = CallPermissionManager(context)
        val shadow = Shadows.shadowOf(context as android.app.Application)
        shadow.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(manager.permissionsFor(PermissionPurpose.FOREGROUND_NOTIFICATIONS).isEmpty())
        assertTrue(manager.hasNotificationPermission())
    }

    @Test
    @Config(sdk = [33, 34, 35, 36, 37])
    fun notificationPermissionRevocationIsObservedOnAndroid13AndLater() {
        val manager = CallPermissionManager(context)
        val shadow = Shadows.shadowOf(context as android.app.Application)

        shadow.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(manager.hasNotificationPermission())

        shadow.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(manager.hasNotificationPermission())
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            manager.missingPermissions(PermissionPurpose.FOREGROUND_NOTIFICATIONS),
        )
    }
}
