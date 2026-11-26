package com.mojtaba.simcallrecorder

import com.mojtaba.simcallrecorder.domain.model.AudioRoute
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent
import com.mojtaba.simcallrecorder.domain.model.SimSlot
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Builds a shareable support snapshot from an allow-list of diagnostic fields.
 *
 * Deliberately accepts slot labels/indexes rather than subscription objects or identifiers and
 * has no input for phone numbers, call content, file paths, or recording bytes.
 */
internal fun buildPrivacySafeDiagnosticsReport(
    generatedAtEpochMillis: Long,
    manufacturer: String,
    model: String,
    androidRelease: String,
    apiLevel: Int,
    buildDisplay: String,
    securityPatch: String,
    missingPermissions: List<String>,
    activeSimSlots: List<SimSlot>,
    callEvents: List<CallStateEvent>,
    audioRoutes: List<AudioRoute>,
    monitoring: Boolean,
    recorderConnected: Boolean,
    recorderActive: Boolean,
    automaticRecordingArmed: Boolean,
): String = buildString {
    appendLine("SIM Call Recorder — privacy-safe diagnostics")
    appendLine("Generated: ${DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(generatedAtEpochMillis))}")
    appendLine()
    appendLine("[Device]")
    appendLine("Manufacturer: $manufacturer")
    appendLine("Model: $model")
    appendLine("Android: $androidRelease (API $apiLevel)")
    appendLine("Build: $buildDisplay")
    appendLine("Security patch: ${securityPatch.ifBlank { "Unavailable" }}")
    appendLine()
    appendLine("[Permissions]")
    appendLine(if (missingPermissions.isEmpty()) "Missing runtime permissions: none" else {
        "Missing runtime permissions: ${missingPermissions.sorted().joinToString(", ")}"
    })
    appendLine()
    appendLine("[SIM slots]")
    if (activeSimSlots.isEmpty()) {
        appendLine("No active SIM slots reported.")
    } else {
        activeSimSlots.sortedBy { it.slotIndex }.forEach { sim ->
            val state = callEvents.lastOrNull { it.simSlotIndex == sim.slotIndex }?.state?.name
                ?: "NO RECENT CALL EVENT"
            appendLine("SIM ${sim.slotIndex + 1}: $state")
        }
    }
    appendLine()
    appendLine("[Recent call-state events]")
    if (callEvents.isEmpty()) {
        appendLine("No call-state events captured.")
    } else {
        callEvents.takeLast(MAX_EXPORTED_EVENTS).forEach { event ->
            val slot = if (event.simSlotIndex >= 0) "SIM ${event.simSlotIndex + 1}" else "Unknown SIM slot"
            val observedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(event.observedAtEpochMillis))
            appendLine("$slot: ${event.state.name} at $observedAt")
        }
    }
    appendLine()
    appendLine("[Audio endpoints]")
    if (audioRoutes.isEmpty()) {
        appendLine("No audio endpoints reported.")
    } else {
        audioRoutes.forEach { route ->
            val selected = if (route.selectedForCommunication) " (selected communication route)" else ""
            appendLine("${route.direction}: ${route.deviceType}$selected")
        }
    }
    appendLine()
    appendLine("[Runtime state]")
    appendLine("Call-state monitoring: ${if (monitoring) "active" else "inactive"}")
    appendLine("Shizuku connection: ${if (recorderConnected) "connected" else "not connected"}")
    appendLine("Capture active: ${if (recorderActive) "yes" else "no"}")
    appendLine("Automatic recording armed: ${if (automaticRecordingArmed) "yes" else "no"}")
    appendLine()
    appendLine("[Evidence boundary]")
    appendLine("Two-way cellular audio: NOT VERIFIED — physical near-end and far-end WAV review is required.")
    appendLine("This report excludes phone numbers, subscription IDs, ICCIDs, device serials, call content, audio samples, recording files, and file paths.")
}

private const val MAX_EXPORTED_EVENTS = 40
