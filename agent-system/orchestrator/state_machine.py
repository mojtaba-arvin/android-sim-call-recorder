"""Deterministic, fail-closed task lifecycle transitions for the repository agent runtime."""

from __future__ import annotations

from dataclasses import dataclass, replace
from enum import Enum
from typing import Any, Mapping


class TaskState(str, Enum):
    DISCOVERED = "discovered"
    TRIAGED = "triaged"
    READY = "ready"
    IN_PROGRESS = "in_progress"
    VALIDATION = "validation"
    PR_REVIEW = "pr_review"
    BLOCKED = "blocked"
    DONE = "done"


class StateTransitionError(ValueError):
    """Raised when a requested lifecycle transition violates the task contract."""


_ALLOWED_TRANSITIONS = {
    TaskState.DISCOVERED: {TaskState.TRIAGED},
    TaskState.TRIAGED: {TaskState.READY},
    TaskState.READY: {TaskState.IN_PROGRESS},
    TaskState.IN_PROGRESS: {TaskState.VALIDATION},
    TaskState.VALIDATION: {TaskState.PR_REVIEW, TaskState.IN_PROGRESS},
    TaskState.PR_REVIEW: {TaskState.DONE, TaskState.IN_PROGRESS},
    TaskState.BLOCKED: set(),
    TaskState.DONE: set(),
}


@dataclass(frozen=True)
class RuntimeTaskState:
    """Runtime-only state; the versioned task contract remains unchanged."""

    task_id: str
    state: TaskState = TaskState.DISCOVERED
    acceptance_criteria: tuple[str, ...] = ()
    met_criteria: frozenset[str] = frozenset()
    evidence: tuple[dict[str, Any], ...] = ()
    blocker: str | None = None
    resume_state: TaskState | None = None
    last_failure: str | None = None
    verification_plan_revision: str | None = None

    def __post_init__(self) -> None:
        if not self.task_id.strip():
            raise ValueError("task_id must be non-empty")
        if len(set(self.acceptance_criteria)) != len(self.acceptance_criteria):
            raise ValueError("acceptance_criteria must not contain duplicates")
        if not self.met_criteria.issubset(set(self.acceptance_criteria)):
            raise ValueError("met_criteria must be declared in acceptance_criteria")
        if self.state is TaskState.BLOCKED:
            if not self.blocker or self.resume_state in (None, TaskState.BLOCKED, TaskState.DONE):
                raise ValueError("blocked tasks require a blocker and a non-terminal resume_state")
        elif self.blocker is not None or self.resume_state is not None:
            raise ValueError("blocker and resume_state are only valid while blocked")
        if self.state is TaskState.DONE:
            self._validate_completion()

    def _validate_completion(self) -> None:
        required = set(self.acceptance_criteria)
        if not required or not required.issubset(self.met_criteria):
            raise ValueError("done requires every declared acceptance criterion to be met")
        evidenced = {
            item.get("criterion_id")
            for item in self.evidence
            if item.get("reference") and item.get("claim") and "head_sha" in item
        }
        missing = required - evidenced
        if missing:
            raise ValueError(f"done requires evidence for every criterion; missing: {sorted(missing)}")

    def to_dict(self) -> dict[str, Any]:
        return {
            "task_id": self.task_id,
            "state": self.state.value,
            "acceptance_criteria": list(self.acceptance_criteria),
            "met_criteria": sorted(self.met_criteria),
            "evidence": [dict(item) for item in self.evidence],
            "blocker": self.blocker,
            "resume_state": self.resume_state.value if self.resume_state else None,
            "last_failure": self.last_failure,
            "verification_plan_revision": self.verification_plan_revision,
        }

    @classmethod
    def from_dict(cls, payload: Mapping[str, Any]) -> "RuntimeTaskState":
        return cls(
            task_id=str(payload["task_id"]),
            state=TaskState(payload["state"]),
            acceptance_criteria=tuple(payload.get("acceptance_criteria", ())),
            met_criteria=frozenset(payload.get("met_criteria", ())),
            evidence=tuple(dict(item) for item in payload.get("evidence", ())),
            blocker=payload.get("blocker"),
            resume_state=TaskState(payload["resume_state"]) if payload.get("resume_state") else None,
            last_failure=payload.get("last_failure"),
            verification_plan_revision=payload.get("verification_plan_revision"),
        )


