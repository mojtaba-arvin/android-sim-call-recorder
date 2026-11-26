#!/usr/bin/env bash
set -euo pipefail

# Clear the logcat buffers only after the caller has established emulator readiness.
# ADB/logd can transiently reject a clear immediately after boot or an ADB restart.
# Restart the ADB server once, then use a bounded retry window; fail closed if the
# diagnostic boundary still cannot be established.
clear_logcat_buffers() {
  timeout 10 adb logcat -c || timeout 10 adb logcat -b all -c
}

clear_logcat_fail_closed() {
  if clear_logcat_buffers; then
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

  for attempt in 1 2 3; do
    if clear_logcat_buffers; then
      echo "Logcat buffers cleared after the one-time ADB server restart (attempt $attempt)." >&2
      return 0
    fi
    if [[ "$attempt" -lt 3 ]]; then
      echo "Logcat clear still unavailable after ADB restart (attempt $attempt/3); waiting 2 seconds." >&2
      sleep 2
    fi
  done

  echo "Unable to clear logcat after the bounded ADB server restart and retries; refusing to continue." >&2
  return 1
}
