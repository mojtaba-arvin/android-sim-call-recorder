package com.mojtaba.simcallrecorder.di

import com.mojtaba.simcallrecorder.BuildConfig
import com.mojtaba.simcallrecorder.recording.CallAudioRecordingBackend
import com.mojtaba.simcallrecorder.recording.SpeakerphoneMicCallCaptureStrategy
import com.mojtaba.simcallrecorder.recording.OemVoiceRecognitionCaptureStrategy
import com.mojtaba.simcallrecorder.recording.AccessibilityVoiceRecognitionCaptureStrategy
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
internal abstract class RecordingBackendBindings {
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

    @Binds
    @IntoSet
    abstract fun bindSpeakerphoneMicBackend(
        implementation: SpeakerphoneMicCallCaptureStrategy,
    ): CallAudioRecordingBackend

    @Binds
    @IntoSet
    abstract fun bindOemVoiceRecognitionBackend(
        implementation: OemVoiceRecognitionCaptureStrategy,
    ): CallAudioRecordingBackend

    @Binds
    @IntoSet
    abstract fun bindAccessibilityVoiceRecognitionBackend(
        implementation: AccessibilityVoiceRecognitionCaptureStrategy,
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
