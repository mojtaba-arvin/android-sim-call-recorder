package com.mojtaba.simcallrecorder.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class WavPcmFileWriterTest {

    @Test
    fun writesValidPcmWavHeaderAndComputesSignalLevel() {
        val folder = Files.createTempDirectory("sim-call-recorder-wav").toFile()
        val output = File(folder, "sample.wav")
        val pcm = byteArrayOf(
            0x00, 0x00,       // 0
            0xff.toByte(), 0x7f, // 32767
            0x00, 0x80.toByte(), // -32768
            0x00, 0x40,       // 16384
        )

        val result = WavPcmFileWriter.copyPcm(
            input = ByteArrayInputStream(pcm),
            destination = output,
            sampleRate = 48_000,
            channels = 1,
        )

        val bytes = output.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + pcm.size, header.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(48_000, header.getInt(24))
        assertEquals(pcm.size, header.getInt(40))
        assertEquals(pcm.size.toLong(), result.dataBytes)
        assertEquals(32_768, result.peakSample)
        assertFalse(result.isSilent)
        assertTrue(output.length() > 44)
        assertFalse(File(folder, "sample.wav.part").exists())
    }

    @Test
    fun emptyPipeDoesNotLeaveAValidLookingRecording() {
        val folder = Files.createTempDirectory("sim-call-recorder-empty").toFile()
        val output = File(folder, "empty.wav")

        val exception = runCatching {
            WavPcmFileWriter.copyPcm(
                input = ByteArrayInputStream(byteArrayOf()),
                destination = output,
                sampleRate = 48_000,
                channels = 1,
            )
        }.exceptionOrNull()

        assertTrue(exception != null)
        assertFalse(output.exists())
        assertFalse(File(folder, "empty.wav.part").exists())
    }
}
