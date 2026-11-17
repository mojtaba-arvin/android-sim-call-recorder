# SIM Call Recorder

A native Android (Kotlin) project investigating reliable SIM-call recording on real devices.

> **Current status:** foundation only. The repository does not yet record calls. Android call-state access and microphone permission do not, by themselves, grant access to both sides of a cellular call. Device and OS feasibility must be demonstrated before an audio recorder is advertised or implemented as production-ready.

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
| CI | GitHub Actions: unit tests, lint, debug APK |

The initial dependency versions are pinned in gradle/libs.versions.toml. The app compiles against Android 17 (API 37) for current AndroidX dependencies, while `targetSdk` remains 36 until Android 17 runtime behavior changes have been explicitly validated. The minimum supported version is Android 9 (API 28).

## Build

Requirements: JDK 17, Android SDK Platform 36, Build Tools 36.0.0, and Gradle 9.6.0.

~~~bash
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
~~~

The first CI workflow uses the same pinned Gradle and SDK versions. A Gradle Wrapper is not checked in yet; it will be added after the first successful build has validated the toolchain.

## Important platform constraint

The public Android SDK exposes VOICE_CALL, VOICE_UPLINK, and VOICE_DOWNLINK audio-source constants, but capturing those streams requires CAPTURE_AUDIO_OUTPUT, a permission reserved for system components and unavailable to ordinary third-party apps. A microphone recording is not equivalent to a two-sided call recording. Some OEM/device combinations may expose different behavior, but that must be proven on named devices and OS builds; it must never be inferred from a successful MediaRecorder.start() call.

See:

- [Android call-audio constraints and feasibility gates](docs/ANDROID_CALL_AUDIO_CONSTRAINTS.md)
- [Architecture and module boundaries](docs/ARCHITECTURE.md)
- [Device validation matrix](docs/DEVICE_TEST_MATRIX.md)

## Current implementation

- Android application and Compose entry point.
- Runtime permission manager for microphone, telephony call-state, and Android 13+ notifications.
- Native call-state source using TelephonyCallback on API 31+ and PhoneStateListener on API 28–30.
- Domain call-state model and repository/use-case boundary.
- Room metadata entity, DAO, database, and Hilt providers.
- Unit tests for telephony-state mapping.
- CI workflow for unit tests, Android lint, and debug APK assembly.

The current call-state skeleton observes the default subscription only. It does not read call-log data or infer the remote phone number. Multi-SIM subscription coverage, recording lifecycle, audio-path capability testing, and device validation remain separate work items.

## Privacy and safety

- No READ_CALL_LOG, contacts, broad storage, overlay, or accessibility permission is requested.
- Permissions are requested just in time: the foundation initially asks only for call-state access; microphone and notification permissions are deferred until their respective features are implemented and entered by the user.
- Audio files are intended for app-private storage; the database stores a generated filename rather than an externally accessible path.
- A permission grant is not treated as consent from every call participant.
- Recording must be clearly indicated to the device user, and applicable local consent laws must be followed.
