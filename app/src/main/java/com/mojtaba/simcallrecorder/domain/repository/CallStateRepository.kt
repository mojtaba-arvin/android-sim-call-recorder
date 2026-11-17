package com.mojtaba.simcallrecorder.domain.repository

import com.mojtaba.simcallrecorder.domain.model.CallState
import kotlinx.coroutines.flow.Flow

interface CallStateRepository {
    fun observeCallState(): Flow<CallState>
}
