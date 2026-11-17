# Android SIM-call audio: constraints and feasibility

## Executive finding

A normal third-party Android application cannot assume that it can record both the uplink (local speaker) and downlink (remote party) of a cellular call through public SDK APIs.

The SDK defines MediaRecorder.AudioSource.VOICE_CALL, VOICE_UPLINK, and VOICE_DOWNLINK. However, Android documents that capturing these call streams requires android.permission.CAPTURE_AUDIO_OUTPUT. That permission is reserved for system components and is not available to ordinary third-party applications. REMOTE_SUBMIX is similarly privileged.

This is a platform access boundary, not a missing Gradle dependency or an architectural inconvenience. A foreground service, Hilt, READ_PHONE_STATE, RECORD_AUDIO, or becoming the default dialer does not automatically grant CAPTURE_AUDIO_OUTPUT.

Official references:

- [MediaRecorder.AudioSource](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource)
- [AudioRecord.Builder](https://developer.android.com/reference/android/media/AudioRecord.Builder)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Foreground-service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15)
- [Google Play Developer Program Policy — Accessibility API](https://support.google.com/googleplay/android-developer/answer/18265346)

## Audio-source implications

| Source / approach | What it means | Product stance |
|---|---|---|
| VOICE_CALL | Combined cellular call uplink and downlink; requires privileged CAPTURE_AUDIO_OUTPUT | Do not present as available to an ordinary app |
| VOICE_UPLINK / VOICE_DOWNLINK | Individual call directions; also privileged | Do not use as a generic fallback |
| REMOTE_SUBMIX | Captures a remote submix; privileged | Not a third-party workaround |
| MIC / DEFAULT | Physical microphone input | May capture local speech; remote speech depends on acoustic routing, speaker volume, device DSP, and hardware |
| Speakerphone + MIC | Acoustic capture of both sides may be possible on some devices | Treat as a user-visible, device-specific mode that requires real validation; never silently toggle speakerphone |
| Root / system-signed OEM integration | May have access unavailable to ordinary apps | Separate product/distribution track; not assumed by this repository |

A successful call to MediaRecorder.start() proves only that the recorder started. It does not prove that both call participants are present in the output. Audio fixtures from both ends must be independently verified.

## Foreground-service rules

Foreground services improve process importance; they do not guarantee survival after force-stop, OEM task killing, or every resource-pressure event.

For microphone capture:

- Declare the microphone foreground-service type and its type-specific permission when implementing the service.
- Request and obtain RECORD_AUDIO.
- On Android 14+ (API 34+), microphone foreground services are subject to while-in-use restrictions. In general, the service must be started while the app has a visible activity, unless a documented exemption applies.
- Android 14+ prohibits starting a microphone foreground service from BOOT_COMPLETED.
- Android 15+ adds further restrictions on starting phoneCall and other foreground-service types from BOOT_COMPLETED.
- POST_NOTIFICATIONS is a runtime notification permission on Android 13+; denial does not mean the app can hide an ongoing foreground service from the system.

The phoneCall foreground-service type is intended for ongoing calls managed through ConnectionService. Its prerequisites include MANAGE_OWN_CALLS or holding the default dialer role. It is not a generic permission to record SIM calls.

Design consequence: do not build a receiver that blindly starts a microphone service after detecting OFFHOOK. Establish a lawful, user-initiated service lifecycle first and test it against every target API level.

## Call-state and metadata limitations

- TelephonyCallback.CallStateListener is available from API 31. The initial implementation uses it on API 31+.
- API 28–30 use the deprecated PhoneStateListener compatibility path.
- Both paths require READ_PHONE_STATE.
- OFFHOOK is a telephony state, not a guarantee that a remote party has answered or that bidirectional audio is available.
- Phone-number exposure is separate from call-state exposure. The initial implementation intentionally ignores any number delivered to the legacy callback.
- READ_CALL_LOG is intentionally not requested. It is restricted by Google Play policy and should only be introduced after a clear eligible core use case and distribution-policy review.
- The initial implementation observes the default subscription only. Multi-SIM support requires explicit subscription enumeration and lifecycle handling.

## Explicit non-goals

The project will not:

- Use Accessibility Services to capture remote call audio or work around platform privacy controls.
- Depend on hidden/non-SDK APIs, reflection, or undocumented vendor behavior as the default path.
- Toggle speakerphone without a clear user action and visible disclosure.
- Claim universal two-way recording based solely on an API returning success.
- Claim a foreground service makes the app immune to force-stop or OEM power-management policies.

Google Play explicitly says the Accessibility API is not designed for remote call audio recording and cannot be used to work around Android security/privacy controls.

## Feasibility gate before implementing the recorder

For each candidate device, record:

1. Manufacturer, exact model, chipset where known, Android version, API level, build fingerprint, and security patch.
2. SIM configuration and subscription ID.
3. Incoming and outgoing call results.
4. Earpiece, speakerphone, wired headset, and Bluetooth routes.
5. Audio source, sample rate, channel count, encoding, and recorder errors.
6. Whether local speech and remote speech are both audible in the saved file.
7. Whether capture can begin under the OS foreground-service rules without a race or permission exception.
8. Behavior after screen-off, app backgrounding, process recreation, battery saver, and OEM power management.

A device is considered supported only after both directions are intelligible in independently reviewed recordings across repeated tests. Unsupported devices must show an explicit limitation rather than silently producing one-sided recordings.

## Decision

Keep the implementation native and modular. Build call-state detection and metadata independently from the audio engine. The audio engine must be capability-driven and may only expose modes proven on a defined device/API matrix. If no public, lawful audio path exists on the target device, report that limitation instead of introducing a superficial cross-platform layer or a security bypass.
