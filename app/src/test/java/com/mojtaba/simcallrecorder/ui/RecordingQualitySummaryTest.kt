package com.mojtaba.simcallrecorder.ui

import com.mojtaba.simcallrecorder.domain.model.CallRecord
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingQualitySummaryTest {
    @Test
    fun completedSignalReportShowsPcmMetricsAndEvidenceBoundary() {
        val lines = recordingQualitySummary(record(isSilent = false, dataBytes = 96_000L))
        val report = lines.joinToString("\n")

        assertTrue(report.contains("Non-zero PCM samples observed"))
        assertTrue(report.contains("16000 Hz"))
        assertTrue(report.contains("96000 bytes"))
        assertTrue(report.contains("Peak sample: 1200"))
        assertTrue(report.contains("RMS sample: 240"))
        assertTrue(report.contains("do not prove near-end or far-end speech"))
    }

    @Test
    fun silentAndUnavailableMeasurementsRemainExplicit() {
        assertTrue(recordingQualitySummary(record(isSilent = true, dataBytes = 0L)).first()
            .contains("Digital silence detected"))
        assertTrue(recordingQualitySummary(record(isSilent = null, dataBytes = 0L)).first()
            .contains("Signal measurement unavailable"))
        assertTrue(recordingQualitySummary(record(isSilent = null, dataBytes = 0L))[2]
            .contains("Unavailable"))
    }

    private fun record(isSilent: Boolean?, dataBytes: Long) = CallRecord(
        startedAtEpochMillis = 1L,
        durationMillis = 1_000L,
        phoneNumber = null,
        simSlotIndex = 0,
        filePath = "/private/recordings/test.wav",
        recordingMode = "SHIZUKU_VOICE_CALL",
        audioSource = "VOICE_CALL",
        sampleRate = 16_000,
        channelCount = 1,
        dataBytes = dataBytes,
        peakSample = 1_200,
        rmsSample = 240,
        isSilent = isSilent,
        status = if (isSilent == null) "FAILED" else "COMPLETED",
        failureReason = null,
    )
}
