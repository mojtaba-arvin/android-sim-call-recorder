package com.mojtaba.simcallrecorder.data.repository

import com.mojtaba.simcallrecorder.data.telephony.AndroidTelephonyCallStateDataSource
import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.repository.CallStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compatibility projection for consumers that only need the aggregate call state.
 * The diagnostics feature should use per-subscription events instead.
 */
@Singleton
class DefaultCallStateRepository @Inject constructor(
    private val dataSource: AndroidTelephonyCallStateDataSource,
) : CallStateRepository {
    override fun observeCallState(): Flow<CallState> =
        dataSource.observeCallStateEvents().map { it.state }
}
