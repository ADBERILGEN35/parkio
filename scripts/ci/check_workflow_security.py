#!/usr/bin/env python3
"""Workflow supply-chain and script-injection checks (CL-F40).

1. Every third-party action is pinned to a full commit SHA (`owner/repo[/path]@<40 hex>`), and a
   `docker://` action to an image digest (`@sha256:<64 hex>`). The same applies to a job that
   calls a reusable workflow from another repository. GitHub-owned actions (`actions/*`,
   `github/*`) and local ones (`./...`) are exempt; whether to pin those too is a separate policy
   choice (U08, CL-F07R).
2. No script substitutes a dispatch or workflow_call input, the triggering event or any of its
   fields, or the head branch name through an expression (`inputs`, `github.event`,
   `github.head_ref`). Scripts are a step's `run:` and `shell:`, `defaults.run.shell` of the
   workflow and of each job, and the `script` input of actions/github-script. Such values reach
   the script through `env:`, where the shell treats them as data. Context names match without
   regard to case, as GitHub's expressions do, and in dot or index syntax (`inputs['x']`,
   `github['head_ref']`, `toJSON(github.event)`). Values laundered through `env.*`,
   `steps.*.outputs` or `needs.*.outputs` are not traced.
3. A job that runs the Gradle wrapper in a step's `run:` has an `actions/setup-java` step before
   it. Without one Gradle runs on the runner image's default JDK; ubuntu-26.04 ships 25, which
   Gradle 8.11.1 cannot run on (G-04, #486). Making the wrapper executable (`chmod +x gradlew`)
   and comment lines do not count as running it; scripts called from a step are not traced.
4. In a workflow that a pull request triggers, no step saves a Gradle cache on a pull request
   (#653): `actions/setup-java` with `cache: gradle`, or `actions/cache` or `actions/cache/save`
   with a path in a `.gradle` directory. setup-java and actions/cache save in their post step
   whenever their exact key had no entry, pull requests included, and neither has a restore-only
   mode. An entry that a pull request saves serves that pull request only, and setup-java's Gradle
   entry is about 0.5 GB. Such a step needs `github.event_name != 'pull_request'` as an `&&` term of
   its own `if:` or of its job's; a condition with `||` outside parentheses has no such term. Pull
   requests restore with `actions/cache/restore`. Reusable workflows and local actions are not
   traced, nor are other cache actions (gradle/actions/setup-gradle), a path given only through
   GRADLE_USER_HOME, or the pull_request_target trigger.

Usage: check_workflow_security.py [workflow ...]   (default: .github/workflows/*.yml and *.yaml)
Exit 1 lists every problem as file: job: step: reason.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
FIRST_PARTY = ("actions/", "github/")
COMMIT_PIN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$")
DIGEST_PIN = re.compile(r"^docker://[^@\s]+@sha256:[0-9a-f]{64}$")
EXPRESSION = re.compile(r"\$\{\{(.*?)\}\}", re.S)
# `inputs` or `github` as a context, not as a property name (steps.inputs.outputs): the whole
# object (toJSON(inputs), toJSON(github)), every property of github through an object filter
# (github.*), or github.event / github.head_ref in dot or index syntax.
UNTRUSTED = re.compile(
    r"(?<![.\w])inputs\b"
    r"|(?<![.\w])github\b(?!\s*(?:\.|\[))"
    r"|(?<![.\w])github\s*\.\s*\*"
    r"|(?<![.\w])github\s*(?:\.|\[)\s*['\"]?\s*(?:event|head_ref)\b",
    re.I)
# The wrapper as a command word: `./gradlew`, `../gradlew`, `gradlew.bat`. A chmod and its operands
# are removed first, so `chmod +x gradlew && ./gradlew build` still counts.
GRADLE_WRAPPER = re.compile(r"(?<![\w-])(?:[\w.$/{}-]*/)?gradlew(?:\.bat)?(?![\w.-])")
CHMOD = re.compile(r"\bchmod\b[^;&|\n]*")
GRADLE_PATH = re.compile(r"(?:^|[/\\])\.gradle(?:[/\\]|$)")
PULL_REQUEST_GUARD = "github.event_name != 'pull_request'"
# The guard as and_terms() returns it: without blanks and in lower case (GitHub compares strings and
# context names without regard to case), in either operand order.
GUARD_TERMS = {"github.event_name!='pull_request'", "'pull_request'!=github.event_name"}


def action_problem(uses: str) -> str | None:
    uses = uses.strip()
    if uses.startswith("./") or uses.startswith(FIRST_PARTY):
        return None
    if uses.startswith("docker://"):
        return None if DIGEST_PIN.match(uses) else f"{uses} is not pinned to an image digest (@sha256:...)"
    return None if COMMIT_PIN.match(uses) else f"{uses} is not pinned to a full commit SHA"


def script_problems(text: str) -> list[str]:
    found = []
    for match in EXPRESSION.finditer(text):
        expression = " ".join(match.group(1).split())
        if UNTRUSTED.search(expression):
            found.append(f"${{{{ {expression} }}}} is substituted into the script; pass it through env:")
    return found


def runs_gradle(script: str) -> bool:
    for line in script.splitlines():
        if line.lstrip().startswith("#"):
            continue
        if GRADLE_WRAPPER.search(CHMOD.sub("", line)):
            return True
    return False


def triggered_by_pull_requests(workflow: dict) -> bool:
    # PyYAML reads the key `on` as the boolean true (YAML 1.1).
    on = workflow.get("on", workflow.get(True))
    if isinstance(on, str):
        on = [on]
    return isinstance(on, (list, dict)) and "pull_request" in on


def and_terms(condition: object) -> set[str]:
    """The `&&` terms of an `if:` outside parentheses and strings, without blanks, in lower case.

    `&&` binds tighter than `||`, so a condition with `||` outside parentheses is no conjunction:
    `x || always() && guard` runs whenever x holds. Such a condition is one term, the whole of it.
    """
    text = str(condition if condition is not None else "").strip()
    if text.startswith("${{") and text.endswith("}}"):
        text = text[3:-2]
    terms, depth, quoted, start, disjunction = [], 0, False, 0, False
    for index, char in enumerate(text):
        if char == "'":
            quoted = not quoted
        elif not quoted and char in "()":
            depth += 1 if char == "(" else -1
        elif not quoted and depth == 0 and text.startswith("&&", index):
            terms.append(text[start:index])
            start = index + 2
        elif not quoted and depth == 0 and text.startswith("||", index):
            disjunction = True
    terms.append(text[start:])
    if disjunction:
        terms = [text]
    found = set()
    for term in terms:
        term = re.sub(r"\s+", "", term).lower()
        while term.startswith("(") and term.endswith(")") and balanced(term[1:-1]):
            term = term[1:-1]
        found.add(term)
    return found


def balanced(text: str) -> bool:
    depth = 0
    for char in text:
        depth += {"(": 1, ")": -1}.get(char, 0)
        if depth < 0:
            return False
    return depth == 0


def gradle_cache_save(step: dict) -> str | None:
    """How the step saves a Gradle cache in its post step or as its own work, if it does."""
    action = step["uses"].split("@")[0].strip().lower() if isinstance(step.get("uses"), str) else ""
    inputs = step.get("with") if isinstance(step.get("with"), dict) else {}
    if action == "actions/setup-java" and str(inputs.get("cache") or "").strip().lower() == "gradle":
        return "setup-java's `cache: gradle` saves a Gradle cache on a pull request"
    paths = [line.strip() for line in str(inputs.get("path") or "").splitlines()]
    if action in ("actions/cache", "actions/cache/save") \
            and any(GRADLE_PATH.search(line) for line in paths if not line.startswith("!")):
        return f"{action} saves its .gradle path on a pull request"
    return None


def default_shell(holder: dict) -> object:
    defaults = holder.get("defaults")
    run = defaults.get("run") if isinstance(defaults, dict) else None
    return run.get("shell") if isinstance(run, dict) else None


def check_workflow(name: str, workflow: dict) -> list[str]:
    problems = []
    pull_requests = triggered_by_pull_requests(workflow)
    if isinstance(default_shell(workflow), str):
        problems.extend(f"{name}: defaults.run.shell: {problem}" for problem in script_problems(default_shell(workflow)))
    for job_id, job in (workflow.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        if isinstance(default_shell(job), str):
            problems.extend(f"{name}: {job_id}: defaults.run.shell: {problem}"
                            for problem in script_problems(default_shell(job)))
        if isinstance(job.get("uses"), str):
            problem = action_problem(job["uses"])
            if problem:
                problems.append(f"{name}: {job_id}: {problem}")
        java_set_up = False
        for index, step in enumerate(job.get("steps") or []):
            label = f"{name}: {job_id}: step {index + 1} ({step.get('name') or step.get('uses') or 'run'})"
            if isinstance(step.get("uses"), str):
                problem = action_problem(step["uses"])
                if problem:
                    problems.append(f"{label}: {problem}")
                java_set_up = java_set_up or step["uses"].split("@")[0].strip().lower() == "actions/setup-java"
            if isinstance(step.get("run"), str) and runs_gradle(step["run"]) and not java_set_up:
                problems.append(f"{label}: runs gradlew without an earlier actions/setup-java step in this job")
            saves = gradle_cache_save(step) if pull_requests else None
            if saves and not GUARD_TERMS & (and_terms(job.get("if")) | and_terms(step.get("if"))):
                problems.append(f"{label}: {saves}; restore it with actions/cache/restore, and save only under an "
                                f"if: with the && term {PULL_REQUEST_GUARD}")
            for key in ("run", "shell"):
                if isinstance(step.get(key), str):
                    problems.extend(f"{label}: {key}: {problem}" for problem in script_problems(step[key]))
            uses = step.get("uses")
            script = (step.get("with") or {}).get("script") if isinstance(step.get("with"), dict) else None
            if isinstance(uses, str) and uses.split("@")[0].strip().lower() == "actions/github-script" \
                    and isinstance(script, str):
                problems.extend(f"{label}: with.script: {problem}" for problem in script_problems(script))
    return problems


def main(argv: list[str]) -> int:
    workflows = ROOT / ".github" / "workflows"
    paths = [Path(arg) for arg in argv] or sorted([*workflows.glob("*.yml"), *workflows.glob("*.yaml")])
    problems = []
    for path in paths:
        problems.extend(check_workflow(path.name, yaml.safe_load(path.read_text(encoding="utf-8")) or {}))
    if problems:
        print("Workflow security check FAILED:\n  " + "\n  ".join(problems))
        return 1
    print(f"Workflow security check passed: {len(paths)} workflow(s); third-party actions pinned, "
          "no input or event field substituted into a script, Java set up before every Gradle step, "
          "no Gradle cache saved on a pull request.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
