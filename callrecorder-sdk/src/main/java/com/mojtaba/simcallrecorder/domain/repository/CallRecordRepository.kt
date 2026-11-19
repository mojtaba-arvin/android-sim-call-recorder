package com.mojtaba.simcallrecorder.domain.repository

import com.mojtaba.simcallrecorder.domain.model.CallRecord
import com.mojtaba.simcallrecorder.domain.model.CallRecordCompletion
import com.mojtaba.simcallrecorder.domain.model.CallRecordFilter
import kotlinx.coroutines.flow.Flow

internal interface CallRecordRepository {
    fun observeRecords(filter: CallRecordFilter = CallRecordFilter()): Flow<List<CallRecord>>
    suspend fun createActiveRecording(record: CallRecord): Long
    suspend fun appendCaptureDiagnostic(recordId: Long, diagnostic: String): Boolean
    suspend fun completeRecording(recordId: Long, completion: CallRecordCompletion): Boolean
    suspend fun markRecordingFailed(recordId: Long, reason: String): Boolean
    suspend fun deleteRecording(recordId: Long): Boolean
    /** Removes oldest completed files until app-private audio usage is within this byte limit. */
    suspend fun enforceStorageLimit(maxBytes: Long): Int
    suspend fun countRecordings(): Int
    /** Forces first-open process recovery to finish before a capture backend can create files. */
    suspend fun prepareForCapture()
}
