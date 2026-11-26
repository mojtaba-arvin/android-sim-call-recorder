import json
import tempfile
import unittest
from pathlib import Path

from agent_system.orchestrator.execution_ledger import ExecutionLedger, LedgerIntegrityError
from agent_system.orchestrator.state_machine import (
    RuntimeTaskState,
    StateTransitionError,
    TaskState,
    TaskStateMachine,
)


class TaskStateMachineTests(unittest.TestCase):
    def setUp(self):
        self.machine = TaskStateMachine()
        self.task = RuntimeTaskState(
            task_id="issue-2",
            acceptance_criteria=("CAPABILITY", "START_GUARD"),
        )

    def test_happy_path_requires_evidence_for_every_acceptance_criterion(self):
        task = self.machine.transition(self.task, TaskState.TRIAGED)
        task = self.machine.transition(task, TaskState.READY)
        task = self.machine.transition(task, TaskState.IN_PROGRESS)
        task = self.machine.transition(task, TaskState.VALIDATION)
        task = self.machine.transition(task, TaskState.PR_REVIEW)
        with self.assertRaises(StateTransitionError):
            self.machine.complete(task)

        task = self.machine.record_acceptance(
            task, "CAPABILITY",
            {"reference": "tests/test_capability.py", "head_sha": "a" * 40, "claim": "Capability gate passed."},
        )
        task = self.machine.record_acceptance(
            task, "START_GUARD",
            {"reference": "run/123/job/456", "head_sha": "b" * 40, "claim": "Start-time guard passed."},
        )
        completed = self.machine.complete(task)
        self.assertEqual(TaskState.DONE, completed.state)
        self.assertEqual({"CAPABILITY", "START_GUARD"}, set(completed.met_criteria))

    def test_illegal_skip_and_done_from_wrong_state_fail_closed(self):
        with self.assertRaises(StateTransitionError):
            self.machine.transition(self.task, TaskState.IN_PROGRESS)
        with self.assertRaises(StateTransitionError):
            self.machine.complete(self.task)

    def test_blocked_task_resumes_only_to_recorded_state_after_context_refresh(self):
        task = self.machine.transition(self.task, TaskState.TRIAGED)
        blocked = self.machine.transition(task, TaskState.BLOCKED, reason="waiting for device evidence")
        self.assertEqual(TaskState.TRIAGED, blocked.resume_state)
        with self.assertRaises(StateTransitionError):
            self.machine.transition(blocked, TaskState.READY, context_refreshed=True)
        with self.assertRaises(StateTransitionError):
            self.machine.transition(blocked, TaskState.TRIAGED)
        resumed = self.machine.transition(blocked, TaskState.TRIAGED, context_refreshed=True)
        self.assertEqual(TaskState.TRIAGED, resumed.state)
        self.assertIsNone(resumed.blocker)
        self.assertIsNone(resumed.resume_state)

    def test_failed_validation_requires_root_cause_and_revised_plan(self):
        task = self.machine.transition(self.task, TaskState.TRIAGED)
        task = self.machine.transition(task, TaskState.READY)
        task = self.machine.transition(task, TaskState.IN_PROGRESS)
        task = self.machine.transition(task, TaskState.VALIDATION)
        with self.assertRaises(StateTransitionError):
            self.machine.transition(task, TaskState.IN_PROGRESS)
        retried = self.machine.transition(
            task,
            TaskState.IN_PROGRESS,
            failure_note="The fixture omitted the subscription slot.",
            revised_verification_plan="Add the slot to the fixture and rerun the focused test.",
        )
        self.assertEqual(TaskState.IN_PROGRESS, retried.state)
        self.assertIn("fixture", retried.last_failure)

    def test_done_is_terminal_and_same_state_transition_is_idempotent(self):
        self.assertIs(self.task, self.machine.transition(self.task, TaskState.DISCOVERED))
        task = self.machine.transition(self.task, TaskState.BLOCKED, reason="owner input required")
        with self.assertRaises(StateTransitionError):
            self.machine.transition(task, TaskState.DONE, context_refreshed=True)

    def test_runtime_state_round_trips_without_changing_task_contract(self):
        task = self.machine.transition(self.task, TaskState.BLOCKED, reason="external gate")
        restored = RuntimeTaskState.from_dict(task.to_dict())
        self.assertEqual(task, restored)


class ExecutionLedgerTests(unittest.TestCase):
    def test_hash_chain_verifies_and_sensitive_fields_are_redacted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "execution.jsonl"
            ledger = ExecutionLedger(path)
            first = ledger.append({
                "task_id": "issue-2",
                "action": "branch_created",
                "commit_sha": "a" * 40,
                "authorization": "Bearer do-not-store",
                "details": {"phone_number": "+000000000", "result": "created"},
            })
            second = ledger.append({"task_id": "issue-2", "action": "ci_observed", "run_id": 123})

            self.assertEqual("", first["previous_hash"])
            self.assertEqual(first["entry_hash"], second["previous_hash"])
            self.assertEqual(second["entry_hash"], ledger.verify())
            stored = path.read_text(encoding="utf-8")
            self.assertNotIn("Bearer do-not-store", stored)
            self.assertNotIn("+000000000", stored)
            self.assertIn("[REDACTED]", stored)

    def test_tampering_is_detected_before_a_later_append(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "execution.jsonl"
            ledger = ExecutionLedger(path)
            ledger.append({"task_id": "issue-2", "action": "started"})
            entry = json.loads(path.read_text(encoding="utf-8"))
            entry["action"] = "fabricated"
            path.write_text(json.dumps(entry) + "\n", encoding="utf-8")

            with self.assertRaises(LedgerIntegrityError):
                ledger.verify()
            with self.assertRaises(LedgerIntegrityError):
                ledger.append({"task_id": "issue-2", "action": "next"})

    def test_malformed_and_incomplete_events_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger = ExecutionLedger(Path(directory) / "execution.jsonl")
            with self.assertRaises(ValueError):
                ledger.append({"task_id": "issue-2"})
            with self.assertRaises(ValueError):
                ledger.append({"task_id": "issue-2", "action": "x", "entry_hash": "caller-controlled"})


if __name__ == "__main__":
    unittest.main()
