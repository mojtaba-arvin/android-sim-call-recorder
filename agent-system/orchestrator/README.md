# Orchestrator runtime primitives

This package currently provides two deterministic primitives for the repository-local R&D runtime:

- `state_machine.py` enforces the task lifecycle from the versioned contract. Blocked tasks retain their safe resume state and require a refreshed repository context before resuming. Validation failures require a root-cause note and revised verification plan. Completion is possible only from PR review after every acceptance criterion has evidence.
- `execution_ledger.py` appends JSONL events with a SHA-256 hash chain, serializes concurrent writers, verifies the existing chain before every append, and redacts sensitive fields before persistence.

These modules do not call GitHub, dispatch workflows, change issues, merge PRs, publish releases, access secrets, or grant permissions. They are pure local runtime primitives. GitHub synchronization and planning remain separate phases and must use the allow-listed semantic tool grants in the task contract.

Run the unit tests from the repository root:

```bash
python -m unittest discover -s agent-system/tests -p 'test_*.py' -v
```

The ledger is tamper-evident, not cryptographically signed. Its hash chain detects accidental or unauthorized modification when the trusted head hash is retained separately; it does not prevent an actor with write access from rewriting the entire file and recomputing the chain. Do not store raw tool arguments, secret values, phone numbers, call audio, transcripts, or subscription identifiers in events.
