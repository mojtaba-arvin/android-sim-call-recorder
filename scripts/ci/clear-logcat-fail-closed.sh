#!/usr/bin/env bash
set -euo pipefail

# Clear the logcat buffers only after the caller has established emulator readiness.
# ADB can lose its daemon during emulator startup; restart it once, re-verify the device,
# and retry. If diagnostics still cannot be isolated, fail closed.
clear_logcat_fail_closed() {
  if adb logcat -c || adb logcat -b all -c; then
    return 0
  fi

  echo "Logcat clear failed; restarting the ADB server once before refusing instrumentation." >&2
  timeout 15 adb kill-server || true
  if ! timeout 30 adb start-server; then
    echo "ADB server did not restart after logcat clear failure." >&2
    return 1
  fi
  if ! timeout 120 adb wait-for-device; then
    echo "Emulator did not reconnect after the ADB server restart." >&2
    return 1
  fi
  if ! adb get-state >/dev/null 2>&1; then
    echo "ADB default target is not in the device state after restart." >&2
    return 1
  fi
  if ! adb devices -l | awk 'NR > 1 && $1 ~ /^emulator-/ && $2 == "device" { count++ } END { exit (count == 1 ? 0 : 1) }'; then
    echo "Expected exactly one online emulator after the ADB server restart." >&2
    return 1
  fi

  if adb logcat -c || adb logcat -b all -c; then
    echo "Logcat buffers cleared after the one-time ADB server restart." >&2
    return 0
  fi

  echo "Unable to clear logcat after the bounded ADB server restart; refusing to continue." >&2
  return 1
}
