# Android 16+ protected call-audio interception research

**Research date:** 2026-11-18  
**Status:** Research candidate only; not implemented and not device-verified.

## Findings

Android 16 (API 36) exposes protected `AudioManager` system/test APIs related to PSTN call interception:

- `isPstnCallAudioInterceptable()` reports whether the platform allows access to uplink/downlink audio for an ongoing PSTN call.
- `getCallDownlinkExtractionAudioRecord(AudioFormat)` returns an `AudioRecord` for extracting the **call downlink**.
- `getCallUplinkInjectionAudioTrack(AudioFormat)` returns an `AudioTrack` for **injecting** audio into the call uplink. It is not an uplink recording API.

These APIs are annotated `@SystemApi`, `@TestApi`, and require `android.permission.CALL_AUDIO_INTERCEPTION`. The framework source describes them as reserved for system apps with privileged permission. The downlink method requires the platform to be in a call/screening/redirection audio mode and rejects PSTN capture when `isPstnCallAudioInterceptable()` is false.

References:
- Android 16 API surface: https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r3/core/api/system-current.txt
- `AudioManager` implementation and method contracts: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/media/java/android/media/AudioManager.java
- `AudioService` permission checks: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/services/core/java/com/android/server/audio/AudioService.java
- Permission declaration: https://android.googlesource.com/platform/frameworks/base/+/android17-release/core/res/AndroidManifest.xml
- Android 16 Shell manifest: https://android.googlesource.com/platform/frameworks/base/+/android16-release/packages/Shell/AndroidManifest.xml

## Important interpretation

1. The downlink method is a remote-party extraction path. It does not provide a local microphone/uplink recording stream.
2. The uplink method is for sending audio into a call, not reading the local participant's voice.
3. Therefore this API pair is **not a drop-in full-duplex recorder** and must not be represented as one.
4. The AOSP Shell manifest requests `CALL_AUDIO_INTERCEPTION` for a CallAudioInterception test and `CAPTURE_AUDIO_OUTPUT` for a dialer-audio test. This is evidence about the AOSP shell image, not a guarantee that every OEM grants the same permission set to UID 2000.
5. A possible experimental composition is digital downlink extraction plus a separately captured local microphone. That would still need proof that microphone capture is allowed concurrently, not silenced, and intelligible on the target build and route. It must remain labelled experimental until both participant phrases pass repeated physical tests.
6. Current app implementation continues to use the isolated Shizuku `AudioRecord(VOICE_CALL)` path. This research does not replace it or change runtime selection.

## Proposed experiment (API 36+)

1. In the Shizuku UserService, check the actual `CALL_AUDIO_INTERCEPTION` permission for the live helper UID. Do not infer it from Shizuku authorization or shell UID alone.
2. Reflectively query `AudioManager.isPstnCallAudioInterceptable()` only on API 36+ and record the result in diagnostics.
3. If the platform reports support, create a test-only `getCallDownlinkExtractionAudioRecord()` using supported PCM format and channel masks. Do not route this source into the production recording selector yet.
4. Independently test local microphone availability during the same cellular call. Record AudioRecordingConfiguration and `isClientSilenced()` where available.
5. Preserve separate local/downlink tracks during the experiment so the two directions can be assessed independently; do not mix them until timing, drift, and route behavior are understood.
6. Verify incoming and outgoing calls, SIM 1 and SIM 2, earpiece, speaker, wired headset, Bluetooth, VoLTE, and Wi-Fi Calling on multiple OEM builds.
7. Stop and discard test output on permission failure, API mode mismatch, uninitialized AudioRecord, read error, or unconfirmed stop.

## Decision gate

Implement only if a physical API 36+ handset proves that:
- the helper UID actually holds `CALL_AUDIO_INTERCEPTION`;
- `isPstnCallAudioInterceptable()` returns true for the target carrier/call;
- downlink extraction yields intelligible remote speech repeatedly;
- the separate local track yields intelligible local speech without being silenced;
- both tracks finalize reliably through call end, route change, and service teardown.

Until then, this remains a research lead, not an implemented feature or compatibility claim.
