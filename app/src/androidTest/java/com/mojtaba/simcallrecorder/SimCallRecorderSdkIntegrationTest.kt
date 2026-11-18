package com.mojtaba.simcallrecorder

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojtaba.simcallrecorder.sdk.SimCallRecorderSdk
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimCallRecorderSdkIntegrationTest {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SdkEntryPoint {
        fun sdk(): SimCallRecorderSdk
    }

    @Test
    fun publicFacadeIsAvailableAndExposesLiveEngineState() {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext.applicationContext
        val sdk = EntryPointAccessors.fromApplication(context, SdkEntryPoint::class.java).sdk()

        assertNotNull(sdk)
        assertEquals("Idle", sdk.recordingStatus.value)
        assertNotNull(sdk.automaticRecordingArmed.value)
        assertNotNull(sdk.audioInputDiagnostics.value)
        assertNotNull(sdk.shizukuConnectionStatus.value)
        assertNotNull(sdk.recordedItems.value)
        assertNotNull(sdk.callStates.value)
    }
}
