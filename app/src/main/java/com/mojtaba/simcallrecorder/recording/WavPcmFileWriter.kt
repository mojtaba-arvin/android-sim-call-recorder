package com.mojtaba.simcallrecorder.recording

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.math.sqrt

data class WavCaptureResult(
    val file: File,
    val dataBytes: Long,
    val peakSample: Int,
    val rmsSample: Int,
) {
    val isSilent: Boolean
        get() = peakSample == 0
}

/**
 * Wraps little-endian signed 16-bit PCM from the privileged capture pipe in a valid RIFF/WAVE file.
 * The temporary file is renamed only after its lengths are finalized and at least one PCM frame exists.
 */
object WavPcmFileWriter {
    fun copyPcm(
        input: InputStream,
        destination: File,
        sampleRate: Int,
        channels: Int,
    ): WavCaptureResult {
        require(sampleRate > 0) { "Sample rate must be positive." }
        require(channels in 1..2) { "Only mono and stereo PCM are supported." }

        destination.parentFile?.let { parent ->
            check(parent.exists() || parent.mkdirs()) { "Could not create recording directory." }
        }
        val temporary = File(destination.parentFile, destination.name + ".part")
        var dataBytes = 0L
        var peak = 0
        var sumSquares = 0.0
        var sampleCount = 0L

        try {
            RandomAccessFile(temporary, "rw").use { output ->
                output.setLength(0L)
                writeHeader(output, sampleRate, channels, 0L)

                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue

                    val alignedCount = count - (count % (channels * BYTES_PER_SAMPLE))
                    if (alignedCount == 0) continue

                    output.write(buffer, 0, alignedCount)
                    dataBytes += alignedCount

                    var index = 0
                    while (index + 1 < alignedCount) {
                        val raw = (buffer[index].toInt() and 0xff) or
                            ((buffer[index + 1].toInt() and 0xff) shl 8)
                        val sample = if (raw >= 0x8000) raw - 0x1_0000 else raw
                        val absolute = kotlin.math.abs(sample)
                        if (absolute > peak) peak = absolute
                        sumSquares += sample.toDouble() * sample.toDouble()
                        sampleCount++
                        index += BYTES_PER_SAMPLE
                    }
                }

                check(dataBytes >= channels * BYTES_PER_SAMPLE) {
                    "No PCM frames were received; refusing to save an empty recording."
                }
                output.seek(0L)
                writeHeader(output, sampleRate, channels, dataBytes)
                output.fd.sync()
            }

            if (destination.exists() && !destination.delete()) {
                error("Could not replace the previous capture file.")
            }
            check(temporary.renameTo(destination)) { "Could not finalize the WAV recording file." }
            val rms = if (sampleCount == 0L) 0 else sqrt(sumSquares / sampleCount).toInt()
            return WavCaptureResult(destination, dataBytes, peak, rms)
        } catch (exception: Exception) {
            temporary.delete()
            throw exception
        } finally {
            input.close()
        }
    }

    private fun writeHeader(
        output: RandomAccessFile,
        sampleRate: Int,
        channels: Int,
        dataBytes: Long,
    ) {
        val bytesPerSample = BYTES_PER_SAMPLE
        val blockAlign = channels * bytesPerSample
        val byteRate = sampleRate * blockAlign

        output.writeBytes("RIFF")
        output.writeIntLittleEndian((36L + dataBytes).toInt())
        output.writeBytes("WAVE")
        output.writeBytes("fmt ")
        output.writeIntLittleEndian(16)
        output.writeShortLittleEndian(1) // PCM
        output.writeShortLittleEndian(channels)
        output.writeIntLittleEndian(sampleRate)
        output.writeIntLittleEndian(byteRate)
        output.writeShortLittleEndian(blockAlign)
        output.writeShortLittleEndian(16)
        output.writeBytes("data")
        output.writeIntLittleEndian(dataBytes.toInt())
    }

    private fun RandomAccessFile.writeIntLittleEndian(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun RandomAccessFile.writeShortLittleEndian(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private const val COPY_BUFFER_BYTES = 16 * 1024
    private const val BYTES_PER_SAMPLE = 2
}
