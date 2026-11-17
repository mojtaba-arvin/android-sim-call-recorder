# SIM Call Recorder

A native Android (Kotlin) project investigating reliable SIM-call recording on real devices.

> **Current status:** native foundation and automated Android-framework tests. The repository does not yet claim to record cellular calls. Android call-state access, microphone permission, foreground services, and audio endpoint inventory do not grant access to both sides of a cellular call.

## Project goals

- Native Kotlin implementation; no cross-platform runtime or accessibility-service workaround.
- Android 9 (API 28) and newer, with a current target SDK.
- Explicit separation between call-state detection, audio-capture capability, recording lifecycle, persistence, and UI.
- App-private audio storage and local metadata; no broad storage permission.
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
| Tests | JUnit and Robolectric 4.17 |
| CI | GitHub Actions: unit tests, Robolectric, lint, debug APK |

The initial dependency versions are pinned in gradle/libs.versions.toml. The app compiles against Android 17 (API 37) for current AndroidX dependencies, while `targetSdk` remains 36 until Android 17 runtime behavior changes have been explicitly validated. The minimum supported version is Android 9 (API 28).

## Build

Requirements: JDK 21, Android SDK Platform 37, Build Tools 37.0.0, and Gradle 9.6.0.

```bash
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The test task executes the existing JVM unit tests and Robolectric tests against Android framework shadows. GitHub Actions runs the same tasks on every branch push and pull request. A Gradle Wrapper is not checked in yet; it will be added after the first successful build has validated the toolchain.

## Important platform constraint

The public Android SDK exposes `VOICE_CALL`, `VOICE_UPLINK`, and `VOICE_DOWNLINK` audio-source constants, but capturing cellular call streams requires `CAPTURE_AUDIO_OUTPUT`, a permission reserved for system components and unavailable to ordinary third-party apps. During an active voice call Android's input-sharing rules give the call priority; a normal app cannot assume that `MIC` or `VOICE_COMMUNICATION` will receive call audio. A microphone recording is not equivalent to a two-sided call recording.

The open-source [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) uses a separate Shizuku/ADB shell helper and hidden Android APIs; it is not a public-SDK source fallback. The project and compatibility implications are documented here:

- [Android call-audio constraints and feasibility gates](docs/ANDROID_CALL_AUDIO_CONSTRAINTS.md)
- [Architecture and module boundaries](docs/ARCHITECTURE.md)
- [Device validation matrix](docs/DEVICE_TEST_MATRIX.md)

## Current implementation

- Android application and Compose entry point.
- Runtime permission manager for microphone, telephony call-state, and Android 13+ notifications.
- Subscription-aware call-state diagnostics using `TelephonyCallback` on API 31+ and `PhoneStateListener` on API 28–30.
- Device diagnostics screen showing device/build information, visible SIM slots, per-SIM call-state events, and reported audio input/output endpoints.
- Domain call-state model and repository/use-case boundary.
- Room metadata entity, DAO, database, and Hilt providers.
- Unit tests for telephony-state mapping.
- Robolectric integration tests for modern/legacy call-state delivery, multi-SIM slot association, permission enforcement, audio route inventory, and communication-route selection.
- CI workflow for unit tests, Robolectric, Android lint, and debug APK assembly.

The diagnostics screen observes each active subscription visible to the app and shows the Android-reported audio endpoint inventory. It does not read call-log data, phone numbers, ICCIDs, or persist subscription identifiers. This is an on-screen diagnostic session only; it is not a background service and does not record audio. Robolectric coverage does not replace physical modem/audio-HAL validation.

## Privacy and safety

- No READ_CALL_LOG, contacts, broad storage, overlay, or accessibility permission is requested.
- Permissions are requested just in time: the foundation initially asks only for call-state access; microphone and notification permissions are deferred until their respective features are implemented and entered by the user.
- Audio files are intended for app-private storage; the database stores a generated filename rather than an externally accessible path.
- A permission grant is not treated as consent from every call participant.
- Recording must be clearly indicated to the device user, and applicable local consent laws must be followed.
