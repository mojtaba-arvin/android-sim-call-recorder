#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
touch "$tmp/app.apk"

cat > "$tmp/bin/apksigner" <<'APKSIGNER'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == "verify" ]] || exit 2
cat <<EOF
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Signer #1 certificate SHA-256 digest: ${STUB_CERT_DIGEST:?}
EOF
APKSIGNER
chmod +x "$tmp/bin/apksigner"

digest="A1:B2:C3:D4"
PATH="$tmp/bin:$PATH" STUB_CERT_DIGEST="$digest" EXPECTED_SIGNING_CERT_SHA256="a1b2c3d4" \
  bash "$repo_root/scripts/verify-apk-signature.sh" "$tmp/app.apk"

if PATH="$tmp/bin:$PATH" STUB_CERT_DIGEST="$digest" EXPECTED_SIGNING_CERT_SHA256="deadbeef" \
  bash "$repo_root/scripts/verify-apk-signature.sh" "$tmp/app.apk" >"$tmp/mismatch.log" 2>&1; then
  echo "FAIL: mismatched signing certificate was accepted." >&2
  exit 1
fi
grep -Fq "APK_CERTIFICATE_FINGERPRINT_MISMATCH" "$tmp/mismatch.log"

if PATH="$tmp/bin:$PATH" STUB_CERT_DIGEST="$digest" env -u EXPECTED_SIGNING_CERT_SHA256 \
  bash "$repo_root/scripts/verify-apk-signature.sh" "$tmp/app.apk" >"$tmp/missing.log" 2>&1; then
  echo "FAIL: missing expected certificate fingerprint was accepted." >&2
  exit 1
fi
grep -Fq "EXPECTED_SIGNING_CERT_SHA256 is required" "$tmp/missing.log"

if PATH="$tmp/bin:$PATH" STUB_CERT_DIGEST="$digest" EXPECTED_SIGNING_CERT_SHA256="$digest" \
  bash "$repo_root/scripts/verify-apk-signature.sh" "$tmp/missing.apk" >"$tmp/file.log" 2>&1; then
  echo "FAIL: missing APK path was accepted." >&2
  exit 1
fi
grep -Fq "APK_SIGNATURE_FILE_MISSING" "$tmp/file.log"

echo "Production APK signature verifier fail-closed tests passed."
