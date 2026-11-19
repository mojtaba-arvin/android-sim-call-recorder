package com.mojtaba.simcallrecorder.recording

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Temporary CI policy probe. This file must be removed by closing the probe PR.
 * Commit update intentionally supersedes the first probe run to verify cancel-in-progress.
 */
class RequiredCheckFailureProbeTest {
    @Test
    fun deliberateFailureMustFailTheRequiredChecksAggregator() {
        assertTrue("Intentional CI policy probe: this assertion must fail.", false)
    }
}
