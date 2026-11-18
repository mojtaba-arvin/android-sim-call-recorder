# Android CI policy

## Required check

The stable branch-protection status check is **Android CI / Required checks** (job ID: `required`, job name: `Required checks`).

Do not make the conditional `verify` or `instrumented` jobs individually required: documentation-only changes intentionally skip those jobs. The `required` aggregator handles both cases and fails closed if change detection or any required code job fails.

Repository Settings still need to require pull requests and this stable status check in the active `Protect main` ruleset. See [Issue #8](https://github.com/mojtaba-arvin/android-sim-call-recorder/issues/8).

## Change classification

The `changes` job always runs and compares the PR base/head or push before/after commits.

- Markdown files and files under `docs/` are documentation-only.
- Source, Gradle/build files, workflow files, manifests, tests, and all other paths trigger the full Android pipeline.
- Manual workflow dispatch always triggers the full pipeline.
- Mixed documentation + code changes trigger the full pipeline.

The cheap required-check aggregator still runs for documentation-only changes, so a skipped workflow cannot leave a required check permanently pending.

## Full code pipeline

1. Unit and Robolectric tests.
2. Android Lint with `abortOnError = true`.
3. Debug APK assembly and artifact upload.
4. Compose UI instrumentation on Android API 35 emulator.
5. Upload instrumentation reports even when the emulator test step fails.

The first three Gradle tasks run in one invocation to share configuration and daemon work. Gradle dependency/build caching is provided by `gradle/actions/setup-gradle`. Emulator Quick Boot is enabled; do not force cold boots without a reproducible reason.

## Concurrency and permissions

- One concurrency group per PR, or per branch for pushes/manual runs.
- A newer commit supersedes and cancels older work for the same PR/branch.
- Do not include the commit SHA in the concurrency group; doing so defeats superseded-run cancellation.
- Repository token permission is read-only (`contents: read`).
- Use a stable Ubuntu 24.04 runner and JDK 21.
- Keep PR checks independent of secrets and safe for forked contributions.

## What CI does not prove

- Emulator success does not prove cellular modem audio access.
- An initialized `AudioRecord` or a non-empty WAV does not prove that both call participants are audible.
- Shizuku eligibility is not a device compatibility result.
- Physical-device validation must separately record device/build, Android API, carrier/network, SIM, route, actual capture backend/source, and intelligibility of both directions.
