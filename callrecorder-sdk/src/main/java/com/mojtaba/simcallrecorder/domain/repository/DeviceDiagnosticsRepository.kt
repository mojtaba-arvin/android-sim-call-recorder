package com.mojtaba.simcallrecorder.domain.repository

import com.mojtaba.simcallrecorder.domain.model.AudioRoute
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent
import com.mojtaba.simcallrecorder.domain.model.SimSlot
import kotlinx.coroutines.flow.Flow

internal interface DeviceDiagnosticsRepository {
    suspend fun getActiveSimSlots(): List<SimSlot>

    fun observeCallStateEvents(): Flow<CallStateEvent>

    fun getAudioRoutes(): List<AudioRoute>
}
