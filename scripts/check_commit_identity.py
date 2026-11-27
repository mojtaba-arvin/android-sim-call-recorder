#!/usr/bin/env python3
"""Validate commit authors and co-author trailers for a GitHub pull request."""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

NAME = "Mojtaba Arvin"
EMAIL = "m.arvin@live.com"
LOGIN = "mojtaba-arvin"
MARKER = "<!-- maintainer-directed-ai-work -->"
AUTO_LABEL = "allow-automation-commits"
AUTO_LOGINS = {"github-actions[bot]", "dependabot[bot]"}
# Co-author trailers have no GitHub account object, so retain explicit,
# conservative name/email markers in addition to account-type checks.
BOT_MARKERS = (
    "[bot]", "dependabot", "github actions", "renovate", "snyk",
    "ai agent", "ai-agent", "agent bot", "copilot", "claude",
    "cursor", "windsurf", "codex", "chatgpt", "gemini", "openai",
    "gpt", "ai assistant", "assistant bot", "codeium", "tabnine",
    "amazon q", "devin", "replit", "sourcegraph", "aider",
    "continue.dev", "junie", "qodo", "sweep",
)


def is_bot(name, email, login=None, account_type=None):
    if (account_type or "").lower() == "bot":
        return True
    if login and login.lower().endswith("[bot]"):
        return True
    value = " ".join(str(part or "") for part in (name, email, login)).lower()
    return any(marker in value for marker in BOT_MARKERS)


def validate(pr, commits):
    errors = []
    labels = {
        str(label.get("name", "")).lower()
        for label in pr.get("labels", [])
    }
    allow_auto = AUTO_LABEL in labels
    maintainer_work = MARKER in (pr.get("body") or "")
    maintainer_commit_seen = False

    for item in commits:
        commit = item.get("commit") or {}
        git_author = commit.get("author") or {}
        github_author = item.get("author") or {}
        name = git_author.get("name") or ""
        email = git_author.get("email") or ""
        login = github_author.get("login")
        account_type = github_author.get("type")
        sha = (item.get("sha") or "unknown")[:12]

        if not name or not email:
            errors.append(sha + ": missing author name/email")

        if is_bot(name, email, login, account_type):
            allowed = (
                allow_auto
                and (login or "").lower() in AUTO_LOGINS
                and (account_type or "").lower() == "bot"
            )
            if not allowed:
                errors.append(
                    f"{sha}: bot/agent author rejected: "
                    f"{name!r} <{email}> login={login!r}"
                )

        if (login or "").lower() == LOGIN:
            maintainer_commit_seen = True
            if name != NAME or email.lower() != EMAIL.lower():
                errors.append(
                    f"{sha}: maintainer account must use {NAME} <{EMAIL}>, "
                    f"got {name!r} <{email}>"
                )
        elif email.lower() == EMAIL.lower():
            errors.append(
                f"{sha}: maintainer email must resolve to {LOGIN}, "
                f"not {login!r}"
            )

        for line in (commit.get("message") or "").splitlines():
            trailer = line.strip()
            if trailer.lower().startswith("co-authored-by:"):
                identity = trailer.split(":", 1)[1].strip()
                if is_bot(identity, ""):
                    errors.append(
                        f"{sha}: bot/AI Co-authored-by trailer rejected: "
                        f"{identity!r}"
                    )

    if maintainer_work and not maintainer_commit_seen:
        errors.append(
            "maintainer-directed AI work marker requires at least one "
            f"commit authored by {LOGIN}"
        )
    return errors


def get_json(url, token):
    request = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "Authorization": "Bearer " + token,
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "commit-identity-policy-check",
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode())


