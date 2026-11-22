package com.mojtaba.simcallrecorder

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojtaba.simcallrecorder.sdk.SimCallRecorderSdkEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimCallRecorderSdkIntegrationTest {
    @Test
    @Suppress("DEPRECATION")
    fun mergedManifestDeclaresAudioRoutingPermission() {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )

        assertTrue(
            packageInfo.requestedPermissions
                ?.contains(Manifest.permission.MODIFY_AUDIO_SETTINGS) == true,
        )
    }

    @Test
    fun publicFacadeIsAvailableAndExposesLiveEngineState() {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext.applicationContext
        val sdk = EntryPointAccessors.fromApplication(
            context,
            SimCallRecorderSdkEntryPoint::class.java,
        ).simCallRecorderSdk()

        assertNotNull(sdk)
        assertTrue(sdk.recordingStatus.value.isNotBlank())
        assertNotNull(sdk.automaticRecordingArmed.value)
        assertNotNull(sdk.audioInputDiagnostics.value)
        assertNotNull(sdk.shizukuConnectionStatus.value)
        assertNotNull(sdk.recordedItems.value)
        assertNotNull(sdk.callStates.value)
    }
    @Test
    fun debugProcessDeathHarnessRequiresShellDumpPermission() {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        val activity = context.packageManager.getActivityInfo(
            android.content.ComponentName(
                context.packageName,
                "${context.packageName}.ProcessDeathHarnessActivity",
            ),
            0,
        )

        assertTrue(activity.exported)
        org.junit.Assert.assertEquals(Manifest.permission.DUMP, activity.permission)
    }

}
