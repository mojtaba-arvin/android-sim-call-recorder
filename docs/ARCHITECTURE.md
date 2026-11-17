# Architecture

## Dependency direction

The project uses a small Clean Architecture boundary:

~~~text
Presentation (Compose / Activity)
          |
          v
Domain (call events, SIM slots, audio route models, repository contract)
          ^
          |
Data (subscription-aware telephony, audio device inventory, Room)
          |
          v
Android platform APIs / SQLite

Hilt wires application-scoped implementations.
~~~

The domain layer does not depend on Android framework classes. Telephony state integers are translated into the domain CallState model inside the data layer.

## Current packages

- core.permissions: runtime permission evaluation and purpose-specific permission groups.
- domain.model: call state, per-SIM call event, visible SIM slot, and audio route models.
- domain.repository: diagnostic repository abstraction.
- data.telephony: API 31+ TelephonyCallback and API 28–30 PhoneStateListener, each created from a subscription-specific TelephonyManager.
- data.audio: input/output endpoint inventory from AudioManager.
- data.repository: diagnostics repository adapter.
- data.local: Room database, DAO, and recording metadata entity.
- di: Hilt bindings and providers.

## Multi-SIM behavior

The diagnostics path obtains the active subscriptions visible to the app using READ_PHONE_STATE. Each visible subscription is mapped to a SIM slot label; the subscription identifier is not rendered or persisted. A TelephonyManager created for each subscription observes its own call-state events.

The legacy listener is constructed and registered on the main dispatcher for API 28–30. API 31+ uses TelephonyCallback with the app main executor. Cancellation unregisters every subscription-specific callback/listener.

The initial monitor uses a snapshot of active subscriptions taken when monitoring starts. Refresh the diagnostics screen after a SIM is inserted, removed, enabled, or disabled. Dynamic subscription-change monitoring is not implemented yet.

## Audio route inventory

AudioManager.getDevices(GET_DEVICES_ALL) is used to list audio inputs and outputs. On API 31+, AudioManager.getCommunicationDevice identifies the selected communication endpoint. Device addresses, Bluetooth MAC addresses, phone numbers, and call content are not collected.

The inventory does not prove that cellular call uplink/downlink audio is available to the app. It only records which audio endpoints Android currently reports.

## Lifecycle boundaries

The diagnostic Flow is collected while the diagnostics screen is active. It is not a process-persistent service and should not be mistaken for a production background recorder.

The eventual service layer must own its own lifecycle, notification, error reporting, and recording finalization. It must not make the domain layer depend on Service, MediaRecorder, or TelephonyManager.

## Persistence rules

- Recording metadata is represented by Room entities.
- The diagnostics event timeline is in-memory only and is not persisted.
- The initial diagnostic implementation does not collect phone numbers, ICCIDs, subscription IDs for display/storage, or call content.
- No destructive database migration fallback is configured.

## Next engineering gates

1. Build and lint the diagnostic screen in CI.
2. Run the documented call-state scenarios on at least one API 28–30 device and one API 31+ device.
3. Validate per-subscription behavior on a physical dual-SIM handset.
4. Prove an audio path on a small, named device/route matrix before implementing the recorder.
5. Add a microphone foreground service only with a tested, user-initiated lifecycle and visible disclosure.
6. Implement atomic file finalization, Room metadata insertion, crash recovery, retention, and deletion together.
