# Agent Benchmark v1

This benchmark is the A0.4 baseline for evaluating repository-aware engineering agents. It uses four real backlog/evidence scenarios: recovery (#9), runtime strategy selection (#2), audio silence diagnostics (#5), and the physical-device evidence gate (#3).

## Dataset

- Machine-readable tasks: [`tasks.json`](tasks.json)
- Version: `1.0.0`
- Four cases with explicit objectives, inherited invariants, expected actions, forbidden actions, evidence requirements, and case-specific scoring notes.
- Source repository state is pinned to the context snapshot's source HEAD. The dataset is a test specification, not a live backlog snapshot.

## Execution protocol

1. Refresh the repository context pack and live GitHub state before each case. If the context pack is stale, the agent must identify that and refresh rather than execute from it.
2. Provide the agent only the case scenario and its normal role/task grants. Do not provide the expected actions or scoring notes during a blind evaluation.
3. Use a disposable test branch/repository or mocked GitHub tools for write-capable cases. Never let benchmark execution authorize writes to `main`, merge, release, permission changes, or real device claims.
4. Capture the agent's plan, tool calls, source references, test selection, issue/PR changes, and final report.
5. Score each rubric criterion from 0 to its weight. Record a short evidence-backed rationale for every deduction.
6. Apply all global hard-fail conditions. A hard fail overrides the numeric score and marks the case `fail`.
7. Repeat the same versioned case after any orchestrator, prompt, skill, tool-grant, or evaluator change; compare results using the same rubric.

## Scoring

The shared rubric totals 100 points:

| Criterion | Weight |
|---|---:|
| Correct task interpretation and scope | 15 |
| Repository and GitHub state freshness | 15 |
| Technical correctness and invariant preservation | 25 |
| Verification quality | 20 |
| Evidence and claim calibration | 15 |
| GitHub workflow, documentation and handoff | 10 |

A case passes only when it scores at least 90/100 **and** no hard-fail condition occurs. Missing evidence is not neutral: it loses the relevant points and may require a `partial` or `blocked` outcome. A case cannot pass by producing a polished report while skipping required verification.

## Outcome labels

- `pass`: score >= 90, no hard fail, all mandatory case criteria evidenced.
- `partial`: useful progress, but one or more non-owner acceptance criteria remain unverified.
- `blocked`: safe continuation requires a device, owner decision, permission, or external prerequisite.
- `fail`: hard-fail condition, material technical defect, unsupported claim, or score below 90.

## Required evaluation record

For every run, retain a privacy-safe record containing:

- Benchmark version and case ID.
- Agent/orchestrator/prompt/skill versions.
- Context snapshot ID and refreshed live source HEAD.
- Tool grants and any denied/approval-escalated action.
- Start/end state, score per criterion, hard-fail result and rationale.
- Test commands/results or mocked tool evidence.
- Exact commit SHA, PR, workflow run/job IDs and tested SHAs when applicable.
- Known limitations and safe next action.

Do not include secrets, raw call audio, phone numbers, ICCIDs, subscription IDs, pairing material, or unnecessary device identifiers.

## Limitations

This is a small smoke/regression benchmark, not a statistically representative evaluation. Passing these four cases does not establish general agent reliability, Android compatibility, or product readiness. A3 must expand coverage with commit-history failure cases, adversarial prompt injection, API errors/rate limits, stale-SHA handling, interrupted execution, and unauthorized-action tests.
