package com.mojtaba.simcallrecorder.recording

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Keeps failed-start teardown and its persistence commit running if the service scope is cancelled.
 */
internal object NonCancellableCleanup {
    suspend fun run(block: suspend () -> Unit) {
        withContext(NonCancellable) {
            block()
        }
    }
}
