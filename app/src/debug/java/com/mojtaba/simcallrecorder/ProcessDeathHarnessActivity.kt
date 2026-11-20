package com.mojtaba.simcallrecorder

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mojtaba.simcallrecorder.domain.model.CallRecord
import com.mojtaba.simcallrecorder.domain.model.CallRecordFilter
import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.sdk.SdkRecordingMode
import com.mojtaba.simcallrecorder.sdk.SimCallRecorderSdk
import com.mojtaba.simcallrecorder.sdk.SimCallRecorderSdkEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Debug-only host-driven process-death harness. The host kills the entire app process between
 * phases, so the recovery assertions run in a genuinely new Android process.
 */
internal class ProcessDeathHarnessActivity : Activity() {
    private val sdk: SimCallRecorderSdk by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            SimCallRecorderSdkEntryPoint::class.java,
        ).simCallRecorderSdk()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                when (intent?.action) {
                    ACTION_PREPARE_WINDOW_ONE -> prepareWindowOne()
                    ACTION_RECOVER_ONE_AND_PREPARE_WINDOW_TWO -> recoverWindowOneAndPrepareWindowTwo()
                    ACTION_RECOVER_TWO_AND_TEST_NEW_CAPTURE -> recoverWindowTwoAndTestNewCapture()
                    else -> error("Unknown process-death harness action: ${intent?.action}")
                }
            } catch (failure: Throwable) {
                Log.e(TAG, "HARNESS_FAILED", failure)
                finish()
            }
        }
    }

    private suspend fun prepareWindowOne() {
        startSyntheticCapture()
        val active = awaitActiveRecord()
        awaitPartialFile(active)
        Log.i(TAG, "WINDOW1_READY id=${active.id} path=${active.filePath}")
        finish()
    }

    private suspend fun recoverWindowOneAndPrepareWindowTwo() {
        val recovered = awaitRecordsWithInterruptedCount(1)
        val windowOne = recovered.filter { it.status == STATUS_INTERRUPTED }.minBy { it.id }
        check(!File(windowOne.filePath).exists()) { "Crash-window-1 must not leave a promoted WAV." }
        check(!File(windowOne.filePath + ".part").exists()) { "Crash-window-1 orphan .part was not removed." }
        Log.i(TAG, "WINDOW1_RECOVERED id=${windowOne.id}")

        startSyntheticCapture()
        val active = awaitActiveRecord(excludingIds = setOf(windowOne.id))
        awaitPartialFile(active)
        Log.i(TAG, "WINDOW2_CAPTURE_READY id=${active.id}")

        val crashStop = sdk.createStopRecordingIntent().putExtra(
            EXTRA_TEST_CRASH_AFTER_PROMOTION,
            true,
        )
        startService(crashStop)
        Log.i(TAG, "WINDOW2_STOP_REQUESTED id=${active.id}")
        // The service kills this process after atomically promoting the WAV and before Room commit.
    }

    private suspend fun recoverWindowTwoAndTestNewCapture() {
        val recovered = awaitRecordsWithInterruptedCount(2)
        val interrupted = recovered.filter { it.status == STATUS_INTERRUPTED }.sortedBy { it.id }
        val windowOne = interrupted[0]
        val windowTwo = interrupted[1]

        check(!File(windowOne.filePath).exists()) { "Crash-window-1 unexpectedly has a finalized WAV." }
        check(!File(windowOne.filePath + ".part").exists()) { "Crash-window-1 .part survived recovery." }
        val promoted = File(windowTwo.filePath)
        check(promoted.isFile) { "Crash-window-2 finalized WAV was not preserved." }
        check(promoted.inputStream().use { input -> String(ByteArray(4).also { input.read(it) }, Charsets.US_ASCII) } == "RIFF") {
            "Crash-window-2 preserved file does not have a RIFF header."
        }
        check(!File(windowTwo.filePath + ".part").exists()) { "Crash-window-2 left a stale .part file." }
        Log.i(TAG, "WINDOW2_RECOVERED id=${windowTwo.id} preserved=${promoted.absolutePath}")

        // Start capture as the first database consumer in this fresh process. The service's
        // prepareForCapture() barrier must recover before the backend creates the new .part file.
        startSyntheticCapture()
        val active = awaitActiveRecord(excludingIds = setOf(windowOne.id, windowTwo.id))
        awaitPartialFile(active)
        val afterStart = sdk.observeRecordings(CallRecordFilter()).first()
        check(afterStart.single { it.id == windowOne.id }.status == STATUS_INTERRUPTED)
        check(afterStart.single { it.id == windowTwo.id }.status == STATUS_INTERRUPTED)
        check(afterStart.single { it.id == active.id }.status == STATUS_RECORDING)
        Log.i(TAG, "RECOVERY_CAPTURE_RACE_PASSED activeId=${active.id}")

        startService(sdk.createStopRecordingIntent())
        withTimeout(45_000) {
            sdk.observeRecordings(CallRecordFilter()).first { records ->
                records.any { it.id == active.id && it.status == STATUS_COMPLETED && it.dataBytes > 0L }
            }
        }
        Log.i(TAG, "PROCESS_DEATH_SUITE_PASSED")
        finish()
    }

    private fun startSyntheticCapture() {
        val start = sdk.createStartRecordingIntent(
            mode = SdkRecordingMode.SPEAKERPHONE_MIC,
            simSlotIndex = 0,
            callState = CallState.OFFHOOK,
            participantConsentConfirmed = true,
        ).putExtra(EXTRA_TEST_DISABLE_CALL_END_WATCH, true)
        ContextCompat.startForegroundService(this, start)
    }

    private suspend fun awaitActiveRecord(excludingIds: Set<Long> = emptySet()): CallRecord =
        withTimeout(45_000) {
            sdk.observeRecordings(CallRecordFilter()).first { records ->
                records.any { it.status == STATUS_RECORDING && it.id !in excludingIds }
            }.single { it.status == STATUS_RECORDING && it.id !in excludingIds }
        }

    private suspend fun awaitPartialFile(record: CallRecord) {
        withTimeout(15_000) {
            while (!File(record.filePath + ".part").isFile) delay(100)
        }
    }

    private suspend fun awaitRecordsWithInterruptedCount(count: Int): List<CallRecord> =
        withTimeout(30_000) {
            sdk.observeRecordings(CallRecordFilter()).first { records ->
                records.count { it.status == STATUS_INTERRUPTED } >= count
            }
        }

    private companion object {
        const val TAG = "CallRecorderProcessDeath"
        const val ACTION_PREPARE_WINDOW_ONE = "com.mojtaba.simcallrecorder.debug.PREPARE_WINDOW_ONE"
        const val ACTION_RECOVER_ONE_AND_PREPARE_WINDOW_TWO =
            "com.mojtaba.simcallrecorder.debug.RECOVER_ONE_AND_PREPARE_WINDOW_TWO"
        const val ACTION_RECOVER_TWO_AND_TEST_NEW_CAPTURE =
            "com.mojtaba.simcallrecorder.debug.RECOVER_TWO_AND_TEST_NEW_CAPTURE"
        const val EXTRA_TEST_DISABLE_CALL_END_WATCH =
            "com.mojtaba.simcallrecorder.extra.TEST_DISABLE_CALL_END_WATCH"
        const val EXTRA_TEST_CRASH_AFTER_PROMOTION =
            "com.mojtaba.simcallrecorder.extra.TEST_CRASH_AFTER_PROMOTION"
        const val STATUS_RECORDING = "RECORDING"
        const val STATUS_INTERRUPTED = "INTERRUPTED"
        const val STATUS_COMPLETED = "COMPLETED"
    }
}
