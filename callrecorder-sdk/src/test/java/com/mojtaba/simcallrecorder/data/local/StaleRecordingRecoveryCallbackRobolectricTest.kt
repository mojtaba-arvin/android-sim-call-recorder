package com.mojtaba.simcallrecorder.data.local

import androidx.room.Room
import com.mojtaba.simcallrecorder.recording.WavPcmFileWriter
import java.io.ByteArrayInputStream
import com.mojtaba.simcallrecorder.data.local.entity.CallRecordEntity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StaleRecordingRecoveryCallbackRobolectricTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var databaseFile: File
    private lateinit var recordingDirectory: File
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        databaseFile = context.getDatabasePath("stale-recovery-test.db")
        databaseFile.parentFile?.mkdirs()
        databaseFile.delete()
        recordingDirectory = File(context.filesDir, "stale-recovery-recordings").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        database?.close()
        databaseFile.delete()
        recordingDirectory.deleteRecursively()
    }

    @Test
    fun firstOpenMarksPriorSessionsInterruptedAndRemovesOnlyStalePartFiles() = runBlocking {
        val activeWav = File(recordingDirectory, "active.wav")
        val activePart = File(recordingDirectory, "active.wav.part").apply { writeBytes(byteArrayOf(1, 2)) }
        // Models abrupt process death after the WAV header was created and PCM writing began:
        // the header still advertises an empty data chunk while PCM payload bytes are already present.
        val orphanPart = File(recordingDirectory, "orphan.wav.part").apply {
            writeBytes(partialWavFromInterruptedPcmWrite())
        }
        assertTrue(orphanPart.readBytes().let { String(it, 0, 4, Charsets.US_ASCII) } == "RIFF")
        assertFalse(File(recordingDirectory, "orphan.wav").exists())
        val completedPart = File(recordingDirectory, "completed.wav.part").apply { writeBytes(byteArrayOf(5, 6)) }
        val completedWav = File(recordingDirectory, "completed.wav").apply { writeBytes(byteArrayOf(7, 8)) }
        // Models a crash after the temporary file was promoted but before the Room commit.
        val promotedBeforeCommitWav = File(recordingDirectory, "promoted-before-commit.wav")
        WavPcmFileWriter.copyPcm(
            ByteArrayInputStream(byteArrayOf(1, 0, 2, 0)),
            promotedBeforeCommitWav,
            sampleRate = 48_000,
            channels = 2,
        )
        val unrelated = File(recordingDirectory, "notes.txt").apply { writeText("keep") }

        // Seed a database as the previous process would have left it, without recovery callback.
        val previousProcessDatabase = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            databaseFile.name,
        ).allowMainThreadQueries().build()
        try {
            previousProcessDatabase.callRecordDao().insert(
                record(activeWav, status = "RECORDING", startedAt = 100L),
            )
            previousProcessDatabase.callRecordDao().insert(
                record(completedWav, status = "COMPLETED", startedAt = 200L),
            )
            previousProcessDatabase.callRecordDao().insert(
                record(promotedBeforeCommitWav, status = "RECORDING", startedAt = 300L),
            )
        } finally {
            previousProcessDatabase.close()
        }

        database = Room.databaseBuilder(context, AppDatabase::class.java, databaseFile.name)
            .allowMainThreadQueries()
            .addCallback(StaleRecordingRecoveryCallback(recordingDirectory))
            .build()

        val records = database!!.callRecordDao().observeAll().first()

        assertEquals(3, records.size)
        assertEquals("INTERRUPTED", records.single { it.startedAtEpochMillis == 100L }.status)
        assertEquals("COMPLETED", records.single { it.startedAtEpochMillis == 200L }.status)
        assertEquals("INTERRUPTED", records.single { it.startedAtEpochMillis == 300L }.status)
        assertFalse(activePart.exists())
        assertFalse(orphanPart.exists())
        assertFalse(completedPart.exists())
        assertTrue(completedWav.exists())
        assertTrue(promotedBeforeCommitWav.exists())
        assertEquals("RIFF", promotedBeforeCommitWav.readBytes().let { String(it, 0, 4, Charsets.US_ASCII) })
        assertTrue(unrelated.exists())
    }

    @Test
    fun repeatedOpenDoesNotInterruptOrDeleteFilesFromANewSessionInTheSameProcess() = runBlocking {
        val previousWav = File(recordingDirectory, "previous.wav")
        val previousProcessDatabase = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            databaseFile.name,
        ).allowMainThreadQueries().build()
        try {
            previousProcessDatabase.callRecordDao().insert(
                record(previousWav, status = "RECORDING", startedAt = 100L),
            )
        } finally {
            previousProcessDatabase.close()
        }

        val callback = StaleRecordingRecoveryCallback(recordingDirectory)
        database = Room.databaseBuilder(context, AppDatabase::class.java, databaseFile.name)
            .allowMainThreadQueries()
            .addCallback(callback)
            .build()

        // First open represents process startup and must recover the previous process.
        assertEquals(
            "INTERRUPTED",
            database!!.callRecordDao().observeAll().first().single().status,
        )

        // A new capture begins later in this same process.
        val newSessionWav = File(recordingDirectory, "new-session.wav")
        val newSessionPart = File(recordingDirectory, "new-session.wav.part")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val newSessionId = database!!.callRecordDao().insert(
            record(newSessionWav, status = "RECORDING", startedAt = 200L),
        )

        // Room may call onOpen again after reopening its connection. Recovery must stay
        // process-scoped: it must not classify this live session as stale or delete its part.
        callback.onOpen(database!!.openHelper.writableDatabase)

        assertEquals("RECORDING", database!!.callRecordDao().findById(newSessionId)?.status)
        assertTrue(newSessionPart.exists())
    }

    private fun partialWavFromInterruptedPcmWrite() = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, 0x24, 0, 0, 0,
        0x57, 0x41, 0x56, 0x45,
        0x66, 0x6d, 0x74, 0x20, 0x10, 0, 0, 0,
        0x01, 0, 0x01, 0,
        0x80.toByte(), 0x3e, 0, 0, 0, 0x7d, 0, 0,
        0x02, 0, 0x10, 0,
        0x64, 0x61, 0x74, 0x61, 0, 0, 0, 0,
        0x01, 0,
    )

    private fun record(file: File, status: String, startedAt: Long) = CallRecordEntity(
        startedAtEpochMillis = startedAt,
        durationMillis = if (status == "COMPLETED") 1_000L else null,
        phoneNumber = null,
        simSlotIndex = 0,
        filePath = file.absolutePath,
        recordingMode = "SPEAKERPHONE_MIC",
        audioSource = "MIC",
        sampleRate = 16_000,
        channelCount = 1,
        dataBytes = 0L,
        peakSample = 0,
        rmsSample = 0,
        isSilent = null,
        status = status,
        failureReason = null,
    )
}
