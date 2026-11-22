# Physical-device evidence report contract

This contract turns the manual acceptance procedure in [PHYSICAL_DEVICE_ACCEPTANCE.md](PHYSICAL_DEVICE_ACCEPTANCE.md) and [DEVICE_TEST_MATRIX.md](DEVICE_TEST_MATRIX.md) into a machine-checked, privacy-safe record.

## Scope

One report represents exactly one device/build, app commit, capture strategy, SIM slot, and audio route. Create separate reports when any of those dimensions changes. Do not combine results from different routes or devices to reach the three-call threshold.

The report records observations, not raw call content. Never commit WAV files or include phone numbers, ICCIDs, IMSIs, IMEIs, device serials, Android IDs, recording paths, or subscriber identifiers. Keep WAV files on the test handset or a controlled local test workstation. The report stores only their SHA-256 digest and total file byte count.

## Acceptance rule

The validator returns `PASS_TWO_WAY` only when all of the following hold:

- At least three incoming and three outgoing calls are recorded in the same report.
- Every call contains ordered call-state evidence: incoming includes RINGING before OFFHOOK, and all calls reach IDLE after OFFHOOK.
- Every recording starts and finalizes and has a valid SHA-256 digest, canonical PCM WAV header, matching file size, sample rate, channel count, and non-empty 16-bit PCM payload.
- The operator independently listens for the unique local and remote test phrases in every WAV and marks both intelligible.
- Shizuku reports identify `VOICE_CALL`, helper UID 0 or 2000, protected call-audio permission readiness, and shell audio attribution readiness.

Any missing or one-sided phrase, missing WAV, hash mismatch, or malformed audio returns `FAIL`; malformed reports or privacy-sensitive fields return `INVALID_REPORT`.

**Important:** the tool verifies the submitted WAV bytes and validates human observations. It does not use speech recognition or prove that the operator listened honestly. CI never performs or simulates a physical call.

## Generate and validate

Use `schemas/physical-device-evidence.schema.json` as the canonical JSON structure. The Python validator uses only the standard library. Store files locally as `<call_id>.wav`, then run:

```bash
python scripts/validate_physical_device_report.py path/to/report.json --wav-dir /secure/local/wav-directory
python -m unittest discover -s scripts/tests -p 'test_validate_physical_device_report.py' -v
```

Exit codes: 0 = `PASS_TWO_WAY`, 1 = `FAIL`, 2 = `INVALID_REPORT`, 3 = `REPORT_VALIDATION_ONLY`. Without `--wav-dir`, the tool can validate report structure but deliberately cannot issue PASS_TWO_WAY.

The validator computes the outcome; reports must not supply their own status field. It rejects unknown fields and privacy-sensitive field names recursively. WAV files remain local and are never uploaded by the evidence workflow.

## Hardware gate remains external

A green contract CI proves only that the report validator behaves as specified. It does not provide a handset, SIM, carrier, Shizuku privilege, or real cellular audio. Until actual reports exist, every device/route combination remains **Not tested**, and the product must not claim universal two-way call recording.
