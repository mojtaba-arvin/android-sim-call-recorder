# Architecture Decision Record

Current product and engineering decisions are recorded here so later changes do not silently reintroduce unsupported recording assumptions.

## ADR-001 — No ordinary microphone fallback for SIM calls

**Decision:** The Standard backend refuses cellular-call recording. It does not switch the loudspeaker automatically and does not save MIC, DEFAULT, or VOICE_COMMUNICATION capture under a call-recording label.

**Reason:** Android's audio-input sharing policy gives an active voice call priority. The public SDK does not grant a normal third-party app access to both cellular uplink and downlink. Speakerphone routing does not change the permission boundary and can change the user's call privacy/route. A non-empty file or non-zero waveform does not prove that both speakers were captured.

**Consequence:** The standard backend currently has no cellular-call capture implementation. A future microphone voice-note feature must have a separate label and user flow; it must not be an automatic fallback for failed privileged capture.

## ADR-002 — Optional Shizuku backend as a narrow privilege boundary

**Decision:** Advanced capture uses a user-authorized Shizuku UserService. The app verifies the helper UID (shell 2000 or root 0) and attempts only AudioRecord(VOICE_CALL). It has no generic shell command API and no MIC fallback.

**Security boundaries:**
- Shizuku must be started by the device owner and this app must be explicitly authorized.
- The helper exposes only start, stop, status, UID, and error methods.
- The helper rejects identities other than UID 2000 or UID 0.
- Shell attribution compatibility is isolated and fails closed if the framework field cannot be adjusted.
- The Shizuku path does not use AccessibilityService, overlays, contacts, call-log access, or remote upload. A separate, explicitly enabled Accessibility-priority microphone experiment exists as its own strategy; it does not inspect UI content and is not a Google Play distribution path for call recording.
- Recording requires an in-app acknowledgement and manual start while a SIM reports OFFHOOK.
- A persistent notification exposes an explicit Stop action.
- Shizuku authorization is not consent from the other participants.

**Consequence:** The backend is experimental and device-dependent. CI validates code, state, and WAV format—not modem audio compatibility. A device is supported only after repeated tests demonstrate intelligible audio in both directions.

## ADR-003 — Foreground service is not a Doze immunity guarantee

**Decision:** User-initiated recording runs in a foreground service with an ongoing notification. The service owns a subscription-scoped telephony listener and stops when the selected SIM returns to IDLE.

**Reason:** Foreground services improve process importance and make long-running user work visible, but do not guarantee survival against force-stop, process death, vendor task killers, or every power-management policy.

**Lifecycle policy:** Start only from a visible activity action and a confirmed active call; never auto-start on RINGING/OFFHOOK. Finalize WAV and update Room on IO coroutines. Do not block the main thread from Service.onDestroy. Persist RECORDING metadata before treating the session as active. Convert stale RECORDING rows to INTERRUPTED at next startup.

## ADR-004 — Room WAL for concurrent metadata reads

**Decision:** Use Room Write-Ahead Logging with explicit migrations.

**Reason:** The records UI observes data while the service inserts and updates completion metadata. WAL supports concurrent readers and a writer.

**Consequence:** WAL does not replace transactions or recovery. DAO writes remain suspend functions, repository operations run on Dispatchers.IO, and no destructive migration fallback is configured.

## ADR-005 — App-private audio storage and bounded retention

**Decision:** WAV files live under filesDir/recordings. The repository rejects paths outside that directory. The configured quota removes oldest completed files only.

**Reason:** Audio may contain highly sensitive personal information and should not be exposed in shared storage by default. Active/interrupted sessions must not be deleted to satisfy a quota.

**Consequence:** Phone number remains nullable because callbacks do not consistently expose it and the app intentionally avoids READ_CALL_LOG.

## ADR-006 — Compose state navigation without a Navigation dependency

**Decision:** Use a small explicit Compose destination state for Recordings, Diagnostics, Settings, and Player.

**Reason:** The app currently has three top-level destinations. A navigation library would add dependency surface without providing meaningful deep-link/back-stack requirements.

**Consequence:** The player opens from a record row and returns to the list. Reconsider Navigation only when deep links or a larger back stack are required.

## ADR-007 — Media3 ExoPlayer for WAV playback

**Decision:** Use Media3 ExoPlayer for local WAV playback. Create one player for the selected recording and release it from DisposableEffect.

**Reason:** ExoPlayer provides playback state, seeking, and maintained playback lifecycle management.

**Consequence:** Playback is local-only. Missing, non-COMPLETED, or unreadable files are never passed to the player.

## ADR-008 — Test boundary

**Decision:** Robolectric covers framework state, Room, repository and state-machine logic. Compose interactions run as Android instrumentation tests on an emulator.

**Reason:** Compose UI interaction semantics are best exercised on Android runtime; Robolectric remains useful for fast JVM tests but cannot validate modem/audio HAL behavior.

**Consequence:** Emulator CI proves UI behavior, not cellular call-audio support.
