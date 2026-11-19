# Android CI policy

## Required check

The stable branch-protection status check is **Android CI / Required checks** (job ID: `required`, job name: `Required checks`).

Do not make the conditional `verify` or `instrumented` jobs individually required: documentation-only changes intentionally skip those jobs. The `required` aggregator handles both cases and fails closed if change detection or any required code job fails.

The active `Protect main` ruleset (ID `27531148`) was verified on 2026-11-19 to require pull requests and this stable status check. It also prevents branch deletion and non-fast-forward updates. Required approving reviews are 0, strict/up-to-date checks are disabled, and no bypass actors are configured. These are observed live settings, not inferred from this document. See [Issue #8](https://github.com/mojtaba-arvin/android-sim-call-recorder/issues/8) for outstanding behavior tests and the strict-check policy decision.

## Change classification

The `changes` job compares the PR base/head or push before/after commits.

- Markdown files and files under `docs/` are documentation-only.
- Source, Gradle/build files, workflow files, manifests, tests, scripts, and all other paths trigger the full Android pipeline.
- Manual workflow dispatch always triggers the full pipeline.
- Mixed documentation + code changes trigger the full pipeline.

The required-check aggregator still runs for documentation-only changes, so skipped Android jobs do not leave the required check pending.

## Full code pipeline

The workflow `.github/workflows/android-ci.yml` currently performs. Its `pull_request.branches` filter includes both `main` and the temporary `bootstrap/native-android-foundation` base so stacked PRs can receive independent CI before the foundation is accepted; after stack acceptance, dependent PRs are retargeted to `main`:

1. SDK boundary guard.
2. SDK and app JUnit/Robolectric unit tests.
3. SDK and app Android Lint.
4. Android instrumentation-test compilation occurs as part of `:app:connectedDebugAndroidTest` on each emulator lane; do not compile the same test sources again in the unit/build lane.
5. SDK release AAR and app debug APK assembly, with artifacts/reports uploaded.
6. Compose UI instrumentation on Android emulator API 26 and API 35, with logcat, service, and audio diagnostics collected even after test failure.
7. A final `Required checks` aggregator that fails unless change detection, verification, and both emulator jobs have the required result.

The configured toolchain is Ubuntu 24.04, JDK 21, and Gradle 9.6.0. The workflow installs Android SDK Platform 37.2 and Build Tools 37.0.0. Emulator Quick Boot is enabled; do not force cold boots without a reproducible reason.

## Performance controls

- `gradle/actions/setup-gradle@v5` restores/saves Gradle User Home state; `org.gradle.caching=true` enables task-output caching and `org.gradle.parallel=true` enables parallel task execution. Preserve read-only cache behavior for fork PRs.
- The emulator job explicitly grants the runner user access to `/dev/kvm` using the documented udev rule and asserts the device is readable/writable before launching the AVD. Without this, the emulator action auto-detected no KVM access and launched with `-accel off`. On the first KVM-enabled run, API 26 boot fell from 132.601 s to 19.469 s and the job from 858.591 s to 269.116 s; API 35 boot fell from 720.172 s to 30.163 s and the job from 1566.208 s to 336.797 s. This is measured evidence from run #44532876770 vs prior Foundation run #44512168015.
- AVD snapshot caching was experimentally evaluated and removed. It restored roughly 1 GB per API and still required SDK system-image installation; API 26 job time was 205.6 s on cache miss vs 207.9 s on cache hit, and API 35 was 202.6 s vs 191.2 s. The small/inconsistent gain did not justify cache restore, quota use, and snapshot complexity. KVM remains the primary performance win.
- Pin `android-emulator-runner` v2.38.0 to immutable commit `a421e43855164a8197daf9d8d40fe71c6996bb0d`.
- End-to-end critical path: previous Foundation run #44512168015 took 1,658.393 s (27m38.4s) from first job start to last completion; KVM + parallel run #44532876770 took 350.482 s (5m50.5s), a 4.73x improvement. AVD cache-miss run #44534029954 passed both API levels and Required checks after a failed API 35 dependency-resolution attempt was retried; both API snapshots were saved.
- The two emulator API lanes now start after change detection and run concurrently with unit/lint/build verification. `Required checks` still depends on all jobs and remains fail-closed. This removes the previous serial `verify -> emulator` critical-path dependency without reducing coverage.
- `:app:compileDebugAndroidTestKotlin` is not separately invoked in the verify job because each emulator lane compiles the instrumentation APK as part of `:app:connectedDebugAndroidTest`; the previous separate compile was redundant work.
- Measure wall-clock duration and cache hit/reuse evidence on multiple exact-head runs before quantifying improvement. A single run or a runner-side emulator boot stall is not a valid performance benchmark.


## Concurrency and permissions

- One concurrency group per PR, or per branch for pushes/manual runs.
- A newer commit supersedes and cancels older work for the same PR/branch.
- Do not include the commit SHA in the concurrency group; doing so defeats superseded-run cancellation.
- Repository token permission is read-only (`contents: read`).
- Keep PR checks independent of secrets and safe for forked contributions.

## What CI does not prove

- Emulator success does not prove cellular modem audio access.
- An initialized `AudioRecord` or a non-empty/non-silent WAV does not prove that both call participants are audible.
- Shizuku eligibility is not a device compatibility result.
- Physical-device validation must separately record device/build, Android API, carrier/network, SIM slot, route, actual capture backend/source, and intelligibility of both directions.


## API 36 Shell permission probe

The change classifier emits an `audio` flag for Shizuku UserService and its tests. Only those changes trigger an API 36 emulator probe, which verifies the shell UID is 2000 and that `com.android.shell` is granted both `CALL_AUDIO_INTERCEPTION` and `CAPTURE_AUDIO_OUTPUT`. The stable Required checks aggregator requires this probe when `audio=true`; it is skipped for unrelated code and documentation-only changes. This tests the AOSP permission assumption without adding an emulator lane to every PR.
