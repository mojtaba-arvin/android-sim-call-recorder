package com.mojtaba.simcallrecorder.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mojtaba.simcallrecorder.data.local.dao.CallRecordDao
import com.mojtaba.simcallrecorder.data.local.entity.CallRecordEntity

@Database(
    entities = [CallRecordEntity::class],
    version = 3,
    exportSchema = true,
)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun callRecordDao(): CallRecordDao

    companion object {
        /** Allows records screens to read while the recording service commits completion metadata. */
        val JOURNAL_MODE = JournalMode.WRITE_AHEAD_LOGGING
    }
}

/**
 * Marks sessions left active by a previous app process as interrupted when this process first
 * opens the database. This must not be called from Activity.onCreate: an Activity can be
 * recreated while a foreground recording service in the same process is still writing audio.
 */
internal class StaleRecordingRecoveryCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        db.execSQL(
            """
            UPDATE call_records
            SET status = 'INTERRUPTED',
                failureReason = 'Process stopped before finalization'
            WHERE status = 'RECORDING'
            """.trimIndent(),
        )
    }
}
