#!/usr/bin/env python3
"""Validate privacy-safe physical SIM-call evidence and local WAV artifacts.

A PASS_TWO_WAY result requires human-observed intelligibility for both test phrases
and byte-for-byte verification of every WAV file supplied through --wav-dir.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import wave
from collections import Counter
from datetime import date, datetime
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
REPORT_ID_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]{2,63}$")
CALL_ID_RE = re.compile(r"^[a-zA-Z0-9._-]{1,32}$")

ROOT_KEYS = {"schema_version", "report_id", "tested_at", "device", "app", "capture", "calls"}
DEVICE_KEYS = {"manufacturer", "model", "android_release", "api_level", "build_id", "security_patch"}
APP_KEYS = {"version", "commit"}
CAPTURE_KEYS = {
    "strategy", "actual_audio_source", "sim_slot_index", "route", "network_mode",
    "helper_uid", "shell_audio_attribution_ready", "protected_call_audio_permission_ready",
}
CALL_KEYS = {
    "call_id", "direction", "attempt", "call_state_events", "recording_started",
    "recording_finalized", "local_phrase_intelligible", "remote_phrase_intelligible",
    "wav_sha256", "wav_bytes", "sample_rate", "channels",
}


class ReportError(ValueError):
    """The evidence report is malformed or fails a required evidence gate."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ReportError(message)


def _exact_keys(value: dict[str, Any], allowed: set[str], path: str) -> None:
    unexpected = set(value) - allowed
    missing = allowed - set(value)
    _require(not unexpected, f"unexpected fields at {path}: {sorted(unexpected)}")
    _require(not missing, f"missing fields at {path}: {sorted(missing)}")


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


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _verify_wav(call: dict[str, Any], wav_root: Path) -> str | None:
    call_id = call["call_id"]
    path = wav_root / f"{call_id}.wav"
    try:
        resolved_root = wav_root.resolve(strict=True)
        resolved_path = path.resolve(strict=True)
        _require(resolved_path.parent == resolved_root,
                 f"{call_id}: WAV path escapes the supplied evidence directory")
        _require(resolved_path.is_file(), f"{call_id}: WAV artifact is not a regular file")
        _require(resolved_path.stat().st_size == call["wav_bytes"],
                 f"{call_id}: WAV byte count does not match the report")
        _require(_sha256(resolved_path) == call["wav_sha256"],
                 f"{call_id}: WAV SHA-256 does not match the report")
        with wave.open(str(resolved_path), "rb") as audio:
            _require(audio.getcomptype() == "NONE", f"{call_id}: WAV must use uncompressed PCM")
            _require(audio.getsampwidth() == 2, f"{call_id}: WAV must contain 16-bit PCM")
            _require(audio.getnchannels() == call["channels"],
                     f"{call_id}: WAV channel count does not match the report")
            _require(audio.getframerate() == call["sample_rate"],
                     f"{call_id}: WAV sample rate does not match the report")
            _require(audio.getnframes() > 0, f"{call_id}: WAV contains no PCM frames")
            expected_size = 44 + audio.getnframes() * audio.getnchannels() * audio.getsampwidth()
            _require(resolved_path.stat().st_size == expected_size,
                     f"{call_id}: WAV length/header is not the canonical 44-byte PCM layout")
    except (OSError, wave.Error, EOFError) as exc:
        return f"{call_id}: WAV verification failed: {exc}"
    except ReportError as exc:
        return str(exc)
    return None


