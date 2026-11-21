# Android Telecom call streaming and embedded ADB: research decision

**Research date:** 2026-11-19  
**Scope:** determine whether newer Android Telecom APIs or maintained embedded-ADB clients create a practical new SIM-call recording backend.

## Executive decision

No public Android Telecom API found in this review grants a third-party app access to both directions of an ordinary cellular/SIM call. Two implementation tracks remain worth separating:

1. **Do not implement InCallService / CallStreamingService as a SIM-audio capture backend.** These APIs manage call UI, call endpoints, or streaming of calls owned by a participating VoIP app. They do not grant protected PSTN uplink/downlink.
2. **Advance embedded Wireless ADB as a separate privileged transport prototype**, using a maintained Kotlin ADB client rather than copying a GPL recorder. This can provide a second route to a shell-UID helper, independent of Shizuku installation, but it does not grant any additional audio permission beyond what the live shell identity actually has.

The Android 16 `CALL_AUDIO_INTERCEPTION` API remains a separate API-36+ research path: current AOSP contracts expose protected downlink extraction and uplink injection, not a public full-duplex recording pair. Do not merge it into the recorder selector until both local and remote tracks are proven on hardware.

## 1. InCallService and default-dialer role

The public `InCallService` contract exposes call lifecycle, call state, mute and audio-endpoint controls. It does not expose a cellular call PCM stream. Android's Telecom package documentation describes it as the UI side of a replacement dialer; it must handle calls from Telecom without assuming every call is SIM telephony.

The default-dialer role is therefore not a substitute for `CAPTURE_AUDIO_OUTPUT`. A September 2026 public developer-community response explicitly states that a third-party app remains unable to access call audio even when it holds the default phone-handler role. This is consistent with the platform permission contract and a reported Android 16 Xiaomi/MediaTek test where a third-party dialer received `EX_SECURITY` from `VOICE_CALL`, while the OEM dialer recorder worked under OEM privilege.

**Decision:** do not add a default-dialer role request or `InCallService` just to record calls. It would materially expand product permissions and UX without solving the audio entitlement.

References:
- https://developer.android.com/reference/android/telecom/InCallService
- https://developer.android.com/reference/android/telecom/package-summary
- https://developer.android.com/reference/android/media/MediaRecorder.AudioSource
- https://support.google.com/googleplay/games-on-pc-developer/thread/468919175/official-api-for-cellular-call-recording-in-a-default-dialer

## 2. CallStreamingService / startCallStreaming

`CallControl.startCallStreaming()` (API 34+) requests Telecom to bind a `CallStreamingService` so a general call-streaming sender can stream local-device audio to another remote device and control that streaming session. The associated `CAPABILITY_SUPPORTS_CALL_STREAMING` is documented for VoIP `PhoneAccount`s.

This is a call-continuity/remote-device feature for an app-managed call, not an API for extracting PSTN call audio from the cellular modem. It does not document a way for an unrelated recorder to attach to the default dialer's SIM call.

**Decision:** reject as a SIM recorder backend. Revisit only if the product later owns a VoIP call and needs call handoff/continuity; that is a different product scope.

References:
- https://developer.android.com/reference/android/telecom/CallControl#startCallStreaming(java.util.concurrent.Executor,android.os.OutcomeReceiver)
- https://developer.android.com/reference/android/telecom/PhoneAccount#CAPABILITY_SUPPORTS_CALL_STREAMING
- https://developer.android.com/reference/androidx/core/telecom/CallsManager

## 3. Android 16 CALL_AUDIO_INTERCEPTION

The separate API-36+ research document already records the hidden/system API boundary. Reconfirmed implications:
- `CALL_AUDIO_INTERCEPTION` is a privileged/system integration surface, not a normal runtime permission that a consumer APK can request and receive.
- The documented downlink extraction method is a receive-side track.
- The uplink method is an injection AudioTrack, not an uplink recording source.
- A possible shell experiment is downlink extraction plus an independently captured local microphone, but Android's call input-sharing rules and OEM audio policy may silence or deny the microphone concurrently.

