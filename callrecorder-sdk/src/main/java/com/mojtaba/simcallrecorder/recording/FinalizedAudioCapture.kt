package com.mojtaba.simcallrecorder.recording

import java.io.File

data class FinalizedAudioCapture(
    val file: File,
    val startedAtEpochMillis: Long,
    val durationMillis: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val dataBytes: Long,
    val peakSample: Int,
    val rmsSample: Int,
    val isSilent: Boolean,
)
