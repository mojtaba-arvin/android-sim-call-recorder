# Embedded ADB shell capture research

**Research date:** 2026-11-18

## Decision

Investigate a third privileged strategy, separate from Shizuku:

`EMBEDDED_ADB_VOICE_CALL` — an on-device ADB client pairs with Android Wireless debugging, then launches a narrowly scoped `app_process` recorder under shell UID 2000. This can remove the requirement to install the Shizuku companion app, but it does not remove Android's developer-option, ADB, network, OEM, or modem limitations.

This is a research-backed candidate, not an implemented backend or a verified compatibility claim.

## Reference implementation findings

[CallVault](https://github.com/madkongo/CallVault) describes two privileged capture modes: its built-in embedded-ADB mode and Shizuku. Its README reports these constraints for its implementation:

- Android 11+ and 64-bit ARM (`arm64-v8a`).
- Developer options must remain enabled for built-in ADB mode.
- On Android 11, its screen must remain unlocked during calls.
- After every reboot, the device must join Wi-Fi briefly so its debugging access can be re-established.
- Behavior depends on hidden Android APIs and may vary or break across OEM/Android updates.
- OPPO, OnePlus and Realme may deny ADB grants unless the user disables system optimization/permission monitoring. This is a security-sensitive user choice and must never be changed automatically.
- Its README says carrier Wi-Fi calling (VoWiFi/VoLTE) is not covered by its current verification.

These are CallVault's published implementation requirements and claims, not Android platform guarantees and not evidence for this repository.

References:
- https://github.com/madkongo/CallVault
- https://github.com/kitsumed/ShizuCallRecorder

## Proposed architecture

1. User explicitly enables Developer options and Wireless debugging.
2. Pair using Android's TLS pairing-code flow. The user must understand that ADB grants shell-level access.
3. Store pairing identity/keys using Android Keystore-backed storage. Detect revoked/expired pairing and ask for a new pairing.
4. Connect through a constrained embedded ADB client.
5. Launch a fixed `app_process` recorder entry point from this APK's DEX/classpath under shell UID 2000.
6. The daemon attempts only full-duplex `AudioRecord(VOICE_CALL)`. It must not accept `VOICE_UPLINK` or `VOICE_DOWNLINK` alone as a successful call recording.
7. Stream PCM over a narrow, authenticated local IPC channel into the existing WAV finalizer.
8. Expose daemon state, pairing state, last error, actual UID, actual source, channel count and signal metrics in Diagnostics.
9. Keep the ADB daemon, Shizuku UserService, and Standard acoustic service lifecycles separate.

## Library and licensing review

- [libadb-android](https://github.com/MuntashirAkon/libadb-android) is dual-licensed under GPL-3.0-or-later **or** Apache-2.0. Its README notes an LGPL dependency; audit the resolved dependency graph and obligations before adoption.
- Prefer the Apache-2.0 grant if the exact dependency version and transitive dependencies are compatible with this repository's MIT licensing; include the required notices and pin/verify the dependency.
- CallVault and ShizuCallRecorder application code is GPL-3.0. Do not copy their recorder, ADB pairing, scrcpy server, or application code. Use them as architectural research only.
- Do not expose a general-purpose shell or arbitrary command runner in the product UI. The ADB privilege is constrained to a fixed recorder launcher, lifecycle control, and diagnostics.
- Do not expose the pairing endpoint beyond the local device/network or transmit pairing keys off-device.

## Open technical questions

- Can the ADB daemon be relaunched after reboot without user interaction beyond the phone joining Wi-Fi?
- Does ADB pairing expire after inactivity on every supported Android release? What authenticated refresh behavior is safe?
- Which API levels and ABIs can run the embedded client and `app_process` recorder?
- Does Android 17 change the Wireless-debugging service, mDNS discovery, local-network permission, or loopback behavior?
- Can the recorder continue away from Wi-Fi, and under what USB-debugging state?
- Can the shell daemon survive adbd restart, screen lock, app process death, and OEM background restrictions?
- How do Android 11 unlocked-screen constraints and OEM permission-monitoring settings affect the daemon?
- Can the same `VOICE_CALL` source capture VoLTE, VoWiFi, Bluetooth, wired headset, call waiting and both SIM slots? Each requires independent evidence.

## Security model

ADB shell is materially more powerful than a normal application UID. The user must explicitly pair and authorize the device. Pairing keys remain local and are protected at rest. The app never runs arbitrary user-provided commands, never exposes a network shell, never disables Developer options/security settings, and never toggles OEM permission-monitoring settings. Every privilege failure is visible and fails closed.

## Acceptance gates

- License and dependency audit complete; no GPL application code copied.
- Automated tests for pairing state, pairing expiry/revocation, connection loss, daemon launch, process death/restart, IPC framing, PCM integrity and fail-closed behavior.
- Runtime profile reports the actual ADB UID and daemon readiness; a pairing grant alone is not capture readiness.
- Only `VOICE_CALL` is accepted as the full-duplex success source.
- Repeated incoming and outgoing physical calls independently verify local and remote phrases across device/build/carrier/route combinations.
- A non-empty or unsilenced WAV is not accepted as proof of two-way speech.

Tracked implementation: [Issue #6](https://github.com/mojtaba-arvin/android-sim-call-recorder/issues/6).
