#!/usr/bin/env bash
set -euo pipefail

APP_SOURCES="app/src/main app/src/test app/src/androidTest"
FORBIDDEN='com\.mojtaba\.simcallrecorder\.(core|data|di|recording|domain\.(repository|usecase))'

if grep -RInE --include='*.kt' "$FORBIDDEN" $APP_SOURCES; then
  echo "The sample app must consume the SDK public API, not implementation packages." >&2
  exit 1
fi

if ! grep -Fq 'implementation(project(":callrecorder-sdk"))' app/build.gradle.kts; then
  echo "The sample app must depend on :callrecorder-sdk." >&2
  exit 1
fi

echo "SDK module boundary verified: app source uses only public SDK/domain model contracts."
