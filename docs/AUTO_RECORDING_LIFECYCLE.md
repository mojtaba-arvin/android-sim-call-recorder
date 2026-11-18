# Consent-gated automatic recording lifecycle

## Product target

The target behavior is automatic capture when a cellular SIM call enters `OFFHOOK`, with two strictly separated modes:

- **Standard automatic mode:** product-level Standard mode maps to the explicit `SPEAKERPHONE_MIC` strategy with a bounded initialization chain (`MIC` → `VOICE_COMMUNICATION` → `VOICE_RECOGNITION`), after opt-in, disclosure and consent. It is acoustic capture, not digital call downlink, and must be labelled honestly. The enum value `STANDARD` remains a legacy fail-closed app-UID backend.
- **Advanced Shizuku mode:** a separate privileged `VOICE_CALL` strategy that requires explicit Shizuku authorization, an active and validated UserService, accepted UID (shell 2000 or root 0), feature-flag enablement, known SIM slot, OFFHOOK, and recording consent. It must fail closed.

The implementation now includes an explicit **Arm best available automatic recording** control. While the activity is visible, the app snapshots runtime capabilities and fixes the session to Shizuku `VOICE_CALL` if the live UserService, shell UID and attribution are ready; otherwise it selects `SPEAKERPHONE_MIC` if RECORD_AUDIO is granted. The mode-specific foreground service then monitors SIM call-state events and starts that fixed backend on OFFHOOK, stopping and finalizing on the matching SIM's IDLE. A Shizuku failure never downgrades to microphone capture. This is an implemented software lifecycle, not evidence of two-way audio compatibility. `STANDARD` remains a legacy fail-closed digital backend; the bounded microphone-source chain is implemented for Standard acoustic capture only.

## Best-eligible automatic mode state flow

```text
DISABLED
   |
   | user explicitly arms best-eligible automatic capture
   v
ARMING (while app is visible)
   |
   | permissions + disclosure + persistent notification
   v
MONITORING (mode fixed at arm time: Shizuku VOICE_CALL or Speakerphone + MIC)
   |
   | subscription-scoped OFFHOOK event
   v
STARTING (the fixed selected strategy)
   |
   +---- start rejected / FGS restriction ----> VISIBLE FAILURE
   |
   v
RECORDING (persistent notification + Stop action)
   |
   | matching SIM returns to IDLE
   v
FINALIZING (WAV + Room + retention)
   |
   v
MONITORING
```

Rules:

1. Automatic mode is disabled by default until the user explicitly arms it and acknowledges the recording/consent disclosure. If the selected mode is acoustic, the UI must disclose its acoustic limitations.
2. The monitoring service must be armed from a visible user action. It owns subscription-scoped call-state listeners and must not depend on the Diagnostics screen remaining open.
3. Only a matching SIM-slot `OFFHOOK` event can start a session. `RINGING` never starts recording.
4. The selected mode is fixed for the armed session. Standard acoustic mode may use its bounded microphone-source initialization chain. Shizuku mode attempts only full-duplex VOICE_CALL. No runtime cross-mode downgrade is allowed.
5. The foreground notification must remain visible during monitoring and capture, with a Stop/Disable action.
6. A denied permission, unavailable FGS start, unknown SIM slot, or failed source initialization must be surfaced; do not create a successful-looking empty recording.
7. A matching `IDLE` event finalizes the file and metadata. Interrupted sessions remain recoverable and visible.

## Advanced Shizuku mode

Shizuku capture is a privileged automatic candidate after explicit user arming, not an ordinary microphone fallback.

- Require explicit user authorization and recording consent.
- Verify the Shizuku binder, actual bound UserService, host UID and helper attribution.
- Require the feature flag, active OFFHOOK call and known SIM slot.
- Record the actual privilege identity and source.
- If any gate fails or `VOICE_CALL` cannot initialize, report failure. Never start MIC, DEFAULT, VOICE_RECOGNITION or speakerphone capture as an automatic downgrade.
- The best-eligible arm action chooses Shizuku only when readiness is verified; otherwise it may choose Standard acoustic before monitoring begins. Once armed, that selection is immutable until disarm.

