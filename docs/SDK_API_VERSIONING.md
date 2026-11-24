# SDK API and versioning policy

**Status:** policy defined; no SDK release has been published yet.

## Public API boundary

The supported consumer surface is the `com.mojtaba.simcallrecorder.sdk` API package and the documented SDK entry point/contract types. Consumers must not import implementation packages such as `recording`, `data`, `domain.repository`, `domain.usecase`, `di`, or internal Shizuku/AIDL classes.

The sample app is the reference consumer. `scripts/verify-sdk-boundary.sh` checks that app production/test code does not import the SDK's implementation packages. SDK implementation changes are free to refactor behind the public facade unless they change a documented public contract.

## Version format

Use Git tags in the form:

- Stable: `vMAJOR.MINOR.PATCH`
- Pre-release: `vMAJOR.MINOR.PATCH-alpha.N`, `-beta.N`, or `-rc.N`

The current release workflow derives the release version from the existing tag and names the published asset `callrecorder-sdk-VERSION.aar`. It publishes a GitHub Release asset and SHA-256 checksum; it does **not** publish a Maven repository coordinate. Consumers currently integrate the AAR as a downloaded artifact or use the source project module.

The app's `versionName` is a separate application version and must not be treated as the SDK's version.

## Semantic compatibility rules

- **MAJOR:** removal/renaming of public types or members; incompatible parameter/return-type changes; changed threading, lifecycle, permission, consent, storage, or callback semantics; changed minimum supported Android API.
- **MINOR:** additive public APIs, optional configuration, new observable diagnostics, or new compatible capture capabilities.
- **PATCH:** backward-compatible bug fixes, internal refactors, documentation corrections, and test-only changes.
- **Pre-release:** API and behavior may change before the first stable major release. Mark the release as a GitHub pre-release.

Do not use implementation package names as a workaround for missing public API. Add or extend the facade and its contract types instead.

## Deprecation and migration

1. Mark a public member deprecated before removing it from a stable major line.
2. Provide a replacement API and a short migration example in the SDK integration guide.
3. Keep the deprecated API through at least one subsequent minor release where practical.
4. Remove it only in a major release, with the migration noted in release notes.

## Release gate

A release candidate must satisfy all of the following:

- Changes are merged into `main`; release tags must point to a commit reachable from `main`.
- The SDK/app unit tests, lint, SDK Release AAR assembly, and required CI checks pass on the exact tagged SHA.
- The public API diff is reviewed against the previous released API surface. Until an automated binary-compatibility baseline is introduced, this review is a required human release checklist item.
- The AAR is accompanied by a SHA-256 checksum.
- Release workflow dispatch occurs from `main`, uses an existing tag, and requires explicit `confirm_publish=true`.
- No APK release is implied by an SDK AAR release. APK signing and physical-device acceptance remain separate gates.

## First stable release preparation

Before `1.0.0`:

- Freeze the initial public API inventory and add a checked-in API signature baseline or binary compatibility validator.
- Test consumption from a clean, independent Android application using only the documented public facade.
- Document minSdk, required permissions, Shizuku setup, supported/experimental capture modes, consent requirements, and known device/OEM limitations.
- Do not describe a device as two-way compatible without physical-device evidence for its exact build/route/SIM configuration.
