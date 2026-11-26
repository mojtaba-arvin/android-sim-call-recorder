package com.mojtaba.simcallrecorder.recording

import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.sqrt

internal data class WavCaptureResult(
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
internal object WavPcmFileWriter {
    fun copyPcm(
        input: InputStream,
        destination: File,
        sampleRate: Int,
        channels: Int,
    ): WavCaptureResult {
        require(sampleRate > 0) { "Sample rate must be positive." }
        require(channels in 1..2) { "Only mono and stereo PCM are supported." }
        val blockAlign = channels * BYTES_PER_SAMPLE
        require(sampleRate.toLong() * blockAlign <= MAX_UNSIGNED_INT) {
            "The WAV byte rate exceeds the RIFF/WAVE format limit."
        }

        destination.parentFile?.let { parent ->
            check(parent.exists() || parent.mkdirs()) { "Could not create recording directory." }
        }
        val temporary = File(destination.parentFile, destination.name + ".part")
        var dataBytes = 0L
        var peak = 0
        var sumSquares = 0.0
        var sampleCount = 0L
        val frameBytes = channels * BYTES_PER_SAMPLE
        var pending = ByteArray(0)

        try {
            RandomAccessFile(temporary, "rw").use { output ->
                output.setLength(0L)
                writeHeader(output, sampleRate, channels, 0L)

                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue

                    val combined = ByteArray(pending.size + count)
                    pending.copyInto(combined)
                    buffer.copyInto(combined, destinationOffset = pending.size, startIndex = 0, endIndex = count)
                    val alignedCount = combined.size - (combined.size % frameBytes)
                    if (alignedCount == 0) {
                        pending = combined
                        continue
                    }

                    check(dataBytes + alignedCount <= MAX_WAV_DATA_BYTES) {
                        "The PCM stream exceeds the maximum size supported by a standard RIFF/WAVE file."
                    }
                    output.write(combined, 0, alignedCount)
                    dataBytes += alignedCount
                    pending = combined.copyOfRange(alignedCount, combined.size)

                    var index = 0
                    while (index + 1 < alignedCount) {
                        val raw = (combined[index].toInt() and 0xff) or
                            ((combined[index + 1].toInt() and 0xff) shl 8)
                        val sample = if (raw >= 0x8000) raw - 0x1_0000 else raw
                        val absolute = kotlin.math.abs(sample)
                        if (absolute > peak) peak = absolute
                        sumSquares += sample.toDouble() * sample.toDouble()
                        sampleCount++
                        index += BYTES_PER_SAMPLE
                    }
                }

                check(pending.isEmpty()) {
                    "The PCM stream ended in a partial frame; refusing to save a truncated recording."
                }
                check(dataBytes >= frameBytes) {
                    "No PCM frames were received; refusing to save an empty recording."
                }
                output.seek(0L)
                writeHeader(output, sampleRate, channels, dataBytes)
                output.fd.sync()
            }

            moveIntoPlace(temporary, destination)
            val rms = if (sampleCount == 0L) 0 else sqrt(sumSquares / sampleCount).toInt()
            return WavCaptureResult(destination, dataBytes, peak, rms)
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        } finally {
            // Once EOF has been consumed and the WAV is finalized, a descriptor-close error
            // must not turn a valid saved recording into a reported capture failure.
            runCatching { input.close() }
        }
    }

    private fun moveIntoPlace(temporary: File, destination: File) {
        // The temporary file is created beside the destination. Android's File.renameTo delegates
        // to the same-filesystem native rename operation, available on the API 24 floor, and
        // atomically replaces an existing destination on Android/Linux.
        if (!temporary.renameTo(destination)) {
            throw IOException("Unable to atomically promote the finalized WAV file.")
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
        val byteRate = sampleRate.toLong() * blockAlign

        output.writeBytes("RIFF")
        output.writeIntLittleEndian((36L + dataBytes).toInt())
        output.writeBytes("WAVE")
        output.writeBytes("fmt ")
        output.writeIntLittleEndian(16)
        output.writeShortLittleEndian(1) // PCM
        output.writeShortLittleEndian(channels)
        output.writeIntLittleEndian(sampleRate)
        output.writeIntLittleEndian(byteRate.toInt())
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
    private const val MAX_UNSIGNED_INT = 0xffff_ffffL
    // RIFF chunk size includes the 36-byte header prefix preceding PCM data.
    private const val MAX_WAV_DATA_BYTES = MAX_UNSIGNED_INT - 36L
}
