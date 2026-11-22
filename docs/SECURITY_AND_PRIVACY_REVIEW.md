# Security and privacy review

**Review date:** 2026-11-22  
**Scope:** current Foundation branch implementation, not a production certification or independent penetration test.

## Verified controls

### Call identity and metadata

- The telephony observer uses subscription-scoped call-state events and deliberately ignores the deprecated `PhoneStateListener.onCallStateChanged(..., phoneNumber)` argument.
- The foreground service creates new `CallRecord` rows with `phoneNumber = null`. The nullable domain/database field remains for compatibility; it is not populated by the current automatic SIM-call flow.
- Subscription IDs are used transiently to bind a `TelephonyManager` to a subscription. Recording metadata stores the SIM slot index, not the subscription identifier.
- The recording diagnostics record device/API/strategy/source information; do not add phone numbers, ICCIDs, IMSIs, IMEIs, device serials, or raw call content to diagnostics.

### Audio and privilege boundary

- Shizuku is an explicit user-authorized integration; the app does not silently start a Shizuku server.
- The UserService validates its host UID (shell 2000 or root 0), shell audio attribution, and protected call-audio permission readiness before capture.
- The privileged backend requests only `VOICE_CALL`; it rejects one-sided UPLINK/DOWNLINK and does not fall back to MIC.
- The Shizuku UserService exposes a narrow recorder AIDL contract, not a general-purpose shell-command interface.
- Standard acoustic capture is a separate user-selected mode and must remain labelled as microphone/acoustic capture.

### Recording files and database

- The repository writes recordings under the host app's private `files/recordings` directory.
- Repository creation and resolution canonicalize paths and require files to be direct children of that private recording directory.
- A recording cannot be marked `COMPLETED` unless the file is present and its canonical 44-byte RIFF/WAVE PCM header, sample rate, channel count, byte rate, block alignment, data length, and database completion metadata agree.
- Empty, malformed, misaligned, or truncated PCM is rejected by the WAV writer/repository validation path.
- The sample app sets `android:allowBackup="false"` and `android:backupInForeground="false"`; its backup/data-extraction rules exclude recording files, the recordings database, and secure recording preferences.
- The SDK integration guide instructs host apps to make an explicit backup decision and exclude recordings and consent state from cloud backup and device transfer.

### Consent and foreground operation

- Both manual start and automatic arming pass through the service state-machine consent gate.
- Automatic recording requires an explicit visible Arm action and participant-consent confirmation; it does not silently re-arm after reboot.
- The foreground service keeps an ongoing notification with a Stop/Disable action.
- Notification and call-state permission revocation disarms automatic monitoring; an active recording is stopped.
- The app service is not exported.

### Release boundary

- The current manual release workflow publishes only the SDK AAR and SHA-256 checksum after explicit dispatch and confirmation.
- Production APK signing and APK publication remain disabled. No production keystore or signing secrets are configured.
- A debug APK is not a production release artifact.

## Remaining security/privacy acceptance work

| ID | Required work | Current state |
|---|---|---|
| I1 | Audit all merged-manifest permissions and API-level permission gates | Partial; source manifests reviewed, merged-manifest/device audit still required |
| I2 | Independent Shizuku shell/root trust-boundary review | Partial; UID/permission/attribution gates implemented, independent review pending |
| I3 | Binder input/descriptor abuse and lifecycle review | Partial; narrow AIDL and teardown tests exist, adversarial review pending |
| I4 | Private-file path, symlink, race and backup verification on host apps | Partial; canonical path checks and sample backup exclusions exist |
| I5 | Log/diagnostic redaction audit across all failure paths | Partial; call-number input is ignored and new records use null phoneNumber; complete log audit pending |
| I6 | Consent wording, participant disclosure and jurisdiction review | Technical consent gate exists; legal/product wording review pending |
| I7 | Retention/deletion for failed, interrupted, orphaned and completed recordings | Partial; completed recordings are quota-managed and user deletion is implemented; failed/orphan quota policy needs explicit verification |
| I8 | Google Play policy and distribution review, especially Accessibility service use | Research documented; final distribution decision pending |
| I9 | Device-specific compatibility and limitation documentation | Documented; physical-device evidence remains absent |

## Non-negotiable release rules

1. Never claim that a non-empty WAV, non-zero RMS, successful AudioRecord initialization, emulator run, or Shizuku connection proves two-way cellular audio.
2. Do not upload recordings, call content, phone numbers, subscriber identifiers, or device-unique identifiers to CI artifacts, issue comments, analytics, or crash reports.
3. Keep raw WAVs on the handset or a controlled local workstation; share only sanitized metadata and digests for evidence review.
4. Do not publish an APK until signing-key custody, protected signing Environment, certificate fingerprint verification, independent review, and physical-device acceptance for the exact candidate are complete.
5. Treat Accessibility microphone capture as an opt-in compatibility experiment, not a digital cellular downlink API or an automatic Google Play distribution entitlement.

## Evidence references

- [SDK integration and host backup obligations](SDK_INTEGRATION.md)
- [Production APK signing design](PRODUCTION_APK_SIGNING.md)
- [Android call-audio platform constraints](ANDROID_CALL_AUDIO_CONSTRAINTS.md)
- [Physical-device acceptance protocol](PHYSICAL_DEVICE_ACCEPTANCE.md)
- [Physical-device evidence report contract](PHYSICAL_DEVICE_EVIDENCE_REPORT.md)
- [Capture compatibility matrix](COMPATIBILITY_MATRIX.md)

This document records verified implementation facts and explicit remaining gates. It is not a substitute for a third-party security assessment, legal review, or physical-device acceptance.
