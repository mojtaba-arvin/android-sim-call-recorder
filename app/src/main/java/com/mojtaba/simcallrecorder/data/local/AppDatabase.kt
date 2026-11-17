package com.mojtaba.simcallrecorder.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mojtaba.simcallrecorder.data.local.dao.RecordingDao
import com.mojtaba.simcallrecorder.data.local.entity.RecordingEntity

@Database(
    entities = [RecordingEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
}
