# Engineering Roadmap

Last reviewed: 2026-11-24

## Product outcome

Deliver a native Android application and reusable SDK that can automatically capture cellular SIM calls with the highest practical device coverage, while accurately distinguishing a selectable capture path from verified two-way audio support.

A recording is accepted as successful only when the file is finalized, persisted metadata is consistent, and physical-device evidence confirms intelligible audio from both participants. A non-empty WAV, non-zero RMS, an opened AudioRecord, or green emulator CI is not proof of two-way capture.

## Current baseline

- The project is split into `:callrecorder-sdk` (recording engine) and `:app` (sample, diagnostics, playback, and integration-test host).
- The SDK exposes a public facade; implementation packages are internal and guarded against direct app imports.
- The user-armed automatic monitor and best-eligible selection lifecycle are implemented in software. Shizuku privileged `VOICE_CALL` and explicitly consented acoustic microphone strategies remain distinct.
- Shizuku must remain a strict privilege boundary: live UserService, allowed UID, audio-attribution readiness, and explicit user authorization. Never silently downgrade a Shizuku failure to a microphone source.
- Standard acoustic capture is not digital cellular downlink. Its limitations must remain explicit in product UI and documentation.
- Recording data remains app-private. WAV finalization and Room metadata consistency are release-critical.
- APK/AAR release automation exists, but no first public beta release is accepted until the required CI gate and physical-device validation policy are satisfied.
- API 24 is an experimental software compatibility floor, not a promise of cellular audio compatibility.
- The agent workflow is governed by the repository-root `AGENTS.md`; detailed history-derived process lessons are in [Engineering Execution Policy](ENGINEERING_EXECUTION_POLICY.md).

## Execution plan and acceptance gates

### P0 — Make CI trustworthy and economical

1. **Implemented, awaiting proof on the current head:** emulator-runner command now preserves the Gradle exit code while collecting best-effort diagnostics in one physical shell command.
2. **In progress:** complete API 24, API 26 and API 35 instrumentation on the current PR head; classify test failures separately from runner/bootstrap failures. A running job is not a pass.
3. Keep a single required-check aggregator. Documentation-only changes may skip expensive Android jobs, but every code/build/workflow change must execute the full required pipeline.
4. Keep PR concurrency cancellation for superseded commits, but do not interpret cancellation as a code failure.
5. Upload unit, lint, instrumentation, and emulator diagnostics even when tests fail.
6. Keep dependency and action versions pinned or managed by Dependabot; review action runtime deprecation warnings.
7. Add the Gradle Wrapper only after validating the pinned Gradle distribution and wrapper checksum policy.

**Exit criteria:** required-check aggregator is green on the current PR head; all emulator API levels (24, 26, 35) pass; reports are retained; no false-green path exists.

### P1 — Recording lifecycle and file integrity

1. Continue adversarial verification of duplicate OFFHOOK events, concurrent starts, SIM switching, call-end during STARTING, service destruction, process death, and retry after failed start.
2. Keep file finalization independent of service watcher cancellation; atomically replace destination only after a complete WAV has been written.
3. Verify persisted source, selected strategy, SIM slot, start/stop reason, and capture diagnostics without persisting unnecessary subscription identifiers or call content.
4. Preserve first-open recovery semantics: stale active rows become INTERRUPTED, orphaned app-private `.wav.part` files are removed (including the pre-row-insert crash window), and promoted `.wav` files are preserved.
5. Verify retention cannot delete active/interrupted recordings or the last valid recording after a failed replacement. Validate finalized WAV headers and persisted sample-rate/channel metadata before marking COMPLETED.
6. Test notification Stop, disarm, denied foreground-service start, reboot/force-stop recovery, and Android 14+ microphone foreground-service restrictions.

**Exit criteria:** deterministic automated tests for lifecycle and storage invariants; no lost/false-COMPLETED recordings in fault-injection tests.

### P2 — Runtime strategy selection and observability

1. Preserve the distinction between strategy capability, runtime eligibility, arm-time session selection, observed signal, and verified device support.
2. **Implemented:** best-eligible selection snapshots live Shizuku UserService/UID/attribution readiness when the user arms monitoring, then fixes the selected mode until disarm. Remaining work is adversarial service-level coverage for stale/disconnected readiness and exact backend invocation.
3. **Partially implemented:** actual source and bounded diagnostic history are persisted. Structured fallback reasons are persisted as a first-class nullable field and shown in recording details; retain the timestamped event timeline for runtime source/permission changes.
4. **Implemented in current base:** `MicrophoneDiagnosticsPolicyTest` covers callback registration/session filtering/silenced transitions/cleanup/failure retry, and API 35 callback instrumentation covers platform delivery. Structured fallback-reason persistence and UI coverage are implemented. Keep these checks required; physical two-way evidence remains separate.
5. Preserve the fail-closed contract: no implicit cross-mode substitution, no one-sided VOICE_UPLINK/DOWNLINK success path, and no Shizuku-to-MIC fallback.
6. Ensure observability never logs phone numbers, ICCIDs, subscription IDs, call content, or audio samples.

**Exit criteria:** tests prove selector-to-backend invocation, stale/missing binder handling, UID/attribution/API/feature-flag gates, consent gates, actual-source persistence, diagnostic callback cleanup, route/source failures, and no-silent-fallback behavior.

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

## R&D agent system

The agent platform is tracked separately from product implementation under umbrella issue [#10](https://github.com/mojtaba-arvin/android-sim-call-recorder/issues/10), with phase issues #11–#15. Its authority and evidence contract is defined in [R&D Agent System Charter](RD_AGENT_CHARTER.md), and its session input/output format is specified in [R&D Agent Task Contract](RD_AGENT_TASK_CONTRACT.md) and `agent-system/contracts/task.schema.json`. The planned sequence is A0 (charter/task contract/context pack/benchmark), A1 (GitHub-aware orchestrator and execution ledger), A2 (bounded specialists), A3 (evaluation and safety), and A4 (controlled automation and dry run). The system must operate the existing product backlog (#2–#9), not replace it. Research is read-only; code changes use reviewed branches/PRs; merge, release, permission, and hardware-evidence gates remain human-controlled.

## Working policy

- Use focused `feat/*`, `fix/*`, `ci/*`, and `docs/*` branches.
- Keep `main` stable; work through PRs and required CI.
- Prefer coherent, independently reviewable commits with explicit validation evidence; do not split one logical change into repetitive file-by-file commits.
- Re-run only failed jobs when the source SHA is unchanged; run the full gate after source/build/workflow changes.
- A cancelled or superseded workflow is neither a pass nor a source failure.
- Do not create a release tag, mark a PR ready, or claim device support without the corresponding acceptance evidence.

## Immediate next actions

1. Finish the API 26/API 35 run on the current PR head and inspect the required-check aggregator.
2. Resolve any real emulator failures without weakening the required gate.
3. Audit the service lifecycle and WAV/Room recovery paths against P1, adding fault injection for process death during PCM write and after atomic WAV promotion but before Room commit.
4. Verify the public SDK snapshot reflects build-enabled and actually installed backends, and that diagnostics preserve privacy-safe failure reason codes.
5. Prepare the physical-device test protocol and begin evidence collection before any beta-support claims.
