# Physical Device Acceptance — SIM Call Audio

## Status and claim boundary

**Status: BLOCKED — no physical handset evidence is recorded yet.**

Emulator microphone capture, synthetic telephony state, Shizuku connection, shell UID, and protected-permission grants do not prove cellular downlink/uplink access. Do not describe this project as a device-compatible SIM call recorder until both near-end and far-end speech are intelligible in saved recordings on named physical device/build/carrier/route combinations.

This document is an executable test protocol and evidence register. Empty or `UNKNOWN` fields are not passes.

## Safety and test setup

- Use only test SIMs, consenting participants, and calls for which recording is lawful in the test jurisdiction. Announce recording and obtain explicit consent from every participant before each call.
- Use a quiet room. Record the handset model, exact Android build fingerprint, security patch, modem/baseband, carrier, SIM slot, network type, Shizuku version/UID, selected capture backend, route, and app commit.
- Test the actual intended call path: outgoing and incoming cellular calls. Do not substitute VoIP, emulator calls, prerecorded audio, or synthetic OFFHOOK state.
- Use a second handset/line as the far-end endpoint. Read distinct spoken phrase sets in both directions at a steady conversational level. Include silence before/after each phrase to expose leakage and clipping.
- Run speakerphone + built-in microphone and Shizuku/VOICE_CALL as separate route/backend cases. Repeat with wired and Bluetooth routes only when those routes are in scope; never infer their result from speakerphone.
- Preserve raw test WAVs and diagnostic logs only in a restricted test location. Use synthetic participant IDs; do not put phone numbers, contact names, or real speech transcripts in public CI artifacts.
- Verify the resulting file is finalized, decodable, non-empty, frame-aligned, and has the expected duration. Listen to the beginning, middle, and end. Record objective measurements and human intelligibility separately.
- A backend is not accepted if it silently substitutes MIC-only or one-sided audio. Record selected backend, fallback reason, observed near/far levels, and final support verdict.

## Per-case procedure

1. Install the exact debug build identified by commit SHA. Capture `adb shell getprop ro.build.fingerprint`, `getprop ro.build.version.security_patch`, `getprop gsm.version.baseband`, `getprop ro.build.version.sdk`, and carrier/SIM details with personal identifiers redacted.
2. Record app version/commit, Shizuku version and running UID, actual grants for `CALL_AUDIO_INTERCEPTION` and `CAPTURE_AUDIO_OUTPUT`, selected backend, and requested audio route.
3. Start a consented real cellular call. The near-end participant reads the near-end phrase list; the far-end participant reads the far-end phrase list. Repeat in reverse order on a second call.
4. Stop normally. Confirm the database row is `COMPLETED`, the output WAV is present and decodable, and diagnostics match the selected backend. Repeat once after a screen-off interval and once after Activity recreation.
5. Score near-end and far-end independently. Any missing, unintelligible, or materially intermittent direction is a failure for two-way support, even if the other direction is clear.
6. Repeat the full case at least three times. Keep each trial as a separate evidence row and attach redacted logs plus checksums for the WAVs.

## Required trial coverage per device configuration

A device configuration is the exact tuple of handset model, build fingerprint/security patch, modem/baseband, Android/API level, carrier/SIM slot, audio route, and capture backend. Do not combine results across different tuples.

For **each** configuration and each route/backend pair in scope:

- Run at least **3 separate incoming cellular calls** and **3 separate outgoing cellular calls** (minimum 6 calls total).
- Score near-end and far-end independently for every call. A missing direction in any required trial prevents a two-way pass for that configuration.
- Keep six distinct evidence rows, one per call, with direction, trial number (IN-1..IN-3 / OUT-1..OUT-3), WAV checksum, integrity result, and both intelligibility scores.
- Repeat the six-call set for every additional route/backend pair. For example, speakerphone + built-in MIC and Shizuku/VOICE_CALL are separate configurations; success on one does not transfer to the other.
- Mark incomplete coverage as `INCOMPLETE`, never as a pass. If a route/backend cannot be exercised on the handset, record the reason and leave its verdict `UNKNOWN`.

The stability checks (screen-off and Activity recreation) must be exercised within the same exact configuration and recorded against the affected trial. They do not replace any of the six required calls.

## Acceptance thresholds

| Signal | Minimum evidence | Pass rule |
|---|---|---|
| Near-end | Distinct phrase list recorded by handset user | All required phrases intelligible in all 3 trials |
| Far-end | Distinct phrase list spoken by remote participant | All required phrases intelligible in all 3 trials |
| Two-way | Both directions scored independently | Both pass; no MIC-only/one-sided fallback |
| Integrity | WAV decode, duration, non-zero PCM, finalized DB row | All checks pass for every trial |
| Stability | 3 calls plus screen-off and Activity recreation case | No unexplained truncation, service loss, or stuck session |
| Provenance | Device/build/modem/carrier/route/backend/commit | All fields recorded and reproducible |

