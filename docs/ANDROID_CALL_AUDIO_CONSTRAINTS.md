# Android SIM-call audio: constraints and implementation decision

## Executive finding

A normal third-party Android application cannot assume that it can record both uplink (local speaker) and downlink (remote party) of a cellular call through public SDK APIs.

Android exposes `MediaRecorder.AudioSource.VOICE_CALL`, `VOICE_UPLINK`, and `VOICE_DOWNLINK`, but capturing cellular call streams requires `android.permission.CAPTURE_AUDIO_OUTPUT`, which is reserved for privileged/system components. During an active voice call, Android's input-sharing rules give the call priority; ordinary app microphone capture does not receive a second copy of the call audio.

Official references:

- [MediaRecorder.AudioSource](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource)
- [Audio input sharing rules](https://developer.android.com/media/platform/sharing-audio-input)
- [AudioManager](https://developer.android.com/reference/android/media/AudioManager)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Foreground-service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15)
- [Google Play Developer Program Policy — Accessibility API](https://support.google.com/googleplay/android-developer/answer/18265346)

## Why speakerphone + MIC is not a reliable workaround

Turning on speakerphone does not change Android's permission/concurrency rules. It may produce an acoustic path on a particular handset, but it cannot be assumed to make a normal app's microphone stream available while the cellular call owns input. Android also documents `AudioManager.setSpeakerphoneOn()` as intended for the main telephony app or apps replacing platform-wide audio management; it is deprecated from API 34.

A successful `MediaRecorder.start()`, a growing file, or non-zero sample count is not proof that both call participants were captured. The app's automatic Speakerphone + MIC mode is an explicitly armed acoustic strategy with an honest label; it is not a digital downlink and does not claim two-way support.

## Open-source implementation research

### ShizuCallRecorder

[ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) uses Shizuku and a helper process that runs with ADB shell identity. It is not simply switching public AudioSource values in the normal application process. Its implementation is GPL-3.0 with additional terms; code was reviewed but not copied into this repository.

### CallVault

[CallVault](https://github.com/madkongo/CallVault) is a more recent fork of ShizuCallRecorder. Its README documents two privileged backends: embedded Wireless ADB and Shizuku. It explicitly warns that behavior depends on OEM/hidden framework APIs and can break after Android updates. Its published requirement is Android 11+, arm64, developer options enabled for its built-in ADB mode. It also documents the need to re-establish ADB authorization after reboot and that some OEMs impose extra setup. CallVault is GPL-3.0 with additional Section 7 terms. Its source was inspected as research only; no code was copied.

### Shizuku API

The separate [Shizuku API](https://github.com/RikkaApps/Shizuku-API) is MIT-licensed. Its documented `UserService` runs code in another process under UID 2000 (ADB shell) or UID 0 (root), after the user explicitly starts/authorizes Shizuku or Sui. API 13.1.5 is used here. UserService lifecycle requires a binder interface and explicit destroy cleanup.

This repository uses that API as an optional, explicit helper boundary. It does not embed CallVault/ShizuCallRecorder code, their scrcpy server, their ADB pairing code, or their GPL dependencies. The repository's MIT license is unchanged.

## Implemented prototype in this repository

The optional capture prototype has these layers:

1. The regular application asks the user to connect/authorize Shizuku. It checks the actual UserService UID and accepts only shell UID 2000 or root UID 0.
2. A Shizuku `UserService` process first detaches the app's initial ActivityThread application attribution, reports whether shell audio attribution succeeded, and clears the incoming Binder caller identity before opening `AudioRecord(MediaRecorder.AudioSource.VOICE_CALL)` under the authorized shell/root identity. It prefers 48 kHz PCM16 stereo and falls back to PCM16 mono. It now attempts only `VOICE_CALL`; it does **not** fall back to one-sided `VOICE_UPLINK`/`VOICE_DOWNLINK` or microphone sources.
3. PCM is streamed over a one-session Binder-passed pipe to the app process and finalized as a WAV file under the app-private `files/recordings` directory.
4. A user-initiated foreground service shows a persistent notification with a Stop action. The app requests notification permission before starting capture.
5. The file writer rejects an empty stream and computes peak/RMS. It labels an all-zero WAV as digital silence. Non-silent audio is still not proof of both talk directions; hardware/firmware and carrier behavior must be validated separately.

Capture is manual and visible in the current prototype. It does not automatically begin on OFFHOOK or send audio off-device. The accepted product target is consent-gated automatic Standard-mode microphone/acoustic capture with a persistent notification; Shizuku remains a separate explicit, fail-closed mode. See [the automatic lifecycle design](AUTO_RECORDING_LIFECYCLE.md).

### Requirements and expected limitations

- Android 11 (API 30) or newer.
- Shizuku/Sui running and the user must grant this app Shizuku authorization.
- Without root, Shizuku must be started via ADB. On Android 11+ this can be done with Wireless Debugging on the device; Android clears the debugging authorization on reboot and Shizuku normally needs to be restarted/authorized again. Older Android versions need a computer for ADB setup.
- UID 2000 does not have identical privileges on every Android build. The helper's shell-attribution initialization must succeed before the UI advertises capture readiness. Some OEMs restrict protected audio capture, and carrier/OEM audio HALs can still return silence or one direction only even when `VOICE_CALL` initializes.
- The foreground-service notification is part of the recording disclosure. The Shizuku backend does not use Accessibility APIs; the separate Accessibility-priority microphone experiment is opt-in, does not inspect UI content, and is excluded from Google Play call-recording distribution.

## Foreground-service and platform constraints

Foreground services improve process importance; they do not grant protected audio sources. A microphone FGS cannot be assumed to start later from a background telephony callback because of while-in-use restrictions. This prototype starts an explicitly user-initiated special-use foreground service from the visible app, and uses a privileged external UserService for capture.

The `phoneCall` foreground-service type is intended for ongoing calls managed through `ConnectionService` and requires `MANAGE_OWN_CALLS` or the default dialer role. It is not a generic SIM-call recording permission.

## Accessibility, hidden APIs, and privilege boundaries

Google Play's Accessibility policy does not authorize using Accessibility to bypass Android's privacy controls for call recording. This repository currently contains a separate, explicitly opt-in Accessibility-priority VOICE_RECOGNITION microphone experiment. It does not inspect window content or collect UI text, and it is not a digital VOICE_CALL/downlink source. Because its purpose is call-recording compatibility, this strategy must not be shipped as a Google Play call-recording feature. The Shizuku backend does not depend on Accessibility.

The Shizuku helper is a deliberate, user-visible privilege boundary. Shizuku runs user code with shell/root identity and has a different attack surface from the ordinary app UID. The app does not execute arbitrary shell commands; the helper exposes a narrow Binder API for starting/stopping one VOICE_CALL capture stream and reporting status. The feature must remain opt-in and clearly disclosed.

## Automated validation boundary

Robolectric tests validate:

- API 31+ `TelephonyCallback` state delivery.
- API 28–30 subscription-specific `PhoneStateListener`.
- `RINGING → OFFHOOK → IDLE` transitions.
- Multiple active subscription-to-SIM-slot associations.
- READ_PHONE_STATE permission enforcement.
- Audio input/output endpoint inventory and selected communication endpoint.
- Recorder-service UID gate, lifecycle transitions, duplicate-start rejection, stop/restart, and failure/retry.
- WAV header, PCM sizing, signal peak/RMS measurement, and empty-stream rejection.

These tests do not emulate a modem, OEM audio HAL, carrier VoLTE implementation, Shizuku's privileged process, or actual acoustic/cellular call streams. A CI green result can prove code/state/file-format correctness, not prove that a particular phone exposes both participants. The capture engine intentionally surfaces initialization errors and silence rather than reporting success optimistically.

## Explicit non-goals

The project will not:

- Use Accessibility APIs to inspect UI content or claim privileged digital downlink access. The isolated Accessibility-priority microphone experiment remains optional, clearly labelled, and excluded from Google Play distribution.
- Attempt privileged VOICE_CALL sources in the ordinary app UID and disguise failure with a microphone recording.
- Toggle speakerphone automatically on OFFHOOK.
- Claim universal two-way recording based only on API success or a non-empty file.
- Claim a foreground service makes the app immune to force-stop or OEM power management.

## Device support acceptance criteria

A device can be marked supported only after repeated real calls demonstrate both participants are independently intelligible in recordings, for both SIM slots and relevant routes (earpiece, speaker, wired, Bluetooth). Record make/model, Android release/API, build fingerprint, security patch, carrier, SIM slot, route, selected channel count, peak/RMS, and the two-direction result. Keep failures and unsupported device cases as first-class results.

## Current implementation and mode decision

The app exposes multiple strategies behind `CallCaptureStrategy` / `CallAudioRecordingBackend`:

- **Standard mode:** deliberately refuses cellular-call capture. It does not automatically switch to speakerphone or fall back to `MIC`. A separate voice-note/microphone-only feature would need its own product label and must not appear as SIM-call recording.
- **Advanced Shizuku mode:** optional `UserService` backend. The user must connect/authorize Shizuku, the app verifies UID 2000 or UID 0, the helper verifies shell attribution, and only then attempts `AudioRecord(VOICE_CALL)`. It prefers 48 kHz PCM16 stereo and falls back only to mono `VOICE_CALL`. There is no `MIC` fallback.
- **Accessibility-priority VOICE_RECOGNITION:** isolated microphone/acoustic experiment, explicitly enabled by the user, not digital downlink and not for Google Play call-recording distribution.
- **Platform VOICE_RECOGNITION:** generic microphone experiment, not a vendor-specific OEM API.
- **Speakerphone + MIC:** explicit acoustic capture experiment.

Standard microphone/acoustic strategies may be evaluated as a separately consented automatic mode; none may be used as an implicit fallback for Shizuku.
- A Gradle feature flag, `enableShizukuCallCapture=false`, disables the advanced backend at build time.
- In the current prototype, recording is manually initiated while the selected SIM reports `OFFHOOK`; the user must explicitly confirm they are authorized and have obtained all participant consent required by local law. A foreground notification stays visible with a Stop action. A service-side subscription-scoped listener stops capture when the selected SIM returns to `IDLE`. The target automatic Standard-mode monitor is specified separately in `AUTO_RECORDING_LIFECYCLE.md`.
- If Shizuku permission, UID validation, app-attribution detachment, protected source initialization, pipe streaming, or PCM finalization fails, the session reports failure. It does not retry as standard microphone audio.
- WAV files are app-private. Empty PCM is rejected; all-zero PCM is identified as silence. A non-zero waveform is not proof of two-way call capture.

Keep call-state detection and audio-capture capability separate. The standard app cannot access cellular call uplink/downlink through the public SDK. The Shizuku path is a device-dependent advanced experiment—not a universal guarantee—and still needs physical modem/OEM/route validation.
