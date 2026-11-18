package com.mojtaba.simcallrecorder.domain.repository

import com.mojtaba.simcallrecorder.domain.model.CallRecord
import com.mojtaba.simcallrecorder.domain.model.CallRecordCompletion
import com.mojtaba.simcallrecorder.domain.model.CallRecordFilter
import kotlinx.coroutines.flow.Flow

interface CallRecordRepository {
    fun observeRecords(filter: CallRecordFilter = CallRecordFilter()): Flow<List<CallRecord>>
    suspend fun createActiveRecording(record: CallRecord): Long
    suspend fun updateCaptureDiagnostics(recordId: Long, diagnostics: String): Boolean
    suspend fun completeRecording(recordId: Long, completion: CallRecordCompletion): Boolean
    suspend fun markRecordingFailed(recordId: Long, reason: String): Boolean
    suspend fun markActiveRecordingsInterrupted(): Int
    suspend fun deleteRecording(recordId: Long): Boolean
    /** Removes oldest completed files until app-private audio usage is within this byte limit. */
    suspend fun enforceStorageLimit(maxBytes: Long): Int
    suspend fun countRecordings(): Int
}
