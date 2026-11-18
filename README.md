# SIM Call Recorder

A native Android (Kotlin) project investigating reliable SIM-call recording on real devices.

> **Current status:** native diagnostics, automated Android-framework tests, and an opt-in Shizuku `VOICE_CALL` capture prototype. Two-way audio is not claimed as universally supported; OEM/modem behavior must be validated.

## Project goals

- Native Kotlin implementation; no cross-platform runtime or Accessibility workaround.
- Android 9 (API 28) diagnostics baseline, with privileged capture prototype requiring Android 11+.
- Explicit separation between call-state detection, audio-capture capability, recording lifecycle, persistence, and UI.
- App-private WAV storage and local metadata; no broad storage permission.
- Transparent user controls and explicit recording disclosure.

## Technology

| Area | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Architecture | Domain, data, presentation, dependency injection |
| Async | Coroutines and Flow |
| Dependency injection | Hilt |
| Metadata | Room / SQLite |
| Build | Gradle Kotlin DSL, version catalog |
| Privileged helper | MIT-licensed Shizuku API 13.1.5, optional UserService |
| Playback | AndroidX Media3 ExoPlayer |
| Settings | Preferences DataStore |
| Tests | JUnit and Robolectric 4.17 |
| CI | GitHub Actions: unit tests, Robolectric, lint, debug APK |

## Build

Requirements: JDK 21, Android SDK Platform 37, Build Tools 37.0.0, and Gradle 9.6.0.

```bash
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

GitHub Actions runs JUnit/Robolectric, lint, Compose UI instrumentation tests on an Android 35 emulator, and debug APK assembly on branch pushes and pull requests. A Gradle Wrapper is not checked in yet; it will be added after the toolchain is validated in CI.

## Call-audio capture

The standard third-party app process does not have public-SDK access to cellular uplink/downlink. `VOICE_CALL`, `VOICE_UPLINK`, and `VOICE_DOWNLINK` require `CAPTURE_AUDIO_OUTPUT`, which is reserved for privileged/system components. During an active voice call, Android's input-sharing rules prioritize the call; a normal app cannot assume `MIC` or `VOICE_COMMUNICATION` receives call audio.

The application exposes two backends through a common recording contract:

- **Standard:** refuses cellular-call capture. The app does not force the loudspeaker or pretend a standard microphone stream is a call recording.
- **Advanced:** an **optional, manually started Shizuku capture prototype**:

- The user must run Shizuku/Sui and explicitly authorize the app.
- The capture helper runs as shell UID 2000 or root UID 0 and opens `AudioRecord(VOICE_CALL)`.
- It prefers 48 kHz PCM16 stereo, then falls back to mono (still VOICE_CALL only).
- PCM is written as a WAV file in the app-private `files/recordings` directory.
- A foreground notification remains visible and includes a Stop action.
- Empty streams are rejected; all-zero recordings are labelled silent.
- No automatic speakerphone toggle and no MIC fallback are used.

This is a device-dependent privileged capture path, not a guarantee of two-sided audio on every model/carrier. CallVault and ShizuCallRecorder were reviewed as open-source references, but their GPL code was not copied. This project remains MIT-licensed. The Shizuku API itself is MIT-licensed.

To use the prototype, install and start Shizuku/Sui separately, return to the app, tap **Connect / authorize Shizuku**, grant the request in Shizuku, then start a recording manually during a call. On a non-root phone, starting Shizuku normally requires ADB Wireless Debugging on Android 11+ and re-authorization after reboot. The app does not start recording automatically.

Read the detailed research and safety constraints:

- [Android call-audio constraints and implementation decision](docs/ANDROID_CALL_AUDIO_CONSTRAINTS.md)
- [Architecture and module boundaries](docs/ARCHITECTURE.md)
- [Device validation matrix](docs/DEVICE_TEST_MATRIX.md)

## Application screens

- **Recordings:** search, SIM-slot filter, date filter, and newest/oldest sorting.
- **Player:** Media3 ExoPlayer with play/pause, seek/progress controls; only completed readable private WAVs are playable.
- **Settings:** Standard vs. Shizuku backend, local storage quota, and manual-only recording trigger policy.
- **Diagnostics:** the SIM-state, audio-route, and Shizuku connection panel remains available as a dedicated tab.

## Diagnostics implementation

- Subscription-aware call-state observation using `TelephonyCallback` on API 31+ and `PhoneStateListener` on API 28–30.
- Device diagnostics screen showing device/build information, visible SIM slots, per-SIM call-state events, and Android-reported audio input/output endpoints.
- AudioManager communication-route reporting on API 31+.
- Robolectric tests for modern/legacy call-state delivery, multi-SIM slot association, permission enforcement, audio route inventory, and communication-route selection.
- Recording-service controller tests for shell/root UID gating, duplicate starts, stop/restart, and initialization failure/retry.
- WAV file tests for RIFF header correctness, PCM lengths, peak/RMS, and empty-stream rejection.

The diagnostics screen does not read phone numbers, ICCIDs, or call-log data, and does not persist subscription identifiers. The recordings UI supports text, SIM-slot, and date filtering with newest/oldest sorting. WAV playback uses Media3 ExoPlayer and is released with the player screen lifecycle. Settings persist the backend choice and storage quota; the recording service prunes oldest completed private files only after a successful finalization. Recording requires a visible participant-consent acknowledgement, starts only on a SIM slot currently reporting OFFHOOK, and is stopped by the recording service when that slot returns to IDLE. Recording files remain in app-private storage and are not uploaded. The current metadata model keeps phone number nullable; it does not request READ_CALL_LOG because the public telephony callbacks do not consistently expose a number across supported API levels.

## Limits of automated testing

Robolectric can verify framework callback wiring, state transitions, service policy, and PCM/WAV file logic. It cannot emulate the cellular modem, carrier VoLTE path, OEM audio HAL, or prove that both call participants are audible. A green CI run is not a device-compatibility claim.

## Privacy and legal use

- No READ_CALL_LOG, contacts, broad storage, overlay, or accessibility permission is requested.
- No microphone permission prompt is needed for the privileged UserService path; the ordinary app does not capture the microphone.
- Recording starts only from the visible user action in this prototype and shows an ongoing foreground notification with Stop.
- A Shizuku authorization is an additional privilege grant and must be understood by the user.
- A permission grant is not consent from every call participant. The user must comply with applicable local law and obtain all required consent.
