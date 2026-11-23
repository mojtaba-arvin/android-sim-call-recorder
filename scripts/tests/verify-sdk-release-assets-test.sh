#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
verifier="$root/scripts/verify-sdk-release-assets.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

make_valid_bundle() {
  local dir="$1"
  mkdir -p "$dir"
  printf 'release-aar-fixture\n' > "$dir/callrecorder-sdk-0.1.0.aar"
  (
    cd "$dir"
    sha256sum callrecorder-sdk-0.1.0.aar > SHA256SUMS.txt
  )
}

expect_rejected() {
  local dir="$1"
  if bash "$verifier" "$dir" >/dev/null 2>&1; then
    fail "verifier accepted invalid bundle: $dir"
  fi
}

valid="$tmp/valid"
make_valid_bundle "$valid"
bash "$verifier" "$valid" >/dev/null

tampered="$tmp/tampered"
make_valid_bundle "$tampered"
printf 'tampered\n' >> "$tampered/callrecorder-sdk-0.1.0.aar"
expect_rejected "$tampered"

multiple="$tmp/multiple"
make_valid_bundle "$multiple"
cp "$multiple/callrecorder-sdk-0.1.0.aar" "$multiple/callrecorder-sdk-0.1.1.aar"
expect_rejected "$multiple"

unexpected="$tmp/unexpected"
make_valid_bundle "$unexpected"
printf 'unexpected\n' > "$unexpected/notes.txt"
expect_rejected "$unexpected"

malformed="$tmp/malformed"
make_valid_bundle "$malformed"
printf '%s\n' '../outside.aar' > "$malformed/SHA256SUMS.txt"
expect_rejected "$malformed"

missing="$tmp/missing"
make_valid_bundle "$missing"
rm "$missing/SHA256SUMS.txt"
expect_rejected "$missing"

echo "Release asset checksum verification tests passed."
