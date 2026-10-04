#!/usr/bin/env python3
"""CI gate: every check a pull request's changes require must run, pass and show its tests ran.

The required checks come from the workflows themselves. A workflow is required when its
pull_request trigger matches the PR's base branch and changed files, the way GitHub decides
whether to run it. Each of its jobs is then a required check (a matrix job by its name pattern).
.github/ci-gate-policy.json adds what the YAML cannot say:
- jobs that skip by design on pull requests (dispatch- or schedule-only jobs);
- the log line that proves a job ran tests, with a minimum count.

The gate fails when a required workflow never ran, a required job is missing, failed, was
cancelled or timed out, was skipped without a policy reason, or shows less test evidence than the
policy asks. Jobs with continue-on-error are advisory and only reported.

Usage (CI): ci_gate.py --repository OWNER/REPO --head-sha SHA --base-ref api [--timeout-minutes 80]
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
POLICY = ROOT / ".github" / "ci-gate-policy.json"
ANSI = re.compile(r"\x1b\[[0-9;]*m")
TEMPLATE = re.compile(r"\$\{\{.*?\}\}")
UNSUPPORTED_GLOB = re.compile(r"[?+\[\]]")


# --- GitHub filter patterns -------------------------------------------------------------------

def glob_regex(pattern: str) -> re.Pattern[str]:
    """`**` matches across directories (`**/` also matches none), `*` within one directory.
    Other glob syntax is refused, not guessed."""
    if UNSUPPORTED_GLOB.search(pattern):
        raise ValueError(f"filter pattern {pattern!r} uses syntax the gate does not implement")
    out, i = [], 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out.append("(?:.*/)?")
            i += 3
        elif pattern.startswith("**", i):
            out.append(".*")
            i += 2
        elif pattern[i] == "*":
            out.append("[^/]*")
            i += 1
        else:
            out.append(re.escape(pattern[i]))
            i += 1
    return re.compile("".join(out) + r"\Z")


def filter_matches(value: str, patterns: list[str]) -> bool:
    """GitHub semantics: patterns apply in order, a `!` pattern excludes, the last match wins."""
    matched = False
    for pattern in patterns:
        negative = pattern.startswith("!")
        if glob_regex(pattern[1:] if negative else pattern).match(value):
            matched = not negative
    return matched


# --- What the changes require -------------------------------------------------------------------

@dataclass
class Expected:
    workflow: str
    job: str
    pattern: re.Pattern[str]
    advisory: bool = False
    skip_reason: str | None = None
    evidence: list[dict] = field(default_factory=list)


def pull_request_trigger(workflow: dict) -> dict | None:
    on = workflow.get(True, workflow.get("on"))
    if isinstance(on, str):
        on = {on: None}
    elif isinstance(on, list):
        on = {name: None for name in on}
    if not isinstance(on, dict) or "pull_request" not in on:
        return None
    return on["pull_request"] or {}


def triggered(trigger: dict, base_ref: str, changed: list[str]) -> tuple[bool, str]:
    if "branches" in trigger and not filter_matches(base_ref, trigger["branches"]):
        return False, f"base branch {base_ref} is outside its branches filter"
    if "branches-ignore" in trigger and filter_matches(base_ref, trigger["branches-ignore"]):
        return False, f"base branch {base_ref} is in its branches-ignore filter"
    if "paths" in trigger and not any(filter_matches(path, trigger["paths"]) for path in changed):
        return False, "no changed path matches its paths filter"
    if "paths-ignore" in trigger and all(filter_matches(path, trigger["paths-ignore"]) for path in changed):
        return False, "every changed path matches its paths-ignore filter"
    return True, "its pull_request trigger matches"


def job_pattern(name: str, has_matrix: bool) -> re.Pattern[str]:
    if TEMPLATE.search(name):
        parts = TEMPLATE.split(name)
        return re.compile(".+".join(re.escape(part) for part in parts) + r"\Z")
    if has_matrix:
        return re.compile(re.escape(name) + r" \(.+\)\Z")
    return re.compile(re.escape(name) + r"\Z")


def load_workflows(directory: Path = WORKFLOWS) -> dict[str, dict]:
    return {path.name: yaml.safe_load(path.read_text(encoding="utf-8")) for path in sorted(directory.glob("*.yml"))}


def validate_policy(policy: dict, workflows: dict[str, dict]) -> list[str]:
    """A policy entry that names a workflow or job that does not exist is stale."""
    problems = []
    for section in ("allowed_skips", "evidence"):
        for entry in policy.get(section, []):
            workflow = workflows.get(entry["workflow"])
            if workflow is None:
                problems.append(f"policy {section}: no workflow {entry['workflow']}")
                continue
            names = [job.get("name", job_id) for job_id, job in workflow.get("jobs", {}).items()]
            if entry["job"] not in names:
                problems.append(f"policy {section}: {entry['workflow']} has no job {entry['job']!r}")
    for name in policy.get("ignore_workflows", []):
        if name not in workflows:
            problems.append(f"policy ignore_workflows: no workflow {name}")
    return problems


def required_checks(
    workflows: dict[str, dict], policy: dict, base_ref: str, changed: list[str]
) -> tuple[dict[str, list[Expected]], list[str]]:
    required: dict[str, list[Expected]] = {}
    notes: list[str] = []
    ignored = set(policy.get("ignore_workflows", []))
    skips = {(e["workflow"], e["job"]): e["reason"] for e in policy.get("allowed_skips", [])}
    evidence: dict[tuple[str, str], list[dict]] = {}
    for entry in policy.get("evidence", []):
        evidence.setdefault((entry["workflow"], entry["job"]), []).append(entry)
    for name, workflow in workflows.items():
        trigger = pull_request_trigger(workflow)
        if name in ignored or trigger is None:
            continue
        runs, why = triggered(trigger, base_ref, changed)
        notes.append(f"{name}: {'required' if runs else 'not required'} ({why})")
        if not runs:
            continue
        expected = []
        for job_id, job in workflow.get("jobs", {}).items():
            job_name = job.get("name", job_id)
            has_matrix = bool((job.get("strategy") or {}).get("matrix"))
            expected.append(
                Expected(
                    workflow=name,
                    job=job_name,
                    pattern=job_pattern(job_name, has_matrix),
                    advisory=job.get("continue-on-error") is True,
                    skip_reason=skips.get((name, job_name)),
                    evidence=evidence.get((name, job_name), []),
                )
            )
        required[name] = expected
    return required, notes


# --- Judging what ran ------------------------------------------------------------------------------

def evidence_count(log: str, pattern: str) -> int:
    return sum(int(match) for match in re.findall(pattern, ANSI.sub("", log)))


def judge(
    required: dict[str, list[Expected]],
    runs: dict[str, dict],
    jobs: dict[int, list[dict]],
    job_log,
) -> tuple[list[str], list[str]]:
    """runs: workflow file name -> latest run; jobs: run id -> its jobs; job_log(job_id) -> text."""
    failures: list[str] = []
    report: list[str] = []
    for workflow, expected in sorted(required.items()):
        run = runs.get(workflow)
        if run is None:
            failures.append(f"{workflow}: required, but no run for this commit")
            continue
        if run.get("status") != "completed":
            failures.append(f"{workflow}: still {run.get('status')} when the gate stopped waiting")
            continue
        run_jobs = jobs.get(run["id"], [])
        for exp in expected:
            matched = [job for job in run_jobs if exp.pattern.match(job["name"])]
            if not matched:
                failures.append(f"{workflow} / {exp.job}: required job missing from run {run['id']}")
                continue
            for job in matched:
                label = f"{workflow} / {job['name']}"
                conclusion = job.get("conclusion")
                if exp.advisory:
                    report.append(f"{label}: {conclusion} (advisory, continue-on-error)")
                elif conclusion == "skipped":
                    if exp.skip_reason:
                        report.append(f"{label}: skipped ({exp.skip_reason})")
                    else:
                        failures.append(f"{label}: skipped, and the policy allows no skip for it")
                elif conclusion != "success":
                    failures.append(f"{label}: {conclusion}")
                else:
                    shown = []
                    for rule in exp.evidence:
                        count = evidence_count(job_log(job["id"]), rule["pattern"])
                        shown.append(f"{rule['label']} {count}")
                        if count < rule["min"]:
                            failures.append(
                                f"{label}: {rule['label']} {count}, below {rule['min']} (pattern {rule['pattern']!r})"
                            )
                    report.append(f"{label}: success" + (f" ({', '.join(shown)})" if shown else ""))
    return failures, report


# --- GitHub API ---------------------------------------------------------------------------------------

class _KeepRedirect(urllib.request.HTTPRedirectHandler):
    """Surface a redirect instead of following it with the Authorization header still attached."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401, N802
        return None


