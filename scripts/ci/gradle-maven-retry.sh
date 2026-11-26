#!/usr/bin/env bash

# Retry Gradle only when a failed invocation indicates transient Maven Central
# or plugin/dependency resolution trouble. Never force-refresh all cached metadata:
# doing so can turn one Maven 429 into a new plugin-marker resolution failure.
run_gradle_with_maven_rate_limit_retry() {
  local log_file="$1"
  shift
  local attempt_log
  local status=0
  local max_retries="${GRADLE_MAVEN_RETRY_MAX_ATTEMPTS:-3}"
  local backoff_seconds="${GRADLE_MAVEN_RETRY_BACKOFF_SECONDS:-15}"
  local retry_pattern='Received status code 429 from server|Too Many Requests|HTTP 429|Could not resolve org\.junit\.platform:junit-platform-(engine|launcher):1\.13\.3|Could not (GET|get resource).*repo\.maven\.apache\.org.*429|Plugin \[id: .*com\.google\.devtools\.ksp.*\] was not found|Could not resolve plugin artifact .*com\.google\.devtools\.ksp'

  if [[ ! "$max_retries" =~ ^[0-9]+$ || ! "$backoff_seconds" =~ ^[0-9]+$ ]]; then
    echo "Invalid Gradle retry bounds; expected non-negative integers." >> "$log_file"
    return 2
  fi

  attempt_log="$(mktemp)"
  if gradle --console=plain "$@" > "$attempt_log" 2>&1; then
    cat "$attempt_log" >> "$log_file"
    rm -f "$attempt_log"
    return 0
  else
    status="$?"
  fi
  cat "$attempt_log" >> "$log_file"

  if ! grep -Eq "$retry_pattern" "$attempt_log"; then
    rm -f "$attempt_log"
    return "$status"
  fi

  for ((attempt = 1; attempt <= max_retries; attempt++)); do
    local delay=$((backoff_seconds * attempt))
    echo "Transient Maven/Gradle dependency resolution failure; retry $attempt/$max_retries in ${delay}s without refreshing cached metadata." >> "$log_file"
    if (( delay > 0 )); then
      sleep "$delay"
    fi

    : > "$attempt_log"
    if gradle --console=plain "$@" > "$attempt_log" 2>&1; then
      cat "$attempt_log" >> "$log_file"
      rm -f "$attempt_log"
      return 0
    else
      status="$?"
    fi
    cat "$attempt_log" >> "$log_file"

    if ! grep -Eq "$retry_pattern" "$attempt_log"; then
      rm -f "$attempt_log"
      return "$status"
    fi
  done

  rm -f "$attempt_log"
  return "$status"
}
