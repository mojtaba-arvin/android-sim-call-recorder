#!/usr/bin/env python3
"""Validate versioned agent contracts and SHA-bound evidence metadata."""
from __future__ import annotations

import json
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

SHA40 = re.compile(r"^[0-9a-f]{40}$")
ACCESS = {"read", "mutation", "high_risk_mutation", "ci_retry"}


def load_object(path: Path, errors: list[str]) -> dict[str, Any] | None:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        errors.append(f"{path}: invalid JSON: {exc}")
        return None
    if not isinstance(value, dict):
        errors.append(f"{path}: top-level value must be an object")
        return None
    return value


def validate_tool_inventory(root: Path) -> list[str]:
    errors: list[str] = []
    path = root / "agent-system/tooling/github-capability-map.v1.json"
    if not path.exists():
        return errors
    data = load_object(path, errors)
    if data is None:
        return errors

    tools = data.get("tools")
    if not isinstance(tools, list) or not tools:
        errors.append("tool inventory must contain a non-empty tools array")
        return errors
    names = [item.get("name") for item in tools if isinstance(item, dict)]
    valid_names = [name for name in names if isinstance(name, str) and name.strip()]
    if len(names) != len(tools) or len(valid_names) != len(names):
        errors.append("every tool entry must have a non-empty string name")
    if len(valid_names) != len(set(valid_names)):
        errors.append("tool inventory contains duplicate names")
    if data.get("github_tool_count") != len(tools):
        errors.append("github_tool_count differs from tools array length")
    for item in tools:
        if isinstance(item, dict):
            if item.get("access") not in ACCESS:
                errors.append(f"tool {item.get('name')!r} has an unknown access class")
            if not isinstance(item.get("summary"), str) or not item["summary"].strip():
                errors.append(f"tool {item.get('name')!r} has no summary")

    verification = data.get("verification")
    if not isinstance(verification, dict):
        errors.append("tool inventory is missing verification metadata")
    else:
        if verification.get("exact_name_parity") is not True:
            errors.append("live tool catalog parity is not verified")
        if verification.get("live_catalog_count") != len(tools):
            errors.append("live catalog count differs from recorded tool count")
        if verification.get("recorded_catalog_count") != len(tools):
            errors.append("recorded catalog count differs from tools array length")
        if verification.get("missing_tools") or verification.get("stale_tools"):
            errors.append("tool inventory reports missing or stale tool names")
        try:
            datetime.fromisoformat(str(verification.get("refreshed_at", "")).replace("Z", "+00:00"))
        except ValueError:
            errors.append("verification.refreshed_at must be ISO-8601")

    try:
        datetime.fromisoformat(str(data.get("captured_at", "")).replace("Z", "+00:00"))
    except ValueError:
        errors.append("captured_at must be ISO-8601")
    source_sha = data.get("source_foundation_head_sha")
    if source_sha and (not isinstance(source_sha, str) or not SHA40.fullmatch(source_sha)):
        errors.append("source_foundation_head_sha must be a full commit SHA")

    high_risk = data.get("high_risk_tools", [])
    if not isinstance(high_risk, list) or any(not isinstance(name, str) for name in high_risk) or not set(high_risk).issubset(set(valid_names)):
        errors.append("high_risk_tools must reference inventoried tools only")

    roles = data.get("roles")
    if not isinstance(roles, dict) or not roles:
        errors.append("tool inventory must define role grants")
    else:
        known = set(valid_names) | ACCESS
        for role, grants in roles.items():
            if not isinstance(grants, dict):
                errors.append(f"role {role!r} must define allow and deny")
                continue
            allow, deny = grants.get("allow"), grants.get("deny")
            if not isinstance(allow, list) or not isinstance(deny, list):
                errors.append(f"role {role!r} allow/deny values must be arrays")
                continue
            if any(not isinstance(value, str) for value in allow + deny):
                errors.append(f"role {role!r} capabilities must be strings")
                continue
            if set(allow) & set(deny):
                errors.append(f"role {role!r} both allows and denies the same capability")
            unknown_allow = set(allow) - known
            if unknown_allow:
                errors.append(f"role {role!r} allows unknown capabilities: {sorted(unknown_allow)}")
        implementer = roles.get("implementer", {})
        reviewer = roles.get("reviewer", {})
        release_manager = roles.get("release_manager", {})
        if not isinstance(implementer, dict) or "merge_pull_request" not in implementer.get("deny", []):
            errors.append("implementer must explicitly deny PR merges")
        if not isinstance(reviewer, dict) or "approve_own_pull_request" not in reviewer.get("deny", []):
            errors.append("reviewer must explicitly deny self-approval")
        if not isinstance(release_manager, dict) or release_manager.get("allow") != []:
            errors.append("release_manager must have no default grants")

    constraints = data.get("argument_constraints")
    required = {"update_file", "update_issue", "update_pull_request", "merge_pull_request", "enable_auto_merge"}
    if not isinstance(constraints, dict) or not required.issubset(constraints):
        errors.append("argument_constraints is missing high-risk operation policies")
    if not isinstance(data.get("global_invariants"), list) or len(data["global_invariants"]) < 5:
        errors.append("global_invariants must contain at least five rules")

    markdown = root / "agent-system/tooling/GITHUB_TOOL_CAPABILITY_INVENTORY.md"
    if not markdown.is_file():
        errors.append("human-readable GitHub tool inventory is missing")
    else:
        text = markdown.read_text(encoding="utf-8")
        if "github-capability-map.v1.json" not in text or "89 GitHub tools" not in text:
            errors.append("human-readable inventory must link and describe the captured machine-readable catalog")
    return errors


