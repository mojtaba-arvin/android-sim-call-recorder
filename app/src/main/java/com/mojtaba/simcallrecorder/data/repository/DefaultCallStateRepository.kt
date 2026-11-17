package com.mojtaba.simcallrecorder.data.repository

import com.mojtaba.simcallrecorder.data.telephony.AndroidTelephonyCallStateDataSource
import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.repository.CallStateRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultCallStateRepository @Inject constructor(
    private val dataSource: AndroidTelephonyCallStateDataSource,
) : CallStateRepository {
    override fun observeCallState(): Flow<CallState> = dataSource.observe()
}
