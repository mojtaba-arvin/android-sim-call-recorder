package com.mojtaba.simcallrecorder.data.repository

import androidx.room.Room
import com.mojtaba.simcallrecorder.data.local.AppDatabase
import com.mojtaba.simcallrecorder.data.local.MIGRATION_1_2
import com.mojtaba.simcallrecorder.data.local.MIGRATION_2_3
import com.mojtaba.simcallrecorder.domain.model.CallRecord
import com.mojtaba.simcallrecorder.domain.model.CallRecordCompletion
import com.mojtaba.simcallrecorder.domain.model.CallRecordFilter
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
import java.io.ByteArrayInputStream
import java.io.File
import com.mojtaba.simcallrecorder.recording.WavPcmFileWriter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultCallRecordRepositoryRobolectricTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: DefaultCallRecordRepository
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java,
        ).allowMainThreadQueries().addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        repository = DefaultCallRecordRepository(database.callRecordDao(), context)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun createsCompletesFiltersAndDeletesPrivateRecording() = runBlocking {
        val folder = File(context.filesDir, "recordings").apply { mkdirs() }
        val audio = File(folder, "repository-test.wav")
        WavPcmFileWriter.copyPcm(ByteArrayInputStream(ByteArray(192_000)), audio, 48_000, 2)
        val id = repository.createActiveRecording(
            CallRecord(
                startedAtEpochMillis = 100_000L,
                durationMillis = null,
                phoneNumber = null,
                simSlotIndex = 1,
                filePath = audio.absolutePath,
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
                captureProfile = "api=35; device=Test Emulator; recordAudio=true",
            ),
        )
        assertTrue(repository.appendCaptureDiagnostic(id, "[1000] session=7; clientSilenced=false"))
        assertTrue(repository.appendCaptureDiagnostic(id, "[2000] session=7; clientSilenced=true"))

        assertTrue(
            repository.completeRecording(
                id,
                CallRecordCompletion(
                    durationMillis = 2_500L,
                    dataBytes = 192_000L,
                    peakSample = 900,
                    rmsSample = 120,
                    isSilent = false,
                ),
            ),
        )
        val results = repository.observeRecords(
            CallRecordFilter(simSlotIndex = 1, query = "VOICE_CALL"),
        ).first()

        assertEquals(1, results.size)
        assertEquals("COMPLETED", results.single().status)
        assertEquals(2_500L, results.single().durationMillis)
        assertEquals("api=35; device=Test Emulator; recordAudio=true", results.single().captureProfile)
        assertEquals("[1000] session=7; clientSilenced=false\n[2000] session=7; clientSilenced=true", results.single().captureDiagnostics)
        assertFalse(results.single().isSilent ?: true)

        assertTrue(repository.deleteRecording(id))
        assertFalse(audio.exists())
        assertEquals(0, repository.countRecordings())
    }

    @Test
    fun refusesToCompleteWhenFinalizedWavIsMissingOrMalformed() = runBlocking {
        val folder = File(context.filesDir, "recordings").apply { mkdirs() }
        val audio = File(folder, "invalid-finalization.wav")
        val id = repository.createActiveRecording(
            CallRecord(
                startedAtEpochMillis = 100_000L,
                durationMillis = null,
                phoneNumber = null,
                simSlotIndex = 0,
                filePath = audio.absolutePath,
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
            ),
        )
        val completion = CallRecordCompletion(
            durationMillis = 1_000L,
            dataBytes = 4L,
            peakSample = 10,
            rmsSample = 5,
            isSilent = false,
        )

        assertFalse(repository.completeRecording(id, completion))
        assertEquals("RECORDING", database.callRecordDao().findById(id)?.status)

        audio.writeBytes(ByteArray(48))
        assertFalse(repository.completeRecording(id, completion))
        assertEquals("RECORDING", database.callRecordDao().findById(id)?.status)
    }

    @Test
    fun rejectsValidWavWhenItsFormatDoesNotMatchPersistedCaptureMetadata() = runBlocking {
        val folder = File(context.filesDir, "recordings").apply { mkdirs() }
        val audio = File(folder, "metadata-mismatch.wav")
        val id = repository.createActiveRecording(
            CallRecord(
                startedAtEpochMillis = 100_000L,
                durationMillis = null,
                phoneNumber = null,
                simSlotIndex = 0,
                filePath = audio.absolutePath,
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
            ),
        )
        val completion = CallRecordCompletion(
            durationMillis = 1_000L,
            dataBytes = 4L,
            peakSample = 10,
            rmsSample = 5,
            isSilent = false,
        )

        WavPcmFileWriter.copyPcm(
            ByteArrayInputStream(byteArrayOf(1, 0, 2, 0)),
            audio,
            sampleRate = 44_100,
            channels = 2,
        )
        assertFalse(repository.completeRecording(id, completion))

        WavPcmFileWriter.copyPcm(
            ByteArrayInputStream(byteArrayOf(1, 0)),
            audio,
            sampleRate = 48_000,
            channels = 1,
        )
        assertFalse(repository.completeRecording(id, completion))

        WavPcmFileWriter.copyPcm(
            ByteArrayInputStream(byteArrayOf(1, 0, 2, 0)),
            audio,
            sampleRate = 48_000,
            channels = 2,
        )
        assertTrue(repository.completeRecording(id, completion))
        assertEquals("COMPLETED", database.callRecordDao().findById(id)?.status)
    }

    @Test
    fun activeRecordingCannotBeDeletedWhileCaptureMayStillBeWriting() = runBlocking {
        val folder = File(context.filesDir, "recordings").apply { mkdirs() }
        val audio = File(folder, "active.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val id = repository.createActiveRecording(
            CallRecord(
                startedAtEpochMillis = 100_000L,
                durationMillis = null,
                phoneNumber = null,
                simSlotIndex = 0,
                filePath = audio.absolutePath,
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
            ),
        )

        assertFalse(repository.deleteRecording(id))
        assertTrue(audio.exists())
        assertEquals("RECORDING", database.callRecordDao().findById(id)?.status)
    }

    @Test
    fun rejectsPathsOutsideThePrivateRecordingDirectory() = runBlocking {
        val failure = runCatching {
            repository.createActiveRecording(
                CallRecord(
                    startedAtEpochMillis = 100_000L,
                    durationMillis = null,
                    phoneNumber = null,
                    simSlotIndex = 0,
                    filePath = File(context.cacheDir, "outside.wav").absolutePath,
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
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, repository.countRecordings())
    }

    @Test
    fun storageLimitDeletesOldestCompletedFilesOnly() = runBlocking {
        val folder = File(context.filesDir, "recordings").apply { mkdirs() }
        val oldestFile = File(folder, "oldest.wav")
        val newestFile = File(folder, "newest.wav")
        WavPcmFileWriter.copyPcm(ByteArrayInputStream(ByteArray(4) { 1 }), oldestFile, 48_000, 2)
        WavPcmFileWriter.copyPcm(ByteArrayInputStream(ByteArray(8) { 2 }), newestFile, 48_000, 2)

        suspend fun createCompleted(file: File, start: Long, bytes: Long): Long {
            val id = repository.createActiveRecording(
                CallRecord(
                    startedAtEpochMillis = start,
                    durationMillis = null,
                    phoneNumber = null,
                    simSlotIndex = 0,
                    filePath = file.absolutePath,
                    recordingMode = "SHIZUKU_VOICE_CALL",
                    audioSource = "VOICE_CALL",
                    sampleRate = 48_000,
                    channelCount = 2,
                    dataBytes = 0,
                    peakSample = 0,
                    rmsSample = 0,
                    isSilent = null,
                    status = "RECORDING",
                    failureReason = null,
                ),
            )
            assertTrue(
                repository.completeRecording(
                    id,
                    CallRecordCompletion(
                        durationMillis = 1_000,
                        dataBytes = bytes,
                        peakSample = 20,
                        rmsSample = 10,
                        isSilent = false,
                    ),
                ),
            )
            return id
        }

        val oldestId = createCompleted(oldestFile, 1_000L, 4L)
        val newestId = createCompleted(newestFile, 2_000L, 8L)

        assertEquals(1, repository.enforceStorageLimit(8L))
        assertFalse(oldestFile.exists())
        assertTrue(newestFile.exists())
        assertEquals(null, database.callRecordDao().findById(oldestId))
        assertEquals("COMPLETED", database.callRecordDao().findById(newestId)?.status)
    }

    @Test
    fun activeRowsAreMarkedInterruptedWhenTheAppRestarts() = runBlocking {
        val folder = File(context.filesDir, "recordings").apply { mkdirs() }
        val audio = File(folder, "interrupted.wav")
        val id = repository.createActiveRecording(
            CallRecord(
                startedAtEpochMillis = 10L,
                durationMillis = null,
                phoneNumber = null,
                simSlotIndex = 0,
                filePath = audio.absolutePath,
                recordingMode = "SHIZUKU_VOICE_CALL",
                audioSource = "VOICE_CALL",
                sampleRate = 48_000,
                channelCount = 2,
                dataBytes = 0,
                peakSample = 0,
                rmsSample = 0,
                isSilent = null,
                status = "RECORDING",
                failureReason = null,
            ),
        )

        assertEquals(1, repository.markActiveRecordingsInterrupted())
        val record = repository.observeRecords().first().single()
        assertEquals(id, record.id)
        assertEquals("INTERRUPTED", record.status)
    }
}
