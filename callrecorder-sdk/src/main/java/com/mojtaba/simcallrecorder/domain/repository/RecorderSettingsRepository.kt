package com.mojtaba.simcallrecorder.domain.repository

import com.mojtaba.simcallrecorder.domain.model.RecorderSettings
import kotlinx.coroutines.flow.Flow

interface RecorderSettingsRepository {
    val settings: Flow<RecorderSettings>
    suspend fun updateStorageLimit(storageLimitMb: Int)
    suspend fun updateRecordingMode(modeName: String)
}
