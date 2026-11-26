#!/usr/bin/env bash
set -euo pipefail

asset_dir="${1:-release-assets}"
[[ -d "$asset_dir" ]] || {
  echo "Release asset directory does not exist: $asset_dir" >&2
  exit 1
}

mapfile -t aar_files < <(find "$asset_dir" -mindepth 1 -maxdepth 1 -type f -name 'callrecorder-sdk-*.aar' -printf '%f\n' | sort)
[[ ${#aar_files[@]} -eq 1 ]] || {
  echo "Expected exactly one SDK AAR; found ${#aar_files[@]}." >&2
  exit 1
}

aar_name="${aar_files[0]}"
aar_path="$asset_dir/$aar_name"
[[ ! -L "$aar_path" ]] || {
  echo "SDK AAR must not be a symlink." >&2
  exit 1
}
[[ -s "$aar_path" ]] || {
  echo "SDK AAR is empty or unreadable." >&2
  exit 1
}

manifest="$asset_dir/SHA256SUMS.txt"
[[ ! -L "$manifest" ]] || {
  echo "SHA256SUMS.txt must not be a symlink." >&2
  exit 1
}
[[ -f "$manifest" ]] || {
  echo "SHA256SUMS.txt is missing." >&2
  exit 1
}

entry_count="$(wc -l < "$manifest" | tr -d ' ')"
[[ "$entry_count" == "1" ]] || {
  echo "SHA256SUMS.txt must contain exactly one checksum entry." >&2
  exit 1
}

expected="$(sha256sum "$aar_path" | awk -v name="$aar_name" '{ print $1 "  " name }')"
actual="$(cat "$manifest")"
[[ "$actual" == "$expected" ]] || {
  echo "SDK AAR checksum manifest does not match the downloaded asset." >&2
  exit 1
}

top_level_entries="$(find "$asset_dir" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')"
[[ "$top_level_entries" == "2" ]] || {
  echo "Release asset directory contains unexpected files or directories." >&2
  exit 1
}

(
  cd "$asset_dir"
  sha256sum --check --strict SHA256SUMS.txt
)
