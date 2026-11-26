# Engineering Roadmap

Last reviewed: 2026-11-26

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

## Operational readiness snapshot — 2026-11-26

- Integration PR [#65](https://github.com/mojtaba-arvin/android-sim-call-recorder/pull/65) is the single Draft integration candidate against `main`. Foundation PR [#1](https://github.com/mojtaba-arvin/android-sim-call-recorder/pull/1) remains Draft and proposes the same 164 changed files; do not merge both. PR #65 carries the consolidated tree plus CI/test diagnostics.
- The active `Protect main` ruleset requires PRs and `Android CI / Required checks`, prevents deletion/non-fast-forward updates, and requires zero approving reviews. No bypass actors are configured. The owner performs the full diff review; no external reviewer is required.
- Android CI run #45800526963 on code head `16ae6e27b35f919584dc79c127f424d2b7724700` failed in the broad telephony Robolectric matrix after 360 seconds with no JUnit lifecycle events (exit 124; no assertion failure). API 24/26/35 emulator process-death lanes and the API 36 Shell permission probe passed on that head.
- The test matrix is now split by individual telephony method/API group, with explicit JUnit and shard timeouts plus a 60-second JVM thread-dump watchdog. No tests are removed or skipped; the 15-minute full SDK suite remains required. Per-shard dumps are included in the unit/lint artifact.
- Agent System Contracts passed on current PR head `0714dc6b9e67d15d1dceb49be37e52aeb6f1fd12` (run #45802848696). Android CI run #45802848761 is in progress on that same head; a running job is not a pass.
- Keep PR #65 Draft until exact-head Android CI, Agent System Contracts, full diff review, and all repository acceptance gates are satisfied.
- Milestones/issues remain open where evidence is incomplete: #2/#7 software/API acceptance; #3 physical-device validation; #10/#12–#15 R&D deliverables; #38 production signing. Do not claim all milestones complete.
- Issue #3 records zero physical devices tested. CI/emulators do not prove near/far cellular speech. Require at least three incoming and three outgoing calls per claimed device/build/carrier/SIM/route, assess near-end and far-end intelligibility separately, and keep untested combinations unknown. No `PASS_TWO_WAY` is claimed.
- Issue #38 remains blocked on owner-provisioned production signing material, a protected GitHub Environment, and an independently verified certificate fingerprint. No production APK, tag, GitHub Release, or publication is authorized.
- Branches are `main`, `bootstrap/native-android-foundation`, and `temp-main-from-origin`. No branch has been deleted; preserve both integration branches until PR #65 is accepted and PR #1 is reconciled.

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
6. Keep the PR Draft until all required gates are met; merge via a reviewed merge commit that preserves the existing commit history, never push directly to `main`.

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

1. Inspect the exact-head Android CI run #45802848761, especially the per-method telephony shards and uploaded JVM thread dumps. Diagnose the first failing method/API level; do not extend timeouts or weaken tests without evidence.
2. Require the full SDK suite, app tests, lint, AAR/consumer smoke build, API 24/26/35 emulator lanes, API 36 Shell probe, Agent System Contracts, and Required checks to pass on the final PR #65 head.
3. Keep PR #65 Draft while the P3 physical-device evidence and P4 production-signing/governance gates remain incomplete. Do not infer either from CI.
4. After all explicit gates are satisfied, merge PR #65 with a merge commit to preserve the existing history; then close PR #1 as superseded and delete `bootstrap/native-android-foundation` and `temp-main-from-origin` only after confirming no dependent PRs/workflows remain.
5. Keep #2, #3, #7, #10, #12–#15, and #38 open until their own acceptance evidence is met. Do not close milestones just to make the branch list or roadmap appear complete.
