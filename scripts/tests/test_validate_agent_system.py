import unittest

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from validate_agent_system import parse_changed_paths, validate_merge_authority


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



class MergeAuthorityPolicyTest(unittest.TestCase):
    def valid_roles(self):
        return {
            "implementer": {"allow": ["merge_pull_request"], "deny": ["enable_auto_merge", "delete_file", "update_ref", "release_or_publish"]},
            "reviewer": {"allow": ["read"], "deny": ["self_approval", "approve_own_pull_request", "merge_pull_request"]},
            "pr_coordinator": {"allow": ["create_pull_request"], "deny": ["merge_pull_request"]},
            "release_manager": {"allow": [], "deny": ["merge", "release", "publish"]},
        }

    def test_allows_evidence_gated_implementer_merge_without_self_approval(self):
        self.assertEqual([], validate_merge_authority(self.valid_roles()))

    def test_rejects_reintroduced_blanket_implementer_merge_denial(self):
        roles = self.valid_roles()
        roles["implementer"]["deny"].append("merge_pull_request")
        self.assertIn("implementer must not have a blanket PR-merge denial", validate_merge_authority(roles))

    def test_keeps_auto_merge_denied_to_implementer(self):
        roles = self.valid_roles()
        roles["implementer"]["deny"].remove("enable_auto_merge")
        self.assertIn("implementer must continue to deny auto-merge", validate_merge_authority(roles))

    def test_reviewer_and_pr_coordinator_cannot_merge_or_self_approve(self):
        roles = self.valid_roles()
        roles["reviewer"]["deny"].remove("self_approval")
        roles["pr_coordinator"]["deny"].remove("merge_pull_request")
        errors = validate_merge_authority(roles)
        self.assertIn("reviewer must deny self-approval and PR merges", errors)
        self.assertIn("PR coordinator must not merge PRs", errors)


if __name__ == "__main__":
    unittest.main()
