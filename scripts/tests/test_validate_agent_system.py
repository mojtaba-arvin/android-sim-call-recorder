import unittest

from validate_agent_system import parse_changed_paths


class ParseChangedPathsTest(unittest.TestCase):
    def test_splits_nul_delimited_paths_without_merging_entries(self):
        output = (
            b".github/workflows/android-ci.yml\x00"
            b"agent-system/context/REPOSITORY_CONTEXT_SNAPSHOT.md\x00"
            b"scripts/tests/test_validate_agent_system.py\x00"
        )
        self.assertEqual(
            [
                ".github/workflows/android-ci.yml",
                "agent-system/context/REPOSITORY_CONTEXT_SNAPSHOT.md",
                "scripts/tests/test_validate_agent_system.py",
            ],
            parse_changed_paths(output),
        )

    def test_empty_output_returns_no_paths(self):
        self.assertEqual([], parse_changed_paths(b""))

    def test_trailing_nul_does_not_create_an_empty_path(self):
        self.assertEqual(["README.md"], parse_changed_paths(b"README.md\x00"))


if __name__ == "__main__":
    unittest.main()
