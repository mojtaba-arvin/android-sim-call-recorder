package com.mojtaba.simcallrecorder

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises the platform AudioRecordingCallback against a real AudioRecord session.
 *
 * This verifies framework registration and delivery for this app's microphone session only.
 * It does not establish cellular downlink access or two-way call audio.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class AudioRecordingCallbackInstrumentedTest {
    @get:Rule
    val recordAudioPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun frameworkDeliversActiveRecordingConfigurationForRegisteredSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(AudioManager::class.java)
        val sampleRate = 16_000
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        assertTrue("AudioRecord rejected the test PCM format: $minBufferBytes", minBufferBytes > 0)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            (minBufferBytes * 2).coerceAtLeast(4_096),
        )
        assertEquals(AudioRecord.STATE_INITIALIZED, recorder.state)

        val delivered = CountDownLatch(1)
        val observed = AtomicReference<AudioRecordingConfiguration?>()
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                configs.firstOrNull { it.clientAudioSessionId == recorder.audioSessionId }?.let {
                    observed.set(it)
                    delivered.countDown()
                }
            }
        }
        var registered = false
        try {
            audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
            registered = true

            recorder.startRecording()
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, recorder.recordingState)
            assertTrue(
                "AudioManager did not deliver an active configuration for AudioRecord session ${recorder.audioSessionId}",
                delivered.await(10, TimeUnit.SECONDS),
            )
            assertNotNull(observed.get())
            assertEquals(recorder.audioSessionId, observed.get()?.clientAudioSessionId)
        } finally {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
            try {
                if (registered) {
                    audioManager.unregisterAudioRecordingCallback(callback)
                }
            } finally {
                recorder.release()
            }
        }
    }
}
