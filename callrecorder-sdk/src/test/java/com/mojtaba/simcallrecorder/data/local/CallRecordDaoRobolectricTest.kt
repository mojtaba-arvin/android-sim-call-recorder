package com.mojtaba.simcallrecorder.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.mojtaba.simcallrecorder.data.local.entity.CallRecordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallRecordDaoRobolectricTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun filtersRecordsBySimDateAndSearchQuery() = runBlocking {
        val dao = database.callRecordDao()
        val first = dao.insert(record(startedAt = 1_000L, simSlot = 0, file = "/private/recordings/alpha.wav"))
        val second = dao.insert(record(startedAt = 2_000L, simSlot = 1, file = "/private/recordings/voice-call.wav"))
        dao.insert(record(startedAt = 3_000L, simSlot = 1, file = "/private/recordings/other.wav"))

        val selectedSim = dao.observeFiltered(
            simSlotIndex = 1,
            fromEpochMillis = 1_500L,
            toEpochMillisExclusive = 2_500L,
            query = "voice-call",
        ).first()

        assertEquals(listOf(second), selectedSim.map { it.id })
        assertEquals(3, dao.countAll())
        assertNotNull(dao.findById(first))
    }

    @Test
    fun captureDiagnosticsArePersistedForTheActiveRecording() = runBlocking {
        val dao = database.callRecordDao()
        val id = dao.insert(record(startedAt = 5_000L, simSlot = 0, file = "/private/recordings/diagnostic.wav"))

        val first = "[1000] session=17; clientSource=MIC; clientSilenced=false"
        val second = "[2000] session=17; clientSource=MIC; clientSilenced=true"
        assertEquals(1, dao.appendCaptureDiagnostic(id, first))
        assertEquals(1, dao.appendCaptureDiagnostic(id, second))

        val saved = dao.findById(id)
        assertEquals("$first\n$second", saved?.captureDiagnostics)
    }

    @Test
    fun completionUpdatesMetadataAndDeleteRemovesOnlyTheSelectedRow() = runBlocking {
        val dao = database.callRecordDao()
        val id = dao.insert(record(startedAt = 4_000L, simSlot = 0, file = "/private/recordings/sample.wav"))

        assertEquals(
            1,
            dao.markCompleted(
                recordId = id,
                durationMillis = 8_500L,
                dataBytes = 480_000L,
                peakSample = 1200,
                rmsSample = 220,
                isSilent = false,
            ),
        )
        val completed = dao.findById(id)
        assertEquals("COMPLETED", completed?.status)
        assertEquals(8_500L, completed?.durationMillis)
        assertEquals(false, completed?.isSilent)

        assertEquals(1, dao.deleteById(id))
        assertNull(dao.findById(id))
    }

    @Test
    fun migrationPreservesExistingV1Rows() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "call-records-v1-migration-test.db"
        context.deleteDatabase(databaseName)
        val legacyFile: File = context.getDatabasePath(databaseName)
        legacyFile.parentFile?.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(legacyFile, null)
        legacy.execSQL(
            """
            CREATE TABLE recordings (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startedAtEpochMillis INTEGER NOT NULL,
                durationMillis INTEGER,
                phoneNumber TEXT,
                audioFileName TEXT NOT NULL,
                audioSource TEXT NOT NULL
            )
            """.trimIndent(),
        )
        legacy.execSQL(
            """
            INSERT INTO recordings (id, startedAtEpochMillis, durationMillis, phoneNumber, audioFileName, audioSource)
            VALUES (41, 123456, 9000, NULL, 'legacy.wav', 'VOICE_CALL')
            """.trimIndent(),
        )
        legacy.version = 1
        legacy.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        try {
            val record = migrated.callRecordDao().findById(41)
            assertNotNull(record)
            assertEquals(123456L, record?.startedAtEpochMillis)
            assertEquals("legacy.wav", record?.filePath)
            assertEquals("LEGACY", record?.recordingMode)
            assertEquals("LEGACY", record?.status)
            assertEquals(-1, record?.simSlotIndex)
            assertNull(record?.captureProfile)
            assertNull(record?.captureDiagnostics)
        } finally {
            migrated.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun processRecoveryInterruptsOnlySessionsLeftActiveByThePreviousProcess() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "recording-process-recovery-test.db"
        context.deleteDatabase(databaseName)

        val previousProcess = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            databaseName,
        ).allowMainThreadQueries().addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        val activeId = previousProcess.callRecordDao().insert(
            record(10_000L, 0, "/private/recordings/active.wav"),
        )
        val completedId = previousProcess.callRecordDao().insert(
            record(20_000L, 1, "/private/recordings/completed.wav"),
        )
        previousProcess.callRecordDao().markCompleted(
            completedId,
            durationMillis = 2_000L,
            dataBytes = 8_000L,
            peakSample = 100,
            rmsSample = 50,
            isSilent = false,
        )
        previousProcess.close()

        val currentProcess = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            databaseName,
        )
            .allowMainThreadQueries()
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .addCallback(StaleRecordingRecoveryCallback())
            .build()

        try {
            val dao = currentProcess.callRecordDao()
            val interrupted = dao.findById(activeId)
            val completed = dao.findById(completedId)

            assertEquals("INTERRUPTED", interrupted?.status)
            assertEquals("Process stopped before finalization", interrupted?.failureReason)
            assertEquals("COMPLETED", completed?.status)
            assertNull(completed?.failureReason)
        } finally {
            currentProcess.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun recoveryCallbackDoesNotInterruptSessionsCreatedAfterItsFirstOpen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "recording-recovery-idempotency-test.db"
        context.deleteDatabase(databaseName)
        val callback = StaleRecordingRecoveryCallback()
        val current = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .addCallback(callback)
            .build()

        try {
            val dao = current.callRecordDao()
            val activeId = dao.insert(
                record(30_000L, 0, "/private/recordings/current-session.wav"),
            )

            // Room invokes onOpen when a database connection is opened. Simulate a later
            // onOpen callback on the same process-owned Room database instance.
            callback.onOpen(current.openHelper.writableDatabase)

            assertEquals("RECORDING", dao.findById(activeId)?.status)
        } finally {
            current.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun record(startedAt: Long, simSlot: Int, file: String) = CallRecordEntity(
        startedAtEpochMillis = startedAt,
        durationMillis = null,
        phoneNumber = null,
        simSlotIndex = simSlot,
        filePath = file,
        recordingMode = "SHIZUKU_VOICE_CALL",
        audioSource = "VOICE_CALL",
        sampleRate = 48_000,
        channelCount = 2,
        dataBytes = 0L,
        peakSample = 0,
        rmsSample = 0,
        isSilent = null,
        status = "RECORDING",
        failureReason = null,
    )
}
