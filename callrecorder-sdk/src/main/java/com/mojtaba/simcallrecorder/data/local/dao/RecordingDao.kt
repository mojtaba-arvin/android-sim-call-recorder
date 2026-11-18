package com.mojtaba.simcallrecorder.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mojtaba.simcallrecorder.data.local.entity.RecordingEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY startedAtEpochMillis DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(recording: RecordingEntity): Long

    @Query("DELETE FROM recordings WHERE id = :recordingId")
    suspend fun deleteById(recordingId: Long): Int
}
