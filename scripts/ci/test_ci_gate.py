#!/usr/bin/env python3
"""Unit tests for scripts/ci/ci_gate.py on synthetic workflows, runs and job logs."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ci_gate  # noqa: E402

WORKFLOWS = {
    "backend-ci.yml": {
        True: {"pull_request": None, "push": {"branches": ["api"]}},
        "jobs": {"build": {"name": "Build & unit tests"}},
    },
    "frontend-ci.yml": {
        True: {"pull_request": {"paths": ["frontend/**", "web/marketing/**"]}},
        "jobs": {"frontend-build": {"name": "Typecheck, lint, test & build"}},
    },
    "security-ci.yml": {
        True: {"pull_request": None},
        "jobs": {
            "secrets": {"name": "Secret scan"},
            "container-images": {"name": "Container scan (${{ matrix.service }})", "strategy": {"matrix": {"service": ["a", "b"]}}},
        },
    },
    "deploy.yml": {
        True: {"pull_request": {"branches": ["api"], "paths": ["docker/**", "!docker/README.md"]}},
        "jobs": {"build": {"name": "Build images"}, "deploy": {"name": "Deploy", "if": "dispatch"}},
    },
    "mobile-ci.yml": {
        True: ["pull_request"],
        "jobs": {"legacy": {"name": "Legacy mobile (advisory)", "continue-on-error": True}},
    },
    "dispatch-only.yml": {True: {"workflow_dispatch": None}, "jobs": {"x": {}}},
    "ci-gate.yml": {True: {"pull_request": None}, "jobs": {"gate": {"name": "CI gate"}}},
}
POLICY = {
    "ignore_workflows": ["ci-gate.yml"],
    "allowed_skips": [{"workflow": "deploy.yml", "job": "Deploy", "reason": "dispatch only"}],
    "evidence": [
        {"workflow": "backend-ci.yml", "job": "Build & unit tests", "label": "unit tests",
         "pattern": "Unit test evidence: (\\d+) tests", "min": 1},
    ],
}


def required(changed, base="api"):
    return ci_gate.required_checks(WORKFLOWS, POLICY, base, changed)[0]


def run(run_id, status="completed"):
    return {"id": run_id, "status": status}


def job(job_id, name, conclusion="success"):
    return {"id": job_id, "name": name, "conclusion": conclusion}


class FilterPatterns(unittest.TestCase):
    def test_double_star_crosses_directories_and_star_does_not(self):
        self.assertTrue(ci_gate.filter_matches("frontend/apps/web/src/a.ts", ["frontend/**"]))
        self.assertTrue(ci_gate.filter_matches("scripts/backup-run.sh", ["scripts/backup-*.sh"]))
        self.assertFalse(ci_gate.filter_matches("scripts/lib/backup-run.sh", ["scripts/backup-*.sh"]))

    def test_the_last_matching_pattern_wins(self):
        self.assertFalse(ci_gate.filter_matches("docker/README.md", ["docker/**", "!docker/README.md"]))
        self.assertTrue(ci_gate.filter_matches("docker/a.yml", ["docker/**", "!docker/README.md"]))

    def test_unsupported_syntax_is_refused(self):
        with self.assertRaises(ValueError):
            ci_gate.glob_regex("docs/?.md")


class RequiredChecks(unittest.TestCase):
    def test_a_backend_only_change_requires_backend_and_security_but_not_frontend(self):
        req = required(["services/auth-service/src/main/A.java"])
        self.assertEqual(sorted(req), ["backend-ci.yml", "mobile-ci.yml", "security-ci.yml"])

    def test_a_frontend_change_also_requires_frontend_ci(self):
        self.assertIn("frontend-ci.yml", required(["frontend/apps/web/src/a.ts"]))

    def test_branch_and_negated_path_filters_apply(self):
        self.assertIn("deploy.yml", required(["docker/compose.yml"]))
        self.assertNotIn("deploy.yml", required(["docker/README.md"]))
        self.assertNotIn("deploy.yml", required(["docker/compose.yml"], base="master"))

    def test_ignored_and_dispatch_only_workflows_are_never_required(self):
        req = required(["anything.txt"])
        self.assertNotIn("ci-gate.yml", req)
        self.assertNotIn("dispatch-only.yml", req)

    def test_matrix_jobs_match_by_their_name_pattern(self):
        container = next(e for e in required(["x"])["security-ci.yml"] if e.job.startswith("Container scan"))
        self.assertTrue(container.pattern.match("Container scan (auth-service)"))
        self.assertFalse(container.pattern.match("Container scan"))


class Judge(unittest.TestCase):
    LOGS = {11: "x\n2026-10-04T00:00:00Z Unit test evidence: 1415 tests in 12 modules (0 failures, 0 errors, 3 skipped)\n"}

    def judge(self, req, runs, jobs, logs=None):
        logs = self.LOGS if logs is None else logs
        return ci_gate.judge(req, runs, jobs, lambda job_id: logs.get(job_id, ""))

    def backend_only(self, backend_jobs, backend_status="completed"):
        req = required(["services/auth-service/src/main/A.java"])
        runs = {"backend-ci.yml": run(1, backend_status), "security-ci.yml": run(2), "mobile-ci.yml": run(3)}
        jobs = {
            1: backend_jobs,
            2: [job(21, "Secret scan"), job(22, "Container scan (a)"), job(23, "Container scan (b)")],
            3: [job(31, "Legacy mobile (advisory)", "failure")],
        }
        return self.judge(req, runs, jobs)

    def test_a_backend_only_change_with_its_checks_green_passes(self):
        failures, report = self.backend_only([job(11, "Build & unit tests")])
        self.assertEqual(failures, [])
        self.assertIn("backend-ci.yml / Build & unit tests: success (unit tests 1415)", report)
        self.assertIn("mobile-ci.yml / Legacy mobile (advisory): failure (advisory, continue-on-error)", report)

    def test_a_failed_required_job_fails_the_gate(self):
        failures, _ = self.backend_only([job(11, "Build & unit tests", "failure")])
        self.assertEqual(failures, ["backend-ci.yml / Build & unit tests: failure"])

    def test_an_unexpected_skip_fails_the_gate(self):
        failures, _ = self.backend_only([job(11, "Build & unit tests", "skipped")])
        self.assertEqual(failures, ["backend-ci.yml / Build & unit tests: skipped, and the policy allows no skip for it"])

    def test_zero_tests_fail_the_gate(self):
        req = required(["services/a/B.java"])
        runs = {"backend-ci.yml": run(1), "security-ci.yml": run(2), "mobile-ci.yml": run(3)}
        jobs = {1: [job(11, "Build & unit tests")], 2: [job(21, "Secret scan"), job(22, "Container scan (a)")],
                3: [job(31, "Legacy mobile (advisory)")]}
        zero = {11: "Unit test evidence: 0 tests in 0 modules (0 failures, 0 errors, 0 skipped)"}
        failures, _ = self.judge(req, runs, jobs, zero)
        self.assertEqual(len(failures), 1)
        self.assertIn("unit tests 0, below 1", failures[0])
        failures, _ = self.judge(req, runs, jobs, {11: "no evidence line at all"})
        self.assertIn("unit tests 0, below 1", failures[0])

    def test_a_missing_run_job_or_matrix_job_fails_the_gate(self):
        req = required(["services/a/B.java"])
        runs = {"security-ci.yml": run(2), "mobile-ci.yml": run(3)}
        jobs = {2: [job(21, "Secret scan")], 3: [job(31, "Legacy mobile (advisory)")]}
        failures, _ = self.judge(req, runs, jobs)
        self.assertIn("backend-ci.yml: required, but no run for this commit", failures)
        self.assertTrue(any("Container scan" in f and "missing" in f for f in failures))

    def test_a_run_still_in_progress_at_the_deadline_fails_the_gate(self):
        failures, _ = self.backend_only([job(11, "Build & unit tests")], backend_status="in_progress")
        self.assertEqual(failures, ["backend-ci.yml: still in_progress when the gate stopped waiting"])

    def test_a_policy_skip_is_reported_not_failed(self):
        req = required(["docker/compose.yml"])
        runs = {"backend-ci.yml": run(1), "security-ci.yml": run(2), "mobile-ci.yml": run(3), "deploy.yml": run(4)}
        jobs = {1: [job(11, "Build & unit tests")], 2: [job(21, "Secret scan"), job(22, "Container scan (a)")],
                3: [job(31, "Legacy mobile (advisory)")], 4: [job(41, "Build images"), job(42, "Deploy", "skipped")]}
        failures, report = self.judge(req, runs, jobs)
        self.assertEqual(failures, [])
        self.assertIn("deploy.yml / Deploy: skipped (dispatch only)", report)

    def test_evidence_ignores_ansi_colour_codes(self):
        self.assertEqual(ci_gate.evidence_count("\x1b[2m Tests \x1b[22m \x1b[1m\x1b[32m928 passed\x1b[39m", r"Tests\s+(\d+) passed"), 928)


class JobLogDownload(unittest.TestCase):
    def test_the_storage_redirect_is_followed_without_the_token(self):
        seen = []

        class FakeResponse:
            def __init__(self, body):
                self.body = body

            def read(self):
                return self.body

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

        class FakeOpener:
            def open(self, request, timeout):
                seen.append(dict(request.header_items()))
                raise ci_gate.urllib.error.HTTPError(request.full_url, 302, "Found",
                                                     {"Location": "https://storage.example/log?sig=1"}, None)

        def fake_urlopen(request, timeout):
            seen.append(dict(request.header_items()))
            return FakeResponse(b"Unit test evidence: 5 tests")

        original = (ci_gate.urllib.request.build_opener, ci_gate.urllib.request.urlopen)
        ci_gate.urllib.request.build_opener = lambda *handlers: FakeOpener()
        ci_gate.urllib.request.urlopen = fake_urlopen
        try:
            text = ci_gate.GitHub("o/r", "secret-token").job_log(7)
        finally:
            ci_gate.urllib.request.build_opener, ci_gate.urllib.request.urlopen = original
        self.assertEqual(text, "Unit test evidence: 5 tests")
        self.assertIn("Authorization", seen[0])
        self.assertNotIn("Authorization", seen[1])


class Policy(unittest.TestCase):
    def test_a_policy_entry_for_a_missing_workflow_or_job_is_stale(self):
        stale = {"allowed_skips": [{"workflow": "deploy.yml", "job": "Gone", "reason": "x"}],
                 "evidence": [{"workflow": "nope.yml", "job": "x", "label": "l", "pattern": "p", "min": 1}],
                 "ignore_workflows": ["ci-gate.yml"]}
        problems = ci_gate.validate_policy(stale, WORKFLOWS)
        self.assertEqual(problems, ["policy allowed_skips: deploy.yml has no job 'Gone'",
                                    "policy evidence: no workflow nope.yml"])

    def test_the_repository_policy_matches_the_repository_workflows(self):
        policy = json.loads(ci_gate.POLICY.read_text(encoding="utf-8"))
        self.assertEqual(ci_gate.validate_policy(policy, ci_gate.load_workflows()), [])

    def test_every_repository_workflow_filter_uses_supported_syntax(self):
        for name, workflow in ci_gate.load_workflows().items():
            trigger = ci_gate.pull_request_trigger(workflow) or {}
            for key in ("paths", "paths-ignore", "branches", "branches-ignore"):
                for pattern in trigger.get(key) or []:
                    ci_gate.glob_regex(pattern.lstrip("!"))  # raises on unsupported syntax


if __name__ == "__main__":
    unittest.main()
