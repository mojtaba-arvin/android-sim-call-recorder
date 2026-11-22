# Physical-device evidence report contract

This contract turns the manual acceptance procedure in [PHYSICAL_DEVICE_ACCEPTANCE.md](PHYSICAL_DEVICE_ACCEPTANCE.md) and [DEVICE_TEST_MATRIX.md](DEVICE_TEST_MATRIX.md) into a machine-checked, privacy-safe record.

## Scope

One report represents exactly one device/build, app commit, capture strategy, SIM slot, and audio route. Create separate reports when any of those dimensions changes. Do not combine results from different routes or devices to reach the three-call threshold.

The report records observations, not raw call content. Never attach WAV files or include phone numbers, ICCIDs, IMSIs, IMEIs, device serials, Android IDs, recording paths, or subscriber identifiers. Keep WAV files on the test handset. Record only their SHA-256 digest and byte count.

## Acceptance rule

The validator returns `PASS_TWO_WAY` only when all of the following hold:

- At least three incoming and three outgoing calls are recorded in the same report.
- Every call contains the expected call-state evidence: incoming includes RINGING, and all calls include OFFHOOK and IDLE.
- Every recording starts and finalizes and has a plausible WAV byte count, sample rate, channel count, and SHA-256 digest.
- The operator independently listens for the unique local and remote test phrases in every WAV and marks both intelligible.
- Shizuku reports identify `VOICE_CALL`, helper UID 0 or 2000, protected call-audio permission readiness, and shell audio attribution readiness.

Any missing or one-sided phrase returns `FAIL`; malformed reports or privacy-sensitive fields return `INVALID_REPORT`. The result is intentionally conservative.

**Important:** this validates submitted observations and report structure, not automatic speech recognition or cryptographic proof of what a WAV contains. A report can be marked PASS only after a human has actually listened to both phrases in every recording. CI never performs or simulates a physical call.

## Generate and validate

Use `schemas/physical-device-evidence.schema.json` as the canonical JSON structure. The Python validator uses only the standard library:

```bash
python scripts/validate_physical_device_report.py path/to/report.json
python -m unittest discover -s scripts/tests -p 'test_validate_physical_device_report.py' -v
```

Exit codes: 0 = `PASS_TWO_WAY`, 1 = `FAIL`, 2 = `INVALID_REPORT`.

The validator computes the outcome; reports must not supply their own status field. It rejects privacy-sensitive field names recursively and enforces the minimum repeated incoming/outgoing sample count.

## Hardware gate remains external

A green contract CI proves only that the report validator behaves as specified. It does not provide a handset, SIM, carrier, Shizuku privilege, or real cellular audio. Until actual reports exist, every device/route combination remains **Not tested**, and the product must not claim universal two-way call recording.