def self_test():
    def item(name, email, login, account_type, message="feat: test",
             committer_login="web-flow", committer_type="User"):
        return {
            "sha": "a" * 40,
            "commit": {
                "author": {"name": name, "email": email},
                "committer": {"name": "GitHub", "email": "noreply@github.com"},
                "message": message,
            },
            "author": {"login": login, "type": account_type},
            "committer": {"login": committer_login, "type": committer_type},
        }

    no_labels = {"body": "", "labels": []}
    assert not validate(no_labels, [
        item(NAME, EMAIL, LOGIN, "User"),
    ])
    assert not validate(no_labels, [
        item("External Human", "human@example.org", "external-human", "User"),
    ])
    assert validate(no_labels, [
        item("Maintainer", "maintainer@example.org", LOGIN, "User",
             committer_login="web-flow"),
    ])  # A web-flow committer does not repair incorrect author metadata.
    assert validate(no_labels, [
        item("Copilot", "bot@example.org", "copilot[bot]", "Bot"),
    ])
    assert validate(no_labels, [
        item("Human", "human@example.org", "human", "User",
             "feat: test\n\nCo-authored-by: Copilot <copilot[bot]@users.noreply.github.com>"),
    ])
    assert validate(no_labels, [
        item("Human", "human@example.org", "human", "User",
             "feat: test\n\nCo-authored-by: Dependabot <support@github.com>"),
    ])
    assert validate(no_labels, [
        item("Human", "human@example.org", "human", "User",
             "feat: test\n\nCo-authored-by: GitHub Actions <noreply@github.com>"),
    ])
    assert not validate(no_labels, [
        item("Human", "human@example.org", "human", "User",
             "feat: test\n\nCo-authored-by: Another Human <other@example.org>"),
    ])
    assert not validate({"body": MARKER, "labels": []}, [
        item(NAME, EMAIL, LOGIN, "User"),
        item("Other Human", "other@example.org", "other-human", "User"),
    ])
    assert validate({"body": MARKER, "labels": []}, [
        item("Other Human", "other@example.org", "other-human", "User"),
    ])
    assert validate(no_labels, [
        item(NAME, "wrong@example.org", LOGIN, "User"),
    ])
    assert validate(no_labels, [
        item("", "", None, None),
    ])

    automation_label = {"body": "", "labels": [{"name": AUTO_LABEL}]}
    assert validate(no_labels, [
        item("github-actions[bot]", "bot@users.noreply.github.com",
             "github-actions[bot]", "Bot"),
    ])
    assert not validate(automation_label, [
        item("github-actions[bot]", "bot@users.noreply.github.com",
             "github-actions[bot]", "Bot"),
    ])
    assert not validate(automation_label, [
        item("dependabot[bot]", "bot@users.noreply.github.com",
             "dependabot[bot]", "Bot"),
    ])
    assert validate(automation_label, [
        item("Copilot", "bot@example.org", "copilot[bot]", "Bot"),
    ])
    assert validate(automation_label, [
        item("Human", "human@example.org", "human", "User",
             "feat: test\n\nCo-authored-by: Dependabot <support@github.com>"),
    ])
    print("PASS: commit identity policy self-tests")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", default=os.getenv("GITHUB_REPOSITORY"))
    parser.add_argument("--pr-number", type=int, default=int(os.getenv("PR_NUMBER", "0")))
    parser.add_argument("--token", default=os.getenv("GITHUB_TOKEN"))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
    if not (args.repository and args.pr_number and args.token):
        if args.self_test and not args.pr_number:
            return 0
        parser.error("--repository, --pr-number and --token are required")

    base = "https://api.github.com/repos/" + args.repository
    pr = get_json(base + "/pulls/" + str(args.pr_number), args.token)
    commits = []
    page = 1
    while True:
        batch = get_json(
            base + f"/pulls/{args.pr_number}/commits?per_page=100&page={page}",
            args.token,
        )
        if not isinstance(batch, list):
            raise RuntimeError("GitHub API returned a non-list commit response")
        commits.extend(batch)
        if len(batch) < 100:
            break
        page += 1

    if not commits:
        print("ERROR: empty PR commit list; refusing to pass")
        return 2

    errors = validate(pr, commits)
    print(f"Checked {len(commits)} commits in {args.repository}#{args.pr_number}")
    if errors:
        print("\n".join("ERROR: " + error for error in errors))
        return 1
    print("PASS: no prohibited bot/agent authors or co-author trailers")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except urllib.error.HTTPError as error:
        print(
            f"ERROR: GitHub API HTTP {error.code}: {error.reason}",
            file=sys.stderr,
        )
        sys.exit(2)
    except (urllib.error.URLError, TimeoutError, ValueError, RuntimeError) as error:
        print(
            f"ERROR: identity validation incomplete: {error}",
            file=sys.stderr,
        )
        sys.exit(2)
