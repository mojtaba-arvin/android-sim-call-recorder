#!/usr/bin/env python3
"""Validate privacy-safe physical SIM-call recording evidence.

The result is a repeatability gate, not an acoustic classifier: local and remote
phrase fields are human-observed assertions and must be backed by a WAV hash.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any

FORBIDDEN_KEYS = {
    "phone_number", "phonenumber", "msisdn", "iccid", "imsi", "subscriber_id",
    "subscriberid", "imei", "device_serial", "serial_number", "android_id",
    "recording_path", "audio_file", "audio_base64", "raw_audio",
}
NORMALIZED_FORBIDDEN_KEYS = {re.sub(r"[^a-z0-9]", "", key) for key in FORBIDDEN_KEYS}
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")


class ReportError(ValueError):
    """The evidence report is malformed or fails a required evidence gate."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ReportError(message)


def _scan_forbidden_keys(value: Any, path: str = "$") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = re.sub(r"[^a-z0-9]", "", str(key).lower())
            _require(normalized not in NORMALIZED_FORBIDDEN_KEYS,
                     f"privacy-sensitive field is forbidden at {path}.{key}")
            _scan_forbidden_keys(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _scan_forbidden_keys(child, f"{path}[{index}]")


def validate_report(report: Any) -> tuple[str, list[str]]:
    _require(isinstance(report, dict), "report must be a JSON object")
    _scan_forbidden_keys(report)
    required = {"schema_version", "report_id", "tested_at", "device", "app", "capture", "calls"}
    _require(required <= report.keys(), f"missing top-level fields: {sorted(required - report.keys())}")
    _require(report["schema_version"] == 1, "schema_version must be 1")
    _require(isinstance(report["report_id"], str) and 3 <= len(report["report_id"]) <= 64,
             "report_id must contain 3..64 characters")
    try:
        datetime.fromisoformat(str(report["tested_at"]).replace("Z", "+00:00"))
    except ValueError as exc:
        raise ReportError("tested_at must be an ISO-8601 timestamp") from exc

    device = report["device"]
    _require(isinstance(device, dict), "device must be an object")
    for field in ("manufacturer", "model", "android_release", "build_id", "security_patch"):
        _require(isinstance(device.get(field), str) and bool(device[field].strip()),
                 f"device.{field} is required")
    _require(isinstance(device.get("api_level"), int) and device["api_level"] >= 26,
             "device.api_level must be an integer >= 26")

    app = report["app"]
    _require(isinstance(app, dict), "app must be an object")
    _require(isinstance(app.get("version"), str) and bool(app["version"].strip()), "app.version is required")
    _require(isinstance(app.get("commit"), str) and COMMIT_RE.fullmatch(app["commit"]) is not None,
             "app.commit must be a 40-character lowercase git SHA")

    capture = report["capture"]
    _require(isinstance(capture, dict), "capture must be an object")
    strategy = capture.get("strategy")
    source = capture.get("actual_audio_source")
    _require(strategy in {"SHIZUKU_VOICE_CALL", "SPEAKERPHONE_MIC", "ACCESSIBILITY_MIC",
                          "PLATFORM_VOICE_RECOGNITION"}, "unsupported capture.strategy")
    _require(source in {"VOICE_CALL", "MIC", "VOICE_COMMUNICATION", "VOICE_RECOGNITION", "UNKNOWN"},
             "unsupported capture.actual_audio_source")
    _require(isinstance(capture.get("sim_slot_index"), int) and capture["sim_slot_index"] >= 0,
             "capture.sim_slot_index must be >= 0")
    _require(capture.get("route") in {"EARPIECE", "SPEAKER", "WIRED_HEADSET", "BLUETOOTH"},
             "capture.route is invalid")
    _require(capture.get("network_mode") in {"LTE", "5G", "VOLTE", "WIFI_CALLING", "OTHER", "UNKNOWN"},
             "capture.network_mode is invalid")
    if strategy == "SHIZUKU_VOICE_CALL":
        _require(source == "VOICE_CALL", "Shizuku evidence must identify VOICE_CALL as its actual source")
        _require(capture.get("helper_uid") in {0, 2000}, "Shizuku evidence requires helper_uid 0 or 2000")
        _require(capture.get("shell_audio_attribution_ready") is True,
                 "Shizuku evidence requires shell audio attribution readiness")
        _require(capture.get("protected_call_audio_permission_ready") is True,
                 "Shizuku evidence requires protected call-audio permission readiness")

    calls = report["calls"]
    _require(isinstance(calls, list), "calls must be an array")
    directions: Counter[str] = Counter()
    ids: set[str] = set()
    failures: list[str] = []
    for index, call in enumerate(calls):
        prefix = f"calls[{index}]"
        _require(isinstance(call, dict), f"{prefix} must be an object")
        call_id = call.get("call_id")
        _require(isinstance(call_id, str) and bool(call_id), f"{prefix}.call_id is required")
        _require(call_id not in ids, f"duplicate call_id: {call_id}")
        ids.add(call_id)
        direction = call.get("direction")
        _require(direction in {"INCOMING", "OUTGOING"}, f"{prefix}.direction is invalid")
        directions[direction] += 1
        _require(isinstance(call.get("attempt"), int) and call["attempt"] >= 1,
                 f"{prefix}.attempt must be >= 1")
        events = call.get("call_state_events")
        _require(isinstance(events, list) and "OFFHOOK" in events and "IDLE" in events,
                 f"{prefix} must contain OFFHOOK and IDLE call-state events")
        if direction == "INCOMING":
            _require("RINGING" in events, f"{prefix} incoming call must include RINGING")
        _require(call.get("recording_started") is True, f"{prefix} recording did not start")
        _require(call.get("recording_finalized") is True, f"{prefix} recording did not finalize")
        _require(isinstance(call.get("wav_sha256"), str) and SHA256_RE.fullmatch(call["wav_sha256"]) is not None,
                 f"{prefix}.wav_sha256 must be a lowercase SHA-256 digest")
        _require(isinstance(call.get("wav_bytes"), int) and call["wav_bytes"] >= 45,
                 f"{prefix}.wav_bytes must be >= 45")
        _require(isinstance(call.get("sample_rate"), int) and 8000 <= call["sample_rate"] <= 96000,
                 f"{prefix}.sample_rate is out of range")
        _require(call.get("channels") in {1, 2}, f"{prefix}.channels must be 1 or 2")
        if call.get("local_phrase_intelligible") is not True:
            failures.append(f"{call_id}: local phrase not confirmed intelligible")
        if call.get("remote_phrase_intelligible") is not True:
            failures.append(f"{call_id}: remote phrase not confirmed intelligible")

    _require(directions["INCOMING"] >= 3, "at least three incoming calls are required")
    _require(directions["OUTGOING"] >= 3, "at least three outgoing calls are required")
    return ("FAIL", failures) if failures else ("PASS_TWO_WAY", [])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="path to a privacy-safe JSON evidence report")
    args = parser.parse_args()
    try:
        report = json.loads(args.report.read_text(encoding="utf-8"))
        outcome, failures = validate_report(report)
    except (OSError, json.JSONDecodeError, ReportError) as exc:
        print(json.dumps({"outcome": "INVALID_REPORT", "error": str(exc)}, indent=2))
        return 2
    print(json.dumps({"outcome": outcome, "report_id": report.get("report_id"), "failures": failures}, indent=2))
    return 0 if outcome == "PASS_TWO_WAY" else 1


if __name__ == "__main__":
    sys.exit(main())
