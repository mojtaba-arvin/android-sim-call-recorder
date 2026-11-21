package com.mojtaba.simcallrecorder.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "call_records",
    indices = [
        Index(value = ["startedAtEpochMillis"]),
        Index(value = ["simSlotIndex", "startedAtEpochMillis"]),
        Index(value = ["status"]),
    ],
)
internal data class CallRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startedAtEpochMillis: Long,
    val durationMillis: Long?,
    /** Nullable: call-state APIs intentionally do not expose a number on every supported API. */
    val phoneNumber: String?,
    val simSlotIndex: Int,
    /** Absolute path under the app-private filesDir/recordings directory. */
    val filePath: String,
    val recordingMode: String,
    val audioSource: String,
    val sampleRate: Int,
    val channelCount: Int,
    val dataBytes: Long,
    val peakSample: Int,
    val rmsSample: Int,
    val isSilent: Boolean?,
    /** RECORDING, COMPLETED, INTERRUPTED, FAILED, or LEGACY. */
    val status: String,
    val failureReason: String?,
    /** Runtime device/API/permission profile captured when this session started. */
    val captureProfile: String? = null,
    /** Latest matching AudioRecordingConfiguration snapshot; null for non-microphone backends. */
    val captureDiagnostics: String? = null,
)