def parse_changed_paths(stdout: bytes) -> list[str]:
    """Parse git diff --name-only -z output without splitting filenames on whitespace."""
    return [
        item.decode("utf-8", errors="replace")
        for item in stdout.split(b"\x00")
        if item
    ]


def validate_benchmark(root: Path) -> list[str]:
    errors: list[str] = []
    path = root / "agent-system/benchmarks/v1/tasks.json"
    if not path.exists():
        return errors
    data = load_object(path, errors)
    if data is None:
        return errors

    source_sha = data.get("source_head_sha")
    if not isinstance(source_sha, str) or not SHA40.fullmatch(source_sha):
        errors.append("benchmark source_head_sha must be a full commit SHA")

    rubric = data.get("rubric")
    if not isinstance(rubric, list) or not rubric:
        errors.append("benchmark rubric must be non-empty")
    else:
        weights = [item.get("weight") for item in rubric if isinstance(item, dict)]
        if len(weights) != len(rubric) or any(not isinstance(weight, int) for weight in weights):
            errors.append("rubric weights must be integers")
        elif sum(weights) != 100:
            errors.append(f"rubric weights must sum to 100, got {sum(weights)}")

    hard_fails = data.get("global_hard_fail_conditions")
    if not isinstance(hard_fails, list) or not hard_fails:
        errors.append("benchmark must define a non-empty global hard-fail conditions array")
    policy = data.get("evaluation_policy", {})
    if not isinstance(policy, dict):
        errors.append("evaluation_policy must be an object")
        policy = {}
    for key in ("hard_fail_overrides_score", "must_refresh_live_state_before_mutation",
                "evidence_must_be_sha_bound", "partial_or_unknown_evidence_must_not_be_promoted_to_success"):
        if policy.get(key) is not True:
            errors.append(f"evaluation_policy.{key} must be true")

    tasks = data.get("tasks")
    if not isinstance(tasks, list) or not tasks:
        errors.append("benchmark tasks must be non-empty")
        return errors
    ids = [task.get("id") for task in tasks if isinstance(task, dict)]
    valid_ids = [task_id for task_id in ids if isinstance(task_id, str) and task_id]
    if len(ids) != len(tasks) or len(valid_ids) != len(ids):
        errors.append("every benchmark task must have a non-empty string id")
    if len(valid_ids) != len(set(valid_ids)):
        errors.append("benchmark contains duplicate task ids")
    for task in tasks:
        if not isinstance(task, dict):
            continue
        for key in ("required_constraints", "expected_actions", "forbidden_actions", "acceptance_evidence"):
            if not isinstance(task.get(key), list) or not task[key]:
                errors.append(f"task {task.get('id')!r} must define non-empty {key}")

    snapshot_ref = data.get("context_snapshot")
    if not isinstance(snapshot_ref, str) or not snapshot_ref:
        errors.append("benchmark context_snapshot must be a repository-relative path")
        return errors
    snapshot = (root / snapshot_ref).resolve()
    if root.resolve() not in snapshot.parents or not snapshot.is_file():
        errors.append("context_snapshot must resolve to an existing file inside the repository")
        return errors
    text = snapshot.read_text(encoding="utf-8")
    match = re.search(r"^\*\*Source HEAD:\*\*\s+\x60([0-9a-f]{40})\x60", text, re.MULTILINE)
    if not match:
        errors.append("context snapshot must declare a full Source HEAD SHA")
    elif isinstance(source_sha, str) and match.group(1) != source_sha:
        errors.append("benchmark source_head_sha differs from context snapshot Source HEAD")
    if isinstance(source_sha, str) and SHA40.fullmatch(source_sha):
        ancestor = subprocess.run(
            ["git", "merge-base", "--is-ancestor", source_sha, "HEAD"],
            cwd=root, capture_output=True, check=False,
        )
        if ancestor.returncode != 0:
            errors.append("benchmark source_head_sha is not an ancestor of the checked-out commit")
        else:
            changed = subprocess.run(
                ["git", "diff", "--name-only", "-z", source_sha, "HEAD"],
                cwd=root, capture_output=True, check=False,
            )
            if changed.returncode != 0:
                errors.append("could not compare the checked-out tree with benchmark source_head_sha")
            else:
                paths = parse_changed_paths(changed.stdout)
                unexpected = [
                    item for item in paths
                    if not item.startswith(("agent-system/context/", "agent-system/benchmarks/"))
                ]
                if unexpected:
                    errors.append(
                        "Foundation changed outside the context/benchmark snapshot paths since source_head_sha; "
                        f"refresh the context snapshot before accepting it: {unexpected}"
                    )
    if "More than 30 minutes have elapsed since capture" not in text:
        errors.append("context snapshot must define its freshness invalidation window")
    return errors


def validate_agent_instructions(root: Path) -> list[str]:
    path = root / "AGENTS.md"
    if not path.exists():
        return []
    text = path.read_text(encoding="utf-8")
    required = [
        ".github/AGENT_GITHUB_WORKFLOW.md",
        "docs/RD_AGENT_CHARTER.md",
        "docs/RD_AGENT_TASK_CONTRACT.md",
    ]
    if (root / "agent-system/tooling/github-capability-map.v1.json").exists():
        required.extend((
            "agent-system/tooling/GITHUB_TOOL_CAPABILITY_INVENTORY.md",
            "agent-system/tooling/github-capability-map.v1.json",
        ))
    return [f"AGENTS.md is missing canonical reference {item!r}" for item in required if item not in text]


def validate_repository(root: Path) -> list[str]:
    return validate_agent_instructions(root) + validate_tool_inventory(root) + validate_benchmark(root)


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    errors = validate_repository(root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("Agent-system contracts are structurally consistent.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
