package com.mojtaba.simcallrecorder.di

import com.mojtaba.simcallrecorder.data.repository.DefaultCallStateRepository
import com.mojtaba.simcallrecorder.domain.repository.CallStateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CallStateModule {
    @Binds
    @Singleton
    abstract fun bindCallStateRepository(
        implementation: DefaultCallStateRepository,
    ): CallStateRepository
}
