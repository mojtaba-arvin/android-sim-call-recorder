import unittest

from validate_physical_device_report import ReportError, validate_report


def make_report():
    calls = []
    for direction in ("INCOMING", "OUTGOING"):
        for attempt in range(1, 4):
            calls.append({
                "call_id": f"{direction.lower()}-{attempt}",
                "direction": direction,
                "attempt": attempt,
                "call_state_events": (["RINGING", "OFFHOOK", "IDLE"]
                                      if direction == "INCOMING" else ["OFFHOOK", "IDLE"]),
                "recording_started": True,
                "recording_finalized": True,
                "local_phrase_intelligible": True,
                "remote_phrase_intelligible": True,
                "wav_sha256": "a" * 64,
                "wav_bytes": 4096,
                "sample_rate": 48000,
                "channels": 2,
            })
    return {
        "schema_version": 1,
        "report_id": "pixel8-api35-speaker-shizuku",
        "tested_at": "2026-11-22T12:00:00Z",
        "device": {
            "manufacturer": "Example",
            "model": "Example Phone",
            "android_release": "15",
            "api_level": 35,
            "build_id": "BP1A.250",
            "security_patch": "2026-10-01",
        },
        "app": {"version": "0.1.0", "commit": "b" * 40},
        "capture": {
            "strategy": "SHIZUKU_VOICE_CALL",
            "actual_audio_source": "VOICE_CALL",
            "sim_slot_index": 0,
            "route": "SPEAKER",
            "network_mode": "VOLTE",
            "helper_uid": 2000,
            "shell_audio_attribution_ready": True,
            "protected_call_audio_permission_ready": True,
        },
        "calls": calls,
    }


class PhysicalDeviceReportTest(unittest.TestCase):
    def test_six_successful_calls_produce_two_way_pass(self):
        outcome, failures = validate_report(make_report())
        self.assertEqual("PASS_TWO_WAY", outcome)
        self.assertEqual([], failures)

    def test_one_sided_audio_fails_closed(self):
        report = make_report()
        report["calls"][0]["remote_phrase_intelligible"] = False
        outcome, failures = validate_report(report)
        self.assertEqual("FAIL", outcome)
        self.assertTrue(any("remote phrase" in item for item in failures))

    def test_fewer_than_three_calls_per_direction_is_rejected(self):
        report = make_report()
        report["calls"] = report["calls"][:-1]
        with self.assertRaisesRegex(ReportError, "three outgoing"):
            validate_report(report)

    def test_shizuku_report_cannot_claim_microphone_source_or_missing_privilege(self):
        report = make_report()
        report["capture"]["actual_audio_source"] = "MIC"
        with self.assertRaisesRegex(ReportError, "VOICE_CALL"):
            validate_report(report)
        report = make_report()
        report["capture"]["shell_audio_attribution_ready"] = False
        with self.assertRaisesRegex(ReportError, "attribution"):
            validate_report(report)

    def test_privacy_sensitive_fields_are_rejected_recursively(self):
        report = make_report()
        report["device"]["imei"] = "must-not-be-present"
        with self.assertRaisesRegex(ReportError, "privacy-sensitive"):
            validate_report(report)

    def test_duplicate_call_ids_are_rejected(self):
        report = make_report()
        report["calls"][1]["call_id"] = report["calls"][0]["call_id"]
        with self.assertRaisesRegex(ReportError, "duplicate"):
            validate_report(report)


if __name__ == "__main__":
    unittest.main()
