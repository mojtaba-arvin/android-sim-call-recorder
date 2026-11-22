package com.mojtaba.simcallrecorder.recording

import com.mojtaba.simcallrecorder.domain.model.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneDiagnosticsPolicyTest {

    @Test
    fun allMicrophoneBackedStrategiesExposeRecordingDiagnostics() {
        assertTrue(RecordingMode.SPEAKERPHONE_MIC.usesMicrophoneRecordingDiagnostics())
        assertTrue(RecordingMode.OEM_VOICE_RECOGNITION.usesMicrophoneRecordingDiagnostics())
        assertTrue(RecordingMode.ACCESSIBILITY_VOICE_RECOGNITION.usesMicrophoneRecordingDiagnostics())
    }

    @Test
    fun nonMicrophoneStrategiesDoNotExposeMicrophoneRecordingDiagnostics() {
        assertFalse(RecordingMode.STANDARD.usesMicrophoneRecordingDiagnostics())
        assertFalse(RecordingMode.SHIZUKU_VOICE_CALL.usesMicrophoneRecordingDiagnostics())
    }

    @Test
    fun callbackSnapshotIsCorrelatedOnlyToTheActiveAudioSession() {
        val unrelated = snapshot(sessionId = 10, silenced = false)
        val active = snapshot(sessionId = 42, silenced = true)

        assertEquals(active, listOf(unrelated, active).forAudioSession(42))
        assertNull(listOf(unrelated).forAudioSession(42))
    }

    @Test
    fun callbackSnapshotReportsSilencedAndUnSilencedTransitionsWithoutClaimingTwoWayAudio() {
        val silenced = snapshot(sessionId = 42, silenced = true).toDiagnosticStatus()
        val unsilenced = snapshot(sessionId = 42, silenced = false).toDiagnosticStatus()

        assertTrue(silenced.contains("clientSilenced=true"))
        assertTrue(unsilenced.contains("clientSilenced=false"))
        assertTrue(silenced.contains("not proof of two-way call audio"))
        assertTrue(unsilenced.contains("not proof of two-way call audio"))
    }

    private fun snapshot(sessionId: Int, silenced: Boolean) = AudioRecordingDiagnosticConfiguration(
        sessionId = sessionId,
        clientSource = "MIC",
        activeSource = "MIC",
        inputDeviceType = 15,
        clientSampleRate = 16_000,
        clientChannelCount = 1,
        deviceSampleRate = 48_000,
        deviceChannelCount = 1,
        clientSilenced = silenced,
    )
}
