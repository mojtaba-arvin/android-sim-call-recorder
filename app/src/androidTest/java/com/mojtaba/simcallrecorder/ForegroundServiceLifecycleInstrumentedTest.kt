package com.mojtaba.simcallrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojtaba.simcallrecorder.domain.model.CallRecordFilter
import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.sdk.SdkRecordingMode
import com.mojtaba.simcallrecorder.sdk.SimCallRecorderSdk
import com.mojtaba.simcallrecorder.sdk.SimCallRecorderSdkEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Android foreground Service and Activity lifecycle with an emulator microphone.
 *
 * The OFFHOOK event is intentionally synthetic: these tests validate Android Service ownership,
 * Activity recreation, and WAV/Room teardown, not cellular telephony or two-way call audio.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundServiceLifecycleInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun activeCaptureSurvivesActivityRecreationAndServiceDestructionFinalizesRecord() = runBlocking {
        grantCapturePermissions()
        val sdk = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SimCallRecorderSdkEntryPoint::class.java,
        ).simCallRecorderSdk()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(
                    activity,
                    sdk.createStartRecordingIntent(
                        mode = SdkRecordingMode.SPEAKERPHONE_MIC,
                        simSlotIndex = 0,
                        callState = CallState.OFFHOOK,
                        participantConsentConfirmed = true,
                    ),
                )
            }

            val activeStatus = awaitStatus(sdk) { it.startsWith("Recording active") }
            assertTrue(activeStatus, activeStatus.startsWith("Recording active"))

            // Recreating the UI must not stop or replace the independent foreground Service.
            scenario.recreate()
            val afterRecreation = awaitStatus(sdk) { it.startsWith("Recording active") }
            assertEquals(activeStatus, afterRecreation)

            // Give AudioRecord/WAV writer enough time to persist real PCM before teardown.
            delay(1_500)

            // This bypasses the normal ACTION_STOP path and exercises Service.onDestroy().
            scenario.onActivity { activity ->
                activity.stopService(sdk.createStopRecordingIntent())
            }

            val completed = withTimeout(30_000) {
                sdk.observeRecordings(CallRecordFilter()).first { records ->
                    records.any { it.status == "COMPLETED" && it.dataBytes > 0L }
                }
            }
            assertTrue(
                "Service destruction must finalize a non-empty WAV and commit its Room row.",
                completed.any { it.status == "COMPLETED" && it.dataBytes > 0L },
            )
        } finally {
            scenario.close()
        }
    }

    private suspend fun awaitStatus(
        sdk: SimCallRecorderSdk,
        predicate: (String) -> Boolean,
    ): String = withTimeout(30_000) {
        sdk.recordingStatus.first(predicate)
    }

    private fun grantCapturePermissions() {
        val packageName = context.packageName
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissions.forEach { permission ->
            val result = instrumentation.uiAutomation.executeShellCommand(
                "pm grant $packageName $permission",
            )
            result.close()
            assertEquals(
                "Test requires $permission to exercise the microphone foreground service.",
                PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(permission),
            )
        }
    }
}
