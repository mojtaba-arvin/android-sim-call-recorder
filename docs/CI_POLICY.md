# Android CI policy

## Required check

The stable branch-protection status check is **Android CI / Required checks** (job ID: `required`, job name: `Required checks`).

Do not make the conditional `verify` or `instrumented` jobs individually required: documentation-only changes intentionally skip those jobs. The `required` aggregator handles both cases and fails closed if change detection or any required code job fails.

The active `Protect main` ruleset (ID `27531148`) was reverified on 2026-11-25 to require pull requests and this stable status check. It also prevents branch deletion and non-fast-forward updates. Required approving reviews are 0, strict/up-to-date checks are disabled, and no bypass actors are configured. These are observed live settings, not inferred from this document. Issue #8 is closed as completed: docs-only skip, deliberate-failure propagation, superseded-run cancellation, and the owner-operated zero-review policy are all explicitly decided and evidenced.

## Change classification

The `changes` job runs the path classifier unit tests on every workflow invocation, then classifies the PR base/head or push before/after diff. Unknown paths fail safe to the full Android pipeline.

The classifier skips Android build/emulator lanes only for known non-Android paths:
- Markdown, `docs/`, `agent-system/`, `schemas/`, `AGENTS.md`, `DECISIONS.md`, `.editorconfig`, `.gitignore`, and `LICENSE`.
- Issue/PR templates, Dependabot configuration, and the dedicated agent-system / physical-device-evidence / SDK-release workflow files.
- The standalone agent-system and physical-device report validators, the two dedicated Python tests for those validators/classification, and the classifier itself. The classifier tests still run in the `changes` job.

There is deliberately no blanket `scripts/tests/*` exclusion. Emulator ADB/logcat harness tests, SDK release-asset verifier tests, and any other unclassified script/test path trigger the full Android pipeline. Shizuku UserService source/tests additionally trigger the API 36 Shell permission probe. Mixed documentation + code changes trigger full CI. Manual workflow dispatch always triggers full CI.

The stable Required-check aggregator still runs for documentation-only changes, so skipped Android jobs do not leave the required check pending.

## Full code pipeline

The workflow `.github/workflows/android-ci.yml` currently performs. Its `pull_request.branches` filter includes both `main` and the temporary `bootstrap/native-android-foundation` base so stacked PRs can receive independent CI before the foundation is accepted; after stack acceptance, dependent PRs are retargeted to `main`:

1. SDK boundary guard.
2. SDK and app JUnit/Robolectric unit tests.
3. SDK and app Android Lint.
4. Android instrumentation-test compilation occurs as part of `:app:connectedDebugAndroidTest` on each emulator lane; do not compile the same test sources again in the unit/build lane.
5. SDK release AAR and app debug APK assembly, with artifacts/reports uploaded.
6. Compose UI instrumentation on Android emulator API 24, API 26 and API 35, with logcat, service, and audio diagnostics collected even after test failure.
7. A final `Required checks` aggregator that fails unless change detection, verification, and all three emulator API lanes have the required result.

The configured toolchain is Ubuntu 24.04, JDK 21, and Gradle 9.6.0. The workflow installs Android SDK Platform 37.2 and Build Tools 37.0.0. Emulator Quick Boot is enabled; do not force cold boots without a reproducible reason.

## Performance controls

- `gradle/actions/setup-gradle@v5` restores/saves Gradle User Home state; `org.gradle.caching=true` enables task-output caching and `org.gradle.parallel=true` enables parallel task execution. Preserve read-only cache behavior for fork PRs.
- The emulator job explicitly grants the runner user access to `/dev/kvm` using the documented udev rule and asserts the device is readable/writable before launching the AVD. Without this, the emulator action auto-detected no KVM access and launched with `-accel off`. On the first KVM-enabled run, API 26 boot fell from 132.601 s to 19.469 s and the job from 858.591 s to 269.116 s; API 35 boot fell from 720.172 s to 30.163 s and the job from 1566.208 s to 336.797 s. This is measured evidence from run #44532876770 vs prior Foundation run #44512168015.
- AVD snapshot caching was experimentally evaluated and removed. It restored roughly 1 GB per API and still required SDK system-image installation; API 26 job time was 205.6 s on cache miss vs 207.9 s on cache hit, and API 35 was 202.6 s vs 191.2 s. The small/inconsistent gain did not justify cache restore, quota use, and snapshot complexity. KVM remains the primary performance win.
- Pin `android-emulator-runner` v2.38.0 to immutable commit `a421e43855164a8197daf9d8d40fe71c6996bb0d`.
- End-to-end critical path: previous Foundation run #44512168015 took 1,658.393 s (27m38.4s) from first job start to last completion; KVM + parallel run #44532876770 took 350.482 s (5m50.5s), a 4.73x improvement. AVD cache-miss run #44534029954 passed both API levels and Required checks after a failed API 35 dependency-resolution attempt was retried; both API snapshots were saved.
- The three emulator API lanes now start after change detection and run concurrently with unit/lint/build verification. `Required checks` still depends on all jobs and remains fail-closed. This removes the previous serial `verify -> emulator` critical-path dependency without reducing coverage.
- `:app:compileDebugAndroidTestKotlin` is not separately invoked in the verify job because each emulator lane compiles the instrumentation APK as part of `:app:connectedDebugAndroidTest`; the previous separate compile was redundant work.
- Measure wall-clock duration and cache hit/reuse evidence on multiple exact-head runs before quantifying improvement. A single run or a runner-side emulator boot stall is not a valid performance benchmark.


