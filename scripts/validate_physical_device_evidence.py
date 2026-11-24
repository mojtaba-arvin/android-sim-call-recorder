#!/usr/bin/env python3
"""Fail-closed validator for physical cellular two-way audio evidence manifests."""

from __future__ import annotations

import hashlib
from datetime import datetime
import json
import re
import sys
import wave
from pathlib import Path
from typing import Any

FORBIDDEN_KEYS = {
    "phone_number", "msisdn", "imei", "imsi", "iccid", "serial",
    "serial_number", "subscriber_id", "subscription_id", "contact_name",
    "transcript", "phone", "subscriberidentity",
}
DIRECTIONS = ("INCOMING", "OUTGOING")
TRIALS_PER_DIRECTION = 3
BACKENDS = {"VOICE_CALL", "SPEAKERPHONE_MIC"}
SHA256_PATTERN = re.compile(r"^[0-9a-fA-F]{64}$")
COMMIT_PATTERN = re.compile(r"^[0-9a-fA-F]{40}$")


def _text(value: Any, label: str, errors: list[str]) -> None:
    if not isinstance(value, str) or not value.strip() or value.strip().upper() in {"UNKNOWN", "N/A", "NOT TESTED"}:
        errors.append(f"{label}: a real, non-placeholder value is required")


