# OEM integration research: cellular call audio

**Research date:** 2026-11-18

## Decision summary

Do not implement a vendor-branded backend based only on manufacturer detection or a generic Android audio source. The reviewed public Android/OEM material does not establish a supported third-party API for capturing both directions of a cellular SIM call.

The practical integration boundaries remain:

1. **Ordinary third-party APK:** no public-SDK entitlement to digital cellular uplink/downlink.
2. **User-authorized Shizuku shell/root helper:** a separate experimental privilege path; success still depends on the device's audio policy, HAL and modem implementation.
3. **OEM/system integration:** potentially possible only where a manufacturer grants a system/privileged permission, signs or preloads the app, or exposes a documented partner API. This requires a real vendor contract/API, not a model-name heuristic.
4. **Speakerphone + microphone:** acoustic workaround, not an OEM digital capture backend.

## Evidence reviewed

### Android public platform boundary

Android's audio-input sharing guidance says a voice call always receives audio. An ordinary app can capture call audio only if it is an Accessibility service or a privileged pre-installed app with `CAPTURE_AUDIO_OUTPUT`. The permission reference explicitly says it is not for third-party applications.

- https://developer.android.com/media/platform/sharing-audio-input
- https://developer.android.com/reference/android/Manifest.permission#CAPTURE_AUDIO_OUTPUT
- https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#VOICE_CALL

AOSP additionally defines hidden/system permissions for bypassing concurrent-recording restrictions and capturing voice-communication output. Their signature/privileged/role protection is further evidence that these are system integration surfaces, not public APK capabilities.

- https://android.googlesource.com/platform/frameworks/base/+/master/core/res/AndroidManifest.xml

### Xiaomi / HyperOS

The Xiaomi developer portal's public **VoIP Service Kit** documents app-managed VoIP service binding and VoIP call UI events. It is scoped to calls managed by the application; it does not document access to cellular SIM call uplink/downlink. It is therefore not a valid backend for this project's SIM-call recorder.

- https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2245

Xiaomi's published call-log permission standard concerns access to call history metadata (READ_CALL_LOG / WRITE_CALL_LOG), not access to the live cellular audio stream. Do not confuse call-log permission with call-audio capture permission.

- https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2079

### Samsung and other OEMs

The public developer material reviewed in this research pass did not establish a supported Samsung, Pixel, OnePlus, or other OEM third-party API for cellular call audio capture. This is a bounded research finding, not proof that no private carrier/OEM/system integration exists. Any candidate must be backed by an official SDK/API contract or a reproducible, lawful device test before it becomes a strategy.

## Engineering consequences

- Keep `OEM_VOICE_RECOGNITION` labelled as a generic platform microphone experiment until the internal enum can be migrated safely. It is not a vendor-specific implementation.
- Do not add per-manufacturer branches that merely change `AudioRecord` source, audio mode, or route and then claim OEM support.
- Add a vendor-specific strategy only when it has a documented API/permission, explicit device/API prerequisites, a testable implementation, and a separate fallback boundary.
- If a manufacturer integration requires signature/privileged permissions, treat it as an OEM/system-image partnership track—not an installable consumer APK feature.
- Continue validating Shizuku separately; do not assume shell UID grants the same audio path on every OEM.
- Keep speakerphone + MIC explicitly acoustic and never relabel it as digital two-way call capture.

## Acceptance gate for a proposed OEM backend

A vendor backend is not accepted until all are true:

1. Official vendor documentation or a written partner contract identifies the API and permission model.
2. The API is callable by the intended app distribution model (ordinary APK, managed/system app, or OEM-preloaded build).
3. The implementation identifies call direction and active SIM/subscription correctly.
4. It records both independently spoken test phrases in repeated incoming and outgoing cellular calls.
5. Earpiece, loudspeaker, wired headset, Bluetooth, VoLTE and Wi-Fi Calling are tested separately where supported.
6. Failures are explicit; no hidden switch to microphone capture occurs.
