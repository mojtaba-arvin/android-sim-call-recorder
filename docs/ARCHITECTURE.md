# Architecture

## Dependency direction

The project uses a small Clean Architecture boundary:

~~~text
Presentation (Compose / Activity)
          |
          v
Domain (CallState, repository contract, use case)
          ^
          |
Data (Telephony source, repository implementation, Room)
          |
          v
Android platform APIs / SQLite

Hilt wires application-scoped implementations.
~~~

The domain layer does not depend on Android framework classes. Telephony state integers are translated into the domain CallState model inside the data layer.

## Current packages

- core.permissions: runtime permission evaluation and request list.
- domain.model: platform-independent call-state model.
- domain.repository: call-state abstraction.
- domain.usecase: call-state observation entry point.
- data.telephony: API 31+ TelephonyCallback and API 28–30 PhoneStateListener.
- data.repository: repository adapter.
- data.local: Room database, DAO, and metadata entity.
- di: Hilt bindings and providers.

## Lifecycle boundaries

The current Flow registers a telephony callback when collected and unregisters it when collection is cancelled. It is not a process-persistent service and should not be mistaken for a production background recorder.

The eventual service layer must own its own lifecycle, notification, error reporting, and recording finalization. It must not make the domain layer depend on Service, MediaRecorder, or TelephonyManager.

## Persistence rules

- Recordings are represented by metadata rows.
- The generated audio filename is relative to an app-private directory; arbitrary filesystem paths are not accepted.
- Phone number is nullable because it may not be available and is not collected by the initial permission set.
- Schema export is configured through KSP. Schema changes must be reviewed and committed alongside migrations.
- No destructive database migration fallback is configured.

## Next engineering gates

1. Build and lint the foundation in CI.
2. Validate call-state notifications and lifecycle across API 28, 31, 34, 35, and 36.
3. Implement a subscription-aware observer for dual-SIM devices.
4. Prove an audio path on a small, named device matrix before implementing the recorder.
5. Add a microphone foreground service only with a tested, user-initiated lifecycle and visible disclosure.
6. Implement atomic file finalization, Room metadata insertion, crash recovery, retention, and deletion together.
