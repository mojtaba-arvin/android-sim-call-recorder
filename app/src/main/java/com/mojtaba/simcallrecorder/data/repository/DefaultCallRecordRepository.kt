package com.mojtaba.simcallrecorder.data.repository

import android.content.Context
import com.mojtaba.simcallrecorder.data.local.dao.CallRecordDao
import com.mojtaba.simcallrecorder.data.local.entity.CallRecordEntity
import com.mojtaba.simcallrecorder.domain.model.CallRecord
import com.mojtaba.simcallrecorder.domain.model.CallRecordCompletion
import com.mojtaba.simcallrecorder.domain.model.CallRecordFilter
import com.mojtaba.simcallrecorder.domain.repository.CallRecordRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultCallRecordRepository @Inject constructor(
    private val dao: CallRecordDao,
    @ApplicationContext private val context: Context,
) : CallRecordRepository {

    override fun observeRecords(filter: CallRecordFilter): Flow<List<CallRecord>> =
        dao.observeFiltered(
            simSlotIndex = filter.simSlotIndex,
            fromEpochMillis = filter.fromEpochMillis,
            toEpochMillisExclusive = filter.toEpochMillisExclusive,
            query = filter.query.trim(),
        ).map { entities -> entities.map(CallRecordEntity::toDomain) }
            .flowOn(Dispatchers.IO)

    override suspend fun createActiveRecording(record: CallRecord): Long = withContext(Dispatchers.IO) {
        require(record.status == STATUS_RECORDING) { "A new record must start in RECORDING state." }
        require(record.durationMillis == null) { "An active recording must not have a finalized duration." }
        require(record.simSlotIndex >= 0) { "A valid SIM slot is required." }
        requirePrivateRecordingPath(record.filePath)
        dao.insert(record.toEntity())
    }

    override suspend fun completeRecording(
        recordId: Long,
        completion: CallRecordCompletion,
    ): Boolean = withContext(Dispatchers.IO) {
        require(completion.durationMillis >= 0L) { "Recording duration cannot be negative." }
        require(completion.dataBytes > 0L) { "An empty audio file cannot be marked completed." }
        dao.markCompleted(
            recordId = recordId,
            durationMillis = completion.durationMillis,
            dataBytes = completion.dataBytes,
            peakSample = completion.peakSample,
            rmsSample = completion.rmsSample,
            isSilent = completion.isSilent,
        ) == 1
    }

    override suspend fun markRecordingFailed(recordId: Long, reason: String): Boolean =
        withContext(Dispatchers.IO) {
            dao.updateActiveStatus(recordId, STATUS_FAILED, reason.take(MAX_FAILURE_REASON_LENGTH)) == 1
        }

    override suspend fun markActiveRecordingsInterrupted(): Int =
        withContext(Dispatchers.IO) { dao.markAllActiveAsInterrupted() }

    override suspend fun deleteRecording(recordId: Long): Boolean = withContext(Dispatchers.IO) {
        val entity = dao.findById(recordId) ?: return@withContext false
        val deletedRows = dao.deleteById(recordId)
        if (deletedRows != 1) return@withContext false

        val file = resolvePrivateRecordingPath(entity.filePath)
        if (file != null) {
            val partial = File(file.parentFile, file.name + ".part")
            if (file.exists() && !file.delete()) return@withContext false
            if (partial.exists()) partial.delete()
        }
        true
    }

    override suspend fun countRecordings(): Int = withContext(Dispatchers.IO) {
        dao.countAll()
    }

    private fun requirePrivateRecordingPath(path: String) {
        val target = File(path).canonicalFile
        val root = recordingDirectory().canonicalFile
        require(target.parentFile == root) {
            "Recording files must be stored directly inside the app-private recordings directory."
        }
    }

    private fun resolvePrivateRecordingPath(path: String): File? = runCatching {
        val root = recordingDirectory().canonicalFile
        val supplied = File(path)
        val target = if (supplied.isAbsolute) supplied.canonicalFile else File(root, path).canonicalFile
        target.takeIf { it.parentFile == root }
    }.getOrNull()

    private fun recordingDirectory(): File = File(context.filesDir, "recordings")

    private fun CallRecord.toEntity() = CallRecordEntity(
        id = id,
        startedAtEpochMillis = startedAtEpochMillis,
        durationMillis = durationMillis,
        phoneNumber = phoneNumber,
        simSlotIndex = simSlotIndex,
        filePath = filePath,
        recordingMode = recordingMode,
        audioSource = audioSource,
        sampleRate = sampleRate,
        channelCount = channelCount,
        dataBytes = dataBytes,
        peakSample = peakSample,
        rmsSample = rmsSample,
        isSilent = isSilent,
        status = status,
        failureReason = failureReason,
    )

    private fun CallRecordEntity.toDomain() = CallRecord(
        id = id,
        startedAtEpochMillis = startedAtEpochMillis,
        durationMillis = durationMillis,
        phoneNumber = phoneNumber,
        simSlotIndex = simSlotIndex,
        filePath = filePath,
        recordingMode = recordingMode,
        audioSource = audioSource,
        sampleRate = sampleRate,
        channelCount = channelCount,
        dataBytes = dataBytes,
        peakSample = peakSample,
        rmsSample = rmsSample,
        isSilent = isSilent,
        status = status,
        failureReason = failureReason,
    )

    private companion object {
        const val STATUS_RECORDING = "RECORDING"
        const val STATUS_FAILED = "FAILED"
        const val MAX_FAILURE_REASON_LENGTH = 500
    }
}
