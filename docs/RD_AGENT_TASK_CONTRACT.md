# R&D Agent Task Contract

Version: 1.0.0
Schema: [task.schema.json](../agent-system/contracts/task.schema.json)
Charter: [R&D Agent System Charter](RD_AGENT_CHARTER.md)
Last reviewed: 2026-11-19

## Purpose

Every agent session receives one explicit, versioned task envelope. The envelope prevents stale repository context, ambiguous authority, untestable acceptance criteria, and unsupported completion claims. It is runtime-neutral: an orchestrator may be implemented in Python, TypeScript, or another approved runtime, but it must validate inputs and outputs against the same schema.

## Required input

- GitHub issue reference and repository identity.
- Fresh context snapshot: capture time, exact branch/base SHA, active PR, and relevant Actions run IDs with tested SHAs and states.
- One observable objective, scope, non-goals, risk classification, and issue dependencies.
- Acceptance criteria with stable IDs and a verification method.
- Explicit tool grants and forbidden actions. Missing grants mean denied.
- Approval gates for owner-controlled operations.
- Session-sized plan with per-step estimates and dependencies.
- Positive checks, negative/failure cases, and privacy review requirement.

## State transitions

Allowed lifecycle:

```text
discovered -> triaged -> ready -> in_progress -> validation -> pr_review -> done
      |          |         |          |             |           |
      +----------+---------+----------+-------------+----------> blocked
```

Rules:
- A task may move to blocked from any non-terminal state when a dependency, approval, device, permission, or evidence gate prevents safe progress.
- A blocked task resumes at its recorded safe state after the blocker is resolved; it must refresh repository context before continuing.
- done is terminal and requires all mandatory acceptance criteria to be met with evidence tied to the relevant SHA or device record.
- failed validation returns the task to in_progress only after a root-cause note and a revised verification plan are recorded.
- Never mark done because a commit exists, a PR is open, or a workflow is merely queued/in progress.

## Tool permission model

Tool grants are allow-lists, not suggestions. The runtime must check the requested action against the task's grants before dispatch. A grant does not override the charter's forbidden actions or owner-approval gates.

Default profiles:

| Role | Grants | Explicit exclusions |
|---|---|---|
| Research | read_repository, read_issues, read_official_web_sources | all write operations, secrets, merge/release |
| Architecture / platform review | read_repository, read_issues, read_official_web_sources, read_actions, read_logs | code writes, issue closure, merge/release |
| Implementer | read_repository, read_issues, write_issue_progress, create_branch, write_feature_branch, create_pull_request, run_tests, read_actions, read_logs | default-branch writes, merge, ruleset changes, release/tag/publish |
| Test / CI analyst | read_repository, read_issues, run_tests, read_actions, read_logs, read_artifacts | code writes unless separately granted; merge/release |
| Security / privacy | read_repository, read_issues, read_actions, read_logs, read_official_web_sources | policy changes, permission grants, secrets, merge/release |
| Documentation | read_repository, read_issues, write_issue_progress, create_branch, write_feature_branch, create_pull_request, read_actions | product behavior changes, merge/release |

## Evidence requirements

Evidence records must identify the evidence type, stable reference, exact head SHA when applicable, and the specific claim supported. Workflow evidence must refer to the tested SHA, not merely the PR number. Physical-device evidence must identify the tested device/build/API/carrier/SIM/route and separately assess near-end and far-end intelligibility without uploading call audio or personal identifiers.

Evidence is not transferable across different SHAs unless the claim is independent of the changed content and that independence is explained. A cancelled or stale workflow is not a successful check.

## Failure and handoff

On a tool error, timeout, rate limit, cancelled workflow, or process interruption:
1. Preserve the last known state and evidence references.
2. Refresh GitHub state; do not assume the last mutation succeeded.
3. Classify the failure as product/source, test/fixture, build/toolchain, CI runner/environment, GitHub/API permission/rate-limit, or unknown.
4. Retry only idempotent operations or confirmed transient failures.
5. If human action is required, record the exact action, reason, options/consequences, evidence, and safe resume point.

## Output contract

At session completion, the agent reports:
- Outcome and state (complete, partial, blocked, failed).
- Exact branch and commit SHA; PR URL if created.
- Acceptance criteria met, unmet, and blocked by stable ID.
- Tests/checks, exact SHA, workflow run/job URLs, and relevant artifact references.
- Files and docs changed, security/privacy/licensing review status, and remaining limitations.
- Next unblocked task or the specific owner/device/permission decision required.

## Validation

The orchestrator must validate task envelopes before execution and validate completion reports before changing issue state. Invalid, incomplete, stale, or unauthorized contracts fail closed. Contract changes require a schema version change and benchmark updates.

See [AGENTS.md](../AGENTS.md) for repository workflow rules and [the GitHub agent workflow](../.github/AGENT_GITHUB_WORKFLOW.md) for GitHub-specific operating procedures.
