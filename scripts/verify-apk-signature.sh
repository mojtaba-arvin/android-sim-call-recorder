#!/usr/bin/env bash
set -euo pipefail

apk="${1:?Usage: verify-apk-signature.sh <apk>}"
[[ -f "$apk" ]] || { echo "APK_SIGNATURE_FILE_MISSING: $apk" >&2; exit 1; }
expected="${EXPECTED_SIGNING_CERT_SHA256:?EXPECTED_SIGNING_CERT_SHA256 is required}"
command -v apksigner >/dev/null 2>&1 || { echo "APK_SIGNATURE_TOOL_MISSING: apksigner is not on PATH." >&2; exit 1; }

report="$(apksigner verify --verbose --print-certs "$apk" 2>&1)" || {
  printf '%s\n' "$report" >&2
  echo "APK_SIGNATURE_INVALID: apksigner verification failed." >&2
  exit 1
}
grep -Fq "Verified using v2 scheme (APK Signature Scheme v2): true" <<<"$report" || {
  echo "APK_SIGNATURE_V2_REQUIRED: APK Signature Scheme v2 verification did not pass." >&2
  exit 1
}
grep -Fq "Verified using v3 scheme (APK Signature Scheme v3): true" <<<"$report" || {
  echo "APK_SIGNATURE_V3_REQUIRED: APK Signature Scheme v3 verification did not pass." >&2
  exit 1
}
actual="$(sed -n 's/^Signer #1 certificate SHA-256 digest: *//p' <<<"$report" | head -n 1)"
normalize() { tr -d ' :\r\n\t' | tr '[:upper:]' '[:lower:]'; }
expected_normalized="$(printf '%s' "$expected" | normalize)"
actual_normalized="$(printf '%s' "$actual" | normalize)"
[[ -n "$actual_normalized" ]] || { echo "APK_CERTIFICATE_FINGERPRINT_MISSING: apksigner returned no signer SHA-256 digest." >&2; exit 1; }
[[ "$actual_normalized" == "$expected_normalized" ]] || {
  echo "APK_CERTIFICATE_FINGERPRINT_MISMATCH: signer certificate does not match the approved fingerprint." >&2
  exit 1
}
echo "APK signature schemes and approved signer certificate verified."
