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


def default_shell(holder: dict) -> object:
    defaults = holder.get("defaults")
    run = defaults.get("run") if isinstance(defaults, dict) else None
    return run.get("shell") if isinstance(run, dict) else None


def check_workflow(name: str, workflow: dict) -> list[str]:
    problems = []
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
        for index, step in enumerate(job.get("steps") or []):
            label = f"{name}: {job_id}: step {index + 1} ({step.get('name') or step.get('uses') or 'run'})"
            if isinstance(step.get("uses"), str):
                problem = action_problem(step["uses"])
                if problem:
                    problems.append(f"{label}: {problem}")
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
          "no input or event field substituted into a script.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
