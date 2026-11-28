package com.mojtaba.simcallrecorder.recording

import java.io.File

/**
 * Service boundary for selecting the live capability snapshot immediately before backend start.
 *
 * The provider is invoked for every start attempt; an arm-time profile is never reused as proof
 * that Shizuku or another capture strategy is still available at OFFHOOK.
 */
internal object CaptureRuntimeStarter {
    suspend fun start(
        request: RecordingStartRequest,
        profileSnapshot: () -> CaptureDeviceProfile,
        coordinator: RecordingCoordinator,
    ): Pair<CaptureDeviceProfile, File> {
        val profile = profileSnapshot()
        return profile to coordinator.start(request, profile)
    }
}