## Concurrency and permissions

- One concurrency group per PR, or per branch for pushes/manual runs.
- `cancel-in-progress: false`: an active emulator run is allowed to finish; GitHub retains the newest pending run for that group. This avoids killing expensive in-progress emulator jobs merely because another commit arrived.
- A cancelled/superseded historical run is not retried when a newer head exists. Only the latest PR head is authoritative; retry a cancelled job only if it belongs to the current head and no newer run supersedes it.
- Do not include the commit SHA in the concurrency group; doing so would allow overlapping runs for every commit.
- Repository token permission is read-only (`contents: read`).
- Keep PR checks independent of secrets and safe for forked contributions.

## What CI does not prove

- Emulator success does not prove cellular modem audio access.
- An initialized `AudioRecord` or a non-empty/non-silent WAV does not prove that both call participants are audible.
- Shizuku eligibility is not a device compatibility result.
- Physical-device validation must separately record device/build, Android API, carrier/network, SIM slot, route, actual capture backend/source, and intelligibility of both directions.


## API 36 Shell permission probe

The change classifier emits an `audio` flag for Shizuku UserService and its tests. Only those changes trigger an API 36 emulator probe, which verifies the shell UID is 2000, records both protected permission results, and requires both `CALL_AUDIO_INTERCEPTION` and `CAPTURE_AUDIO_OUTPUT` to be granted on the stock AOSP API 36 emulator. The UserService itself remains compatible with either grant on OEM/product builds. The stable Required checks aggregator requires this probe when `audio=true`; it is skipped for unrelated code and documentation-only changes. This tests the AOSP permission assumption without adding an emulator lane to every PR.


## Emulator ADB/logcat preflight

Each emulator lane starts and verifies the ADB server, waits for exactly one online emulator and Android boot completion, and only then clears logcat. If the default clear fails, the harness retries with an explicit all-buffer clear. If both fail, it restarts the ADB server once, re-verifies the online emulator, then performs up to three bounded clear attempts (10-second command timeout, two-second backoff). If logcat still cannot be cleared, it stops before Gradle instrumentation and does not continue with ambiguous pre-test logs. The process-death phase repeats the same fail-closed rule after its own ADB wait. Stubbed shell regression tests cover both delayed recovery and persistent failure.

Definition of Done: ADB server/device/boot checks are explicit and bounded; transient post-restart logcat failures receive only the bounded retries; persistent failure emits diagnostics and exits non-zero; instrumentation is never run after a failed preflight.
## SDK release asset integrity

The manual SDK release workflow creates a SHA-256 manifest for the single Release AAR and verifies the manifest before uploading the build artifact. The isolated publish job verifies the downloaded AAR and manifest again before invoking the GitHub Release action. The verifier rejects missing or multiple AARs, empty or symlinked AARs, symlinked manifests, mismatched or malformed checksum entries, and unexpected top-level files. Its shell regression suite runs in Android CI; it validates packaging integrity only and does not sign an APK or publish a release.

Definition of Done: the verifier accepts an unchanged one-AAR bundle and rejects tampering, duplicate AARs, extra files, missing manifests, malformed manifest paths, empty AARs, and symlinked assets. The publish job cannot reach the release action unless post-download verification succeeds.


## SDK unit-test localization and current acceptance run — 2026-11-26

The broad SDK telephony package shard had timed out without JUnit lifecycle events. A JVM thread dump identified a Robolectric main-thread deadlock. The test class now uses Robolectric 4.18.1 `LooperMode.INSTRUMENTATION_TEST`; the method-level SDK shards passed, and the full SDK/app verification job passed on run #45805234778.

Run #45805234778 also passed API 24 and API 26 emulator tests, including the bounded ADB/logcat recovery, and the API 36 Shell permission probe. API 35 failed because Maven Central returned HTTP 429 while resolving UTP/JUnit dependencies. Its one built-in retry used `--refresh-dependencies`, re-resolved cached plugin metadata, and then failed KSP plugin resolution. This was dependency infrastructure failure, not a test assertion.

Commit `64cf57f619c1d07a0315ac7efe086f626b617fbf` replaces that path with a bounded Gradle dependency-resolution retry helper (three retries, 15/30/45-second backoff, no forced metadata refresh) and regression tests. Only dependency-resolution failures are retried; application/test assertion failures fail immediately.

Do not merge PR #65 until the updated exact-head run passes API 24/26/35, API 36, all SDK/app/lint/AAR checks, Agent System Contracts, and Required checks. Physical-device validation (#3) and production signing/governance (#38) remain separate P3/P4 acceptance gates.
