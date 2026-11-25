package com.mojtaba.simcallrecorder.domain.model

data class CallRecord(
    val id: Long = 0,
    val startedAtEpochMillis: Long,
    val durationMillis: Long?,
    val phoneNumber: String?,
    val simSlotIndex: Int,
    val filePath: String,
    val recordingMode: String,
    val audioSource: String,
    val sampleRate: Int,
    val channelCount: Int,
    val dataBytes: Long,
    val peakSample: Int,
    val rmsSample: Int,
    val isSilent: Boolean?,
    val status: String,
    val failureReason: String?,
    val captureProfile: String? = null,
    val captureDiagnostics: String? = null,
    /** Why the selected backend differs from the user's preferred/privileged path, if applicable. */
    val fallbackReason: String? = null,
)

data class CallRecordFilter(
    val simSlotIndex: Int? = null,
    val fromEpochMillis: Long? = null,
    /** Exclusive upper bound, normally midnight after the selected end date. */
    val toEpochMillisExclusive: Long? = null,
    val query: String = "",
)

internal data class CallRecordCompletion(
    val durationMillis: Long,
    val dataBytes: Long,
    val peakSample: Int,
    val rmsSample: Int,
    val isSilent: Boolean,
)
