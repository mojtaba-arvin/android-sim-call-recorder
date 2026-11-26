# Android API compatibility audit

Reviewed: 2026-11-24
Branch: `work/api24-compatibility-floor`

This is a software/API compatibility audit. It does not claim cellular call-audio support on any device or Android release.

## Declared floor and dependency metadata

- App and SDK `minSdk`: API 24 (Android 7.0), pending the exact-head API 24 emulator gate.
- Shizuku API/provider dependency: 13.1.5. The upstream Shizuku build config declares minSdk 24; the product selector intentionally gates privileged Shizuku capture to API 26+. API 24–25 remain app/SDK install-floor targets for non-Shizuku paths. The API/provider AAR metadata is still checked by Gradle rather than inferred from the project source.
- The actual resolved AAR metadata remains authoritative. CI explicitly runs `:callrecorder-sdk:checkDebugAarMetadata` and `:app:checkDebugAarMetadata` with minSdk 24; any dependency declaring a higher minSdk fails the required gate.
- AndroidX, Compose, Room, Media3, Hilt, DataStore, and test dependency constraints are checked from resolved Gradle metadata by those tasks, not guessed from artifact names.

## Framework APIs and guards

| API / contract | First API | Source location | Compatibility behavior |
|---|---:|---|---|
| `AudioDeviceInfo` and `AudioManager.getDevices()` | 23 | `data/audio/AudioDeviceInventory.kt`, `data/audio/AndroidAudioRouteDataSource.kt` | Available at API 24. Device type values are inventory labels only; inventory does not imply call-audio access. |
| `TelephonyManager.createForSubscriptionId()` | 24 | `data/telephony/AndroidTelephonyCallStateDataSource.kt` | Available at the declared API 24 floor. |
| `PhoneStateListener` subscription call-state path | 1 / multi-SIM APIs 22+ | `data/telephony/AndroidTelephonyCallStateDataSource.kt` | Legacy path is used below API 31; listener creation, registration, and cleanup are dispatched on Main. |
| `SubscriptionManager.addOnSubscriptionsChangedListener(Executor, listener)` | 30 | `data/telephony/AndroidTelephonyCallStateDataSource.kt` | Used on API 30+; API 24–29 use the Looper-backed overload on Main. Listener changes reconcile per-subscription observers without cancelling unchanged subscriptions. |
| `TelephonyCallback` / `registerTelephonyCallback()` | 31 | `data/telephony/AndroidTelephonyCallStateDataSource.kt` | Guarded by `SDK_INT >= S`; older devices use `PhoneStateListener`. A current call-state snapshot is emitted after registration so an already-active call is not missed. |
| `NotificationChannel` / `createNotificationChannel()` | 26 | `recording/CallRecordingForegroundService.kt` | `createNotificationChannel()` returns before touching channel APIs on API 24–25. The foreground notification itself remains required. |
| `AudioRecordingCallback` | 24 | `recording/MicrophoneCallCaptureStrategy.kt` | The app registers it only on API 29+ because the consumed `isClientSilenced()` diagnostic is API 29+. |
| `AudioRecordingConfiguration.isClientSilenced()` | 29 | `recording/MicrophoneCallCaptureStrategy.kt` | Guarded by the API 29+ callback path; it is diagnostic-only and does not prove two-way speech. |
| `AudioManager.availableCommunicationDevices`, `getCommunicationDevice()`, `setCommunicationDevice()`, `clearCommunicationDevice()` | 31 | `recording/MicrophoneCallCaptureStrategy.kt`, `data/audio/AudioDeviceInventory.kt` | Guarded by `SDK_INT >= S`; older releases use the legacy speakerphone mode request. |
| Runtime notification permission `POST_NOTIFICATIONS` | 33 | `core/permissions/CallPermissionManager.kt`, `app/MainActivity.kt` | Requested and enforced only on API 33+; older releases do not expose this runtime permission. |
| Microphone foreground-service type | 29/30 | `recording/CallRecordingForegroundService.kt`, SDK manifest | Service type flags are selected only on supported API levels; API 24–28 use the legacy foreground-service call. |
| Special-use foreground-service type and subtype property | 34 | SDK manifest and `CallRecordingForegroundService.kt` | Runtime type selection is guarded by API 34; older platforms use supported foreground-service behavior. |
| `ContextCompat.startForegroundService()` | AndroidX backport | `app/MainActivity.kt` | AndroidX dispatches to the service-start path supported by the current OS. |

## Additional library compatibility APIs

- `java.time.LocalDate` in the app date filter is supported below API 26 through core-library desugaring (`desugar_jdk_libs:2.1.5`).
- `java.nio.file.Files.move` is not used for WAV promotion; `File.renameTo()` performs same-directory native rename promotion on Android/Linux without an API 26 dependency.
- Dynamic subscription-list reconciliation is included in this branch: the API 30 Executor overload is guarded, legacy listener construction/registration occurs on Main, and no-active-SIM is a waiting state.

## Compatibility test coverage

- Robolectric legacy telephony test covers API 24–30.
- Robolectric modern telephony test covers API 31–37.
- Audio route mapping is exercised on API 24, 26, and 35.
- CI emulator instrumentation matrix is API 24, 26, and 35.
- A successful API 24 build/emulator run establishes software compatibility only. It does not establish a modem, carrier, OEM, route, or two-way audio result.

## Deliberate non-goals

- Do not lower to API 23: the current Shizuku API/provider artifacts are built for API 24.
- Do not infer Shizuku Wireless Debugging setup on API 24–29; non-root Shizuku startup on those releases requires external ADB, while already-running Shizuku/Sui or root may be used.
- Do not claim API 24 improves cellular call-audio capture. It expands install and software lifecycle coverage only.
