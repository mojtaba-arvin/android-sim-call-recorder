package com.mojtaba.simcallrecorder.recording

import java.io.File
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingFileFactoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createsDistinctSafePathsForConcurrentStartsInTheSameMillisecond() {
        val directory = File(temporaryFolder.root, "recordings")
        val first = RecordingFileFactory.create(directory, "call")
        val second = RecordingFileFactory.create(directory, "call")

        assertTrue(directory.isDirectory)
        assertNotEquals(first.name, second.name)
        assertTrue(first.name.matches(Regex("call-[0-9]+-[0-9a-f-]{36}\\.wav")))
        assertTrue(second.name.matches(Regex("call-[0-9]+-[0-9a-f-]{36}\\.wav")))
    }
}