**Decision:** keep the experiment out of production selection. The next safe implementation increment, if pursued, is a read-only capability probe in the Shizuku helper (actual permission check + API availability + interceptable state) and separate-track instrumentation; it must never claim two-way audio until physical evidence passes.

References:
- https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r3/core/api/system-current.txt
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/media/java/android/media/AudioManager.java
- https://developer.android.com/media/platform/sharing-audio-input

## 3A. Android 16 permission model correction from AOSP source audit

A deeper source-level audit found an important distinction between the public `AudioSource` documentation and the current AOSP AudioPolicy implementation:

- The Android 16+ AudioPolicy input path checks `CALL_AUDIO_INTERCEPTION` first for `VOICE_CALL`, `VOICE_UPLINK`, and `VOICE_DOWNLINK`.
- For these historical/legacy audio sources, it retains `CAPTURE_AUDIO_OUTPUT` as a fallback if the newer permission is absent.
- Both permissions are protected (`signature|privileged|role`) in current AOSP; neither is an ordinary runtime permission available to a consumer APK.
- Correction after full-manifest inspection: Android 16 AOSP Shell **does declare both** `CAPTURE_AUDIO_OUTPUT` (GTS DialerAudio test) and `CALL_AUDIO_INTERCEPTION` (CTS CallAudioInterception test). `packages/Shell/Android.bp` builds Shell with `certificate: "platform"` and `privileged: true`; both permissions are `signature|privileged|role`. This strongly suggests the stock AOSP shell UID is eligible for these grants. Still query the actual runtime grant from the UserService UID on every device; OEMs and product builds can differ. Do not infer success from Shizuku's connected state alone.
- Root via Sui is a distinct path, but even root must still pass AudioRecord initialization and the physical two-way audio acceptance test.

