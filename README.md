# SIM Call Recorder

[Download the latest APK from GitHub Releases](https://github.com/mojtaba-arvin/android-sim-call-recorder/releases/latest). Each versioned release includes the sample APK, reusable SDK AAR, and SHA-256 checksums.

A native Android (Kotlin) project investigating reliable SIM-call recording on real devices.

> **Current status:** native diagnostics, a Shizuku `VOICE_CALL` backend with shell-attribution readiness checks, and a user-armed automatic best-eligible strategy selector. It selects Shizuku when its live helper is ready, otherwise Speakerphone + MIC when microphone permission is available. Neither path is proof of two-way call audio; OEM/modem behavior must be validated.

## Project goals

- Native Kotlin implementation; no cross-platform runtime. Accessibility-priority microphone capture is an isolated, optional experiment and is not a general digital call-audio API.
- Android app and Shizuku UserService compatibility floor: API 26 (Android 8.0). Starting Shizuku through on-device Wireless Debugging requires Android 11+; Android 8–10 requires root or computer-based ADB setup.
- Explicit separation between call-state detection, audio-capture capability, recording lifecycle, persistence, and UI.
- App-private WAV storage and local metadata; no broad storage permission.
- Transparent user controls and explicit recording disclosure.

## Technology

| Area | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Architecture | Multi-module: reusable `:callrecorder-sdk` engine + `:app` sample/diagnostics host |
| Async | Coroutines and Flow |
| Dependency injection | Hilt |
| Metadata | Room / SQLite |
| Build | Gradle Kotlin DSL, version catalog |
| Privileged helper | MIT-licensed Shizuku API 13.1.5, optional UserService |
| Playback | AndroidX Media3 ExoPlayer |
| Settings | Preferences DataStore |
| Tests | JUnit, Robolectric, and app-side SDK integration tests |
| CI | GitHub Actions: unit tests, Robolectric, lint, debug APK |

## Beta installation

See [the beta installation and capture guide](docs/INSTALLING_BETA.md) for sideload steps, Shizuku setup, fallback behavior, and device validation.

## SDK module and integration

The recording engine is extracted into `:callrecorder-sdk`; `:app` consumes it with `implementation(project(":callrecorder-sdk"))`. The SDK owns capture strategies, Shizuku integration, telephony listeners, foreground services, Room persistence, and storage repositories. The app owns Compose screens, playback, permission UX, and diagnostics presentation. See [the SDK integration guide](docs/SDK_INTEGRATION.md) for Hilt setup and the `SimCallRecorderSdk` facade.

### Downloadable releases

Push a version tag such as `v0.2.0-beta.1` to build and publish a GitHub Release automatically. The workflow runs module tests and lint, compiles app instrumentation tests, assembles the release AAR and debug-signed sample APK, then attaches both files and SHA-256 checksums to the Release page. The APK is suitable for beta sideloading; production signing requires a protected release-keystore secret and is not committed to the repository.

## Build

Requirements: JDK 21, Android SDK Platform 37, Build Tools 37.0.0, and Gradle 9.6.0.

```bash
gradle --no-daemon :callrecorder-sdk:testDebugUnitTest :app:testDebugUnitTest :callrecorder-sdk:lintDebug :app:lintDebug :callrecorder-sdk:assembleRelease :app:assembleDebug
```

GitHub Actions runs JUnit/Robolectric tests in both modules, lint, SDK release-AAR assembly, app debug-APK assembly, and Compose UI instrumentation tests on Android emulators for pull requests to `main` and pushes to `main`. A Gradle Wrapper is not checked in yet; CI pins Gradle 9.6.0.

## Call-audio capture

The standard third-party app process does not have public-SDK access to cellular uplink/downlink. `VOICE_CALL`, `VOICE_UPLINK`, and `VOICE_DOWNLINK` require protected call-audio permissions: `CALL_AUDIO_INTERCEPTION` on current Android or the legacy `CAPTURE_AUDIO_OUTPUT` fallback, reserved for privileged/system components. During an active voice call, Android's input-sharing rules prioritize the call; a normal app cannot assume `MIC` or `VOICE_COMMUNICATION` receives call audio.

The application exposes multiple strategies through a common `CallCaptureStrategy` contract:

- **Standard app-UID digital capture:** fails closed; it does not claim access to cellular uplink/downlink. The product-level Standard acoustic mode is the separate `Speakerphone + MIC` strategy and now supports explicit foreground arming with automatic OFFHOOK start and same-SIM IDLE finalization.
- **Shizuku VOICE_CALL:** privileged full-duplex candidate; the automatic arm flow selects it when the live UserService, shell UID and attribution are ready.
- **Accessibility-priority VOICE_RECOGNITION:** optional microphone/acoustic experiment; not digital downlink and not for Google Play distribution as a call-recording feature.
- **Platform VOICE_RECOGNITION:** generic microphone experiment, not a vendor-specific OEM API.
- **Speakerphone + MIC:** explicit acoustic capture mode with route and echo limitations; may be armed for automatic OFFHOOK recording with a persistent notification.

The Shizuku backend works as follows:

- The user must run Shizuku/Sui and explicitly authorize the app.
- The capture helper runs as shell UID 2000 or root UID 0, establishes shell audio attribution, clears the incoming Binder caller identity, and opens `AudioRecord(VOICE_CALL)`.
- It prefers 48 kHz PCM16 stereo, then falls back to mono (still VOICE_CALL only; one-sided UPLINK/DOWNLINK are rejected).
- PCM is written as a WAV file in the app-private `files/recordings` directory.
- A foreground notification remains visible and includes a Stop action.
- Empty streams are rejected; all-zero recordings are labelled silent.
- No MIC fallback or one-sided UPLINK/DOWNLINK fallback is used in the Shizuku path.

This is a device-dependent privileged capture path, not a guarantee of two-sided audio on every model/carrier. CallVault and ShizuCallRecorder were reviewed as open-source references, but their GPL code was not copied. This project remains MIT-licensed. The Shizuku API itself is MIT-licensed.

To use privileged automatic capture, install/start Shizuku/Sui separately, tap **Connect / authorize Shizuku**, and grant the request. Then arm **best available automatic recording** from Diagnostics before a call. If Shizuku's UserService and shell audio attribution are ready, the service fixes the session to privileged `VOICE_CALL`; otherwise it chooses Speakerphone + MIC if `RECORD_AUDIO` is granted. A Shizuku failure never downgrades to microphone capture. The chosen mode stays fixed until disarmed. On non-root phones, starting Shizuku uses Wireless Debugging on Android 11+; Android 10 and below require a computer running USB ADB. Rooted devices can start Sui directly. The capture API itself is eligible on API 24+ once the helper is already running and authorized. Re-arm after reboot or force-stop.

Read the detailed research and safety constraints:

- [Android call-audio constraints and implementation decision](docs/ANDROID_CALL_AUDIO_CONSTRAINTS.md)
- [Architecture and module boundaries](docs/ARCHITECTURE.md)
- [Device validation matrix](docs/DEVICE_TEST_MATRIX.md)
- [Capture compatibility matrix and strategy-selection policy](docs/COMPATIBILITY_MATRIX.md)
- [Consent-gated automatic recording lifecycle](docs/AUTO_RECORDING_LIFECYCLE.md)
- [OEM integration research and vendor API decision](docs/OEM_INTEGRATION_RESEARCH.md)
- [Embedded ADB shell capture research](docs/EMBEDDED_ADB_CAPTURE_RESEARCH.md)

## Application screens

- **Recordings:** search, SIM-slot filter, date filter, and newest/oldest sorting.
- **Player:** Media3 ExoPlayer with play/pause, seek/progress controls; only completed readable private WAVs are playable.
- **Settings:** capture strategy candidates, Shizuku status, microphone permission, and local storage quota. Consent-gated automatic best-eligible capture can be armed from Diagnostics; its two-way audio performance remains unverified.
- **Diagnostics:** the SIM-state, audio-route, and Shizuku connection panel remains available as a dedicated tab.

## Diagnostics implementation

- Subscription-aware call-state observation using `TelephonyCallback` on API 31+ and `PhoneStateListener` on API 28–30.
- Device diagnostics screen showing device/build information, visible SIM slots, per-SIM call-state events, Android-reported audio input/output endpoints, and live `AudioRecordingCallback` source/silencing diagnostics on API 29+.
- AudioManager communication-route reporting on API 31+.
- Robolectric tests for modern/legacy call-state delivery, multi-SIM slot association, permission enforcement, audio route inventory, and communication-route selection.
- Recording-service controller tests for shell/root UID gating, duplicate starts, stop/restart, and initialization failure/retry.
- WAV file tests for RIFF header correctness, PCM lengths, peak/RMS, and empty-stream rejection.

The diagnostics screen does not read phone numbers, ICCIDs, or call-log data, and does not persist subscription identifiers. The recordings UI supports text, SIM-slot, and date filtering with newest/oldest sorting. WAV playback uses Media3 ExoPlayer and is released with the player screen lifecycle. Settings persist the backend choice and storage quota; the recording service prunes oldest completed private files only after a successful finalization. Recording requires a visible participant-consent acknowledgement, starts only on a SIM slot currently reporting OFFHOOK, and is stopped by the recording service when that slot returns to IDLE. Recording files remain in app-private storage and are not uploaded. The current metadata model keeps phone number nullable; it does not request READ_CALL_LOG because the public telephony callbacks do not consistently expose a number across supported API levels.

## Limits of automated testing

Robolectric can verify framework callback wiring, state transitions, service policy, and PCM/WAV file logic. It cannot emulate the cellular modem, carrier VoLTE path, OEM audio HAL, or prove that both call participants are audible. A green CI run is not a device-compatibility claim.

## Privacy and legal use

- No READ_CALL_LOG, contacts, broad storage, or overlay permission is requested. The optional Accessibility experiment requires explicit enablement in Android Settings and is not suitable for a Google Play call-recording release.
- No microphone permission prompt is needed for the privileged Shizuku UserService path. The explicitly selected microphone/acoustic strategies do capture microphone input and require RECORD_AUDIO.
- Recording currently starts from a visible user action and shows an ongoing foreground notification with Stop. The accepted target is consent-gated automatic best-eligible capture on OFFHOOK; see [the automatic lifecycle design](docs/AUTO_RECORDING_LIFECYCLE.md).
- A Shizuku authorization is an additional privilege grant and must be understood by the user.
- A permission grant is not consent from every call participant. The user must comply with applicable local law and obtain all required consent.
