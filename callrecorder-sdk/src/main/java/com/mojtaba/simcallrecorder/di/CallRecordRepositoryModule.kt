package com.mojtaba.simcallrecorder.di

import com.mojtaba.simcallrecorder.data.repository.DefaultCallRecordRepository
import com.mojtaba.simcallrecorder.domain.repository.CallRecordRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CallRecordRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindCallRecordRepository(
        implementation: DefaultCallRecordRepository,
    ): CallRecordRepository
}
