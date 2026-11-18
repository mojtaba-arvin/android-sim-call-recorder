package com.mojtaba.simcallrecorder.di

import com.mojtaba.simcallrecorder.data.audio.AndroidAudioDeviceInventorySource
import com.mojtaba.simcallrecorder.data.audio.AudioDeviceInventorySource
import com.mojtaba.simcallrecorder.data.repository.DefaultDeviceDiagnosticsRepository
import com.mojtaba.simcallrecorder.domain.repository.DeviceDiagnosticsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DeviceDiagnosticsModule {
    @Binds
    @Singleton
    abstract fun bindDeviceDiagnosticsRepository(
        implementation: DefaultDeviceDiagnosticsRepository,
    ): DeviceDiagnosticsRepository

    @Binds
    @Singleton
    abstract fun bindAudioDeviceInventorySource(
        implementation: AndroidAudioDeviceInventorySource,
    ): AudioDeviceInventorySource
}
