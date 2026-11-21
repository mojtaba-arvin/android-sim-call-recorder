package com.mojtaba.simcallrecorder.recording

import com.mojtaba.simcallrecorder.domain.model.RecordingMode
import org.junit.Assert.assertFalse
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
}
