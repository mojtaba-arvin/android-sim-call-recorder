# Android SIM-call audio: constraints and feasibility

## Executive finding

A normal third-party Android application cannot assume that it can record both the uplink (local speaker) and downlink (remote party) of a cellular call through public SDK APIs.

The SDK defines `MediaRecorder.AudioSource.VOICE_CALL`, `VOICE_UPLINK`, and `VOICE_DOWNLINK`. Capturing the call streams requires `android.permission.CAPTURE_AUDIO_OUTPUT`, which is reserved for system components and is not available to ordinary third-party applications. This is a platform access boundary, not a missing Gradle dependency.

Official references:

- [MediaRecorder.AudioSource](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource)
- [Audio input sharing rules](https://developer.android.com/media/platform/sharing-audio-input)
- [AudioManager](https://developer.android.com/reference/android/media/AudioManager)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Foreground-service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15)
- [Google Play Developer Program Policy — Accessibility API](https://support.google.com/googleplay/android-developer/answer/18265346)

## Why speakerphone + MIC is not a general call-recording workaround

Android's audio-input sharing documentation states that when a voice call is active, the call always receives the input audio. An ordinary app does not get a second copy of the call microphone input; the documented exceptions are an accessibility service or a privileged pre-installed app with `CAPTURE_AUDIO_OUTPUT`. The call's uplink/downlink capture path uses `VOICE_UPLINK`, `VOICE_DOWNLINK`, and/or `AudioDeviceInfo.TYPE_TELEPHONY`, which are privileged.

Turning on speakerphone does not change that permission/concurrency policy. It may produce an acoustic path on a particular OEM build, but it cannot be assumed to make a normal app's microphone stream available during a cellular call. A successful `MediaRecorder.start()` is not evidence that either call participant is present in the saved file.

Android also says `AudioManager.setSpeakerphoneOn()` should only be used by applications replacing platform-wide audio management or the main telephony application. API 34 deprecated it. `setCommunicationDevice()` is intended for communication use cases controlled by the requesting app and remains subject to audio-mode priority; it is not a general API for taking control of a cellular call route.

Product consequence: do not automatically toggle speakerphone when `OFFHOOK` arrives. Such behavior changes the user's call privacy and audio route, can be overridden by the telephony app, and still does not establish that microphone capture will receive call audio.

## Other public AudioSource choices

| Source / approach | What it means | Product stance |
|---|---|---|
| `VOICE_CALL` | Combined cellular call uplink and downlink; requires privileged `CAPTURE_AUDIO_OUTPUT` | Not available to an ordinary app |
| `VOICE_UPLINK` / `VOICE_DOWNLINK` | Individual cellular call directions; privileged | Not a fallback for a normal app |
| `REMOTE_SUBMIX` | Captures a remote submix; privileged | Not a third-party workaround |
| `MIC` / `DEFAULT` | Physical microphone input | Suitable for an explicitly started microphone test; not a supported call-audio path while a cellular call owns input |
| `VOICE_COMMUNICATION` | Communication-oriented microphone source with audio processing | Does not grant call uplink/downlink access or bypass call-input sharing |
| Speakerphone + `MIC` | Attempts to record the acoustic output of the loudspeaker | Device-dependent and not supported as a universal call-recording mechanism |

## What the open-source projects actually do

### ShizuCallRecorder

[ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) is the most technically relevant modern non-root open-source reference found during this review. Its README requires Shizuku/ADB shell authorization, documents per-Android-version limitations, and explicitly warns that the implementation uses hidden Android APIs which may break with new Android releases or OEM modifications.

Inspection of its implementation confirms that it does not merely switch to another public `AudioSource`: recording runs through a shell-UID/Shizuku service, constructs `AudioRecord` with internal Android context/attribution details, and uses reflection against private framework members on fallback paths. It also has an Android 11-specific foreground workaround. This is a separate privileged-helper architecture with a larger security and compatibility surface, not a drop-in recorder service for an ordinary app.

The project is licensed under GPL-3.0 with additional terms. Its implementation has not been copied into this repository.

### Other microphone/speakerphone approaches

Open-source projects that offer a microphone or speakerphone fallback typically document device-dependent or one-sided recordings. A local microphone test can verify that a microphone recorder writes a valid audio file, but cannot establish a two-sided cellular-call capture path. This repository will not label that as successful call recording.

## Android foreground-service constraints

Foreground services improve process importance; they do not grant protected audio sources or bypass audio-input sharing.

For microphone capture:

- Declare the microphone foreground-service type and its type-specific permission.
- Request and obtain `RECORD_AUDIO`.
- On Android 14+ (API 34+), microphone foreground services are subject to while-in-use restrictions. In general, the service must be started while the app has a visible activity, unless a documented exemption applies.
- A microphone foreground service cannot be assumed to be startable later from a background telephony callback.
- The `phoneCall` foreground-service type is intended for ongoing calls managed through `ConnectionService`. Its prerequisites include `MANAGE_OWN_CALLS` or holding the default dialer role; it is not a generic permission to record SIM calls.

Android 17 also introduces `BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION` as a signature/privileged permission. That reinforces that bypassing concurrent-recording restrictions is a system/OEM capability, not a runtime permission an ordinary app can request.

## Accessibility, hidden APIs, and privileged helpers

Google Play policy says the Accessibility API is not designed for remote call-audio recording and must not be used to work around Android security/privacy controls. This repository will not use Accessibility for call-audio capture.

A Shizuku/ADB shell helper is a possible separate advanced product track, but it needs an explicit user-authorized setup, an isolated helper process, a threat model for shell privileges, a non-copied implementation and dependency/license review, version-specific framework compatibility tests, and a physical-device validation matrix. It is not included implicitly in the standard app build.

This repository will not rely on hidden APIs, reflection, shell identity impersonation, or undocumented vendor behavior as the default recording path.

## Telephony monitoring and automated tests

There is no telephony `BroadcastReceiver` in the current application. The implementation uses:

- `TelephonyCallback.CallStateListener` on API 31+.
- Subscription-specific `PhoneStateListener` on API 28–30.
- `SubscriptionManager.getActiveSubscriptionInfoList()` to enumerate visible active subscriptions.

Robolectric tests exercise both callback/listener paths, the `RINGING → OFFHOOK → IDLE` transitions, multiple subscription-to-slot associations, the just-in-time `READ_PHONE_STATE` permission check, audio input/output inventories, deterministic ordering, and the selected communication endpoint on API 31+.

These tests validate Android framework integration and application state logic in a simulated runtime. They cannot simulate a modem, OEM audio HAL, acoustic speaker/microphone coupling, carrier VoLTE behavior, or prove that both participants are audible in a physical recording.

## Explicit non-goals

The project will not:

- Use Accessibility Services to capture remote call audio or work around platform privacy controls.
- Attempt `VOICE_CALL`, `VOICE_UPLINK`, or `VOICE_DOWNLINK` as fake fallbacks in an ordinary app.
- Toggle speakerphone automatically when a call becomes `OFFHOOK`.
- Claim universal two-way recording based solely on an API returning success or a non-empty audio file.
- Claim a foreground service makes the app immune to force-stop or OEM power-management policies.

## Feasibility gate before implementing a call recorder

For each candidate device, record:

1. Manufacturer, exact model, chipset where known, Android version, API level, build fingerprint, and security patch.
2. SIM configuration and subscription slot.
3. Incoming and outgoing call results.
4. Earpiece, speakerphone, wired headset, and Bluetooth routes.
5. Audio source, sample rate, channel count, encoding, and recorder errors.
6. Whether local speech and remote speech are both independently audible in the saved file.
7. Whether capture starts under OS foreground-service rules without a permission exception or race.
8. Behavior after screen-off, app backgrounding, process recreation, battery saver, and OEM power management.

A device is considered supported only after both directions are intelligible in independently reviewed recordings across repeated tests. Unsupported devices must show an explicit limitation rather than silently producing one-sided recordings.

## Decision

Keep call-state detection and audio-capture capability separate. The standard app can reliably test telephony state handling and report available audio endpoints, but public SDK APIs do not provide an ordinary third-party app with guaranteed cellular uplink/downlink access. A real two-way recording implementation needs a separately selected and reviewed platform-specific track (for example, an opt-in Shizuku helper or OEM/system integration), followed by explicit device/API validation. Do not substitute a microphone-only prototype and call it a call recorder.
