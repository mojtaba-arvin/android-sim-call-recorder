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
