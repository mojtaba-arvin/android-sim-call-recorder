#!/usr/bin/env bash
set -uo pipefail

mkdir -p app/build/reports/emulator
test_log="app/build/reports/emulator/connected-tests.log"

adb_diagnostics() {
  echo "==> ADB diagnostics"
  adb version || true
  adb devices -l || true
  pgrep -af 'adb|emulator' || true
}

echo "==> Starting and verifying the ADB server"
if ! adb start-server; then
  echo "ADB server could not start; refusing to run instrumentation without a device." >&2
  adb_diagnostics
  exit 1
fi

echo "==> Waiting for the emulator device"
if ! timeout 120 adb wait-for-device; then
  echo "Emulator did not become available through ADB within 120 seconds." >&2
  adb_diagnostics
  exit 1
fi

boot_completed=""
for attempt in $(seq 1 60); do
  boot_completed="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [[ "$boot_completed" == "1" ]]; then
    break
  fi
  sleep 2
done
if [[ "$boot_completed" != "1" ]]; then
  echo "Emulator connected but Android did not report sys.boot_completed=1 within 120 seconds." >&2
  adb_diagnostics
  exit 1
fi

echo "==> Emulator ready: API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if ! adb logcat -c; then
  # Clearing logcat is diagnostic-only. Some emulator/platform-tools
  # combinations reject buffer clearing even after the device is booted;
  # do not turn that into a false product-test failure.
  echo "Warning: could not clear logcat; continuing with boot messages in the diagnostic capture." >&2
fi

# The connected instrumentation task may uninstall the target APK on completion.
# Run the optional Maven Central retry before deciding whether to enter process-death tests.
if gradle --console=plain :app:connectedDebugAndroidTest > "$test_log" 2>&1; then
  test_exit_code=0
else
  test_exit_code=$?
fi

if [[ "$test_exit_code" -ne 0 ]] && grep -Eq "Could not (find org\\.junit\\.platform:junit-platform-(engine|launcher):1\\.13\\.3|GET .*repo\\.maven\\.apache\\.org.*(429|Too Many Requests)|get resource .*repo\\.maven\\.apache\\.org.*429)|Received status code 429 from server" "$test_log"; then
  echo "Detected transient Maven Central rate limiting / UTP resolution failure; waiting 15 seconds before one dependency-refresh retry." >> "$test_log"
  sleep 15
  if gradle --refresh-dependencies --console=plain :app:connectedDebugAndroidTest >> "$test_log" 2>&1; then
    test_exit_code=0
  else
    test_exit_code=$?
  fi
fi

if [[ "$test_exit_code" -eq 0 ]]; then
  echo "==> Re-establishing ADB after connected instrumentation tests" >> "$test_log"
  if ! adb start-server >> "$test_log" 2>&1; then
    echo "ADB server could not be restarted after instrumentation tests." >> "$test_log"
    adb_diagnostics >> "$test_log" 2>&1
    test_exit_code=1
  elif ! timeout 120 adb wait-for-device >> "$test_log" 2>&1; then
    echo "Emulator did not reconnect through ADB after instrumentation tests." >> "$test_log"
    adb_diagnostics >> "$test_log" 2>&1
    test_exit_code=1
  fi
fi

if [[ "$test_exit_code" -eq 0 ]]; then
  apk="app/build/outputs/apk/debug/app-debug.apk"
  if [[ ! -s "$apk" ]]; then
    echo "Debug APK is missing after connected instrumentation tests." >> "$test_log"
    test_exit_code=1
  elif ! adb install -r "$apk" >> "$test_log" 2>&1; then
    echo "Unable to reinstall the debug APK before process-death tests." >> "$test_log"
    test_exit_code=1
  elif ! adb shell pm path com.mojtaba.simcallrecorder >/dev/null 2>&1; then
    echo "Debug APK installation did not produce the expected application package." >> "$test_log"
    test_exit_code=1
  elif bash -x scripts/run-process-death-emulator-tests.sh >> "$test_log" 2>&1; then
    test_exit_code=0
  else
    test_exit_code=$?
  fi
fi

cat "$test_log"
adb logcat -d -t 5000 > app/build/reports/emulator/logcat.txt || true
adb shell dumpsys activity services > app/build/reports/emulator/services.txt || true
adb shell dumpsys audio > app/build/reports/emulator/audio.txt || true
exit "$test_exit_code"
