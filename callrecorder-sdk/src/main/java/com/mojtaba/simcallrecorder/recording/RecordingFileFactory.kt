package com.mojtaba.simcallrecorder.recording

import java.io.File
import java.util.UUID

/**
 * Generates collision-resistant app-private recording paths. Wall-clock timestamps are useful
 * for browsing, but are not unique when starts race or the clock moves backwards.
 */
internal object RecordingFileFactory {
    fun create(directory: File, prefix: String): File {
        require(prefix.matches(Regex("[A-Za-z0-9_-]+"))) { "Recording filename prefix is invalid." }
        check(directory.isDirectory || directory.mkdirs()) {
            "Could not create the private recordings directory."
        }
        return File(directory, "$prefix-${System.currentTimeMillis()}-${UUID.randomUUID()}.wav")
    }
}
