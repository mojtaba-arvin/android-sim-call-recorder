package com.mojtaba.simcallrecorder.di

import com.mojtaba.simcallrecorder.data.repository.DefaultDeviceDiagnosticsRepository
import com.mojtaba.simcallrecorder.domain.repository.DeviceDiagnosticsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceDiagnosticsModule {
    @Binds
    @Singleton
    abstract fun bindDeviceDiagnosticsRepository(
        implementation: DefaultDeviceDiagnosticsRepository,
    ): DeviceDiagnosticsRepository
}