def _find_forbidden_keys(value: Any, prefix: str, errors: list[str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = str(key).strip().lower()
            if normalized in FORBIDDEN_KEYS:
                errors.append(f"{prefix}.{key}: sensitive identifier fields are not allowed")
            _find_forbidden_keys(child, f"{prefix}.{key}", errors)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _find_forbidden_keys(child, f"{prefix}[{index}]", errors)


def _safe_evidence_file(root: Path, relative_path: Any, label: str, errors: list[str]) -> Path | None:
    if not isinstance(relative_path, str) or not relative_path.strip():
        errors.append(f"{label}.wav: a relative WAV path is required")
        return None
    candidate_path = Path(relative_path)
    if candidate_path.is_absolute():
        errors.append(f"{label}.wav: absolute paths are not allowed")
        return None
    try:
        candidate = (root / candidate_path).resolve(strict=True)
        candidate.relative_to(root.resolve())
    except (OSError, ValueError):
        errors.append(f"{label}.wav: file is missing or resolves outside the evidence directory")
        return None
    if not candidate.is_file() or candidate.suffix.lower() != ".wav":
        errors.append(f"{label}.wav: an existing .wav file is required")
        return None
    return candidate


def _validate_wav(root: Path, evidence: Any, label: str, errors: list[str]) -> None:
    if not isinstance(evidence, dict):
        errors.append(f"{label}: evidence object is required")
        return
    path = _safe_evidence_file(root, evidence.get("wav"), label, errors)
    expected_sha = evidence.get("sha256")
    if not isinstance(expected_sha, str) or not SHA256_PATTERN.fullmatch(expected_sha):
        errors.append(f"{label}.sha256: a 64-character SHA-256 digest is required")
    if path is None:
        return

    try:
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as exception:
        errors.append(f"{label}.wav: cannot read evidence file ({type(exception).__name__})")
        return
    if isinstance(expected_sha, str) and SHA256_PATTERN.fullmatch(expected_sha):
        if digest.lower() != expected_sha.lower():
            errors.append(f"{label}.sha256: checksum does not match the evidence file")

    try:
        with wave.open(str(path), "rb") as audio:
            channels = audio.getnchannels()
            sample_width = audio.getsampwidth()
            sample_rate = audio.getframerate()
            frames = audio.getnframes()
            compression = audio.getcomptype()
            pcm = audio.readframes(frames)
        if compression != "NONE":
            errors.append(f"{label}.wav: uncompressed PCM WAV is required")
        if channels not in (1, 2):
            errors.append(f"{label}.wav: mono or stereo PCM is required")
        if sample_width != 2:
            errors.append(f"{label}.wav: signed 16-bit PCM is required")
        if sample_rate <= 0 or frames <= 0:
            errors.append(f"{label}.wav: sample rate and frame count must be positive")
        if len(pcm) != frames * channels * sample_width:
            errors.append(f"{label}.wav: PCM data is truncated")
        if pcm and not any(pcm):
            errors.append(f"{label}.wav: all-zero PCM is not valid speech evidence")
    except (OSError, EOFError, wave.Error) as exception:
        errors.append(f"{label}.wav: invalid or unreadable WAV ({type(exception).__name__})")


def validate_manifest(manifest_path: Path) -> list[str]:
    errors: list[str] = []
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        return [f"manifest: cannot read valid JSON ({type(exception).__name__})"]

    if not isinstance(manifest, dict):
        return ["manifest: root must be a JSON object"]

    _find_forbidden_keys(manifest, "manifest", errors)
    if manifest.get("schema_version") != 1:
        errors.append("schema_version: expected integer 1")

    if manifest.get("verdict") != "PASS_TWO_WAY":
        errors.append("verdict: only PASS_TWO_WAY can be validated; incomplete/unknown evidence is not a pass")

    device = manifest.get("device")
    if not isinstance(device, dict):
        errors.append("device: object is required")
        device = {}
    for field in (
        "manufacturer", "model", "android_version", "build_fingerprint",
        "security_patch", "modem_baseband", "carrier", "country", "network_type",
    ):
        _text(device.get(field), f"device.{field}", errors)
    api_level = device.get("api_level")
    if not isinstance(api_level, int) or api_level < 24:
        errors.append("device.api_level: integer API level >= 24 is required")
    sim_slot = device.get("sim_slot")
    if not isinstance(sim_slot, int) or sim_slot < 0:
        errors.append("device.sim_slot: non-negative slot index is required")

    configuration = manifest.get("configuration")
    if not isinstance(configuration, dict):
        errors.append("configuration: object is required")
        configuration = {}
    if configuration.get("call_path") != "CELLULAR":
        errors.append("configuration.call_path: must be CELLULAR; VoIP/emulator calls are not accepted")
    if configuration.get("is_emulator") is not False:
        errors.append("configuration.is_emulator: must be false")
    if configuration.get("synthetic_call_state") is not False:
        errors.append("configuration.synthetic_call_state: must be false")
    _text(configuration.get("route"), "configuration.route", errors)
    backend = configuration.get("backend")
    if not isinstance(backend, str) or backend not in BACKENDS:
        errors.append("configuration.backend: must be VOICE_CALL or SPEAKERPHONE_MIC")
    if configuration.get("requested_backend") != backend:
        errors.append("configuration.requested_backend: must equal the backend accepted for this evidence set")
    commit = configuration.get("app_commit")
    if not isinstance(commit, str) or not COMMIT_PATTERN.fullmatch(commit):
        errors.append("configuration.app_commit: exact 40-character app commit SHA is required")

    grants = configuration.get("permission_grants")
    if not isinstance(grants, dict):
        errors.append("configuration.permission_grants: actual permission grant map is required")
        grants = {}
    for permission in ("READ_PHONE_STATE", "RECORD_AUDIO"):
        if grants.get(permission) is not True:
            errors.append(f"configuration.permission_grants.{permission}: actual grant must be true")
    if backend == "VOICE_CALL":
        for permission in ("CAPTURE_AUDIO_OUTPUT", "CALL_AUDIO_INTERCEPTION"):
            if grants.get(permission) is not True:
                errors.append(f"configuration.permission_grants.{permission}: actual grant must be true")
        shizuku = configuration.get("shizuku")
        if not isinstance(shizuku, dict):
            errors.append("configuration.shizuku: version and process UID are required for VOICE_CALL")
        else:
            _text(shizuku.get("version"), "configuration.shizuku.version", errors)
            if not isinstance(shizuku.get("process_uid"), int) or shizuku.get("process_uid") < 0:
                errors.append("configuration.shizuku.process_uid: non-negative actual process UID is required")

    trials = manifest.get("trials")
    if not isinstance(trials, list) or len(trials) != TRIALS_PER_DIRECTION * len(DIRECTIONS):
        errors.append("trials: exactly six trials (three incoming and three outgoing) are required")
        trials = trials if isinstance(trials, list) else []

    seen: set[tuple[str, str]] = set()
    evidence_paths: set[str] = set()
    counts = {direction: 0 for direction in DIRECTIONS}
    root = manifest_path.resolve().parent

    for index, trial in enumerate(trials):
        label = f"trials[{index}]"
        if not isinstance(trial, dict):
            errors.append(f"{label}: trial object is required")
            continue
        direction = trial.get("direction")
        trial_id = trial.get("id")
        if direction not in DIRECTIONS:
            errors.append(f"{label}.direction: must be INCOMING or OUTGOING")
        else:
            counts[direction] += 1
        if not isinstance(trial_id, str) or not re.fullmatch(r"(IN|OUT)-[1-3]", trial_id):
            errors.append(f"{label}.id: expected IN-1..IN-3 or OUT-1..OUT-3")
        else:
            expected_prefix = "IN" if direction == "INCOMING" else "OUT"
            if not trial_id.startswith(expected_prefix + "-"):
                errors.append(f"{label}.id: does not match the declared direction")
            key = (str(direction), trial_id)
            if key in seen:
                errors.append(f"{label}.id: duplicate trial identifier")
            seen.add(key)

        if trial.get("outcome") != "PASS":
            errors.append(f"{label}.outcome: each trial must independently pass")
        _text(trial.get("reviewer"), f"{label}.reviewer", errors)
        reviewed_at = trial.get("reviewed_at")
        _text(reviewed_at, f"{label}.reviewed_at", errors)
        if isinstance(reviewed_at, str):
            try:
                reviewed_datetime = datetime.fromisoformat(reviewed_at.replace("Z", "+00:00"))
                if reviewed_datetime.tzinfo is None:
                    errors.append(f"{label}.reviewed_at: timezone offset is required")
            except ValueError:
                errors.append(f"{label}.reviewed_at: valid ISO-8601 timestamp is required")

        if trial.get("recording_mode") != backend:
            errors.append(f"{label}.recording_mode: must match the configured backend exactly")
        if trial.get("fallback_reason") not in (None, ""):
            errors.append(f"{label}.fallback_reason: fallback/substitution disqualifies PASS_TWO_WAY")

        near = trial.get("near_end")
        far = trial.get("far_end")
        for direction_label, item in (("near_end", near), ("far_end", far)):
            if not isinstance(item, dict):
                errors.append(f"{label}.{direction_label}: separate physical reference WAV evidence is required")
                continue
            if item.get("all_required_phrases_intelligible") is not True:
                errors.append(f"{label}.{direction_label}: all required phrases must be independently intelligible")
            if isinstance(item.get("wav"), str):
                if item["wav"] in evidence_paths:
                    errors.append(f"{label}.{direction_label}.wav: each direction requires a distinct physical WAV file")
                evidence_paths.add(item["wav"])
            _validate_wav(root, item, f"{label}.{direction_label}", errors)

        capture = trial.get("capture")
        if not isinstance(capture, dict):
            errors.append(f"{label}.capture: app-produced recording evidence is required")
        else:
            if capture.get("recording_mode") != backend:
                errors.append(f"{label}.capture.recording_mode: must match the configured backend exactly")
            audio_source = capture.get("audio_source")
            _text(audio_source, f"{label}.capture.audio_source", errors)
            if isinstance(audio_source, str) and isinstance(backend, str):
                expected_source = "VOICE_CALL" if backend == "VOICE_CALL" else "MIC"
                if expected_source not in audio_source.upper():
                    errors.append(f"{label}.capture.audio_source: does not match the selected backend")
            if capture.get("fallback_reason") not in (None, ""):
                errors.append(f"{label}.capture.fallback_reason: fallback/substitution disqualifies PASS_TWO_WAY")
            if isinstance(capture.get("wav"), str):
                if capture["wav"] in evidence_paths:
                    errors.append(f"{label}.capture.wav: app capture must be a distinct WAV file")
                evidence_paths.add(capture["wav"])
            if capture.get("db_status") != "COMPLETED":
                errors.append(f"{label}.capture.db_status: finalized COMPLETED record is required")
            if capture.get("finalized") is not True or capture.get("integrity_verified") is not True:
                errors.append(f"{label}.capture: finalized WAV integrity must be independently verified")
            _validate_wav(root, capture, f"{label}.capture", errors)

    for direction, count in counts.items():
        if count != TRIALS_PER_DIRECTION:
            errors.append(f"trials.{direction}: expected exactly three, found {count}")

    return errors


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("Usage: validate_physical_device_evidence.py <manifest.json>", file=sys.stderr)
        return 2
    manifest_path = Path(argv[1])
    errors = validate_manifest(manifest_path)
    if errors:
        for error in errors:
            print(f"BLOCKED: {error}", file=sys.stderr)
        print("INCOMPLETE: PASS_TWO_WAY was not accepted.", file=sys.stderr)
        return 2
    print("PASS_TWO_WAY: six physical cellular trials, separate near/far WAVs, app capture WAVs, checksums, and provenance validated for this exact device/build/route/backend.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
