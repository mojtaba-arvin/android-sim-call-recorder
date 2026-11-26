# Production APK signing design

**Status: design only — not configured or release-ready.**

The repository must not publish an APK until the production signing key, GitHub Environment, expected certificate fingerprint, and physical-device acceptance evidence are all in place. The current release workflow is AAR-only and must remain so until this gate is complete.

## Trust boundary

Use a dedicated GitHub Actions Environment named `android-production-signing`.

Configure the Environment with:
- Deployment branch restriction: `main` only.
- Required environment reviewer(s), independent of the PR author where GitHub's environment policy permits.
- No self-review bypass for production deployment where the repository plan/settings support it.

Store the following as Environment secrets, never repository files, Gradle properties committed to Git, workflow literals, artifacts, or logs:

| Environment secret | Purpose |
| --- | --- |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | Base64-encoded production keystore bytes |
| `ANDROID_RELEASE_STORE_PASSWORD` | Keystore password |
| `ANDROID_RELEASE_KEY_ALIAS` | Signing key alias |
| `ANDROID_RELEASE_KEY_PASSWORD` | Private-key password |

Store `ANDROID_RELEASE_CERT_SHA256` as an Environment variable (not a secret): the independently recorded SHA-256 fingerprint of the expected signing certificate. The fingerprint is public identity metadata, not key material. The workflow maps these Environment values to Gradle's `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, and `EXPECTED_SIGNING_CERT_SHA256` inputs; the keystore path is a temporary runner-local path, not a secret.

## Required job separation

1. **Build/test job:** runs unit/instrumentation tests, lint, SDK AAR and debug APK assembly with read-only repository permissions. It has no access to signing secrets and does not assemble or upload an unsigned release APK. The current Gradle release tasks intentionally fail closed without signing inputs.
2. **Sign/verify job:** runs only after the build succeeds, references the protected `android-production-signing` Environment, and has no publication permission. It builds the release APK with protected signing inputs in that job, then verifies it. It must:
   - Fail immediately if any required secret or the expected fingerprint is missing/blank.
   - Decode the keystore into a unique runner-temporary directory with restrictive file permissions; never print secret values or keytool passwords.
   - Build and sign the release APK within the protected job using the configured alias; do not create or upload an unsigned release APK artifact.
   - Run `apksigner verify --verbose --print-certs` and reject invalid signatures, unexpected package/version metadata, or a certificate SHA-256 fingerprint that differs from `ANDROID_RELEASE_CERT_SHA256`.
   - Upload only the verified signed APK and its checksum. Never upload the keystore, intermediate files, or unsigned APK as a release asset.
   - Remove temporary key material on exit, including failure paths.
3. **Publish job:** remains a separate, explicit-approval job with the minimum `contents: write` permission. It may publish only the signed APK and checksum after successful signing verification and after physical-device acceptance is recorded for the exact build/device matrix.

## Fail-closed conditions

The signing/publishing path must stop when:
- Any signing secret or the expected fingerprint is missing.
- Keystore decoding, key alias lookup, signing, or signature verification fails.
- The observed certificate fingerprint differs from the pinned expected fingerprint.
- The artifact does not match the exact build SHA/package/version being approved.
- Physical-device acceptance for the exact candidate build is incomplete.

Do not silently fall back to a debug key, generate an ephemeral production key in CI, skip certificate comparison, or publish an unsigned/debug-signed APK.

## Current state and activation checklist

- [ ] Create and protect the `android-production-signing` GitHub Environment.
- [ ] Generate and back up the production key outside CI using the organization's approved key-custody process.
- [ ] Record the certificate SHA-256 fingerprint through an independent channel.
- [ ] Configure the four Environment secrets and the fingerprint variable.
- [ ] Implement and test the isolated sign/verify job with negative tests for missing secrets, invalid keystore, and fingerprint mismatch.
- [ ] Complete physical-device acceptance for the exact signed candidate build.
- [ ] Obtain explicit owner approval before enabling APK publication.

Until every item is evidenced, the production APK path remains disabled. The current manual release workflow publishes only the SDK AAR and SHA-256 checksum; no signing secret or keystore is configured in this repository.
