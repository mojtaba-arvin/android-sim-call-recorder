# Production APK signing

The application release variant is production-signed only. Debug signing is restricted to debug builds and must never be used as a production fallback.

## Protected GitHub Environment

Use the protected `android-production-signing` GitHub Actions Environment. Keep key material in Environment secrets, never repository files, Gradle properties committed to Git, workflow literals, artifacts, caches, issue comments, or logs.

| Environment secret / variable | Purpose |
| --- | --- |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | Base64-encoded production keystore bytes (secret) |
| `ANDROID_RELEASE_STORE_PASSWORD` | Keystore password (secret) |
| `ANDROID_RELEASE_KEY_ALIAS` | Signing key alias (secret) |
| `ANDROID_RELEASE_KEY_PASSWORD` | Private-key password (secret) |
| `ANDROID_RELEASE_CERT_SHA256` | Independently verified SHA-256 certificate fingerprint (Environment variable; public identity metadata) |

Immediately before the protected release build, the workflow decodes the keystore into a unique runner-local file under `$RUNNER_TEMP` with mode 0600. It maps the Environment values to the Gradle inputs:

- `ANDROID_KEYSTORE_PATH`: temporary runner-local keystore path (not a secret itself)
- `ANDROID_KEYSTORE_PASSWORD`: mapped from `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_KEY_ALIAS`: mapped from `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`: mapped from `ANDROID_RELEASE_KEY_PASSWORD`
- `EXPECTED_SIGNING_CERT_SHA256`: mapped from `ANDROID_RELEASE_CERT_SHA256`

Never echo secret values. Remove temporary key material on all exit paths.

The app's `:app:packageRelease` and `:app:bundleRelease` tasks depend on `:app:verifyReleaseSigningInputs`. The task fails closed if any Gradle input is absent or the keystore file is missing. Debug builds and the SDK AAR build do not require app-signing secrets.

## Certificate verification

After producing an APK, run:

```bash
EXPECTED_SIGNING_CERT_SHA256="$EXPECTED_SIGNING_CERT_SHA256" \
  bash scripts/verify-apk-signature.sh app/build/outputs/apk/release/app-release.apk
```

The verifier requires valid APK Signature Scheme v2 and v3 signatures and an exact certificate SHA-256 match. A successful Gradle build alone is not release evidence. Independently record the approved fingerprint in the release approval record.

## Key lifecycle

1. Generate the upload/signing key in a controlled offline environment; do not generate or print private key material in CI.
2. Back up the keystore and passwords in an approved encrypted secret manager with recovery ownership documented.
3. Protect the release Environment with required reviewers and restrict who can modify its secrets.
4. Verify the certificate fingerprint out-of-band before configuring `ANDROID_RELEASE_CERT_SHA256`.
5. Rotate only through a documented Android signing-key migration plan; do not silently replace the certificate.
6. Keep a copy of the public certificate/fingerprint for independent APK verification. The public certificate is not a secret.

## Current release boundary

No production keystore or signing secrets are configured by this change. CI intentionally verifies that release packaging is blocked when signing inputs are absent. No APK/AAB release, tag, or publication is performed. The existing SDK AAR release path remains separate and requires explicit manual approval. Physical-device acceptance in Issue #3 remains mandatory before any app APK distribution.
