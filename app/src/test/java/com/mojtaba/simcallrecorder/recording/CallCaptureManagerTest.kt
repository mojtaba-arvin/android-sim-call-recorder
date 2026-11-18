package com.mojtaba.simcallrecorder.recording

import com.mojtaba.simcallrecorder.domain.model.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CallCaptureManagerTest {

    @Test
    fun authorizedShizukuIsRecommendedAheadOfMicrophoneStrategies() {
        val profile = profile(shizuku = true, microphone = true)
        val availability = CaptureStrategySelector.evaluate(profile)

        assertEquals(RecordingMode.SHIZUKU_VOICE_CALL, availability.first { it.recommended }.mode)
        assertTrue(availability.first { it.mode == RecordingMode.SHIZUKU_VOICE_CALL }.available)
        assertTrue(availability.first { it.mode == RecordingMode.SPEAKERPHONE_MIC }.available)
    }

    @Test
    fun speakerphoneAcousticModeIsPreferredOverRestrictedAccessibilityExperiment() {
        val profile = profile(microphone = true, accessibility = true)
        val availability = CaptureStrategySelector.evaluate(profile)

        assertEquals(
            RecordingMode.SPEAKERPHONE_MIC,
            availability.first { it.recommended }.mode,
        )
        assertTrue(availability.first { it.mode == RecordingMode.ACCESSIBILITY_VOICE_RECOGNITION }.available)
        assertFalse(availability.first { it.mode == RecordingMode.ACCESSIBILITY_VOICE_RECOGNITION }.recommended)
    }

    @Test
    fun voiceRecognitionRemainsAnOemExperimentWithoutInventingVendorSupport() {
        val profile = profile(
            manufacturer = "UnlistedVendor",
            model = "Prototype X",
            microphone = true,
        )
        val availability = CaptureStrategySelector.evaluate(profile)
        val oem = availability.first { it.mode == RecordingMode.OEM_VOICE_RECOGNITION }

        assertTrue(oem.available)
        assertEquals(RecordingMode.SPEAKERPHONE_MIC, availability.first { it.recommended }.mode)
        assertTrue(oem.displayName.contains("Platform microphone experiment"))
        assertFalse(oem.recommended)
        assertTrue(oem.reason.contains("UnlistedVendor Prototype X"))
        assertFalse(oem.reason.contains("supported"))
    }

    @Test
    fun microphoneStrategiesAreUnavailableWithoutRecordAudioPermission() {
        val availability = CaptureStrategySelector.evaluate(profile())
        assertTrue(availability.none { it.available })
        assertNull(availability.firstOrNull { it.recommended })
    }

    @Test
    fun accessibilityStrategyRequiresAndroidTenOrNewerAndExplicitEnablement() {
        val oldSdk = CaptureStrategySelector.evaluate(profile(sdk = 28, microphone = true, accessibility = true))
        val notEnabled = CaptureStrategySelector.evaluate(profile(microphone = true, accessibility = false))

        assertFalse(oldSdk.first { it.mode == RecordingMode.ACCESSIBILITY_VOICE_RECOGNITION }.available)
        assertFalse(notEnabled.first { it.mode == RecordingMode.ACCESSIBILITY_VOICE_RECOGNITION }.available)
    }

    @Test
    fun voipNotificationIsOnlyAContextHintAndDoesNotCreateAnAudioStrategy() {
        val withoutHint = CaptureStrategySelector.evaluate(
            profile(microphone = true, accessibility = true, voipCall = false),
        )
        val withHint = CaptureStrategySelector.evaluate(
            profile(microphone = true, accessibility = true, voipCall = true),
        )

        assertEquals(withoutHint.map { it.mode to it.available }, withHint.map { it.mode to it.available })
        assertTrue(
            withHint.first { it.mode == RecordingMode.ACCESSIBILITY_VOICE_RECOGNITION }
                .reason.contains("direct playback capture is not implied"),
        )
    }

    @Test
    fun managerSelectsRecommendedWhenPreferredStrategyIsNotAvailable() {
        val backends = RecordingMode.entries
            .filter { it != RecordingMode.STANDARD }
            .map { FakeStrategy(it) }
            .toSet()
        val manager = CallCaptureManager(backends, shizukuCaptureEnabled = true)
        val profile = profile(microphone = true, accessibility = false)

        val selected = manager.selectBest(
            profile = profile,
            preferred = RecordingMode.ACCESSIBILITY_VOICE_RECOGNITION,
        )

        assertSame(
            backends.first { it.mode == RecordingMode.SPEAKERPHONE_MIC },
            selected,
        )
    }

    @Test
    fun managerDoesNotSelectShizukuWhenFeatureFlagIsDisabled() {
        val shizuku = FakeStrategy(RecordingMode.SHIZUKU_VOICE_CALL)
        val manager = CallCaptureManager(setOf(shizuku), shizukuCaptureEnabled = false)

        assertNull(manager.strategyFor(RecordingMode.SHIZUKU_VOICE_CALL))
        assertFalse(
            manager.availability(profile(shizuku = true))
                .first { it.mode == RecordingMode.SHIZUKU_VOICE_CALL }.available,
        )
    }

    @Test
    fun explicitSelectionIsResolvedWithoutChangingToAnotherCaptureSource() {
        val selectedMic = FakeStrategy(RecordingMode.SPEAKERPHONE_MIC)
        val manager = CallCaptureManager(
            setOf(selectedMic, FakeStrategy(RecordingMode.SHIZUKU_VOICE_CALL)),
            shizukuCaptureEnabled = true,
        )

        assertSame(selectedMic, manager.strategyFor(RecordingMode.SPEAKERPHONE_MIC))
    }

    private fun profile(
        sdk: Int = 35,
        manufacturer: String = "Example",
        model: String = "Reference",
        microphone: Boolean = false,
        shizuku: Boolean = false,
        accessibility: Boolean = false,
        notificationListener: Boolean = false,
        voipCall: Boolean = false,
    ) = CaptureDeviceProfile(
        sdkInt = sdk,
        manufacturer = manufacturer,
        model = model,
        recordAudioGranted = microphone,
        shizukuAuthorized = shizuku,
        accessibilityServiceEnabled = accessibility,
        notificationListenerEnabled = notificationListener,
        voipCallNotificationPresent = voipCall,
    )

    private class FakeStrategy(override val mode: RecordingMode) : CallAudioRecordingBackend {
        override suspend fun start(request: RecordingStartRequest): File = File("fake-${mode.name}.wav")
        override suspend fun stop(): FinalizedAudioCapture? = null
    }
}