class TaskStateMachine:
    """Pure state transitions; it never invokes GitHub or external tools."""

    def transition(
        self,
        current: RuntimeTaskState,
        target: TaskState | str,
        *,
        reason: str | None = None,
        context_refreshed: bool = False,
        failure_note: str | None = None,
        revised_verification_plan: str | None = None,
    ) -> RuntimeTaskState:
        target_state = TaskState(target)
        if target_state is current.state:
            return current
        if current.state is TaskState.DONE:
            raise StateTransitionError("done is terminal")

        if target_state is TaskState.BLOCKED:
            if current.state is TaskState.BLOCKED:
                raise StateTransitionError("blocked tasks already have a recorded blocker")
            if not reason or not reason.strip():
                raise StateTransitionError("blocking requires a specific reason")
            return replace(
                current,
                state=TaskState.BLOCKED,
                blocker=reason.strip(),
                resume_state=current.state,
            )

        if current.state is TaskState.BLOCKED:
            if target_state is not current.resume_state:
                raise StateTransitionError("blocked tasks may resume only at their recorded safe state")
            if not context_refreshed:
                raise StateTransitionError("blocked tasks require a fresh repository context before resuming")
            return replace(current, state=target_state, blocker=None, resume_state=None)

        if target_state is TaskState.DONE:
            return self.complete(current)

        if target_state not in _ALLOWED_TRANSITIONS[current.state]:
            raise StateTransitionError(f"illegal transition: {current.state.value} -> {target_state.value}")

        if current.state is TaskState.VALIDATION and target_state is TaskState.IN_PROGRESS:
            if not failure_note or not failure_note.strip():
                raise StateTransitionError("failed validation requires a root-cause note")
            if not revised_verification_plan or not revised_verification_plan.strip():
                raise StateTransitionError("failed validation requires a revised verification plan")
            return replace(
                current,
                state=target_state,
                last_failure=failure_note.strip(),
                verification_plan_revision=revised_verification_plan.strip(),
            )

        if current.state is TaskState.PR_REVIEW and target_state is TaskState.IN_PROGRESS:
            if not reason or not reason.strip():
                raise StateTransitionError("review rework requires a recorded reason")
            return replace(current, state=target_state, last_failure=reason.strip())

        return replace(current, state=target_state)

    def record_acceptance(
        self,
        current: RuntimeTaskState,
        criterion_id: str,
        evidence: Mapping[str, Any],
    ) -> RuntimeTaskState:
        if current.state in (TaskState.BLOCKED, TaskState.DONE):
            raise StateTransitionError("acceptance evidence cannot be changed while blocked or done")
        if criterion_id not in current.acceptance_criteria:
            raise StateTransitionError(f"unknown acceptance criterion: {criterion_id}")
        if not evidence.get("reference") or not evidence.get("claim") or "head_sha" not in evidence:
            raise StateTransitionError("evidence requires reference, claim, and an explicit head_sha (or null for device evidence)")
        item = dict(evidence)
        item["criterion_id"] = criterion_id
        return replace(
            current,
            met_criteria=current.met_criteria | {criterion_id},
            evidence=current.evidence + (item,),
        )

    def complete(self, current: RuntimeTaskState) -> RuntimeTaskState:
        if current.state is not TaskState.PR_REVIEW:
            raise StateTransitionError("tasks may become done only from pr_review")
        try:
            return replace(current, state=TaskState.DONE)
        except ValueError as error:
            raise StateTransitionError(str(error)) from error
