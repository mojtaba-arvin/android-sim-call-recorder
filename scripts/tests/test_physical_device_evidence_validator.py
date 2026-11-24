import hashlib
import json
import struct
import subprocess
import sys
import tempfile
import unittest
import wave
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "validate_physical_device_evidence.py"


def write_wav(path: Path) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(8_000)
        output.writeframes(struct.pack("<800h", *([1200] * 800)))
    return hashlib.sha256(path.read_bytes()).hexdigest()


class PhysicalDeviceEvidenceValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.manifest_path = self.root / "manifest.json"
        self.manifest = self._valid_manifest()
        self._write_manifest()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _valid_manifest(self) -> dict:
        trials = []
        for direction, prefix in (("INCOMING", "IN"), ("OUTGOING", "OUT")):
            for number in range(1, 4):
                trial_id = f"{prefix}-{number}"
                evidence = {}
                for kind in ("near_end", "far_end", "capture"):
                    relative = f"wav/{trial_id}-{kind}.wav"
                    digest = write_wav(self.root / relative)
                    evidence[kind] = {"wav": relative, "sha256": digest}
                trials.append(
                    {
                        "id": trial_id,
                        "direction": direction,
                        "outcome": "PASS",
                        "reviewer": "Independent reviewer",
                        "reviewed_at": "2026-11-24T10:00:00+04:00",
                        "recording_mode": "SPEAKERPHONE_MIC",
                        "fallback_reason": None,
                        "near_end": {
                            **evidence["near_end"],
                            "all_required_phrases_intelligible": True,
                            "score": 5,
                        },
                        "far_end": {
                            **evidence["far_end"],
                            "all_required_phrases_intelligible": True,
                        },
                        "capture": {
                            **evidence["capture"],
                            "db_status": "COMPLETED",
                            "recording_mode": "SPEAKERPHONE_MIC",
                            "audio_source": "Speakerphone + MIC",
                            "finalized": True,
                            "integrity_verified": True,
                        },
                    }
                )
        return {
            "schema_version": 1,
            "verdict": "PASS_TWO_WAY",
            "device": {
                "manufacturer": "Test manufacturer",
                "model": "Test handset",
                "android_version": "15",
                "api_level": 35,
                "build_fingerprint": "vendor/device/product:15/ABC123/123:user/release-keys",
                "security_patch": "2026-10-01",
                "modem_baseband": "TEST-MODEM",
                "carrier": "Test carrier",
                "country": "Test country",
                "network_type": "LTE",
                "sim_slot": 0,
            },
            "configuration": {
                "call_path": "CELLULAR",
                "is_emulator": False,
                "synthetic_call_state": False,
                "route": "speakerphone",
                "backend": "SPEAKERPHONE_MIC",
                "requested_backend": "SPEAKERPHONE_MIC",
                "app_commit": "a" * 40,
                "permission_grants": {
                    "READ_PHONE_STATE": True,
                    "RECORD_AUDIO": True,
                },
            },
            "trials": trials,
        }

    def _write_manifest(self) -> None:
        self.manifest_path.write_text(json.dumps(self.manifest, indent=2), encoding="utf-8")

    def _run(self) -> subprocess.CompletedProcess:
        self._write_manifest()
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(self.manifest_path)],
            capture_output=True,
            text=True,
            check=False,
        )

    def test_accepts_six_physical_trials_with_separate_near_far_and_capture_wavs(self) -> None:
        result = self._run()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("PASS_TWO_WAY", result.stdout)

    def test_rejects_emulator_or_synthetic_call_state(self) -> None:
        self.manifest["configuration"]["is_emulator"] = True
        result = self._run()
        self.assertEqual(2, result.returncode)
        self.assertIn("configuration.is_emulator", result.stderr)

    def test_rejects_missing_far_end_reference_wav(self) -> None:
        (self.root / self.manifest["trials"][0]["far_end"]["wav"]).unlink()
        result = self._run()
        self.assertEqual(2, result.returncode)
        self.assertIn("trials[0].far_end.wav", result.stderr)

    def test_rejects_checksum_mismatch(self) -> None:
        self.manifest["trials"][0]["capture"]["sha256"] = "0" * 64
        result = self._run()
        self.assertEqual(2, result.returncode)
        self.assertIn("checksum does not match", result.stderr)

    def test_rejects_all_zero_pcm_capture(self) -> None:
        capture = self.root / self.manifest["trials"][0]["capture"]["wav"]
        with wave.open(str(capture), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(8_000)
            output.writeframes(bytes(1_600))
        self.manifest["trials"][0]["capture"]["sha256"] = hashlib.sha256(capture.read_bytes()).hexdigest()
        result = self._run()
        self.assertEqual(2, result.returncode)
        self.assertIn("all-zero PCM", result.stderr)

    def test_rejects_non_five_of_five_intelligibility(self) -> None:
        self.manifest["trials"][0]["far_end"]["score"] = 4
        result = self._run()
        self.assertEqual(2, result.returncode)
        self.assertIn("score 5/5 is required", result.stderr)

    def test_rejects_requested_backend_mismatch(self) -> None:
        self.manifest["configuration"]["requested_backend"] = "VOICE_CALL"
        result = self._run()
        self.assertEqual(2, result.returncode)
        self.assertIn("requested_backend", result.stderr)

    def test_rejects_reused_reference_wav(self) -> None:
        first = self.manifest["trials"][0]["near_end"]
        self.manifest["trials"][0]["far_end"]["wav"] = first["wav"]
        self.manifest["trials"][0]["far_end"]["sha256"] = first["sha256"]
        result = self._run()
        self.assertEqual(2, result.returncode)
        self.assertIn("distinct physical WAV", result.stderr)

    def test_rejects_one_sided_or_incomplete_trial_set(self) -> None:
        self.manifest["trials"] = self.manifest["trials"][:5]
        result = self._run()
        self.assertEqual(2, result.returncode)
        self.assertIn("exactly six trials", result.stderr)

    def test_rejects_actual_backend_substitution(self) -> None:
        self.manifest["trials"][0]["recording_mode"] = "VOICE_CALL"
        result = self._run()
        self.assertEqual(2, result.returncode)
        self.assertIn("recording_mode", result.stderr)

    def test_rejects_mid_trial_fallback(self) -> None:
        self.manifest["trials"][0]["fallback_reason"] = "VOICE_CALL unavailable"
        result = self._run()
        self.assertEqual(2, result.returncode)
        self.assertIn("fallback_reason", result.stderr)

    def test_rejects_sensitive_identifiers(self) -> None:
        self.manifest["device"]["serial_number"] = "not-allowed"
        result = self._run()
        self.assertEqual(2, result.returncode)
        self.assertIn("sensitive identifier", result.stderr)

    def test_rejects_path_traversal(self) -> None:
        self.manifest["trials"][0]["near_end"]["wav"] = "../outside.wav"
        result = self._run()
        self.assertEqual(2, result.returncode)
        self.assertIn("resolves outside", result.stderr)

    def test_voice_call_requires_actual_shizuku_and_protected_permission_evidence(self) -> None:
        self.manifest["configuration"]["backend"] = "VOICE_CALL"
        self.manifest["configuration"]["requested_backend"] = "VOICE_CALL"
        self.manifest["configuration"]["permission_grants"]["CAPTURE_AUDIO_OUTPUT"] = True
        self.manifest["configuration"]["permission_grants"]["CALL_AUDIO_INTERCEPTION"] = True
        self.manifest["configuration"]["shizuku"] = {"version": "13.1.5", "process_uid": 2000}
        for trial in self.manifest["trials"]:
            trial["recording_mode"] = "VOICE_CALL"
            trial["capture"]["recording_mode"] = "VOICE_CALL"
            trial["capture"]["audio_source"] = "Shizuku VOICE_CALL"
        result = self._run()
        self.assertEqual(0, result.returncode, result.stderr)

        self.manifest["configuration"]["shizuku"] = {"version": "13.1.5", "process_uid": 10000}
        result = self._run()
        self.assertEqual(2, result.returncode)
        self.assertIn("process_uid", result.stderr)

        del self.manifest["configuration"]["shizuku"]
        result = self._run()
        self.assertEqual(2, result.returncode)
        self.assertIn("configuration.shizuku", result.stderr)


if __name__ == "__main__":
    unittest.main()
