package com.mojtaba.simcallrecorder.recording

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Temporary CI policy probe. This file must be removed by closing the probe PR.
 */
class RequiredCheckFailureProbeTest {
    @Test
    fun deliberateFailureMustFailTheRequiredChecksAggregator() {
        assertTrue("Intentional CI policy probe: this assertion must fail.", false)
    }
}
