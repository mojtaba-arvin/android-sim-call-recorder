# Capture compatibility matrix

## Purpose

The product target is reliable two-way recording of cellular SIM calls across as many Android phones as the platform permits. Compatibility is evaluated per **device build + Android release + carrier/SIM + call direction + audio route**, not by manufacturer name alone.

A strategy being selectable or successfully opening an audio source does not mean that it captured both participants. Keep capability, runtime availability, signal presence, and verified two-way support as separate facts.

## Current strategy inventory

| Strategy | Current implementation | Eligibility | Audio semantics | Current confidence |
|---|---|---|---|---|
| Shizuku privileged `VOICE_CALL` | Implemented through a narrow Shizuku UserService | Android 11+; Shizuku running and explicitly authorized; helper UID must be shell (2000) or root (0) | Requests privileged cellular-call uplink + downlink | Highest-potential path; device/OEM/HAL dependent; no physical validation recorded |
| Accessibility-priority `VOICE_RECOGNITION` | Implemented as a separate opt-in strategy | Android 10+; RECORD_AUDIO; user enables the app's Accessibility service | Microphone capture with speaker-route request; may acoustically capture remote speech | Experimental; not digital downlink; Google Play policy makes this unsuitable for a Play-distributed call-recording feature |
| Platform `VOICE_RECOGNITION` | Implemented as a separate microphone experiment | RECORD_AUDIO | Ordinary microphone input with communication-mode configuration | Generic microphone experiment, **not** a vendor-specific OEM API |
| Speakerphone + MIC | Implemented as a separate explicit strategy | RECORD_AUDIO | Acoustic capture after requesting speaker route | Limited; susceptible to echo, noise, route changes, headset and Bluetooth behavior |
| Standard app-UID capture | Implemented as fail-closed state | No privileged capability | Does not capture cellular call audio | Correctly unavailable; never disguises microphone audio as a SIM-call recording |

### Not implemented as independent backends yet

- A vendor-specific Samsung/Xiaomi/Pixel/etc. recording integration. The current `OEM_VOICE_RECOGNITION` mode is a generic public microphone source, not a vendor API.
- A separate direct-root backend. The current Shizuku UserService accepts UID 0 when Shizuku is running with root privileges; this is not a separately implemented root transport.
- An OEM/system-signed preinstalled application integration. This requires a vendor/system partnership or a controlled system image and cannot be delivered by an ordinary Play-installed APK.

## Selection and fallback policy

1. Probe the actual runtime prerequisites before selecting a strategy: Android API, microphone permission, Shizuku binder and authorization, UserService binding/UID, Accessibility enablement, and active SIM slot.
2. Rank only eligible strategies. A ranking is a **candidate recommendation**, not proof of compatibility.
3. Prefer the Shizuku privileged source when its helper is actually connected and authorized.
4. Offer the explicitly labelled microphone/acoustic experiments only as separate modes; never silently relabel them as digital two-way capture.
5. Do not switch capture sources silently after a recording has started or after a source fails. A failed privileged capture must remain a visible failure unless the user explicitly approves a different strategy.
6. Record the actual strategy, source, sample rate, channel count, signal metrics, device profile, and validation outcome with each session.
7. Promote a device/route to "verified two-way" only after independent local and remote test phrases are intelligible in repeated incoming and outgoing calls.

**Current implementation gaps:** `CallCaptureManager.selectBest()` can rank candidates and the Settings UI can offer a candidate, but the foreground recording start path still uses the saved user-selected mode. Runtime auto-selection/fallback is not yet wired end-to-end. Standard-mode OFFHOOK auto-start and persistent monitoring are not implemented; the current prototype is manual-start. AudioRecordingCallback/client-silenced diagnostics are also outstanding. No physical-device compatibility result exists.

## Physical-device acceptance matrix

For every test row, record:

- Manufacturer, model, build fingerprint, Android release/API, security patch.
- Carrier/network mode (including VoLTE or Wi-Fi Calling where relevant), SIM slot and single/dual-SIM configuration.
- Strategy, helper privilege/UID, selected input/output route, sample rate and channel count.
- Separate test phrase spoken by the local participant and remote participant.
- Whether each phrase is independently intelligible in the saved file.
- Incoming/outgoing result, repetition count, call-end behavior, file finalization, and background/power behavior.

Test earpiece, loudspeaker, wired headset, and Bluetooth independently. Test both SIM slots on dual-SIM devices. A device/route is not marked supported from a non-empty WAV, non-zero RMS, emulator test, or a single successful call.

## Research references

- Android `VOICE_CALL` / `VOICE_UPLINK` / `VOICE_DOWNLINK` permission boundary: https://developer.android.com/reference/android/media/MediaRecorder.AudioSource
- Android `AudioRecordingCallback` and `AudioRecordingConfiguration.isClientSilenced()` diagnostics: https://developer.android.com/reference/android/media/AudioRecordingConfiguration
- Android 17 concurrent-capture compatibility requirements: https://source.android.com/docs/compatibility/17/android-17-cdd
- Android audio-input sharing during voice calls: https://developer.android.com/media/platform/sharing-audio-input
- Shizuku UserService and shell/root approach: https://github.com/RikkaApps/Shizuku-API
- ShizuCallRecorder reference implementation (GPL; research only): https://github.com/kitsumed/ShizuCallRecorder
- CallVault reference implementation (GPL; research only): https://github.com/madkongo/CallVault
- ACR Phone Helper documentation and Accessibility/Play distribution constraints: https://acr.app/

No GPL implementation code is copied into this MIT-licensed project.
