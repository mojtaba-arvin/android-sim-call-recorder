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
    val frameworkSilence = when {
        record.captureDiagnostics?.contains("clientSilenced=true", ignoreCase = true) == true ->
            "Android reported clientSilenced=true during this recording; do not treat this capture as verified near-end speech."
        record.captureDiagnostics?.contains("clientSilenced=false", ignoreCase = true) == true ->
            "Android reported clientSilenced=false during this recording; this still does not prove near-end or far-end speech."
        else -> null
    }
    return buildList {
        add("Signal: $signal")
        add("PCM format: ${record.sampleRate} Hz · ${record.channelCount} channel(s)")
        add("PCM payload: $payload")
        add("Peak sample: ${record.peakSample}")
        add("RMS sample: ${record.rmsSample}")
        frameworkSilence?.let(::add)
        add("Peak/RMS and non-zero samples do not prove near-end or far-end speech.")
    }
}
