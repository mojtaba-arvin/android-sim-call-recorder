package com.mojtaba.simcallrecorder.consumer

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.mojtaba.simcallrecorder.domain.model.CallRecord
import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.sdk.SdkPermissionPurpose
import com.mojtaba.simcallrecorder.sdk.SdkRecordingMode
import com.mojtaba.simcallrecorder.sdk.SimCallRecorderSdk
import com.mojtaba.simcallrecorder.sdk.SimCallRecorderSdkEntryPoint
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@HiltAndroidApp
class ConsumerSmokeApplication : Application()

@AndroidEntryPoint
class ConsumerSmokeActivity : Activity() {
    @Inject lateinit var sdk: SimCallRecorderSdk

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Compile-time contract: only documented public SDK packages and public domain models.
        val entryPointSdk = EntryPointAccessors.fromApplication(
            applicationContext,
            SimCallRecorderSdkEntryPoint::class.java,
        ).simCallRecorderSdk()
        check(entryPointSdk === sdk) { "Hilt injection and the documented SDK entry point must resolve the same singleton." }
        val status: StateFlow<String> = sdk.recordingStatus
        val automaticArmed: StateFlow<Boolean> = sdk.automaticRecordingArmed
        val recordings: StateFlow<List<CallRecord>> = sdk.recordedItems
        val filtered: Flow<List<CallRecord>> = sdk.observeRecordings()
        val permissionNames: List<String> = sdk.permissionsFor(SdkPermissionPurpose.CALL_STATE)
        val missing: List<String> = sdk.missingPermissions(SdkPermissionPurpose.CALL_STATE)
        val mode: SdkRecordingMode = SdkRecordingMode.SHIZUKU_VOICE_CALL
        val strategy = sdk.captureStrategySnapshot()
        val stopIntent = sdk.createStopRecordingIntent()
        val startIntent = sdk.createStartRecordingIntent(
            mode = mode,
            simSlotIndex = 0,
            callState = CallState.OFFHOOK,
            participantConsentConfirmed = true,
        )

        setContentView(
            TextView(this).apply {
                text = "SDK AAR consumer compile smoke: " +
                    "status=${status.value}, armed=${automaticArmed.value}, " +
                    "recordings=${recordings.value.size}, filtered=${filtered.javaClass.simpleName}, " +
                    "permissions=${permissionNames.size}, missing=${missing.size}, " +
                    "strategies=${strategy.options.size}, intents=${stopIntent.action}/${startIntent.action}"
            },
        )
    }
}
