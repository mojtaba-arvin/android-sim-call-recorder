#!/usr/bin/env bash
set -euo pipefail

SOURCE_DIRS=()
for path in app/src/main app/src/test app/src/androidTest; do
  [[ -d "$path" ]] && SOURCE_DIRS+=("$path")
done

FORBIDDEN='com\.mojtaba\.simcallrecorder\.(core|data|di|recording|domain\.(repository|usecase))'

if (("${#SOURCE_DIRS[@]}" > 0)) && grep -RInE --include='*.kt' "$FORBIDDEN" "${SOURCE_DIRS[@]}"; then
  echo "The sample app must consume the SDK public API, not implementation packages." >&2
  exit 1
fi

if ! grep -Fq 'implementation(project(":callrecorder-sdk"))' app/build.gradle.kts; then
  echo "The sample app must depend on :callrecorder-sdk." >&2
  exit 1
fi

echo "SDK module boundary verified: app source uses only public SDK/domain model contracts."
