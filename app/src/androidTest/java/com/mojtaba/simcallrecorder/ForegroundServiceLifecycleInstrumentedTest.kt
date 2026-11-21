package com.mojtaba.simcallrecorder

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
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

    @get:Rule
    val recordAudioPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
        )

    @Test
    fun activeCaptureSurvivesActivityRecreationAndServiceDestructionFinalizesRecord() = runBlocking {
        val sdk = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SimCallRecorderSdkEntryPoint::class.java,
        ).simCallRecorderSdk()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            }
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(
                    activity,
                    sdk.createArmAutomaticRecordingIntent(
                        SdkRecordingMode.SPEAKERPHONE_MIC,
                        participantConsentConfirmed = true,
                    ),
                )
            }
            withTimeout(30_000) { sdk.automaticRecordingArmed.first { it } }
            scenario.onActivity { activity ->
                activity.startService(
                    sdk.createStartRecordingIntent(
                        mode = SdkRecordingMode.SPEAKERPHONE_MIC,
                        simSlotIndex = 0,
                        callState = CallState.OFFHOOK,
                        participantConsentConfirmed = true,
                    )
                        .putExtra("com.mojtaba.simcallrecorder.extra.AUTOMATIC_TRIGGER", true)
                        .putExtra("com.mojtaba.simcallrecorder.extra.TEST_DISABLE_CALL_END_WATCH", true),
                )
            }

            val activeStatus = awaitStatus(sdk) { it.startsWith("Recording active") }
            assertTrue(activeStatus, activeStatus.startsWith("Recording active"))
            assertTrue(
                "The foreground notification must expose the actual selected backend.",
                activeStatus.contains("Speakerphone + MIC acoustic capture"),
            )
            assertTrue(
                "The notification must retain why Shizuku was not selected.",
                activeStatus.contains("fallback: Shizuku"),
            )
            val activeRecord = sdk.observeRecordings(CallRecordFilter()).first { records ->
                records.any { it.status == "RECORDING" }
            }.single { it.status == "RECORDING" }
            assertEquals("SPEAKERPHONE_MIC", activeRecord.recordingMode)
            assertTrue(activeRecord.audioSource, activeRecord.audioSource.contains("MIC"))
            assertTrue(
                activeRecord.captureDiagnostics.orEmpty(),
                activeRecord.captureDiagnostics.orEmpty().contains("strategy=SPEAKERPHONE_MIC"),
            )
            assertTrue(
                activeRecord.captureDiagnostics.orEmpty(),
                activeRecord.captureDiagnostics.orEmpty().contains("fallbackReason=Shizuku"),
            )

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
            // Do not leak a foreground Service if an assertion fails midway through the test.
            context.stopService(sdk.createStopRecordingIntent())
            scenario.close()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun automaticMonitoringDisarmsWhenMicrophoneAppOpIsDenied() = runBlocking {
        val sdk = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SimCallRecorderSdkEntryPoint::class.java,
        ).simCallRecorderSdk()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            // Runtime permission state and AppOps state are distinct platform gates.
            // Normalize both before proving that RECORD_AUDIO revocation disarms capture.
            executeShellCommand("appops set ${context.packageName} POST_NOTIFICATION allow")
            executeShellCommand("appops set ${context.packageName} RECORD_AUDIO allow")
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(
                    activity,
                    sdk.createArmAutomaticRecordingIntent(
                        SdkRecordingMode.SPEAKERPHONE_MIC,
                        participantConsentConfirmed = true,
                    ),
                )
            }
            withTimeout(30_000) { sdk.automaticRecordingArmed.first { it } }
            scenario.onActivity { activity ->
                activity.startService(
                    sdk.createStartRecordingIntent(
                        mode = SdkRecordingMode.SPEAKERPHONE_MIC,
                        simSlotIndex = 0,
                        callState = CallState.OFFHOOK,
                        participantConsentConfirmed = true,
                    ).putExtra(
                        "com.mojtaba.simcallrecorder.extra.AUTOMATIC_TRIGGER",
                        true,
                    ),
                )
            }
            withTimeout(30_000) { sdk.recordingStatus.first { it.startsWith("Recording active") } }
            val activeRecord = sdk.observeRecordings(CallRecordFilter()).first { records ->
                records.any { it.status == "RECORDING" }
            }.single { it.status == "RECORDING" }

            executeShellCommand("appops set ${context.packageName} RECORD_AUDIO ignore")

            withTimeout(30_000) { sdk.automaticRecordingArmed.first { !it } }
            val stoppedRecord = withTimeout(30_000) {
                sdk.observeRecordings(CallRecordFilter()).first { records ->
                    records.any { it.id == activeRecord.id && it.status != "RECORDING" }
                }.single { it.id == activeRecord.id }
            }
            assertFalse("Permission revocation must leave automatic monitoring disarmed.", sdk.automaticRecordingArmed.value)
            assertTrue("Permission revocation must stop the active recording.", stoppedRecord.status != "RECORDING")
        } finally {
            executeShellCommand("appops set ${context.packageName} RECORD_AUDIO allow")
            executeShellCommand("appops set ${context.packageName} POST_NOTIFICATION allow")
            context.stopService(sdk.createStopRecordingIntent())
            scenario.close()
        }
    }

    private fun executeShellCommand(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private suspend fun awaitStatus(
        sdk: SimCallRecorderSdk,
        predicate: (String) -> Boolean,
    ): String = withTimeout(30_000) {
        sdk.recordingStatus.first(predicate)
    }


}