class GitHub:
    """REST reads with GH_TOKEN in CI; without it (local dry runs) through the gh CLI's own login."""

    def __init__(self, repository: str, token: str | None):
        self.repository = repository
        self.token = token

    def _get(self, path: str, raw: bool = False):
        """One GET, retried twice on a transient error (a connection error or an HTTP 5xx)."""
        for attempt in range(3):
            try:
                return self._get_once(path, raw)
            except urllib.error.HTTPError as error:
                if error.code < 500 or attempt == 2:
                    raise
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                if attempt == 2:
                    raise
            time.sleep(5 * (attempt + 1))
        raise AssertionError("unreachable")

    def _get_once(self, path: str, raw: bool):
        if not self.token:
            body = subprocess.run(["gh", "api", f"repos/{self.repository}/{path}"], check=True,
                                  capture_output=True).stdout
            return body.decode("utf-8", "replace") if raw else json.loads(body)
        request = urllib.request.Request(
            f"https://api.github.com/repos/{self.repository}/{path}",
            headers={"Authorization": f"Bearer {self.token}", "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28"},
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            body = response.read()
        return body.decode("utf-8", "replace") if raw else json.loads(body)

    def latest_runs(self, head_sha: str) -> dict[str, dict]:
        runs: dict[str, dict] = {}
        data = self._get(f"actions/runs?head_sha={head_sha}&event=pull_request&per_page=100")
        for run in data.get("workflow_runs", []):
            name = Path(run.get("path", "")).name
            if name and (name not in runs or run["created_at"] > runs[name]["created_at"]):
                runs[name] = run
        return runs

    def jobs(self, run_id: int) -> list[dict]:
        return self._get(f"actions/runs/{run_id}/jobs?filter=latest&per_page=100").get("jobs", [])

    def job_log(self, job_id: int) -> str:
        """The logs endpoint redirects to a pre-signed storage URL, which must not receive the token."""
        if not self.token:
            return self._get(f"actions/jobs/{job_id}/logs", raw=True)
        request = urllib.request.Request(
            f"https://api.github.com/repos/{self.repository}/actions/jobs/{job_id}/logs",
            headers={"Authorization": f"Bearer {self.token}", "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28"},
        )
        try:
            with urllib.request.build_opener(_KeepRedirect).open(request, timeout=60) as response:
                return response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as error:
            if error.code not in (301, 302, 303, 307, 308):
                raise
            location = error.headers["Location"]
        with urllib.request.urlopen(urllib.request.Request(location), timeout=120) as response:
            return response.read().decode("utf-8", "replace")


def changed_files(base_ref: str) -> list[str]:
    base = subprocess.run(["git", "merge-base", "HEAD", f"origin/{base_ref}"], check=True,
                          capture_output=True, text=True, cwd=ROOT).stdout.strip()
    out = subprocess.run(["git", "diff", "--name-only", "--no-renames", f"{base}...HEAD"], check=True,
                         capture_output=True, text=True, cwd=ROOT).stdout
    return [line for line in out.splitlines() if line]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="CI gate")
    parser.add_argument("--repository", required=True)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument("--base-ref", required=True)
    parser.add_argument("--timeout-minutes", type=float, default=80)
    parser.add_argument("--poll-seconds", type=float, default=30)
    parser.add_argument("--changed-file-list", help="file with one changed path per line (local dry runs)")
    args = parser.parse_args(argv)

    workflows = load_workflows()
    policy = json.loads(POLICY.read_text(encoding="utf-8"))
    problems = validate_policy(policy, workflows)
    if problems:
        print("CI gate policy is stale:\n  " + "\n  ".join(problems))
        return 1
    if args.changed_file_list:
        changed = [line for line in Path(args.changed_file_list).read_text(encoding="utf-8").splitlines() if line]
    else:
        changed = changed_files(args.base_ref)
    required, notes = required_checks(workflows, policy, args.base_ref, changed)
    print(f"{len(changed)} changed file(s) against {args.base_ref}.")
    print("Workflows:\n  " + "\n  ".join(notes))

    github = GitHub(args.repository, os.environ.get("GH_TOKEN"))
    deadline = time.monotonic() + args.timeout_minutes * 60
    while True:
        runs = github.latest_runs(args.head_sha)
        waiting = [name for name in required if runs.get(name, {}).get("status") != "completed"]
        if not waiting or time.monotonic() >= deadline:
            break
        print(f"Waiting for {len(waiting)} workflow(s): {', '.join(sorted(waiting))}", flush=True)
        time.sleep(args.poll_seconds)

    jobs = {run["id"]: github.jobs(run["id"]) for name, run in runs.items() if name in required}
    logs: dict[int, str] = {}

    def job_log(job_id: int) -> str:
        if job_id not in logs:
            try:
                logs[job_id] = github.job_log(job_id)
            except (urllib.error.URLError, OSError) as error:
                print(f"Could not read the log of job {job_id}: {error}")
                logs[job_id] = ""
        return logs[job_id]

    failures, report = judge(required, runs, jobs, job_log)
    print("Checks:\n  " + "\n  ".join(report or ["(none)"]))
    if failures:
        print("CI gate FAILED:\n  " + "\n  ".join(failures))
        return 1
    judged = sum(1 for line in report if ": " in line)
    print(f"CI gate passed: {judged} job(s) judged, from {sum(len(v) for v in required.values())} job "
          f"definition(s) in {len(required)} workflow(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
