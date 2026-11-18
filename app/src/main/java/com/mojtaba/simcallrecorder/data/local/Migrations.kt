package com.mojtaba.simcallrecorder.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Preserves the v1 metadata table while adding per-SIM, capture-format and lifecycle fields.
 * Legacy rows remain visible as COMPLETED; their old filename is retained as a relative path.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS call_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startedAtEpochMillis INTEGER NOT NULL,
                durationMillis INTEGER,
                phoneNumber TEXT,
                simSlotIndex INTEGER NOT NULL,
                filePath TEXT NOT NULL,
                recordingMode TEXT NOT NULL,
                audioSource TEXT NOT NULL,
                sampleRate INTEGER NOT NULL,
                channelCount INTEGER NOT NULL,
                dataBytes INTEGER NOT NULL,
                peakSample INTEGER NOT NULL,
                rmsSample INTEGER NOT NULL,
                isSilent INTEGER,
                status TEXT NOT NULL,
                failureReason TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO call_records (
                id, startedAtEpochMillis, durationMillis, phoneNumber, simSlotIndex,
                filePath, recordingMode, audioSource, sampleRate, channelCount,
                dataBytes, peakSample, rmsSample, isSilent, status, failureReason
            )
            SELECT
                id, startedAtEpochMillis, durationMillis, phoneNumber, -1,
                audioFileName, 'LEGACY', audioSource, 0, 0,
                0, 0, 0, NULL, 'LEGACY', NULL
            FROM recordings
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE recordings")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_call_records_startedAtEpochMillis ON call_records(startedAtEpochMillis)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_call_records_simSlotIndex_startedAtEpochMillis ON call_records(simSlotIndex, startedAtEpochMillis)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_call_records_status ON call_records(status)",
        )
    }
}


/** Adds runtime capture profile and framework audio diagnostics without rewriting old recordings. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE call_records ADD COLUMN captureProfile TEXT")
        db.execSQL("ALTER TABLE call_records ADD COLUMN captureDiagnostics TEXT")
    }
}
