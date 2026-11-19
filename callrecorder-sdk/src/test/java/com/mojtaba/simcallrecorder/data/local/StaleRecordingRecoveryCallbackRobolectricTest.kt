package com.mojtaba.simcallrecorder.data.local

import androidx.room.Room
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
        val orphanPart = File(recordingDirectory, "orphan.wav.part").apply { writeBytes(byteArrayOf(3, 4)) }
        val completedPart = File(recordingDirectory, "completed.wav.part").apply { writeBytes(byteArrayOf(5, 6)) }
        val completedWav = File(recordingDirectory, "completed.wav").apply { writeBytes(byteArrayOf(7, 8)) }
        // Models a crash after the temporary file was promoted but before the Room commit.
        val promotedBeforeCommitWav = File(recordingDirectory, "promoted-before-commit.wav")
            .apply { writeBytes(byteArrayOf(9, 10, 11, 12)) }
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
        assertTrue(unrelated.exists())
    }

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
