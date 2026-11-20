#!/usr/bin/env bash
set -euo pipefail

PACKAGE="com.mojtaba.simcallrecorder"
COMPONENT="${PACKAGE}/.ProcessDeathHarnessActivity"
TAG="CallRecorderProcessDeath"
ACTION_ONE="com.mojtaba.simcallrecorder.debug.PREPARE_WINDOW_ONE"
ACTION_TWO="com.mojtaba.simcallrecorder.debug.RECOVER_ONE_AND_PREPARE_WINDOW_TWO"
ACTION_THREE="com.mojtaba.simcallrecorder.debug.RECOVER_TWO_AND_TEST_NEW_CAPTURE"

adb wait-for-device
adb shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO
adb shell pm grant "$PACKAGE" android.permission.READ_PHONE_STATE
if adb shell pm list permissions -d | grep -q android.permission.POST_NOTIFICATIONS; then
  adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS || true
fi
adb logcat -c

wait_for_marker() {
  local marker="$1"
  local attempt
  for attempt in $(seq 1 120); do
    if adb logcat -d -s "$TAG:I" | grep -Fq "$marker"; then
      echo "Observed: $marker"
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for marker: $marker" >&2
  adb logcat -d -s "$TAG:I" >&2
  return 1
}

wait_for_process_exit() {
  local attempt
  for attempt in $(seq 1 30); do
    if [[ -z "$(adb shell pidof "$PACKAGE" | tr -d '\r')" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "App process did not exit after the requested process-death fault." >&2
  return 1
}

adb shell am start -W -n "$COMPONENT" -a "$ACTION_ONE"
wait_for_marker "WINDOW1_READY"
adb shell am force-stop "$PACKAGE"
wait_for_process_exit

adb shell am start -W -n "$COMPONENT" -a "$ACTION_TWO"
wait_for_marker "WINDOW1_RECOVERED"
wait_for_marker "WINDOW2_STOP_REQUESTED"
wait_for_marker "WINDOW2_KILL_AFTER_WAV_PROMOTION"
wait_for_process_exit

adb shell am start -W -n "$COMPONENT" -a "$ACTION_THREE"
wait_for_marker "RECOVERY_CAPTURE_RACE_PASSED"
wait_for_marker "PROCESS_DEATH_SUITE_PASSED"

API_LEVEL="$(adb shell getprop ro.build.version.sdk | tr -d '\\r')"
if [[ "$API_LEVEL" -ge 31 ]]; then
  adb shell am start -W -n "$COMPONENT" -a "com.mojtaba.simcallrecorder.debug.TEST_BACKGROUND_FGS_DENIAL"
  wait_for_marker "FGS_START_DENIED_BY_PLATFORM"
fi

echo "Real process-death recovery, both crash windows, recovery/new-capture ordering, and background FGS denial passed."
