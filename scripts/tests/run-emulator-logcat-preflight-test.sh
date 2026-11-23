#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_script="$repo_root/scripts/ci/run-emulator-tests.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/bin" "$tmp/scripts/ci" "$tmp/app/build/reports"
cp "$source_script" "$tmp/scripts/ci/run-emulator-tests.sh"

cat > "$tmp/bin/adb" <<'ADB'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  "start-server") exit 0 ;;
  "wait-for-device") exit 0 ;;
  "devices -l")
    printf 'List of devices attached\nemulator-5554 device product:ci\n'
    ;;
  "get-state") echo "device" ;;
  "shell getprop sys.boot_completed") echo "1" ;;
  "shell getprop ro.build.version.sdk") echo "35" ;;
  "logcat -c") exit 23 ;;
  "version") echo "Android Debug Bridge test stub" ;;
  *) exit 0 ;;
esac
ADB
chmod +x "$tmp/bin/adb"

cat > "$tmp/bin/gradle" <<'GRADLE'
#!/usr/bin/env bash
touch "${GRADLE_MARKER:?}"
exit 0
GRADLE
chmod +x "$tmp/bin/gradle"

if (
  cd "$tmp"
  PATH="$tmp/bin:$PATH" GRADLE_MARKER="$tmp/gradle-was-run" \
    bash scripts/ci/run-emulator-tests.sh > "$tmp/output.log" 2>&1
); then
  echo "FAIL: emulator harness continued after logcat preflight failure." >&2
  exit 1
fi

grep -q "refusing to run instrumentation" "$tmp/output.log" || {
  cat "$tmp/output.log" >&2
  echo "FAIL: expected fail-closed logcat diagnostic was not emitted." >&2
  exit 1
}
[[ ! -e "$tmp/gradle-was-run" ]] || {
  echo "FAIL: Gradle instrumentation ran after logcat preflight failed." >&2
  exit 1
}

echo "Emulator logcat preflight fail-closed test passed."