def validate_report(report: Any, wav_dir: Path | None = None) -> tuple[str, list[str]]:
    _require(isinstance(report, dict), "report must be a JSON object")
    _scan_forbidden_keys(report)
    _exact_keys(report, ROOT_KEYS, "$")
    _require(report["schema_version"] == 1, "schema_version must be 1")
    _require(isinstance(report["report_id"], str) and REPORT_ID_RE.fullmatch(report["report_id"]) is not None,
             "report_id must match the documented identifier format")
    try:
        tested_at = datetime.fromisoformat(str(report["tested_at"]).replace("Z", "+00:00"))
        _require(tested_at.utcoffset() is not None, "tested_at must include a timezone offset")
    except ValueError as exc:
        raise ReportError("tested_at must be an ISO-8601 timestamp with timezone") from exc

    device = report["device"]
    _require(isinstance(device, dict), "device must be an object")
    _exact_keys(device, DEVICE_KEYS, "$.device")
    for field in ("manufacturer", "model", "android_release", "build_id", "security_patch"):
        _require(isinstance(device.get(field), str) and bool(device[field].strip()),
                 f"device.{field} is required")
    try:
        date.fromisoformat(device["security_patch"])
    except ValueError as exc:
        raise ReportError("device.security_patch must be an ISO-8601 date") from exc
    _require(type(device.get("api_level")) is int and device["api_level"] >= 26,
             "device.api_level must be an integer >= 26")

    app = report["app"]
    _require(isinstance(app, dict), "app must be an object")
    _exact_keys(app, APP_KEYS, "$.app")
    _require(isinstance(app.get("version"), str) and bool(app["version"].strip()), "app.version is required")
    _require(isinstance(app.get("commit"), str) and COMMIT_RE.fullmatch(app["commit"]) is not None,
             "app.commit must be a 40-character lowercase git SHA")

    capture = report["capture"]
    _require(isinstance(capture, dict), "capture must be an object")
    _require(set(capture) <= CAPTURE_KEYS, f"unexpected fields at $.capture: {sorted(set(capture) - CAPTURE_KEYS)}")
    required_capture = {"strategy", "actual_audio_source", "sim_slot_index", "route", "network_mode"}
    _require(required_capture <= set(capture),
             f"missing fields at $.capture: {sorted(required_capture - set(capture))}")
    strategy = capture.get("strategy")
    source = capture.get("actual_audio_source")
    _require(strategy in {"SHIZUKU_VOICE_CALL", "SPEAKERPHONE_MIC", "ACCESSIBILITY_MIC",
                          "PLATFORM_VOICE_RECOGNITION"}, "unsupported capture.strategy")
    _require(source in {"VOICE_CALL", "MIC", "VOICE_COMMUNICATION", "VOICE_RECOGNITION", "UNKNOWN"},
             "unsupported capture.actual_audio_source")
    _require(type(capture.get("sim_slot_index")) is int and capture["sim_slot_index"] >= 0,
             "capture.sim_slot_index must be >= 0")
    _require(capture.get("route") in {"EARPIECE", "SPEAKER", "WIRED_HEADSET", "BLUETOOTH"},
             "capture.route is invalid")
    _require(capture.get("network_mode") in {"LTE", "5G", "VOLTE", "WIFI_CALLING", "OTHER", "UNKNOWN"},
             "capture.network_mode is invalid")
    if "helper_uid" in capture:
        _require(capture["helper_uid"] in {0, 2000, None}, "capture.helper_uid must be 0, 2000, or null")
    for flag in ("shell_audio_attribution_ready", "protected_call_audio_permission_ready"):
        if flag in capture:
            _require(type(capture[flag]) is bool, f"capture.{flag} must be boolean")
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
    attempts: dict[str, set[int]] = {"INCOMING": set(), "OUTGOING": set()}
    ids: set[str] = set()
    failures: list[str] = []
    for index, call in enumerate(calls):
        prefix = f"calls[{index}]"
        _require(isinstance(call, dict), f"{prefix} must be an object")
        _exact_keys(call, CALL_KEYS, prefix)
        call_id = call.get("call_id")
        _require(isinstance(call_id, str) and CALL_ID_RE.fullmatch(call_id) is not None,
                 f"{prefix}.call_id must match the documented identifier format")
        _require(call_id not in ids, f"duplicate call_id: {call_id}")
        ids.add(call_id)
        direction = call.get("direction")
        _require(direction in {"INCOMING", "OUTGOING"}, f"{prefix}.direction is invalid")
        directions[direction] += 1
        _require(type(call.get("attempt")) is int and call["attempt"] >= 1,
                 f"{prefix}.attempt must be >= 1")
        _require(call["attempt"] not in attempts[direction],
                 f"{direction} attempt numbers must be unique within a report")
        attempts[direction].add(call["attempt"])
        events = call.get("call_state_events")
        _require(isinstance(events, list) and all(event in {"RINGING", "OFFHOOK", "IDLE"} for event in events),
                 f"{prefix}.call_state_events contains an invalid state")
        _require("OFFHOOK" in events and "IDLE" in events,
                 f"{prefix} must contain OFFHOOK and IDLE call-state events")
        _require(events.index("OFFHOOK") < events.index("IDLE"),
                 f"{prefix} call-state events must reach IDLE after OFFHOOK")
        if direction == "INCOMING":
            _require("RINGING" in events and events.index("RINGING") < events.index("OFFHOOK"),
                     f"{prefix} incoming call must transition RINGING before OFFHOOK")
        for flag in ("recording_started", "recording_finalized"):
            _require(call.get(flag) is True, f"{prefix} {flag} is not confirmed")
        _require(type(call.get("local_phrase_intelligible")) is bool,
                 f"{prefix}.local_phrase_intelligible must be boolean")
        _require(type(call.get("remote_phrase_intelligible")) is bool,
                 f"{prefix}.remote_phrase_intelligible must be boolean")
        _require(isinstance(call.get("wav_sha256"), str) and SHA256_RE.fullmatch(call["wav_sha256"]) is not None,
                 f"{prefix}.wav_sha256 must be a lowercase SHA-256 digest")
        _require(type(call.get("wav_bytes")) is int and call["wav_bytes"] >= 45,
                 f"{prefix}.wav_bytes must be total WAV file bytes >= 45")
        _require(type(call.get("sample_rate")) is int and 8000 <= call["sample_rate"] <= 96000,
                 f"{prefix}.sample_rate is out of range")
        _require(type(call.get("channels")) is int and call["channels"] in {1, 2},
                 f"{prefix}.channels must be 1 or 2")
        if not call["local_phrase_intelligible"]:
            failures.append(f"{call_id}: local phrase not confirmed intelligible")
        if not call["remote_phrase_intelligible"]:
            failures.append(f"{call_id}: remote phrase not confirmed intelligible")

    _require(directions["INCOMING"] >= 3, "at least three incoming calls are required")
    _require(directions["OUTGOING"] >= 3, "at least three outgoing calls are required")

    if wav_dir is None:
        if not failures:
            failures.append("WAV directory was not supplied; file hashes and WAV headers were not verified")
            return "REPORT_VALIDATION_ONLY", failures
        return "FAIL", failures

    _require(wav_dir.is_dir(), "--wav-dir must point to an existing directory")
    for call in calls:
        failure = _verify_wav(call, wav_dir)
        if failure:
            failures.append(failure)
    return ("FAIL", failures) if failures else ("PASS_TWO_WAY", [])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="path to a privacy-safe JSON evidence report")
    parser.add_argument("--wav-dir", type=Path,
                        help="local directory containing one <call_id>.wav file per call; required for PASS_TWO_WAY")
    args = parser.parse_args()
    try:
        report = json.loads(args.report.read_text(encoding="utf-8"))
        outcome, failures = validate_report(report, args.wav_dir)
    except (OSError, json.JSONDecodeError, ReportError) as exc:
        print(json.dumps({"outcome": "INVALID_REPORT", "error": str(exc)}, indent=2))
        return 2
    print(json.dumps({"outcome": outcome, "report_id": report.get("report_id"), "failures": failures}, indent=2))
    return {"PASS_TWO_WAY": 0, "FAIL": 1, "INVALID_REPORT": 2, "REPORT_VALIDATION_ONLY": 3}[outcome]


if __name__ == "__main__":
    sys.exit(main())
