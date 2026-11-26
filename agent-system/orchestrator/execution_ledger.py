"""Append-only, hash-chained JSONL execution ledger with conservative redaction."""

from __future__ import annotations

import fcntl
import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any, Mapping


_SENSITIVE_KEY = re.compile(
    r"(secret|token|password|keystore|phone|audio|iccid|subscription|authorization|cookie|transcript|recording_content)",
    re.IGNORECASE,
)


class LedgerIntegrityError(ValueError):
    """Raised when the append-only ledger is malformed or its hash chain is broken."""


def _canonical(value: Mapping[str, Any]) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def _redact(value: Any, key: str = "") -> Any:
    if key and _SENSITIVE_KEY.search(key):
        return "[REDACTED]"
    if isinstance(value, Mapping):
        return {str(child_key): _redact(child_value, str(child_key)) for child_key, child_value in value.items()}
    if isinstance(value, (list, tuple)):
        return [_redact(item) for item in value]
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    raise TypeError(f"ledger values must be JSON-compatible, got {type(value).__name__}")


class ExecutionLedger:
    """Serializes append operations and verifies the entire chain before each write."""

    def __init__(self, path: str | os.PathLike[str]) -> None:
        self.path = Path(path)

    def append(self, event: Mapping[str, Any]) -> dict[str, Any]:
        if not isinstance(event, Mapping):
            raise TypeError("event must be a mapping")
        if "entry_hash" in event or "previous_hash" in event:
            raise ValueError("hash-chain fields are reserved")
        sanitized = _redact(event)
        if not sanitized.get("task_id") or not sanitized.get("action"):
            raise ValueError("each event requires task_id and action")
        sanitized.setdefault("schema_version", "1.0")
        sanitized.setdefault("timestamp", self._timestamp())

        self.path.parent.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(self.path, os.O_CREAT | os.O_RDWR | os.O_APPEND, 0o600)
        try:
            os.fchmod(descriptor, 0o600)
            fcntl.flock(descriptor, fcntl.LOCK_EX)
            os.lseek(descriptor, 0, os.SEEK_SET)
            existing = self._read_all(descriptor)
            previous_hash = self._verify_bytes(existing)
            payload = {**sanitized, "previous_hash": previous_hash}
            entry_hash = hashlib.sha256(_canonical(payload)).hexdigest()
            entry = {**payload, "entry_hash": entry_hash}
            encoded = _canonical(entry) + b"\n"
            view = memoryview(encoded)
            while view:
                written = os.write(descriptor, view)
                if written <= 0:
                    raise OSError("ledger append made no forward progress")
                view = view[written:]
            os.fsync(descriptor)
            return entry
        finally:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
            finally:
                os.close(descriptor)

    def verify(self) -> str:
        if not self.path.exists():
            return ""
        descriptor = os.open(self.path, os.O_RDONLY)
        try:
            fcntl.flock(descriptor, fcntl.LOCK_SH)
            return self._verify_bytes(self._read_all(descriptor))
        finally:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
            finally:
                os.close(descriptor)

    @staticmethod
    def _read_all(descriptor: int) -> bytes:
        chunks: list[bytes] = []
        while True:
            chunk = os.read(descriptor, 65_536)
            if not chunk:
                return b"".join(chunks)
            chunks.append(chunk)

    @staticmethod
    def _verify_bytes(contents: bytes) -> str:
        previous_hash = ""
        if not contents:
            return previous_hash
        for line_number, line in enumerate(contents.splitlines(), start=1):
            if not line.strip():
                raise LedgerIntegrityError(f"blank ledger line at {line_number}")
            try:
                entry = json.loads(line)
            except json.JSONDecodeError as error:
                raise LedgerIntegrityError(f"invalid JSON at ledger line {line_number}") from error
            if not isinstance(entry, dict):
                raise LedgerIntegrityError(f"ledger line {line_number} must be an object")
            actual_hash = entry.pop("entry_hash", None)
            if entry.get("previous_hash") != previous_hash:
                raise LedgerIntegrityError(f"broken previous_hash at ledger line {line_number}")
            expected_hash = hashlib.sha256(_canonical(entry)).hexdigest()
            if not isinstance(actual_hash, str) or actual_hash != expected_hash:
                raise LedgerIntegrityError(f"entry hash mismatch at ledger line {line_number}")
            previous_hash = actual_hash
        return previous_hash

    @staticmethod
    def _timestamp() -> str:
        from datetime import datetime, timezone

        return datetime.now(timezone.utc).isoformat()
