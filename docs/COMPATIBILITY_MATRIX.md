# Capture compatibility matrix

## Purpose

The product target is reliable two-way recording of cellular SIM calls across as many Android phones as the platform permits. Compatibility is evaluated per **device build + Android release + carrier/SIM + call direction + audio route**, not by manufacturer name alone.

A strategy being selectable or successfully opening an audio source does not mean that it captured both participants. Keep capability, runtime availability, signal presence, and verified two-way support as separate facts.

## Software API floor experiment — 2026-11-18

The app's declared `minSdk` is currently **26 (Android 8.0)** as an active compatibility experiment, lowered from 28 to evaluate wider installation coverage. Do not treat API 26 as a confirmed release support floor until the latest CI run passes dependency/manifest checks, unit/Robolectric tests, lint, APK assembly, and emulator instrumentation.

Telephony observer Robolectric coverage is being expanded across:
- API 26–30: legacy `PhoneStateListener` path.
- API 31–37: `TelephonyCallback` path (Robolectric 4.18.1 includes API 37; physical API 37 device validation remains pending), including subscription-to-slot association and permission failure behavior.

The Shizuku UserService can now be attempted from API 26 when a live server is already available. Wireless-debugging startup itself is Android 11+; Android 8–10 users must start Shizuku through root or a computer running ADB, then connect and authorize the app. No claim of cellular two-way audio support follows from this software API-floor experiment.

## Current strategy inventory

| Strategy | Current implementation | Eligibility | Audio semantics | Current confidence |
|---|---|---|---|---|
| Shizuku privileged `VOICE_CALL` | Implemented through a narrow Shizuku UserService with shell-attribution setup and a fail-closed readiness check | API 26+ (current app floor); Shizuku/Sui already running and explicitly authorized; actual UserService binder must be connected, report shell UID (2000) or root UID (0), and report successful shell audio-attribution initialization, and an explicit runtime check that the UserService UID holds `CALL_AUDIO_INTERCEPTION` (current Android) or `CAPTURE_AUDIO_OUTPUT` (legacy AudioPolicy fallback); root is accepted separately. Wireless-debugging startup is Android 11+; Android 10 and below require root/Sui or starting Shizuku from a computer via USB ADB. | Requests only the full-duplex `VOICE_CALL` source; one-sided UPLINK/DOWNLINK sources are rejected | Highest-potential path; device/OEM/HAL dependent; no physical validation recorded |
| Accessibility-priority `VOICE_RECOGNITION` | Implemented as a separate opt-in strategy | Android 10+; RECORD_AUDIO; user enables the app's Accessibility service | Microphone capture with speaker-route request; may acoustically capture remote speech | Experimental; not digital downlink; Google Play policy makes this unsuitable for a Play-distributed call-recording feature |
| Platform `VOICE_RECOGNITION` | Implemented as a separate microphone experiment | RECORD_AUDIO | Ordinary microphone input with communication-mode configuration | Generic microphone experiment, **not** a vendor-specific OEM API |
| Speakerphone + MIC | Implemented as a separate explicit strategy | RECORD_AUDIO | Acoustic capture after requesting speaker route | Limited; susceptible to echo, noise, route changes, headset and Bluetooth behavior |
| Standard app-UID capture | Implemented as fail-closed state | No privileged capability | Does not capture cellular call audio | Correctly unavailable; never disguises microphone audio as a SIM-call recording |

### Not implemented as independent backends yet

- A vendor-specific Samsung/Xiaomi/Pixel/etc. recording integration. The current `OEM_VOICE_RECOGNITION` mode is a generic public microphone source, not a vendor API.
- A separate direct-root backend. The current Shizuku UserService accepts UID 0 when Shizuku is running with root privileges; this is not a separately implemented root transport.
- An OEM/system-signed preinstalled application integration. This requires a vendor/system partnership or a controlled system image and cannot be delivered by an ordinary Play-installed APK.

## Selection and fallback policy

1. Probe the actual runtime prerequisites before selecting a strategy: Android API, microphone permission, Shizuku binder and authorization, live UserService binder plus validated host UID (shell 2000/root 0) and successful shell audio-attribution initialization, Accessibility enablement, and active SIM slot. A Shizuku permission grant or running server alone is not capture readiness.
2. Rank only eligible strategies. A ranking is a **candidate recommendation**, not proof of compatibility.
3. Prefer the Shizuku privileged source when its helper is actually connected and authorized.
4. Offer the explicitly labelled microphone/acoustic experiments only as separate modes; never silently relabel them as digital two-way capture.
5. Do not switch capture sources silently after a recording has started or after a source fails. A failed privileged capture must remain a visible failure unless the user explicitly approves a different strategy.
6. Record the actual strategy, source, sample rate, channel count, signal metrics, device profile, and validation outcome with each session.
7. Promote a device/route to "verified two-way" only after independent local and remote test phrases are intelligible in repeated incoming and outgoing calls.

**Current implementation status:** Shizuku readiness is gated by a live UserService, accepted host UID, successful shell audio attribution, and a runtime check that the helper UID holds `CALL_AUDIO_INTERCEPTION` (current Android) or the legacy `CAPTURE_AUDIO_OUTPUT` permission (root is handled explicitly). The user-armed best-eligible path selects Shizuku when ready; otherwise it may select the separately labelled acoustic strategy when RECORD_AUDIO is granted. The selected mode is fixed for the armed session and revalidated immediately before each start. Best-eligible OFFHOOK auto-start and persistent foreground monitoring are implemented behind an explicit Arm action, with no mid-session cross-mode downgrade. AudioRecordingCallback/client-silenced diagnostics are surfaced for microphone sessions and bounded timestamped per-recording diagnostics are persisted; Shizuku backend status is also retained.

**Remaining validation gaps:** service-level selector-to-backend and permission-revocation tests, callback registration/session-filtering/silenced-transition/cleanup coverage, complete presentation of selection/fallback reasons, and physical-device two-way evidence. No physical-device compatibility result has been recorded.

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
- [OEM integration research and evidence gate](OEM_INTEGRATION_RESEARCH.md)
- [Embedded ADB shell capture research](EMBEDDED_ADB_CAPTURE_RESEARCH.md)
- [Android 16+ protected call-audio interception API research](ANDROID_CALL_AUDIO_INTERCEPTION_RESEARCH.md)
- [Official Shizuku setup and Android-version requirements](https://shizuku.rikka.app/guide/setup/)

No GPL implementation code is copied into this MIT-licensed project.


## Android 17 background-audio hardening gate

Android 17 (API 37) applies background-audio hardening to all apps: audio interactions must occur with a visible activity or a non-short foreground service. Apps targeting API 37 have an additional while-in-use foreground-service capability requirement when interacting with audio in the background. The recorder starts its foreground service from a visible, user-initiated UI action; this is the intended path, but it is not considered device-verified yet.

Before raising `targetSdk` from 36 to 37:

1. Run the API 37 emulator matrix and physical-device tests with the app starting monitoring from a visible activity.
2. Background the app and turn the screen off while a cellular call is active; verify the service remains foreground and both audio directions remain intelligible.
3. On an API 37 test device, enable hardening with `adb shell cmd audio set-enable-hardening enable`; inspect `dumpsys audio` and logcat for `AudioHardening` entries.
4. Verify that no path starts audio capture from boot, a background receiver, or a non-user-initiated background action.

The current `targetSdk = 36` is intentionally held until this gate passes. See the [Android 17 background-audio hardening guidance](https://developer.android.com/about/versions/17/changes/bg-audio).
