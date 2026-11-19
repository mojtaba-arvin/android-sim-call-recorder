# Architecture

## System shape

The repository is a native Kotlin Android application plus a reusable Android library:

- `:callrecorder-sdk` owns the recording engine, capture strategies, telephony integration, foreground services, Shizuku UserService integration, Room persistence, and storage/finalization.
- `:app` is the sample/consumer host. It owns Compose presentation, permission and consent UX, playback, diagnostics presentation, and SDK integration tests.
- The app consumes the SDK through the public `SimCallRecorderSdk` facade and public domain contracts. Implementation packages, repositories, DAOs/entities, services, platform adapters, capture backends, and DI modules remain internal. `scripts/verify-sdk-boundary.sh` enforces the import boundary.

The SDK provides Hilt bindings and manifest contributions; the host app owns the Hilt application component and the user-facing runtime permission/consent flow.

## Dependency direction

~~~text
:app presentation / permission UX / playback / diagnostics UI
                         |
                         v
             Public SimCallRecorderSdk facade
                         |
                         v
:callrecorder-sdk public domain contracts and models
                         |
              +----------+----------+
              |                     |
              v                     v
       Recording orchestration   Diagnostics/data adapters
              |                     |
              v                     v
   Capture strategy contracts   Telephony / AudioManager
              |                     |
       +------+-------+             v
       |              |          Room / SQLite
       v              v
 Shizuku VOICE_CALL  Standard acoustic
 UserService         Speakerphone + MIC
       |              |
       +------ PCM ---+
              |
              v
     WAV finalization + Room metadata
~~~

The domain contracts should not depend on Android framework implementation classes. Platform callback values are translated at the platform/data boundary. Hilt wires application-scoped implementations.

## Runtime and lifecycle ownership

- The diagnostics screen observes presentation-oriented diagnostic streams; its visibility does not own recording or automatic-monitoring lifecycle.
- The user explicitly arms best-eligible automatic recording while the app is visible, after permission, disclosure, and consent gates.
- At arm time, the service snapshots eligibility and fixes the session to Shizuku `VOICE_CALL` when the live UserService, accepted UID, and shell audio attribution are ready; otherwise it can select the separately disclosed Standard acoustic strategy when microphone permission is granted.
- The selected mode remains fixed until disarm. A Shizuku failure never downgrades to microphone capture.
- The mode-specific foreground service owns subscription-scoped call-state monitoring, matching-SIM OFFHOOK start, matching-SIM IDLE finalization, persistent notification/Stop behavior, and session cleanup.
- Reboot and force-stop do not silently restore monitoring; the user must re-arm from the visible app.

See [the consent-gated automatic recording lifecycle](AUTO_RECORDING_LIFECYCLE.md) for the complete state and failure contract.

## Capture privilege and evidence boundaries

- Standard acoustic capture is microphone-based and may use its bounded source initialization sequence. It is not digital cellular downlink and may not contain the remote participant.
- Shizuku uses a narrow UserService boundary and accepts only the validated shell/root identity and attribution required by the helper. It attempts full-duplex `VOICE_CALL`; it does not silently switch to MIC or one-sided sources.
- Shizuku readiness, UID, source initialization, a non-empty WAV, non-zero RMS, or a green emulator test is not proof of two-way speech.
- Only repeated physical calls with separately assessed near-end and far-end intelligibility can establish support for a specific device/build/carrier/SIM/route combination.

See [Android call-audio constraints](ANDROID_CALL_AUDIO_CONSTRAINTS.md), [the compatibility matrix](COMPATIBILITY_MATRIX.md), and [the device test matrix](DEVICE_TEST_MATRIX.md).

## Multi-SIM and diagnostics

- Telephony observation uses subscription-specific `TelephonyManager` instances: `TelephonyCallback` on API 31+ and `PhoneStateListener` on API 28–30.
- Subscription identifiers are operational inputs only; do not display or persist them. Present the SIM slot label where appropriate.
- Audio endpoint inventory comes from `AudioManager.getDevices(GET_DEVICES_ALL)`; on API 31+, the selected communication endpoint is available through `getCommunicationDevice`.
- Endpoint inventory describes Android-reported devices; it does not establish access to cellular call audio.
- Diagnostics must not collect phone numbers, ICCIDs, call content, or audio samples.

## Persistence and file lifecycle

- Room stores recording metadata; diagnostics timelines are in-memory unless an explicit privacy-reviewed requirement changes that.
- WAV data is written to app-private storage and finalized before the corresponding row is treated as completed/playable.
- Before starting a new capture backend, the service awaits `CallRecordRepository.prepareForCapture()`. This forces first-open process recovery to finish before any new `.wav.part` file can be created.
- First-open process recovery marks stale active rows interrupted, removes orphaned temporary `.wav.part` files, and preserves already promoted `.wav` files.
- A crash after file promotion but before the Room completion update must not cause the file to be deleted or the row to be treated as successfully completed.
- Retention and deletion must not remove an active recording or the last valid recording as a side effect of a failed replacement.

## Current package responsibilities

Package names may evolve; verify the actual source tree before changing them.

- `core.permissions`: runtime permission evaluation and purpose-specific permission groups.
- `domain.model`: call state, per-SIM call event, visible SIM slot, audio-route and recording models.
- `domain.repository`: domain-facing repository contracts.
- `data.telephony`: subscription-aware telephony callbacks/listeners.
- `data.audio`: AudioManager endpoint and recording diagnostics.
- `data.local`: Room database, DAO, migrations, and recording metadata.
- `data.repository`: repository adapters and persistence coordination.
- `di`: Hilt bindings and providers.
- Capture/service packages: strategy implementations, foreground-service lifecycle, Shizuku bridge, and WAV pipeline.

## Known gaps and validation gates

- Physical-device verification of both participant directions remains outstanding. Do not claim OEM/carrier/route compatibility without recorded evidence.
- Dynamic subscription-change handling and per-SIM behavior require continued physical-device validation.
- Runtime diagnostic persistence, crash-window fault injection, and the remaining roadmap acceptance tests must be checked against the current implementation before claiming completion.
- API 26 is an experimental software compatibility floor, not a promise of cellular audio support.

See [the engineering roadmap](ROADMAP.md) for prioritized unfinished work and [SDK integration](SDK_INTEGRATION.md) for the supported consumer API.
