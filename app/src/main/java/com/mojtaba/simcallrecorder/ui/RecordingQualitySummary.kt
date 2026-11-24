package com.mojtaba.simcallrecorder.ui

import com.mojtaba.simcallrecorder.domain.model.CallRecord

/** Signal-level metadata only; these values do not establish speech or two-way call audio. */
internal fun recordingQualitySummary(record: CallRecord): List<String> {
    val signal = when (record.isSilent) {
        true -> "Digital silence detected"
        false -> "Non-zero PCM samples observed; speech is not verified"
        null -> "Signal measurement unavailable"
    }
    val payload = if (record.dataBytes > 0L) "${record.dataBytes} bytes" else "Unavailable"
    return listOf(
        "Signal: $signal",
        "PCM format: ${record.sampleRate} Hz · ${record.channelCount} channel(s)",
        "PCM payload: $payload",
        "Peak sample: ${record.peakSample}",
        "RMS sample: ${record.rmsSample}",
        "Peak/RMS and non-zero samples do not prove near-end or far-end speech.",
    )
}