This exposed a readiness-gate gap in the current implementation: the Shizuku helper checked only `CAPTURE_AUDIO_OUTPUT`. A follow-up implementation PR [#29](https://github.com/mojtaba-arvin/android-sim-call-recorder/pull/29) now accepts either `CALL_AUDIO_INTERCEPTION` or the legacy `CAPTURE_AUDIO_OUTPUT` for shell UID, keeps ordinary app UID rejected, and adds unit tests for both grant paths. The runtime engine still attempts only `VOICE_CALL`.

Primary AOSP references:
- [Android 16 Shell manifest](https://android.googlesource.com/platform/frameworks/base/+/android16-release/packages/Shell/AndroidManifest.xml)
- [Current permission declarations](https://android.googlesource.com/platform/frameworks/base/+/master/core/res/AndroidManifest.xml)
- [Current AudioPolicy input permission enforcement](https://android.googlesource.com/platform/frameworks/av/+/master/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp)
- [AudioManager protected interception methods](https://android.googlesource.com/platform/frameworks/base/+/master/media/java/android/media/AudioManager.java)

## 4. Embedded Wireless ADB: implementation candidate

A current Kotlin option surfaced in this review: **Kadb** (`com.flyfishxu:kadb:2.1.5`) documents Android/JVM support, Wireless Debugging pairing, TLS, mDNS discovery, shell, and sync APIs. Its stated scope is a direct ADB client, not a full ADB server. Another mature Android-focused reference, LADB, demonstrates the on-device pairing/session flow. The older `libadb-android` repository explicitly warns that it has not undergone a security audit; it should not be selected without a focused audit.

Candidate comparison:
- **Kadb:** rejected for this MIT-licensed app despite its Apache-2.0 top-level license and Android minSdk 23. Its Android module depends on `com.github.Flyfish233:spake2-java:1.1.1`, which is GPL-3.0-only, and also includes `org.lsposed.hiddenapibypass:hiddenapibypass:6.1`. The latter bypasses non-SDK API restrictions; its upstream documentation warns that Google Play disallows hidden APIs and that runtime compatibility must be tracked. Do not integrate Kadb as-is. The core license does not clear its runtime dependency graph.
- **LADB:** useful architecture/reference for pairing UI, mDNS, TLS identity and lifecycle. Do not copy implementation until license and dependency audit are complete.
- **libadb-android:** protocol reference only until security review; upstream disclaimer says no security audit.

Prototype boundary:
1. Pair only through Android's user-visible Wireless Debugging pairing UI; never silently enable debugging or accept a PIN from an untrusted source.
2. Persist only the ADB host key/certificate material required by the library, encrypted using Android Keystore-backed storage where supported; provide an explicit disconnect/revoke control.
3. Discover only local mDNS `_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp` endpoints; do not expose a general remote shell.
4. Expose a fixed, audited command allow-list or one narrowly defined `app_process` launch. No arbitrary shell textbox, command concatenation, file browsing, package management, or port forwarding.
5. Start a dedicated helper under UID 2000 and verify UID, package/attribution state, actual `CAPTURE_AUDIO_OUTPUT` and (API 36+) `CALL_AUDIO_INTERCEPTION` grants before reporting readiness.
6. Use a narrow authenticated local IPC protocol to the app; enforce one session, timeouts, cancellation, process cleanup and no automatic reconnect that silently re-arms recording.
7. Treat Wireless ADB authorization as a broad device-control grant. Explain the scope and risk to users; keep this optional and off by default.
8. ADB transport success is not audio success. Preserve the same fail-closed audio acceptance gate and test each device/build/carrier/route.

**Decision:** authorize a research spike, not production integration. First task is a dependency/license/security audit and a throwaway pairing/shell proof on API 30+; only after that create a constrained helper transport. Do not add Kadb to the app dependency graph in this documentation-only change.

References:
- https://github.com/flyfishxu/Kadb (README, LICENSE, module build files and dependency catalog; reviewed 2026-11-19)
- https://github.com/Flyfish233/spake2-java (SPAKE2 dependency used by Kadb; current Maven metadata and repository LICENSE identify GPL-3.0; source also limits its scope to ADB pairing and disclaims general-purpose PAKE assurance)
- https://github.com/LSPosed/AndroidHiddenApiBypass (hidden-API bypass behavior and Play-distribution warning)
- https://github.com/rhythmcache/adb-kt (Apache-2.0 ADB client candidate)
- https://github.com/rhythmcache/spake2-kt (Apache-2.0 SPAKE2 candidate; based on BoringSSL/AOSP; not independently audited)
- https://github.com/tytydraco/LADB
- https://github.com/MuntashirAkon/libadb-android

## 5. Revised strategy priority

| Priority | Candidate | Next action | Production status |
|---|---|---|---|
| 1 | Shizuku `VOICE_CALL` | Keep; strengthen service/recovery tests and physical matrix | Implemented prototype; hardware unverified |
| 2 | Embedded Wireless ADB shell helper | Review adb-kt/spake2-kt cryptography and dependencies; API 30+ pairing proof; fixed-command helper | Research spike authorized; not implemented |
| 3 | API 36 protected downlink + local mic | Read-only capability probe, then separate-track experiment on eligible shell/system builds | Research only; not a full-duplex API |
| 4 | OEM/system signed integration | Seek official partner API/permission contract | Partnership-only |
| 5 | Speakerphone + MIC | Keep as explicit acoustic experiment with route-specific evidence | Implemented; not digital downlink |
| Rejected | InCallService/default dialer role as capture privilege | No implementation | Not a call-audio entitlement |
| Rejected | CallStreamingService as SIM-call tap | No implementation | VoIP call continuity only |

## Acceptance and safety

- No new strategy is considered supported because a library pairs, shell UID is 2000, an AudioRecord opens, or a file is non-empty.
- Never request or exploit hidden permissions on ordinary app UID.
- Never execute arbitrary shell commands.
- Never copy GPL code into this MIT repository without an explicit license decision.
- No automatic fallback between privileged digital capture and acoustic microphone capture.
- Physical acceptance remains separate local/remote phrase intelligibility across repeated incoming/outgoing calls, SIMs and routes.

## 6. Engineering assessment of the three approaches considered

The three candidates are not three equivalent audio APIs. One is a privileged source already prototyped, one is a newer protected receive/inject API pair, and one is a transport for obtaining shell identity.

### A. Shizuku UserService + `AudioRecord(VOICE_CALL)`

**What it can do:** ask AudioFlinger/AudioPolicy for the full call mix through the legacy `VOICE_CALL` source, but only when the actual helper UID passes the protected permission gate and the device's audio policy/HAL supports the source. The project keeps 48 kHz PCM16 stereo as the preferred format and mono as the only format fallback.

**What blocks it:** Android 16+ AOSP checks `CALL_AUDIO_INTERCEPTION` first for VOICE_CALL and retains `CAPTURE_AUDIO_OUTPUT` as a legacy fallback. Both are signature/privileged/role permissions. The Android 16 Shell manifest requests both, and the Shell package is platform-signed/privileged, so stock AOSP is expected to grant them to shell UID 2000. OEM builds may differ; verify both permissions from the live helper. Even with permission, AudioRecord may be rejected, silent, one-sided, or unsupported by the modem/HAL/route.

**Project status:** existing prototype; the permission gate correction and unit tests are in [PR #29](https://github.com/mojtaba-arvin/android-sim-call-recorder/pull/29). It is not hardware-verified. No one-sided or microphone fallback is allowed.

**Next decisive experiment:** on at least one stock Android 16 device and one OEM Android 16 device, record the helper's actual UID, both permission results, shell attribution readiness, AudioRecord initialization/read errors, and separate local/remote phrase intelligibility. If either permission is unexpectedly denied on stock AOSP, capture package/permission state and investigate the build configuration before classifying the backend as unsupported.

### B. Android 16+ `CALL_AUDIO_INTERCEPTION`

**What it can do:** when permission and Telecom's call-redirection/screening mode requirements are met, `isPstnCallAudioInterceptable()` can report whether a PSTN call is interceptable; `getCallDownlinkExtractionAudioRecord()` creates a receive-side track. The paired `getCallUplinkInjectionAudioTrack()` is an injection output, not a recording input.

**What it cannot do:** it is not a public two-track recorder. The API does not provide a local-microphone recording track. Composing downlink extraction with a separate local MIC/VOICE_RECOGNITION capture remains a separate hypothesis, because call input-sharing may silence the ordinary microphone client. The API is `@SystemApi`/`@TestApi` and protected by `CALL_AUDIO_INTERCEPTION` (`signature|privileged|role`); consumer apps cannot request it through a runtime prompt. The Android 16 Shell manifest does declare this permission for CTS, and Shell is platform-signed/privileged; the live helper UID's grant must still be checked.

**Project status:** research only. No API call, reflection path, selector entry, or audio backend has been added.

**Next implementation slice:** a read-only API-36+ capability probe in the Shizuku helper: report API availability, actual `CALL_AUDIO_INTERCEPTION` grant, `isPstnCallAudioInterceptable()` result, and exact failure category. Only after the probe passes on real hardware should a test-only downlink track be opened. Keep downlink and local mic in separate files/tracks with independent signal metrics; never merge or mark success unless both phrases are intelligible and the microphone is not silenced.

### C. Embedded Wireless ADB + fixed helper launch

**What it can do:** implement the ADB host side inside the APK, pair with Android's user-visible Wireless Debugging pairing code, establish TLS, authenticate a host key, and use a narrowly fixed shell-v2 operation to start a helper under UID 2000. This can replace the separate Shizuku app/service UX in principle.

**What it cannot do:** it does not grant root or any privilege beyond shell UID. It obtains the same shell UID as ADB-backed Shizuku; because Android 16 Shell requests both protected call-audio permissions and is platform-signed/privileged, embedded ADB may have the same grants as Shizuku on stock AOSP, but it adds no audio capability over Shizuku. It is a transport/bootstrapping alternative, not a new audio source.

**Security/maintenance concerns:** wireless pairing writes a trusted ADB host key to the device's ADB trust store. The key is broad device-debugging authority, not a recorder-only capability. The ADB client library exposes much more than a fixed helper launch (interactive shell, sync, install, root/unroot requests, TCP mode, forwarding, rescue/sideload features). The app must not expose or accidentally call those operations. It would need a dedicated allow-listed adapter, Keystore-backed host-key storage, explicit pair/revoke UX, TLS/mDNS lifecycle handling, timeouts, cancellation, disconnect cleanup, and a threat model.

**Dependency finding:** `adb-kt` currently publishes `com.github.rhythmcache:adb-kt:1.0.13` in its module build, while its README/DOCUMENTATION installation snippets still show `io.github.rhythmcache:adb-kt:1.0.0`. Its version catalog pins `com.github.rhythmcache:spake2-kt:v1.0.2`; that library's own README advertises a different `io.github.rhythmache` coordinate while its Gradle publication uses `com.github.rhythmcache` and version `v1.0.2`. The SPAKE2 source has only a small basic test set (matching/mismatched password and state misuse) in the inspected branch; no independently published AOSP/BoringSSL oracle corpus or independent cryptographic audit was found. Its README says 1.0.0 was cryptographically insecure. This is not sufficient evidence to ship a cryptographic transport in a call recorder.

**Project status:** not implemented. Kadb remains rejected due its GPL-3.0 SPAKE2 and HiddenApiBypass dependencies. `adb-kt`/`spake2-kt` remains a research candidate only; no dependency has been added.

**Next decisive experiment:** before adding a dependency, pin exact immutable commits/releases, resolve the actual published coordinates, verify the complete transitive SBOM and licenses, compare SPAKE2 transcript behavior against AOSP/BoringSSL test vectors, add malformed-peer/fuzz/state-wipe tests, independently review the cryptographic implementation, and prove same-device Wireless Debugging discovery/pairing on API 30+ hardware. Do not proceed to shell launch until all gates pass.

## 7. Recommended order of investment

1. Finish and validate the PR #29 permission gate; it corrects a real Android 16 compatibility gap in the existing backend.
2. Add the read-only Android 16 capability probe. It is lower-risk than opening an audio stream and will reveal whether any target device can use the new API.
3. Run physical tests for Shizuku `VOICE_CALL` on stock AOSP and OEM builds, recording actual grants for both protected permissions. Consider embedded ADB only if direct pairing materially improves setup UX; it is not a separate privilege or capture path.
4. Keep embedded ADB behind an explicit go/no-go decision. It only improves setup UX if the Shizuku flow is a demonstrated bottleneck; it does not improve audio privilege.
5. Treat an OEM-signed/system integration as the only durable route to broad, repeatable privileged call-audio access where OEMs expose no supported user-grantable privilege.


## 8. Additional source-level ADB client audit

The `adb-kt`/`spake2-kt` review was extended from README/dependency metadata into the pairing implementation:

- `adb-kt` uses TLS 1.3 and Bouncy Castle (`BcTlsCrypto`) for the pairing transport, exports 64 bytes with the ADB TLS exporter label, combines that with the six-digit pairing code, then runs SPAKE2 and AES-GCM PeerInfo exchange. This matches the broad AOSP protocol structure; it is not a general-purpose PAKE library.
- The pairing client sets a TCP connect timeout, but the inspected `PairingClient` does not set a socket read timeout or an overall pairing deadline. A peer that stalls after TCP connect can therefore hold the pairing coroutine/thread longer than the connect timeout suggests. A prototype must impose bounded handshake/read deadlines and cancellation.
- The inspected `PairingTlsSocket` parses the presented peer certificate during the initial pairing handshake without ordinary PKI trust validation. This can be part of the intended PAKE bootstrap, but must be verified against AOSP's exact pairing transcript and post-pair certificate pinning before relying on it; it must not be treated as authenticated merely because TLS 1.3 negotiated.
- `PairingIdentity` serializes the long-term RSA private key to PEM. `X509Generator.privateKeyToPem()` uses `privateKey.encoded`; a non-exportable Android Keystore key normally cannot provide PKCS#8 bytes. The library's current pairing path therefore does not directly support a hardware-backed non-exportable key without architectural changes. A software key would need encrypted storage, strict file permissions, backup exclusion, and explicit revoke/rotation handling.
- `PairingConnection` creates a combined password/exporter buffer and the inspected SPAKE2 context stores scalar/password state in byte arrays; the reviewed code does not show an explicit wipe/zeroization lifecycle for these buffers. Treat this as a memory-hygiene review item, not a proven exploitable flaw.
- The inspected `spake2-kt` tests cover basic matching/mismatching passwords and API state misuse. They do not establish AOSP interoperability, malformed-point robustness, fuzz resistance, constant-time behavior, or a security audit. Its README explicitly warns against version 1.0.0; the candidate's current catalog uses v1.0.2.

**Decision strengthened:** do not add this ADB stack to the application dependency graph yet. Before a pairing prototype, require an exact immutable commit, published artifact/coordinate reconciliation, strict dependency verification and SBOM, AOSP/BoringSSL oracle-vector tests, malformed-input/fuzz tests, explicit zeroization, bounded I/O, certificate-pin lifecycle, a Keystore-compatible identity design, and an independent cryptographic review. Even if all pass, embedded ADB still yields shell UID 2000 and does not add privilege over the same UID used by Shizuku.


## 9. Acoustic microphone capture as a separate fallback (not a digital call tap)

The existing speakerphone + microphone path should be evaluated separately from the three privileged paths above:

- Android's audio-input sharing rules state that during a voice call, the call always receives microphone input; an AccessibilityService may also capture the input, while a privileged preinstalled app with `CAPTURE_AUDIO_OUTPUT` can capture the call using protected call sources. An ordinary app's `AudioRecord` may initialize but receive silence. This explains why non-empty WAV and `RECORDSTATE_RECORDING` are not acceptance evidence.
- With speakerphone enabled, the far-end party is emitted by the loudspeaker and can be re-captured acoustically by the handset microphone. The near-end party is captured directly by the same microphone. This is an acoustic mix, not the modem's digital downlink/uplink; it is sensitive to speaker volume, distance, room noise, echo cancellation, gain control, device placement, and OEM call routing.
- Earpiece, wired headset, Bluetooth headset, and USB audio routes generally remove the far-end loudspeaker signal from the handset microphone. Unless the route is deliberately changed to speakerphone, the acoustic path cannot be assumed to contain both parties.
- `VOICE_COMMUNICATION` is privacy-sensitive and can enable AEC/AGC, but call input-sharing and OEM processing can change the resulting signal. AEC may suppress the remote loudspeaker signal as echo; AGC/noise suppression may damage speech. This must be measured on real devices rather than inferred from source selection.
- AccessibilityService's ability to receive microphone input does not grant the protected digital `VOICE_CALL` source. Do not describe it as equivalent to `CAPTURE_AUDIO_OUTPUT` or `CALL_AUDIO_INTERCEPTION`.

**Acceptance for this path:** explicitly label it speakerphone/acoustic mode; require user-visible disclosure and consent; capture route changes and AudioRecord silencing callbacks; measure local and remote speech separately using distinct known phrases; test speaker volume, microphone distance, room noise, and route transitions. Never silently fall back from a denied digital strategy to acoustic capture, and never label a one-sided file as a successful call recording.

Reference: [Android audio input sharing rules](https://developer.android.com/media/platform/sharing-audio-input).

