package com.mojta.simcallrecorder.recording.shizuku

import android.os.ParcelFileDescriptor
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShizukuCaptureAbortTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun binderDeathClosesReaderCancelsWriterAndDeletesUnconfirmedFiles() = runBlocking {
        val pipe = ParcelFileDescriptor.createPipe()
        val writer = launch { awaitCancellation() }
        val partial = File(temporaryFolder.root, "active.wav.part").apply { writeBytes(byteArrayOf(1, 2)) }
        val promoted = File(temporaryFolder.root, "active.wav").apply { writeBytes(byteArrayOf(3, 4)) }

        val clean = discardUnconfirmedShizukuCapture(
            reader = pipe[0],
            writer = writer,
            files = listOf(partial, promoted, partial),
        )

        assertTrue(clean)
        assertFalse(pipe[0].fileDescriptor.valid())
        assertFalse(writer.isActive)
        assertFalse(partial.exists())
        assertFalse(promoted.exists())
        pipe[1].close()
    }

    @Test
    fun cleanupIsIdempotentWhenFilesWereAlreadyRemoved() = runBlocking {
        assertTrue(
            discardUnconfirmedShizukuCapture(
                reader = null,
                writer = null,
                files = listOf(File(temporaryFolder.root, "already-gone.wav")),
            ),
        )
    }
}
