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
sys.path.insert(0, str(SCRIPT.parent))
from validate_physical_device_evidence import validate_manifest


def write_wav(path: Path, silence: bool = False) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    samples = [0] * 800 if silence else [1200] * 800
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(8_000)
        output.writeframes(struct.pack("<800h", *samples))
    return hashlib.sha256(path.read_bytes()).hexdigest()


class PhysicalDeviceEvidenceValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.manifest_path = self.root / "manifest.json"
        self.manifest = self._valid_fixture()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _valid_fixture(self) -> dict:
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
                            "score": 5,
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
            "verdict": "TEST_FIXTURE",
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

    def _errors(self) -> list[str]:
        self.manifest_path.write_text(json.dumps(self.manifest, indent=2), encoding="utf-8")
        return validate_manifest(self.manifest_path, allow_test_fixture=True)

    def test_valid_fixture_only_passes_structural_test_mode(self) -> None:
        self.assertEqual([], self._errors())
        self.assertEqual("TEST_FIXTURE", self.manifest["verdict"])

    def test_cli_never_accepts_test_fixture_as_physical_pass(self) -> None:
        self.manifest_path.write_text(json.dumps(self.manifest, indent=2), encoding="utf-8")
        result = subprocess.run(
            [sys.executable, str(SCRIPT), str(self.manifest_path)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn("TEST_FIXTURE", result.stderr)
        self.assertNotIn("PASS_TWO_WAY:", result.stdout)

    def test_rejects_emulator_or_synthetic_call_state(self) -> None:
        self.manifest["configuration"]["is_emulator"] = True
        self.assertTrue(any("configuration.is_emulator" in error for error in self._errors()))

    def test_rejects_missing_far_end_reference_wav(self) -> None:
        (self.root / self.manifest["trials"][0]["far_end"]["wav"]).unlink()
        self.assertTrue(any("trials[0].far_end.wav" in error for error in self._errors()))

    def test_rejects_checksum_mismatch(self) -> None:
        self.manifest["trials"][0]["capture"]["sha256"] = "0" * 64
        self.assertTrue(any("checksum does not match" in error for error in self._errors()))

    def test_rejects_all_zero_pcm_capture(self) -> None:
        capture = self.root / self.manifest["trials"][0]["capture"]["wav"]
        digest = write_wav(capture, silence=True)
        self.manifest["trials"][0]["capture"]["sha256"] = digest
        self.assertTrue(any("all-zero PCM" in error for error in self._errors()))

    def test_rejects_non_five_of_five_intelligibility(self) -> None:
        self.manifest["trials"][0]["far_end"]["score"] = 4
        self.assertTrue(any("score 5/5 is required" in error for error in self._errors()))

    def test_rejects_any_automatic_backend_fallback(self) -> None:
        self.manifest["trials"][0]["fallback_reason"] = "Shizuku unavailable; microphone selected"
        errors = self._errors()
        self.assertTrue(any("fallback/substitution disqualifies PASS_TWO_WAY" in error for error in errors))

    def test_rejects_requested_backend_mismatch(self) -> None:
        self.manifest["configuration"]["requested_backend"] = "VOICE_CALL"
        self.assertTrue(any("requested_backend" in error for error in self._errors()))

    def test_rejects_one_sided_or_incomplete_trial_set(self) -> None:
        self.manifest["trials"] = self.manifest["trials"][:5]
        self.assertTrue(any("exactly six trials" in error for error in self._errors()))

    def test_rejects_actual_backend_substitution(self) -> None:
        self.manifest["trials"][0]["recording_mode"] = "VOICE_CALL"
        self.assertTrue(any("recording_mode" in error for error in self._errors()))

    def test_rejects_fallback_recorded_in_capture_metadata(self) -> None:
        self.manifest["trials"][0]["capture"]["fallback_reason"] = "MIC substituted"
        errors = self._errors()
        self.assertTrue(any("capture.fallback_reason" in error for error in errors))

    def test_rejects_mid_trial_fallback(self) -> None:
        self.manifest["trials"][0]["fallback_reason"] = "VOICE_CALL unavailable"
        self.assertTrue(any("fallback_reason" in error for error in self._errors()))

    def test_rejects_sensitive_identifiers(self) -> None:
        self.manifest["device"]["serial_number"] = "not-allowed"
        self.manifest["configuration"]["phoneNumber"] = "not-allowed"
        self.assertTrue(any("sensitive identifier" in error for error in self._errors()))

    def test_rejects_unknown_shizuku_fields_even_for_microphone_backend(self) -> None:
        self.manifest["configuration"]["shizuku"] = {
            "version": "13.1.5",
            "process_uid": 2000,
            "unreviewed_metadata": "not allowed",
        }
        self.assertTrue(any("configuration.shizuku.unreviewed_metadata" in error for error in self._errors()))

    def test_rejects_fields_outside_allow_list(self) -> None:
        self.manifest["device"]["notes"] = "not part of the manifest contract"
        self.assertTrue(any("not part of the allow-listed evidence schema" in error for error in self._errors()))

    def test_rejects_path_traversal(self) -> None:
        self.manifest["trials"][0]["near_end"]["wav"] = "../outside.wav"
        self.assertTrue(any("resolves outside" in error for error in self._errors()))

    def test_rejects_reused_reference_wav(self) -> None:
        first = self.manifest["trials"][0]["near_end"]
        self.manifest["trials"][0]["far_end"]["wav"] = first["wav"]
        self.manifest["trials"][0]["far_end"]["sha256"] = first["sha256"]
        self.assertTrue(any("distinct physical WAV" in error for error in self._errors()))

    def test_voice_call_does_not_require_microphone_permission(self) -> None:
        self.manifest["configuration"]["backend"] = "VOICE_CALL"
        self.manifest["configuration"]["requested_backend"] = "VOICE_CALL"
        self.manifest["configuration"]["permission_grants"].pop("RECORD_AUDIO", None)
        self.manifest["configuration"]["permission_grants"].pop("CAPTURE_AUDIO_OUTPUT", None)
        self.manifest["configuration"]["permission_grants"]["CALL_AUDIO_INTERCEPTION"] = True
        self.manifest["configuration"]["shizuku"] = {"version": "13.1.5", "process_uid": 2000}
        for trial in self.manifest["trials"]:
            trial["recording_mode"] = "VOICE_CALL"
            trial["capture"]["recording_mode"] = "VOICE_CALL"
            trial["capture"]["audio_source"] = "Shizuku VOICE_CALL"
        self.assertEqual([], self._errors())

    def test_root_uid_is_accepted_without_shell_protected_permission_grants(self) -> None:
        self.manifest["configuration"]["backend"] = "VOICE_CALL"
        self.manifest["configuration"]["requested_backend"] = "VOICE_CALL"
        self.manifest["configuration"]["permission_grants"].pop("RECORD_AUDIO", None)
        self.manifest["configuration"]["permission_grants"].pop("CAPTURE_AUDIO_OUTPUT", None)
        self.manifest["configuration"]["permission_grants"].pop("CALL_AUDIO_INTERCEPTION", None)
        self.manifest["configuration"]["shizuku"] = {"version": "13.1.5", "process_uid": 0}
        for trial in self.manifest["trials"]:
            trial["recording_mode"] = "VOICE_CALL"
            trial["capture"]["recording_mode"] = "VOICE_CALL"
            trial["capture"]["audio_source"] = "Shizuku VOICE_CALL"
        self.assertEqual([], self._errors())

    def test_voice_call_requires_actual_shizuku_and_protected_permission_evidence(self) -> None:
        self.manifest["configuration"]["backend"] = "VOICE_CALL"
        self.manifest["configuration"]["requested_backend"] = "VOICE_CALL"
        grants = self.manifest["configuration"]["permission_grants"]
        grants["RECORD_AUDIO"] = False
        grants["CAPTURE_AUDIO_OUTPUT"] = False
        grants["CALL_AUDIO_INTERCEPTION"] = True
        self.manifest["configuration"]["shizuku"] = {"version": "13.1.5", "process_uid": 2000}
        for trial in self.manifest["trials"]:
            trial["recording_mode"] = "VOICE_CALL"
            trial["capture"]["recording_mode"] = "VOICE_CALL"
            trial["capture"]["audio_source"] = "Shizuku VOICE_CALL"
        self.assertEqual([], self._errors())

        grants["CALL_AUDIO_INTERCEPTION"] = False
        grants["CAPTURE_AUDIO_OUTPUT"] = True
        self.assertEqual([], self._errors())

        grants["CAPTURE_AUDIO_OUTPUT"] = False
        self.assertTrue(any("at least one accepted protected permission" in error for error in self._errors()))

        self.manifest["configuration"]["shizuku"] = {"version": "13.1.5", "process_uid": 0}
        self.assertEqual([], self._errors())

        self.manifest["configuration"]["shizuku"] = {"version": "13.1.5", "process_uid": 2000}
        self.manifest["configuration"]["permission_grants"].pop("CAPTURE_AUDIO_OUTPUT")
        self.manifest["configuration"]["permission_grants"].pop("CALL_AUDIO_INTERCEPTION")
        self.assertTrue(any("shell UID requires at least one accepted protected permission" in error for error in self._errors()))

        self.manifest["configuration"]["shizuku"] = {"version": "13.1.5", "process_uid": 10000}
        self.assertTrue(any("process_uid" in error for error in self._errors()))

        del self.manifest["configuration"]["shizuku"]
        self.assertTrue(any("configuration.shizuku" in error for error in self._errors()))


if __name__ == "__main__":
    unittest.main()
