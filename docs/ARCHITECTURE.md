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

The diagnostic Flow is collected while the diagnostics screen is active. The current `CallRecordingForegroundService` owns an explicitly started capture session, its notification, stop action, call-end observation and finalization. It does not yet own a persistent idle-time call monitor that automatically starts Standard capture on OFFHOOK.

The target monitor must own its lifecycle, notification, per-subscription call-state listeners, automatic Standard-mode trigger and recovery. Keep domain models independent of Service, MediaRecorder and TelephonyManager.

## Persistence rules

- Recording metadata is represented by Room entities.
- The diagnostics event timeline is in-memory only and is not persisted.
- The initial diagnostic implementation does not collect phone numbers, ICCIDs, subscription IDs for display/storage, or call content.
- No destructive database migration fallback is configured.

## Capture strategy boundary

Capture implementations are registered behind the `CallCaptureStrategy` contract and exposed through a Hilt multibinding set. The current strategy selector evaluates runtime prerequisites and ranks candidates; it does not infer support from an OEM name. The current foreground-service start path still uses the saved selected mode, so end-to-end automatic runtime selection remains outstanding. A source failure is not silently retried with a different audio source.

See [the capture compatibility matrix](COMPATIBILITY_MATRIX.md) for implemented backends, distribution constraints, research references and device acceptance rules. See [the automatic recording lifecycle](AUTO_RECORDING_LIFECYCLE.md) for the consent-gated Standard/Shizuku trigger model and Android FGS constraints.

## Next engineering gates

1. Make runtime capability probing authoritative and wire candidate selection into the foreground-service start path.
2. Keep candidate availability separate from verified two-way support; persist the actual selected strategy and validation evidence.
3. Add automated tests for selection order, missing Shizuku binding, feature-flag behavior, explicit fallback consent, and no silent source substitution.
4. Run call-state scenarios on API 28–30 and API 31+ devices, then validate per-subscription behavior on a physical dual-SIM handset.
5. Establish a physical device/route test matrix for Shizuku, Accessibility-priority microphone, generic VOICE_RECOGNITION, and speakerphone microphone capture.
6. Add vendor-specific backends only when a documented API or reproducible implementation is found and can be tested.
7. Validate crash recovery, process death, OEM power management, atomic file finalization, Room consistency, retention, and deletion on real devices.
