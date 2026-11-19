# Engineering Execution Policy

Last reviewed: 2026-11-19

This policy captures workflow lessons from reviewing the complete 345-commit history reachable from `bootstrap/native-android-foundation` (344 commits beyond the initial repository commit). It is intended for maintainers and coding agents continuing this repository.

## Observed history patterns

The history shows strong investment in tests, lifecycle correctness, diagnostics, and explicit platform boundaries. It also exposes recurring sources of avoidable iteration:

- Many logical changes were split into file-by-file commits. For example, the same diagnostics feature subject appears across eight consecutive commits, each changing one layer of the same Room/domain/repository contract. This is easy to bisect but creates a noisy 344-commit PR.
- Several implementation changes were followed by import, Kotlin syntax/control-flow, test-fixture, resource-path, or API-compatibility corrections. Examples include the SDK public recording-mode move, the WAV validator's non-local returns, and using `use` on a Room database that is not Closeable in this environment.
- Some tests initially asserted the wrong lifecycle policy or used unrealistic fixtures. The stale-part test first preserved a completed session's orphan `.part`; the policy and assertion were later corrected. WAV validation also required valid PCM fixtures rather than arbitrary non-empty files.
- CI diagnostics exposed an important shell-boundary issue: the emulator runner executes script lines separately, so an exit-code variable did not survive between lines. The fix keeps test execution, status capture, diagnostics, and exit in one shell command.
- Documentation was sometimes updated in repeated near-identical commits while the implementation contract was still moving.
- Lifecycle defects often appeared at interactions between otherwise-correct paths: cancellation vs. service destruction, failure cleanup vs. stop, process recovery vs. a live session, and file promotion vs. Room completion.

These are process observations, not a claim that every small commit or correction was avoidable. Small commits remain useful when they isolate independently reviewable behavior or make a failure easy to bisect.

## Required workflow for future changes

### 1. Inspect before editing

- Read the relevant implementation, tests, interfaces, DI bindings, manifests, Gradle configuration, and current documentation before proposing a change.
- Trace the full call path across app, SDK facade, service, coordinator/backend, repository, persistence, and file lifecycle where relevant.
- Check the current branch/head and active CI run before pushing. Do not assume an earlier run belongs to the current SHA.
- Verify platform/API claims against authoritative Android documentation and the repository's explicit support policy.

### 2. Define the contract first

Before implementation, write down:

- The user-visible behavior and explicit non-goals.
- State invariants and allowed transitions.
- Ownership of cancellation, cleanup, finalization, and retries.
- Persistence/file-system consistency rules and crash windows.
- API-level, permission, process, and hardware assumptions.
- Acceptance tests, including negative cases and failure injection.

For concurrency-sensitive changes, enumerate competing events and their expected winner. Do not rely only on happy-path sequential tests.

### 3. Test the behavior, not the implementation detail

- Add or update the regression test with the change, preferably before or in the same coherent change set.
- Use realistic fixtures that satisfy the actual format/contract (for example, structurally valid PCM WAV files).
- Assert both the expected state and the protected side effects: row state, final file, temporary file, cleanup count, callback count, and ownership.
- Include duplicate calls, retries, cancellation, process restart, and race cases where the contract requires them.
- Use APIs and test helpers supported by the configured Kotlin/JVM/Android/Robolectric versions. Do not assume Java `AutoCloseable`/Kotlin `use` support for Android abstractions; verify the type contract.
- Avoid brittle assertions about incidental formatting unless that formatting is itself part of the public contract.

### 4. Make a coherent change, then verify it

- Group implementation, its regression tests, and directly related documentation into one logical commit or a small, reviewable series. Avoid one commit per import, file, or paragraph.
- Before committing, inspect the complete diff and search for stale imports, duplicate logic, incorrect package names, unsupported APIs, missing test source-set paths, and inconsistent docs.
- Run the narrowest relevant tests first, then module tests/lint/build. Run the full required CI after a meaningful code/build/workflow batch.
- Do not stack several unverified changes. If a check fails, stop and classify the failure before adding more work.
- Keep commits atomic and descriptive. Prefer Conventional Commit scopes; do not create repeated identical subjects for unrelated file edits.
- Batch documentation edits after the underlying contract stabilizes, and make each doc change traceable to implementation or evidence.

### 5. Diagnose CI failures without masking them

Classify every failure as one of:

1. Product/source defect.
2. Test or fixture defect.
3. Build/toolchain/configuration defect.
4. CI runner/bootstrap/environment defect.

Preserve the original test exit code while collecting best-effort diagnostics. Diagnostics may fail without replacing the test result. A cancelled or superseded run is neither a pass nor a source failure. Never weaken a required check merely to obtain green CI.

### 6. Keep evidence and claims separate

- A passing JVM/Robolectric/emulator test proves only the behavior it actually exercises.
- A valid WAV proves file-format integrity, not that both call participants were captured.
- A live Shizuku binder, UID, permission, or AudioRecord initialization does not prove far-end audio.
- Mark device/OEM/carrier/route support only after repeatable physical-device trials with separately assessed near-end and far-end intelligibility.
- Keep experimental paths fail-closed; never silently substitute a weaker capture strategy.

### 7. Protect repository and release boundaries

- Work on the feature branch and through the Draft PR; do not push directly to `main`.
- Do not create a release tag or publish a beta until required CI, artifact verification, and physical-device release gates are met.
- Keep the final PR history reviewable; use the repository's intended Squash Merge policy when accepted.

## Checklist for every future engineering task

- [ ] Current branch, head SHA, PR state, and active workflows checked.
- [ ] Existing code path and relevant contracts traced end to end.
- [ ] Invariants, race cases, crash windows, and non-goals stated.
- [ ] Regression tests cover positive, negative, and failure paths.
- [ ] Test fixtures and platform APIs verified as realistic and supported.
- [ ] Full diff reviewed for imports, visibility, source sets, resources, and docs.
- [ ] Targeted checks pass before broader CI is started.
- [ ] CI result is tied to the exact current head SHA.
- [ ] Claims are limited to the evidence actually collected.
- [ ] Implementation, tests, and docs are committed as a coherent unit.
