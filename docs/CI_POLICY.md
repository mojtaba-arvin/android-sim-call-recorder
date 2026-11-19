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
4. App instrumentation-test Kotlin compilation.
5. SDK release AAR and app debug APK assembly, with artifacts/reports uploaded.
6. Compose UI instrumentation on Android emulator API 26 and API 35, with logcat, service, and audio diagnostics collected even after test failure.
7. A final `Required checks` aggregator that fails unless change detection, verification, and both emulator jobs have the required result.

The configured toolchain is Ubuntu 24.04, JDK 21, and Gradle 9.6.0. The workflow installs Android SDK Platform 37.2 and Build Tools 37.0.0. Emulator Quick Boot is enabled; do not force cold boots without a reproducible reason.

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
