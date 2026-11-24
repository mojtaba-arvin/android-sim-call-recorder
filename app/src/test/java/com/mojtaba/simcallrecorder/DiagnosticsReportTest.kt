package com.mojtaba.simcallrecorder

import com.mojtaba.simcallrecorder.domain.model.AudioRoute
import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent
import com.mojtaba.simcallrecorder.domain.model.SimSlot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {
    @Test
    fun reportContainsDeviceAndSessionDiagnosticsButNoSubscriptionIdentifiers() {
        val report = buildPrivacySafeDiagnosticsReport(
            generatedAtEpochMillis = 1_700_000_000_000L,
            manufacturer = "Example OEM",
            model = "Example Device",
            androidRelease = "15",
            apiLevel = 35,
            buildDisplay = "example-build",
            securityPatch = "2026-10-05",
            missingPermissions = listOf("android.permission.READ_PHONE_STATE"),
            activeSimSlots = listOf(SimSlot(slotIndex = 1, label = "SIM 2")),
            callEvents = listOf(
                CallStateEvent(simSlotIndex = 1, state = CallState.RINGING, observedAtEpochMillis = 1_700_000_000_000L),
                CallStateEvent(simSlotIndex = 1, state = CallState.OFFHOOK, observedAtEpochMillis = 1_700_000_001_000L),
            ),
            audioRoutes = listOf(
                AudioRoute(
                    direction = "Input",
                    deviceType = "Built-in microphone",
                    displayName = "Built-in microphone",
                    selectedForCommunication = false,
                ),
            ),
            monitoring = true,
            recorderConnected = true,
            recorderActive = false,
            automaticRecordingArmed = true,
        )

        assertTrue(report.contains("Example OEM"))
        assertTrue(report.contains("Example Device"))
        assertTrue(report.contains("SIM 2: OFFHOOK"))
        assertTrue(report.contains("RINGING"))
        assertTrue(report.contains("Built-in microphone"))
        assertTrue(report.contains("Shizuku connection: connected"))
        assertTrue(report.contains("Automatic recording armed: yes"))
        assertTrue(report.contains("NOT VERIFIED"))
        assertTrue(report.contains("ICCID"))
        assertFalse(report.contains("subscriptionId"))
        assertFalse(report.contains("phoneNumber"))
        assertFalse(report.contains("deviceId"))
    }

    @Test
    fun reportBoundsEventHistoryAndDoesNotIncludeSimLabelsOrRawIdentifiers() {
        val events = (0..49).map { index ->
            CallStateEvent(
                simSlotIndex = 0,
                state = if (index % 2 == 0) CallState.RINGING else CallState.IDLE,
                observedAtEpochMillis = 1_700_000_000_000L + index,
            )
        }
        val report = buildPrivacySafeDiagnosticsReport(
            generatedAtEpochMillis = 1_700_000_000_000L,
            manufacturer = "OEM",
            model = "Model",
            androidRelease = "15",
            apiLevel = 35,
            buildDisplay = "build",
            securityPatch = "",
            missingPermissions = emptyList(),
            activeSimSlots = listOf(SimSlot(slotIndex = 0, label = "Carrier-private-label")),
            callEvents = events,
            audioRoutes = emptyList(),
            monitoring = false,
            recorderConnected = false,
            recorderActive = false,
            automaticRecordingArmed = false,
        )

        assertTrue(report.contains("Unavailable"))
        assertFalse(report.contains("Carrier-private-label"))
        assertFalse(report.contains("at 1,700,000,000,000"))
        assertTrue(report.contains("RINGING"))
        assertTrue(report.contains("Two-way cellular audio: NOT VERIFIED"))
    }
}
