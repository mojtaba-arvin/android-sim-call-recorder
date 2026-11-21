package com.mojtaba.simcallrecorder.recording

/**
 * Makes process-scoped repository recovery an explicit prerequisite for backend startup.
 *
 * Keeping the ordering in one small seam lets tests suspend recovery and prove that no backend
 * side effect can begin until the recovery barrier has completed. A failed recovery must fail
 * closed and must never invoke the backend.
 */
internal object CaptureStartupBarrier {
    suspend fun <T> start(
        prepareForCapture: suspend () -> Unit,
        startBackend: suspend () -> T,
    ): T {
        prepareForCapture()
        return startBackend()
    }
}
