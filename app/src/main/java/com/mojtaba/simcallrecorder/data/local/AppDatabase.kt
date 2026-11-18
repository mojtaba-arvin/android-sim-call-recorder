package com.mojtaba.simcallrecorder.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mojtaba.simcallrecorder.data.local.dao.CallRecordDao
import com.mojtaba.simcallrecorder.data.local.entity.CallRecordEntity

@Database(
    entities = [CallRecordEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callRecordDao(): CallRecordDao

    companion object {
        /** Allows records screens to read while the recording service commits completion metadata. */
        val JOURNAL_MODE = JournalMode.WRITE_AHEAD_LOGGING
    }
}
