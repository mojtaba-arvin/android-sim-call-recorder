# Engineering Roadmap

Last reviewed: 2026-11-19

## Product outcome

Deliver a native Android application and reusable SDK that can automatically capture cellular SIM calls with the highest practical device coverage, while accurately distinguishing a selectable capture path from verified two-way audio support.

A recording is accepted as successful only when the file is finalized, persisted metadata is consistent, and physical-device evidence confirms intelligible audio from both participants. A non-empty WAV, non-zero RMS, an opened AudioRecord, or green emulator CI is not proof of two-way capture.

## Current baseline

- The project is split into `:callrecorder-sdk` (recording engine) and `:app` (sample, diagnostics, playback, and integration-test host).
- The SDK exposes a public facade; implementation packages are internal and guarded against direct app imports.
- Implemented candidates include Shizuku privileged `VOICE_CALL` and explicitly consented acoustic microphone strategies.
- Shizuku must remain a strict privilege boundary: live UserService, allowed UID, audio-attribution readiness, and explicit user authorization. Never silently downgrade a Shizuku failure to a microphone source.
- Standard acoustic capture is not digital cellular downlink. Its limitations must remain explicit in product UI and documentation.
- Recording data remains app-private. WAV finalization and Room metadata consistency are release-critical.
- APK/AAR release automation exists, but no first public beta release is accepted until the required CI gate and device validation policy are satisfied.
- API 26 is an experimental software compatibility floor, not a promise of cellular audio compatibility.

## Execution plan and acceptance gates

### P0 — Make CI trustworthy and economical

1. Fix the emulator-runner shell boundary so the Gradle exit code survives diagnostics collection.
2. Confirm API 26 and API 35 instrumentation jobs complete; classify test failures separately from runner/bootstrap failures.
3. Keep a single required-check aggregator. Documentation-only changes may skip expensive Android jobs, but every code/build/workflow change must execute the full required pipeline.
4. Keep PR concurrency cancellation for superseded commits, but do not interpret cancellation as a code failure.
5. Upload unit, lint, instrumentation, and emulator diagnostics even when tests fail.
6. Keep dependency and action versions pinned or managed by Dependabot; review action runtime deprecation warnings.
7. Add the Gradle Wrapper only after validating the pinned Gradle distribution and wrapper checksum policy.

**Exit criteria:** required-check aggregator is green on the current PR head; both emulator API levels pass; reports are retained; no false-green path exists.

### P1 — Recording lifecycle and file integrity

1. Verify the state machine for duplicate OFFHOOK events, concurrent starts, SIM switching, call-end during STARTING, service destruction, process death, and retry after failed start.
2. Keep file finalization independent of service watcher cancellation; atomically replace destination only after a complete WAV has been written.
3. Persist source, selected strategy, SIM slot, start/stop reason, and capture diagnostics without persisting unnecessary subscription identifiers or call content.
4. Reconcile stale RECORDING metadata to INTERRUPTED at startup; never expose partial files as playable.
5. Verify retention cannot delete active/interrupted recordings and cannot delete the last valid recording after a failed replacement.
6. Test notification Stop, disarm, denied foreground-service start, reboot/force-stop recovery, and Android 14+ microphone foreground-service restrictions.

**Exit criteria:** deterministic automated tests for lifecycle and storage invariants; no lost/false-COMPLETED recordings in fault-injection tests.

### P2 — Runtime strategy selection and observability

1. Separate strategy capability, runtime eligibility, session selection, observed signal, and verified device support.
2. Probe live Shizuku binder and helper UID/attribution at session start; freeze the selected strategy for that session.
3. Record explicit selection/failure reason codes and the actual capture source.
4. Expose actionable diagnostics for permission denial, service-start denial, audio input sharing/silencing, route changes, empty stream, silent stream, and finalization failure.
5. Preserve fail-closed boundaries: no implicit source substitution, no one-sided VOICE_UPLINK/DOWNLINK fallback, and no Shizuku-to-MIC fallback.
6. Ensure observability never logs phone numbers, ICCIDs, subscription IDs, call content, or audio samples.

**Exit criteria:** tests cover selector ordering, missing/stale binder, UID mismatch, missing attribution, consent gates, audio-source failure, route change, and no-silent-fallback behavior.

### P3 — Physical-device validation and compatibility evidence

1. Build a repeatable test protocol covering Android/API level, OEM/build fingerprint, carrier, SIM slot, VoLTE/VoWiFi state where applicable, route, Bluetooth state, and capture strategy.
2. For every trial, capture local diagnostics and separately assess near-end and far-end speech intelligibility.
3. Repeat incoming and outgoing calls, single-SIM and dual-SIM scenarios, screen-off/Doze, route changes, and service interruption.
4. Mark support only after repeated successful two-way trials on the exact device/build/carrier/route combination.
5. Keep untested combinations as unknown; never infer support from brand, chipset, API level, or Shizuku readiness.
6. Investigate vendor-specific APIs only with documented APIs or reproducible, legally distributable evidence.

**Exit criteria:** compatibility matrix contains evidence-backed results, known limitations, and reproducible test records; no blanket OEM support claims.

### P4 — Beta distribution and SDK quality

1. Complete CI and release asset verification before creating a beta tag.
2. Publish a versioned debug-signed sideload APK, SDK AAR, and SHA-256 checksums through GitHub Releases.
3. Clearly label debug signing and sideload-only status; do not imply Play Store readiness.
4. Validate installation, upgrade, downgrade policy, data preservation, uninstall behavior, and APK checksum on at least one physical device.
5. Document SDK integration, public API stability, permissions, lifecycle ownership, and supported Android floor.
6. Keep the PR Draft until all required gates are met; merge via reviewed Squash Merge, never push directly to `main`.

**Exit criteria:** a reproducible beta release can be downloaded and verified; SDK consumer integration works; release notes disclose experimental audio support honestly.

## Working policy

- Use focused `feat/*`, `fix/*`, `ci/*`, and `docs/*` branches.
- Keep `main` stable; work through PRs and required CI.
- Prefer small commits with one concern and explicit validation evidence.
- Re-run only failed jobs when the source SHA is unchanged; run the full gate after source changes.
- A cancelled or superseded workflow is neither a pass nor a source failure.
- Do not create a release tag, mark a PR ready, or claim device support without the corresponding acceptance evidence.

## Immediate next actions

1. Verify the CI fix on the next run and inspect API 35 result.
2. Resolve any real emulator failures without weakening the required gate.
3. Audit the service lifecycle and WAV/Room recovery paths against P1.
4. Add structured, privacy-safe failure diagnostics for runtime strategy selection.
5. Prepare a physical-device test protocol and begin evidence collection before any beta-support claims.
