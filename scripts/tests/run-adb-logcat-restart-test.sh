#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"

cat > "$tmp/bin/adb" <<'ADB'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  "logcat -c"|"logcat -b all -c")
    attempts_file="${ADB_LOGCAT_ATTEMPTS:?}"
    attempts=0
    [[ -f "$attempts_file" ]] && attempts="$(cat "$attempts_file")"
    attempts=$((attempts + 1))
    printf '%s' "$attempts" > "$attempts_file"
    [[ -e "${ADB_RESTARTED:?}" && "$attempts" -ge 5 ]] && exit 0
    exit 23
    ;;
  "kill-server") touch "$ADB_RESTARTED" ;;
  "start-server"|"wait-for-device") exit 0 ;;
  "get-state") echo "device" ;;
  "devices -l") printf 'List of devices attached\nemulator-5554 device product:ci\n' ;;
  *) exit 0 ;;
esac
ADB
chmod +x "$tmp/bin/adb"

(
  cd "$tmp"
  PATH="$tmp/bin:$PATH" ADB_RESTARTED="$tmp/adb-restarted" ADB_LOGCAT_ATTEMPTS="$tmp/adb-logcat-attempts" \
    bash -c 'source "'"$repo_root"'/scripts/ci/clear-logcat-fail-closed.sh"; clear_logcat_fail_closed'
)

[[ -e "$tmp/adb-restarted" ]] || {
  echo "FAIL: helper did not restart the ADB server after logcat clear failure." >&2
  exit 1
}
[[ "$(cat "$tmp/adb-logcat-attempts")" -ge 5 ]] || {
  echo "FAIL: helper did not retry logcat clearing after the ADB restart." >&2
  exit 1
}

echo "ADB restart and logcat clear recovery test passed."
