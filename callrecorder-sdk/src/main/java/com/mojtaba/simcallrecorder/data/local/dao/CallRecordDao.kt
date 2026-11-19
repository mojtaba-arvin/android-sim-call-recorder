package com.mojtaba.simcallrecorder.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mojtaba.simcallrecorder.data.local.entity.CallRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface CallRecordDao {
    @Query(
        """
        SELECT * FROM call_records
        WHERE (:simSlotIndex IS NULL OR simSlotIndex = :simSlotIndex)
          AND (:fromEpochMillis IS NULL OR startedAtEpochMillis >= :fromEpochMillis)
          AND (:toEpochMillisExclusive IS NULL OR startedAtEpochMillis < :toEpochMillisExclusive)
          AND (
              :query = ''
              OR filePath LIKE '%' || :query || '%'
              OR COALESCE(phoneNumber, '') LIKE '%' || :query || '%'
              OR recordingMode LIKE '%' || :query || '%'
          )
        ORDER BY startedAtEpochMillis DESC, id DESC
        """,
    )
    fun observeFiltered(
        simSlotIndex: Int?,
        fromEpochMillis: Long?,
        toEpochMillisExclusive: Long?,
        query: String,
    ): Flow<List<CallRecordEntity>>

    @Query("SELECT * FROM call_records WHERE id = :recordId LIMIT 1")
    suspend fun findById(recordId: Long): CallRecordEntity?

    @Query("SELECT * FROM call_records ORDER BY startedAtEpochMillis DESC, id DESC")
    fun observeAll(): Flow<List<CallRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: CallRecordEntity): Long

    @Query(
        """
        UPDATE call_records
        SET durationMillis = :durationMillis,
            dataBytes = :dataBytes,
            peakSample = :peakSample,
            rmsSample = :rmsSample,
            isSilent = :isSilent,
            status = 'COMPLETED',
            failureReason = NULL
        WHERE id = :recordId AND status = 'RECORDING'
        """,
    )
    suspend fun markCompleted(
        recordId: Long,
        durationMillis: Long,
        dataBytes: Long,
        peakSample: Int,
        rmsSample: Int,
        isSilent: Boolean,
    ): Int

    @Query(
        """
        UPDATE call_records
        SET status = :status, failureReason = :reason
        WHERE id = :recordId AND status = 'RECORDING'
        """,
    )
    suspend fun updateActiveStatus(recordId: Long, status: String, reason: String?): Int

    @Query("UPDATE call_records SET captureDiagnostics = substr(CASE WHEN captureDiagnostics IS NULL OR captureDiagnostics = '' THEN :diagnostics ELSE captureDiagnostics || char(10) || :diagnostics END, -2000) WHERE id = :recordId AND status = 'RECORDING'")
    suspend fun appendCaptureDiagnostic(recordId: Long, diagnostics: String): Int

    @Query("DELETE FROM call_records WHERE id = :recordId")
    suspend fun deleteById(recordId: Long): Int

    @Query("SELECT * FROM call_records WHERE status = 'COMPLETED' ORDER BY startedAtEpochMillis ASC, id ASC")
    suspend fun getOldestCompleted(): List<CallRecordEntity>

    @Query("SELECT COUNT(*) FROM call_records")
    suspend fun countAll(): Int
}
