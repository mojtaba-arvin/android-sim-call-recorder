package com.mojtaba.simcallrecorder.di

import com.mojtaba.simcallrecorder.data.repository.DefaultRecorderSettingsRepository
import com.mojtaba.simcallrecorder.domain.repository.RecorderSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecorderSettingsModule {
    @Binds
    @Singleton
    abstract fun bindRecorderSettingsRepository(
        implementation: DefaultRecorderSettingsRepository,
    ): RecorderSettingsRepository
}
