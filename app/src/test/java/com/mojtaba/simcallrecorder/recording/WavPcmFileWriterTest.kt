package com.mojtaba.simcallrecorder.recording

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavPcmFileWriterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesValidPcmWaveHeaderAndSignalMetrics() {
        val destination = File(temporaryFolder.root, "capture.wav")
        val pcm = byteArrayOf(
            0x00, 0x80.toByte(),
            0x00, 0x00,
            0xff.toByte(), 0x7f,
            0xe8.toByte(), 0x03,
        )

        val result = WavPcmFileWriter.copyPcm(
            input = ByteArrayInputStream(pcm),
            destination = destination,
            sampleRate = 16_000,
            channels = 1,
        )

        val bytes = destination.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(44 + pcm.size, bytes.size)
        assertEquals(pcm.size.toLong(), result.dataBytes)
        assertEquals(pcm.size, readIntLittleEndian(bytes, 40))
        assertEquals(16_000, readIntLittleEndian(bytes, 24))
        assertEquals(32_000, readIntLittleEndian(bytes, 28))
        assertEquals(32_768, result.peakSample)
        assertFalse(result.isSilent)
        assertArrayEquals(pcm, bytes.copyOfRange(44, bytes.size))
        assertFalse(File(destination.parentFile, destination.name + ".part").exists())
    }

    @Test
    fun alignsReadsAcrossArbitraryInputChunkBoundaries() {
        val destination = File(temporaryFolder.root, "chunked.wav")
        val pcm = byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x03, 0x00)
        val chunked = object : InputStream() {
            private var index = 0

            override fun read(): Int =
                if (index >= pcm.size) -1 else pcm[index++].toInt() and 0xff

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (index >= pcm.size) return -1
                buffer[offset] = pcm[index++]
                return 1
            }
        }

        val result = WavPcmFileWriter.copyPcm(chunked, destination, 8_000, 1)

        assertEquals(6L, result.dataBytes)
        assertArrayEquals(pcm, destination.readBytes().copyOfRange(44, 50))
    }

    @Test
    fun rejectsEmptyAndPartialFramesWithoutReplacingExistingRecording() {
        val destination = File(temporaryFolder.root, "existing.wav")
        val original = byteArrayOf(9, 8, 7, 6)
        destination.writeBytes(original)

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            WavPcmFileWriter.copyPcm(ByteArrayInputStream(byteArrayOf()), destination, 16_000, 1)
        }
        assertArrayEquals(original, destination.readBytes())

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            WavPcmFileWriter.copyPcm(ByteArrayInputStream(byteArrayOf(1, 2, 3)), destination, 16_000, 2)
        }
        assertArrayEquals(original, destination.readBytes())
        assertFalse(File(destination.parentFile, destination.name + ".part").exists())
    }

    @Test
    fun successfulCaptureReplacesExistingFileOnlyAfterFinalization() {
        val destination = File(temporaryFolder.root, "replace.wav")
        destination.writeBytes(byteArrayOf(1, 2, 3))
        val pcm = byteArrayOf(0x34, 0x12)

        val result = WavPcmFileWriter.copyPcm(
            ByteArrayInputStream(pcm),
            destination,
            sampleRate = 8_000,
            channels = 1,
        )

        assertTrue(destination.exists())
        assertEquals("RIFF", String(destination.readBytes(), 0, 4, Charsets.US_ASCII))
        assertEquals(2L, result.dataBytes)
        assertArrayEquals(pcm, destination.readBytes().copyOfRange(44, 46))
    }

    @Test
    fun rejectsByteRateThatCannotBeRepresentedInWaveHeader() {
        val destination = File(temporaryFolder.root, "invalid-rate.wav")

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            WavPcmFileWriter.copyPcm(ByteArrayInputStream(byteArrayOf(1, 0)), destination, Int.MAX_VALUE, 2)
        }

        assertFalse(destination.exists())
        assertFalse(File(destination.parentFile, destination.name + ".part").exists())
    }

    @Test
    fun closeFailureAfterEofDoesNotInvalidateFinalizedWave() {
        val destination = File(temporaryFolder.root, "close-failure.wav")
        val pcm = byteArrayOf(0x12, 0x34)
        val input = object : ByteArrayInputStream(pcm) {
            override fun close() {
                throw java.io.IOException("simulated descriptor close failure")
            }
        }

        val result = WavPcmFileWriter.copyPcm(input, destination, 8_000, 1)

        assertEquals(2L, result.dataBytes)
        assertTrue(destination.exists())
        assertEquals("RIFF", String(destination.readBytes(), 0, 4, Charsets.US_ASCII))
        assertArrayEquals(pcm, destination.readBytes().copyOfRange(44, 46))
    }

    private fun readIntLittleEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
