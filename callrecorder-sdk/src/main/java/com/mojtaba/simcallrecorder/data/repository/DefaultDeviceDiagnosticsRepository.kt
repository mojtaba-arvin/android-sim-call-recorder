package com.mojtaba.simcallrecorder.data.repository

import com.mojtaba.simcallrecorder.data.audio.AndroidAudioRouteDataSource
import com.mojtaba.simcallrecorder.data.telephony.AndroidTelephonyCallStateDataSource
import com.mojtaba.simcallrecorder.domain.model.AudioRoute
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent
import com.mojtaba.simcallrecorder.domain.model.SimSlot
import com.mojtaba.simcallrecorder.domain.repository.DeviceDiagnosticsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultDeviceDiagnosticsRepository @Inject constructor(
    private val telephonyDataSource: AndroidTelephonyCallStateDataSource,
    private val audioRouteDataSource: AndroidAudioRouteDataSource,
) : DeviceDiagnosticsRepository {
    override suspend fun getActiveSimSlots(): List<SimSlot> =
        telephonyDataSource.getActiveSimSlots()

    override fun observeCallStateEvents(): Flow<CallStateEvent> =
        telephonyDataSource.observeCallStateEvents()

    override fun getAudioRoutes(): List<AudioRoute> =
        audioRouteDataSource.getRoutes()
}