## Android platform constraints

- The app currently targets Android 9+ (minSdk 28). Shizuku capture requires Android 11+ (API 30).
- Android 10+ applies audio-input sharing priorities. An ordinary app may create an AudioRecord but receive silence while a cellular call owns the input.
- Android documents that a voice call always receives audio; a normal app can capture call input only when it is an accessibility service or a privileged preinstalled app with `CAPTURE_AUDIO_OUTPUT`. The Shizuku route is a separate shell/root experiment and remains device-dependent.
- On Android 14+, microphone foreground services require `RECORD_AUDIO` and are subject to while-in-use restrictions. A microphone FGS generally cannot be created from the background. The design therefore arms the monitor while the app is visible and tests whether the already-running foreground service can continue into the call on each API/OEM.
- The `phoneCall` FGS type is not a generic call-recording permission; it requires `MANAGE_OWN_CALLS` or the default dialer role and is intended for calls managed through `ConnectionService`.
- Force-stop, reboot, process death and OEM task killers can stop monitoring. The product must disclose recovery/re-arming behavior rather than promise uninterrupted automatic recording.
- Google Play policy says Accessibility API is not designed for and cannot be requested for remote call audio recording. The Accessibility-priority experiment is therefore excluded from a Play-distributed call-recording feature.

Official references:
- https://developer.android.com/media/platform/sharing-audio-input
- https://developer.android.com/about/versions/14/changes/fgs-types-required
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/reference/android/media/AudioRecordingConfiguration
- https://support.google.com/googleplay/android-developer/answer/18265346
- https://source.android.com/docs/compatibility/17/android-17-cdd

## Audio-source fallback and evidence

For Standard mode only, evaluate a bounded microphone/acoustic chain. Do not mix it with the Shizuku path.

- Standard acoustic capture now tries a bounded initialization chain: `MIC` → `VOICE_COMMUNICATION` → `VOICE_RECOGNITION`. It advances only when the preceding source cannot initialize or enter recording state; it does not switch sources after a session has started.
- On API 29+, `AudioManager.registerAudioRecordingCallback()` observes the active configuration and correlates by audio session ID.
- Live diagnostics report client-selected source, framework active source, input device, client/device formats and `isClientSilenced()`.
- A silenced input is a clear failure signal for that moment. It is not proof that an unsilenced stream contains both participants.
- The actual selected source and initialization fallback reason are written into the recording's source label. Persisting callback transitions and full diagnostics in Room remains outstanding.
- A non-zero waveform or non-silent WAV is not proof of two-way speech. Two-way support still requires separate known phrases from both participants in repeated real calls.

## Current implementation gaps

- Automatic monitoring now runs in the same user-armed microphone foreground service, independent of the Diagnostics screen. It is intentionally not restored after reboot or force-stop; the user must re-arm from the visible app.
- Automatic mode now selects Shizuku `VOICE_CALL` when the live UserService, accepted UID and shell attribution are ready; otherwise it selects Speakerphone + MIC if RECORD_AUDIO is granted. The selected mode remains fixed until disarm.
- Shizuku capture now exposes shell-attribution readiness over AIDL, clears the incoming Binder caller identity before AudioRecord operations, and attempts only full-duplex `VOICE_CALL`; one-sided UPLINK/DOWNLINK fallbacks are rejected.
- The selector is wired into arm-time best-eligible selection. It does not switch modes after a capture failure.
- AudioRecordingCallback / client-silenced diagnostics are visible live but are not yet persisted as a complete timeline in Room.
- No physical modem/OEM/carrier two-way result has been recorded.

Tracked work:
- Issue #2: runtime capability probing and strategy selection.
- Issue #4: consent-gated automatic OFFHOOK recording and dual-mode lifecycle.
- Issue #5: audio-capture silence diagnostics and Standard-mode fallback.
