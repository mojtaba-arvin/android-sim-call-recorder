#!/usr/bin/env python3
"""Classify changed paths for Android CI without skipping unknown files."""

from __future__ import annotations

import fnmatch
import sys
from collections.abc import Iterable

NON_ANDROID_PATTERNS = (
    "*.md", "docs/*", "agent-system/*", "schemas/*", "AGENTS.md",
    "DECISIONS.md", ".editorconfig", ".gitignore", "LICENSE",
    ".github/ISSUE_TEMPLATE/*", ".github/PULL_REQUEST_TEMPLATE.md",
    ".github/dependabot.yml", ".github/workflows/agent-system-validation.yml",
    ".github/workflows/physical-device-evidence.yml",
    ".github/workflows/release.yml", "scripts/validate_agent_system.py",
    "scripts/validate_physical_device_report.py",
    "scripts/tests/test_validate_physical_device_report.py",
    "scripts/tests/test_classify_android_ci_changes.py",
    "scripts/classify_android_ci_changes.py",
)

SHIZUKU_AUDIO_PREFIXES = (
    "callrecorder-sdk/src/main/java/com/mojtaba/simcallrecorder/recording/shizuku/",
    "callrecorder-sdk/src/test/java/com/mojtaba/simcallrecorder/recording/shizuku/",
)


def classify_paths(paths: Iterable[str]) -> tuple[bool, bool]:
    """Return (android_code_changed, shizuku_call_audio_changed).

    Unknown paths deliberately require the full Android pipeline. The audio probe
    is additive: Shizuku source/test changes require it as well as normal Android CI.
    """
    code_changed = False
    audio_changed = False
    for raw_path in paths:
        path = raw_path.strip()
        if not path:
            continue
        if not any(fnmatch.fnmatchcase(path, pattern) for pattern in NON_ANDROID_PATTERNS):
            code_changed = True
        if path.startswith(SHIZUKU_AUDIO_PREFIXES):
            audio_changed = True
    return code_changed, audio_changed


def main() -> int:
    code_changed, audio_changed = classify_paths(sys.stdin)
    print(f"code={str(code_changed).lower()}")
    print(f"audio={str(audio_changed).lower()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
