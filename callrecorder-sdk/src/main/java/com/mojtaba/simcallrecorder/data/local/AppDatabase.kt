package com.mojtaba.simcallrecorder.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import java.io.File
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mojtaba.simcallrecorder.data.local.dao.CallRecordDao
import com.mojtaba.simcallrecorder.data.local.entity.CallRecordEntity

@Database(
    entities = [CallRecordEntity::class],
    version = 4,
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
 *
 * Room may invoke onOpen again if its database connection is reopened. Keep the recovery
 * idempotent for the lifetime of this callback (the database is a process singleton).
 */
internal class StaleRecordingRecoveryCallback(
    private val recordingDirectory: File? = null,
) : RoomDatabase.Callback() {
    private val recoveryLock = Any()
    private var recoveryCompleted = false

    private fun cleanupStalePartialFiles(db: SupportSQLiteDatabase) {
        val root = recordingDirectory?.let { runCatching { it.canonicalFile }.getOrNull() } ?: return
        db.query("SELECT filePath FROM call_records WHERE status = 'RECORDING'").use { cursor ->
            val partialFiles = buildList {
                while (cursor.moveToNext()) {
                    val target = runCatching { File(cursor.getString(0)).canonicalFile }.getOrNull()
                        ?: continue
                    if (target.parentFile != root || !target.name.endsWith(".wav")) continue
                    val partial = File(root, target.name + ".part")
                    if (runCatching { partial.canonicalFile.parentFile == root }.getOrDefault(false)) {
                        add(partial)
                    }
                }
            }
            partialFiles.forEach { partial -> runCatching { if (partial.isFile) partial.delete() } }
        }

        // The foreground service forces this database's first open before starting any backend.
        // Therefore every .wav.part present during this one-time process recovery belongs to a
        // previous process, including the crash window before its RECORDING row was inserted.
        root.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".wav.part") }
            .forEach { partial ->
                runCatching {
                    val canonical = partial.canonicalFile
                    if (canonical.parentFile == root && canonical.isFile) canonical.delete()
                }
            }
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        synchronized(recoveryLock) {
            if (!recoveryCompleted) {
                cleanupStalePartialFiles(db)
                db.execSQL(
                    """
                    UPDATE call_records
                    SET status = 'INTERRUPTED',
                        failureReason = 'Process stopped before finalization'
                    WHERE status = 'RECORDING'
                    """.trimIndent(),
                )
                recoveryCompleted = true
            }
        }
    }
}
