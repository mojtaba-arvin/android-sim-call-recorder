import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from classify_android_ci_changes import classify_paths


class ClassifyAndroidCiChangesTest(unittest.TestCase):
    def test_documentation_and_schema_changes_skip_android_lanes(self):
        self.assertEqual((False, False), classify_paths([
            "README.md", "docs/PHYSICAL_DEVICE_ACCEPTANCE.md",
            "schemas/physical-device-evidence.schema.json",
            "agent-system/contracts/task.schema.json",
        ]))

    def test_focused_tooling_and_policy_workflows_skip_android_lanes(self):
        self.assertEqual((False, False), classify_paths([
            "scripts/validate_physical_device_report.py",
            "scripts/tests/test_validate_physical_device_report.py",
            ".github/workflows/physical-device-evidence.yml",
            ".github/workflows/release.yml",
            ".github/ISSUE_TEMPLATE/engineering-task.yml",
        ]))

    def test_android_source_requires_full_pipeline(self):
        self.assertEqual((True, False), classify_paths([
            "callrecorder-sdk/src/main/java/com/example/Recorder.kt",
        ]))

    def test_shizuku_source_requires_full_pipeline_and_permission_probe(self):
        self.assertEqual((True, True), classify_paths([
            "callrecorder-sdk/src/main/java/com/mojtaba/simcallrecorder/recording/shizuku/PrivilegedVoiceCallCaptureEngine.kt",
        ]))

    def test_shizuku_tests_require_audio_probe(self):
        self.assertEqual((True, True), classify_paths([
            "callrecorder-sdk/src/test/java/com/mojtaba/simcallrecorder/recording/shizuku/RecorderServiceControllerTest.kt",
        ]))

    def test_unknown_paths_fail_safe_to_full_android_pipeline(self):
        self.assertEqual((True, False), classify_paths(["new-unclassified-root-file.bin"]))

    def test_empty_input_skips_expensive_lanes_but_is_not_used_for_manual_runs(self):
        self.assertEqual((False, False), classify_paths([]))


if __name__ == "__main__":
    unittest.main()
