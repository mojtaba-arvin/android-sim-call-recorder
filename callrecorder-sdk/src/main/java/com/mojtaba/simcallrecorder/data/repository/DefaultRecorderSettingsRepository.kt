package com.mojtaba.simcallrecorder.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mojtaba.simcallrecorder.domain.model.RecorderSettings
import com.mojtaba.simcallrecorder.domain.repository.RecorderSettingsRepository
import com.mojtaba.simcallrecorder.recording.RecordingMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recorderSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "recorder_settings",
)

@Singleton
internal class DefaultRecorderSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) : RecorderSettingsRepository {
    private val dataStore = context.recorderSettingsDataStore

    override val settings: Flow<RecorderSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            RecorderSettings(
                storageLimitMb = preferences[STORAGE_LIMIT_MB] ?: DEFAULT_STORAGE_LIMIT_MB,
                recordingMode = runCatching {
                    RecordingMode.valueOf(
                        preferences[RECORDING_MODE] ?: RecordingMode.SHIZUKU_VOICE_CALL.name,
                    )
                }.getOrDefault(RecordingMode.SHIZUKU_VOICE_CALL),
            )
        }

    override suspend fun updateStorageLimit(storageLimitMb: Int) = withContext(Dispatchers.IO) {
        require(storageLimitMb in SUPPORTED_STORAGE_LIMITS_MB) { "Unsupported storage limit." }
        dataStore.edit { it[STORAGE_LIMIT_MB] = storageLimitMb }
        Unit
    }

    override suspend fun updateRecordingMode(modeName: String) = withContext(Dispatchers.IO) {
        val mode = RecordingMode.valueOf(modeName)
        dataStore.edit { it[RECORDING_MODE] = mode.name }
        Unit
    }

    private companion object {
        val STORAGE_LIMIT_MB = intPreferencesKey("storage_limit_mb")
        val RECORDING_MODE = stringPreferencesKey("recording_mode")
        const val DEFAULT_STORAGE_LIMIT_MB = 512
        val SUPPORTED_STORAGE_LIMITS_MB = setOf(128, 256, 512, 1024, 2048)
    }
}