Optional quantitative measurements (RMS, peak, clipping percentage, speech-band SNR, and objective intelligibility score) supplement but do not replace listening. Record the measurement method and calibration; do not invent a threshold after observing results.

## Evidence register — fill one row per trial

| Field | Value |
|---|---|
| Evidence ID | UNKNOWN |
| Date/time + timezone | UNKNOWN |
| App commit / build checksum | UNKNOWN |
| Device manufacturer / model | UNKNOWN |
| Android version / API | UNKNOWN |
| Build fingerprint | UNKNOWN |
| Security patch | UNKNOWN |
| Modem/baseband | UNKNOWN |
| Carrier / country / network | UNKNOWN |
| SIM slot / incoming or outgoing | UNKNOWN |
| Route (speaker / wired / Bluetooth) | UNKNOWN |
| Backend (VOICE_CALL / speakerphone MIC) | UNKNOWN |
| Shizuku version / process UID | UNKNOWN / N/A |
| Protected permission grants (actual runtime state) | UNKNOWN / N/A |
| Near-end phrase result (0–5) | NOT TESTED |
| Far-end phrase result (0–5) | NOT TESTED |
| Near-end RMS / clipping / SNR | NOT MEASURED |
| Far-end RMS / clipping / SNR | NOT MEASURED |
| WAV duration / bytes / decode | NOT VERIFIED |
| Room status / finalization | NOT VERIFIED |
| Trial outcome | UNKNOWN |
| Redacted log + WAV checksum references | NONE |
| Reviewer / witness | NONE |
| Notes / failure stage | NOT TESTED |

## Automated PASS_TWO_WAY evidence gate

Use [the empty manifest template](physical-device-evidence-template.json) for each exact device/build/route/backend configuration. The template is intentionally `UNKNOWN`; it is not evidence and cannot pass validation.

Each of the six required cellular trials must include three distinct, reviewable WAV files:

- `near_end.wav`: a reference recording of the near-end participant's spoken phrase set
- `far_end.wav`: a reference recording of the far-end participant's spoken phrase set
- `capture.wav`: the WAV produced by this app for that exact call

Record SHA-256 digests for all three files, the app database status/finalization result, requested and actual backend, actual audio-source label, an empty fallback reason, the reviewer and timezone-aware review timestamp, and independent intelligibility results for both directions. The manifest must keep requested backend, configured backend, per-trial recording mode, and persisted capture recording mode identical. Keep all speech WAVs and completed manifests in restricted test storage; never commit them or upload them to public CI artifacts.

Run the fail-closed validator against the completed manifest:

```bash
python3 scripts/validate_physical_device_evidence.py /restricted/evidence/<configuration>/manifest.json
```

A zero exit code is the only software path that accepts `PASS_TWO_WAY`. It requires exactly three incoming and three outgoing real cellular trials; distinct near/far/app WAV files; valid PCM16 WAVs with non-zero data; matching SHA-256 checksums; complete device/build/modem/carrier/route/backend provenance; a finalized `COMPLETED` app record; independently intelligible near/far phrase sets; exact requested-to-actual backend equality; no fallback; and the required actual permission/Shizuku evidence. It rejects emulator or synthetic-call evidence, path traversal, missing files, duplicate WAVs, mismatched checksums, and sensitive identifier fields.

CI tests this validator with generated synthetic WAV fixtures only to test the gate implementation. Those fixtures are never physical evidence and must never be entered in the device compatibility matrix.

## Device compatibility matrix

The matrix is intentionally unpopulated. Add a row only after the above protocol is run; never generalize one carrier/route/build result to an entire model family.

| Manufacturer / model | Exact build + patch | Modem | Android/API | Carrier / SIM | Route + backend | Near-end | Far-end | Trials | Verdict |
|---|---|---|---|---|---|---|---|---:|---|
| UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | NOT TESTED | NOT TESTED | 0 | UNKNOWN |

Allowed verdicts: `PASS_TWO_WAY`, `FAIL_NEAR_END`, `FAIL_FAR_END`, `FAIL_BOTH`, `FAIL_INTEGRITY`, `INCOMPLETE`, `UNKNOWN`. Only `PASS_TWO_WAY` with all provenance and three successful trials may count toward a narrowly scoped compatibility claim.

## Current software evidence versus hardware evidence

- API 26/API 35 emulator instrumentation is software lifecycle evidence only.
- `ProcessDeathHarnessActivity` and `scripts/run-process-death-emulator-tests.sh` are intended to exercise genuine process death, interrupted WAV recovery, post-promotion recovery, and Recovery-vs-new-capture ordering on an emulator. Their markers must be present in a successful CI artifact before claiming that emulator suite passed.
- Android 16 Shell permission probing establishes the emulator's permission baseline only.
- None of these establish physical near-end/far-end cellular audio.
