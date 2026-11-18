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
}
