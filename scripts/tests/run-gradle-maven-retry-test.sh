#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"

cat > "$tmp/bin/gradle" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$GRADLE_MOCK_CALLS"
count="$(wc -l < "$GRADLE_MOCK_CALLS" | tr -d ' ')"
case "$count" in
  1)
    echo "Received status code 429 from server"
    exit 1
    ;;
  2)
    echo "Plugin [id: 'com.google.devtools.ksp', version: '2.3.14'] was not found"
    exit 1
    ;;
  3)
    echo "BUILD SUCCESSFUL"
    exit 0
    ;;
  *)
    echo "Unexpected Gradle invocation count: $count" >&2
    exit 99
    ;;
esac
MOCK
chmod +x "$tmp/bin/gradle"

export PATH="$tmp/bin:$PATH"
export GRADLE_MOCK_CALLS="$tmp/gradle-calls.log"
export GRADLE_MAVEN_RETRY_BACKOFF_SECONDS=0
source "$repo_root/scripts/ci/gradle-maven-retry.sh"

if ! run_gradle_with_maven_rate_limit_retry "$tmp/success.log" :app:connectedDebugAndroidTest; then
  echo "FAIL: transient Maven/UTP failures did not recover." >&2
  cat "$tmp/success.log" >&2
  exit 1
fi
[[ "$(wc -l < "$GRADLE_MOCK_CALLS" | tr -d ' ')" == "3" ]] || {
  echo "FAIL: expected exactly three Gradle attempts." >&2
  exit 1
}
if grep -Fq -- "--refresh-dependencies" "$GRADLE_MOCK_CALLS"; then
  echo "FAIL: retry must not force-refresh cached plugin metadata." >&2
  exit 1
fi

# A non-dependency failure must not be retried.
cat > "$tmp/bin/gradle" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$GRADLE_MOCK_CALLS"
echo "Execution failed for task ':app:connectedDebugAndroidTest'."
exit 1
MOCK
chmod +x "$tmp/bin/gradle"
: > "$GRADLE_MOCK_CALLS"
if run_gradle_with_maven_rate_limit_retry "$tmp/non-transient.log" :app:connectedDebugAndroidTest; then
  echo "FAIL: non-transient Gradle failure unexpectedly passed." >&2
  exit 1
fi
[[ "$(wc -l < "$GRADLE_MOCK_CALLS" | tr -d ' ')" == "1" ]] || {
  echo "FAIL: non-transient failure should not retry." >&2
  exit 1
}

echo "Gradle Maven rate-limit retry regression tests passed."
