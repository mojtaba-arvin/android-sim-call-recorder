# Production APK signing

The application release variant is production-signed only. Debug signing is restricted to debug builds and must never be used as a production fallback.

## Required CI environment

Provide these values only through a protected GitHub Actions Environment or repository secrets:

- `ANDROID_KEYSTORE_PATH`: path to a temporary runner-local keystore file
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The keystore bytes should be stored as a protected secret (for example, `ANDROID_KEYSTORE_BASE64`) and materialized under `$RUNNER_TEMP` with mode 0600 immediately before the release build. Never store the keystore, passwords, private key, or base64 payload in Git, artifacts, caches, issue comments, or logs. Do not echo secret values.

The app's `:app:packageRelease` and `:app:bundleRelease` tasks depend on `:app:verifyReleaseSigningInputs`. The task fails closed if any input is absent or the keystore file is missing. Debug builds and the SDK AAR build do not require app-signing secrets.

## Certificate pinning and APK verification

Set `EXPECTED_SIGNING_CERT_SHA256` to the SHA-256 digest of the production signing certificate (colons and whitespace are ignored). After producing an APK, run:

```bash
EXPECTED_SIGNING_CERT_SHA256="$EXPECTED_SIGNING_CERT_SHA256" \
  bash scripts/verify-apk-signature.sh app/build/outputs/apk/release/app-release.apk
```

The verifier requires valid APK Signature Scheme v2 and v3 signatures and an exact certificate SHA-256 match. A successful Gradle build alone is not release evidence. Keep the approved certificate fingerprint in a protected environment variable and independently record it in the release approval record.

## Key lifecycle

1. Generate the upload/signing key in a controlled offline environment; do not generate or print private key material in CI.
2. Back up the keystore and passwords in an approved encrypted secret manager with recovery ownership documented.
3. Protect the release environment with required reviewers and restrict who can modify its secrets.
4. Verify the certificate fingerprint out-of-band before configuring `EXPECTED_SIGNING_CERT_SHA256`.
5. Rotate only through a documented Android signing-key migration plan; do not silently replace the certificate.
6. Keep a copy of the public certificate/fingerprint for independent APK verification. The public certificate is not a secret.

## Current release boundary

No production keystore or signing secrets are configured by this change. The CI test intentionally verifies that release packaging is blocked when those inputs are absent. No APK/AAB release, tag, or publication is performed. The existing SDK AAR release path remains separate and requires its own explicit manual approval. Physical-device acceptance in Issue #3 remains mandatory before any app APK distribution.
