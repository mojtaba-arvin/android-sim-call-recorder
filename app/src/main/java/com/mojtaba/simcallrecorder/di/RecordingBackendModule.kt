package com.mojtaba.simcallrecorder.di

import com.mojtaba.simcallrecorder.BuildConfig
import com.mojtaba.simcallrecorder.recording.CallAudioRecordingBackend
import com.mojtaba.simcallrecorder.recording.RecordingCoordinator
import com.mojtaba.simcallrecorder.recording.ShizukuVoiceCallRecordingBackend
import com.mojtaba.simcallrecorder.recording.StandardCallRecordingBackend
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RecordingBackendBindings {
    @Binds
    @IntoSet
    abstract fun bindStandardBackend(
        implementation: StandardCallRecordingBackend,
    ): CallAudioRecordingBackend

    @Binds
    @IntoSet
    abstract fun bindShizukuBackend(
        implementation: ShizukuVoiceCallRecordingBackend,
    ): CallAudioRecordingBackend

    companion object {
        @Provides
        @Singleton
        fun provideRecordingCoordinator(
            backends: Set<@JvmSuppressWildcards CallAudioRecordingBackend>,
        ): RecordingCoordinator = RecordingCoordinator(
            backends = backends,
            shizukuCaptureEnabled = BuildConfig.ENABLE_SHIZUKU_CALL_CAPTURE,
        )
    }
}
