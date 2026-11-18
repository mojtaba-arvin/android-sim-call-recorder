# Architecture Decision Record

Current product and engineering decisions are recorded here so later changes do not silently reintroduce unsupported recording assumptions.

## ADR-001 — Separate acoustic Standard mode from privileged call capture

**Decision:** The product has two distinct capture modes. Standard acoustic capture may request speakerphone routing and use an explicitly disclosed microphone source after the user enables automatic recording, grants RECORD_AUDIO, accepts the acoustic limitations, and receives a persistent foreground notification. Shizuku remains a separate privileged VOICE_CALL path. The legacy `STANDARD` app-UID backend continues to fail closed and is not itself a microphone source.

**Reason:** Android's public SDK does not grant an ordinary third-party app digital access to cellular uplink/downlink. Speakerphone + MIC is an acoustic workaround, not digital call capture; it can fail because of input-sharing policy, routing, echo, Bluetooth, or OEM behavior. A non-empty or non-silent file does not prove that both participants were captured.

**Fallback boundary:** Standard acoustic mode may use a bounded, explicitly disclosed microphone-source fallback chain and must record the actual source and reason. A Shizuku/VOICE_CALL failure must never fall back to MIC, DEFAULT, VOICE_RECOGNITION, or speakerphone capture.

## ADR-002 — Optional Shizuku backend as a narrow privilege boundary

**Decision:** Advanced capture uses a user-authorized Shizuku UserService. The app verifies the helper UID (shell 2000 or root 0) and attempts only AudioRecord(VOICE_CALL). It has no generic shell command API and no MIC fallback.

**Security boundaries:**
- Shizuku must be started by the device owner and this app must be explicitly authorized.
- The helper exposes only start, stop, status, UID, and error methods.
- The helper rejects identities other than UID 2000 or UID 0.
- Shell attribution compatibility is isolated and fails closed if the framework field cannot be adjusted.
- The Shizuku path does not use AccessibilityService, overlays, contacts, call-log access, or remote upload. A separate, explicitly enabled Accessibility-priority microphone experiment exists as its own strategy; it does not inspect UI content and is not a Google Play distribution path for call recording.
- The current Shizuku prototype requires an in-app acknowledgement and manual start while a SIM reports OFFHOOK. Any future automatic Shizuku trigger must have a separate explicit opt-in and must retain every privilege/UID/SIM/consent gate.
- A persistent notification exposes an explicit Stop action.
- Shizuku authorization is not consent from the other participants.

**Consequence:** The backend is experimental and device-dependent. CI validates code, state, and WAV format—not modem audio compatibility. A device is supported only after repeated tests demonstrate intelligible audio in both directions.

## ADR-003 — Foreground service is not a Doze immunity guarantee

**Decision:** The target Standard acoustic mode may arm a persistent call-monitoring foreground service from a visible user action. Once armed and explicitly consented, it may start the selected Standard microphone/acoustic strategy on a matching SIM's OFFHOOK event. The current prototype still requires manual Start. Shizuku capture remains separately authorized and fail-closed.

**Reason:** Foreground services improve process importance and make long-running user work visible, but do not guarantee survival against force-stop, process death, vendor task killers, reboot, or every power-management policy. Android 14+ microphone FGS starts are subject to while-in-use restrictions.

**Lifecycle policy:** Never start on RINGING. Arm monitoring while the app is visible and verify that the FGS can continue into the call on each API/OEM. Show a persistent notification with Stop/Disable. A denied or blocked FGS start must be reported rather than bypassed. Finalize WAV and update Room on IO coroutines. Do not block the main thread from Service.onDestroy. Persist RECORDING metadata before treating the session as active. Convert stale RECORDING rows to INTERRUPTED at next startup.

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


## ADR-009 — Strategy eligibility is not verified device support

**Decision:** The selector may rank only strategies whose runtime prerequisites are met, but its result is labelled a candidate. Verified two-way support requires repeated physical tests for the exact device build, carrier, SIM slot and audio route. The actual source and fallback reason are persisted for every recording.

**Reason:** AudioRecord initialization, a non-empty WAV, non-zero RMS, or an unsilenced recording configuration cannot prove that both call participants were captured.

**Consequence:** Use AudioRecordingCallback and client-silenced state as diagnostics where available; keep two-way speech validation as a separate acceptance gate.