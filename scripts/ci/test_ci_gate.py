#!/usr/bin/env python3
"""Unit tests for scripts/ci/ci_gate.py on synthetic workflows, runs and job logs."""
from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ci_gate  # noqa: E402

# Runs the given unittest suites in one process and records every repository file the process reads:
# each file opened for reading (an audit hook sees open(), Path.read_text/read_bytes and os.open), and
# the source of each imported module (an import may read only its cached bytecode). Writes
# {"reads": [repository paths], "tests": {suite: tests run}} as JSON to the file it is given.
SUITE_READ_RECORDER = r"""
import importlib.util, io, json, os, sys, unittest

root, out, suites = os.path.realpath(sys.argv[1]), sys.argv[2], sys.argv[3:]
reads = set()


def record(event, args):
    if event != "open" or not args or not isinstance(args[0], (str, bytes, os.PathLike)):
        return
    mode = args[1] if len(args) > 1 else None
    flags = args[2] if len(args) > 2 else 0
    if isinstance(mode, str) and set(mode) & set("wax+"):
        return
    if mode is None and isinstance(flags, int) and flags & (os.O_WRONLY | os.O_RDWR):
        return
    name = os.fsdecode(args[0])
    if not os.path.isabs(name):
        return  # the suites name repository files by absolute path; a bare name is relative to a dir_fd
    path = os.path.realpath(name)
    if path.startswith(root + os.sep) and os.path.isfile(path) and "__pycache__" not in path.split(os.sep):
        reads.add(os.path.relpath(path, root).replace(os.sep, "/"))


sys.addaudithook(record)
tests = {}
for index, suite in enumerate(suites):
    spec = importlib.util.spec_from_file_location(f"source_guard_suite_{index}", os.path.join(root, suite))
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    result = unittest.TextTestRunner(stream=io.StringIO()).run(unittest.defaultTestLoader.loadTestsFromModule(module))
    tests[suite] = result.testsRun
for module in list(sys.modules.values()):
    source = getattr(module, "__file__", None)
    if source:
        path = os.path.realpath(source)
        if path.startswith(root + os.sep):
            reads.add(os.path.relpath(path, root).replace(os.sep, "/"))
with open(out, "w", encoding="utf-8") as handle:
    json.dump({"reads": sorted(reads), "tests": tests}, handle)
"""

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

    def test_double_star_slash_also_matches_no_directory(self):
        self.assertTrue(ci_gate.filter_matches("services/Dockerfile", ["services/**/Dockerfile"]))
        self.assertTrue(ci_gate.filter_matches("services/a/b/Dockerfile", ["services/**/Dockerfile"]))
        self.assertTrue(ci_gate.filter_matches("README.md", ["**/README.md"]))
        self.assertTrue(ci_gate.filter_matches("docs/x/README.md", ["**/README.md"]))
        self.assertFalse(ci_gate.filter_matches("services/Dockerfile.dev", ["services/**/Dockerfile"]))

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

    def test_the_repository_evidence_patterns_read_real_summary_lines(self):
        policy = json.loads(ci_gate.POLICY.read_text(encoding="utf-8"))
        pattern = {rule["label"]: rule["pattern"] for rule in policy["evidence"]}
        self.assertEqual(ci_gate.evidence_count("      Tests  928 passed (928)", pattern["vitest tests"]), 928)
        self.assertEqual(ci_gate.evidence_count("      Tests  925 passed | 3 skipped (928)", pattern["vitest tests"]), 925)
        self.assertEqual(ci_gate.evidence_count("Tests:       560 passed, 560 total", pattern["jest tests"]), 560)
        self.assertEqual(ci_gate.evidence_count("Tests:       2 skipped, 560 passed, 562 total", pattern["jest tests"]), 560)
        self.assertEqual(ci_gate.evidence_count(
            "Unit test evidence: 2898 tests in 12 modules (0 failures, 0 errors, 4 skipped)", pattern["unit tests"]), 2898)

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


class TransientErrors(unittest.TestCase):
    def test_a_transient_server_error_is_retried(self):
        calls = []

        def flaky(path, raw):
            calls.append(path)
            if len(calls) == 1:
                raise ci_gate.urllib.error.HTTPError("u", 502, "Bad Gateway", {}, None)
            return {"ok": True}

        github = ci_gate.GitHub("o/r", "t")
        github._get_once = flaky
        original_sleep = ci_gate.time.sleep
        ci_gate.time.sleep = lambda seconds: None
        try:
            self.assertEqual(github._get("actions/runs"), {"ok": True})
        finally:
            ci_gate.time.sleep = original_sleep
        self.assertEqual(len(calls), 2)

    def test_a_client_error_is_not_retried(self):
        github = ci_gate.GitHub("o/r", "t")
        calls = []

        def forbidden(path, raw):
            calls.append(path)
            raise ci_gate.urllib.error.HTTPError("u", 403, "Forbidden", {}, None)

        github._get_once = forbidden
        with self.assertRaises(ci_gate.urllib.error.HTTPError):
            github._get("actions/runs")
        self.assertEqual(len(calls), 1)


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


class RestoreDrillSourceGuards(unittest.TestCase):
    """#609, on the repository's own workflows: the drill's source-scanning helper suites run on a change
    to any file they read, in a light workflow that the gate requires from its filter. The full Docker
    drill keeps its narrow filter. #640: the filter is checked against what the suites actually read,
    and each suite has its own test minimum."""

    GUARDS = "restore-drill-01-source-guards.yml"
    DRILL = "restore-drill-01-procedure.yml"
    JOB = "Source-scanning helper tests (no Docker)"
    SUITES = ("scripts/test-recovery-evidence-contract.py", "scripts/test-recovery-persist-protocol.py")
    PREFLIGHT = "scripts/test-restore-safe-preflight.sh"
    _recorded = None

    @classmethod
    def setUpClass(cls):
        cls.workflows = ci_gate.load_workflows()
        cls.policy = json.loads(ci_gate.POLICY.read_text(encoding="utf-8"))

    def required(self, path, base="api"):
        return ci_gate.required_checks(self.workflows, self.policy, base, [path])[0]

    def guard_job(self):
        (expected,) = self.required("services/user-service/src/main/resources/application.yml")[self.GUARDS]
        return expected

    def recorded(self):
        """The repository files the two suites read, and the tests each one ran (one run per class)."""
        if RestoreDrillSourceGuards._recorded is None:
            with tempfile.TemporaryDirectory() as tmp:
                out = Path(tmp) / "recorded.json"
                subprocess.run([sys.executable, "-c", SUITE_READ_RECORDER, str(ci_gate.ROOT), str(out), *self.SUITES],
                               cwd=ci_gate.ROOT, check=True, capture_output=True, text=True, timeout=600)
                RestoreDrillSourceGuards._recorded = json.loads(out.read_text(encoding="utf-8"))
        return RestoreDrillSourceGuards._recorded

    def suite_labels(self):
        """{suite: label} from the guard job's `suite "<label>" <suite>` lines."""
        script = "\n".join(step.get("run", "") for step in self.workflows[self.GUARDS]["jobs"]["source-guards"]["steps"])
        return {suite: label for label, suite in re.findall(r'^\s*suite "([^"]+)" (\S+)$', script, re.M)}

    def evidence_log(self, counts):
        labels = self.suite_labels()
        return "".join(f"2026-11-25T10:00:01.0000000Z {labels[suite]} suite evidence: {count} tests\n"
                       for suite, count in counts.items())

    def test_a_change_to_a_source_the_suites_read_requires_the_guards_and_not_the_drill(self):
        for path in (
            # recovery_persist_protocol.py reads the auth application.yml and the auth main Java tree.
            "services/auth-service/src/main/resources/application.yml",
            "services/auth-service/src/main/java/com/parkio/auth/application/durable/CheckpointWatermark.java",
            # Its fixture-key scan reads every services/*/src/main tree, docker/ and .github/.
            "services/user-service/src/main/resources/application.yml",
            "services/gateway-service/src/main/java/com/parkio/gateway/GatewayServiceApplication.java",
            "docker/docker-compose.apps.yml",
            "docker/caddy/Caddyfile",
            ".github/workflows/frontend-ci.yml",
            ".github/ci-gate-policy.json",
        ):
            for base in ("api", "master"):
                with self.subTest(path=path, base=base):
                    required = self.required(path, base)
                    self.assertIn(self.GUARDS, required)
                    self.assertNotIn(self.DRILL, required)

    def test_the_drill_keeps_its_own_paths_and_the_guards_run_with_it(self):
        for path in (
            "services/auth-service/src/main/java/com/parkio/auth/application/AccountErasureApplicationService.java",
            "services/parking-service/src/main/resources/db/migration/V10__create_idempotency_records.sql",
            "services/auth-service/src/test/resources/durable-erasure-evidence/v2/producer.json",
            "docker/docker-compose.yml",
            "scripts/lib/recovery_evidence_contract.py",
            "scripts/lib/recovery_persist_protocol.py",
            "scripts/test-recovery-evidence-contract.py",
        ):
            with self.subTest(path=path):
                required = self.required(path)
                self.assertIn(self.DRILL, required)
                self.assertIn(self.GUARDS, required)

    def test_a_change_the_suites_do_not_read_requires_neither(self):
        for path in (
            "frontend/apps/web/src/main.tsx",
            "docs/operations/ci-gate.md",
            "services/auth-service/src/test/java/com/parkio/auth/AuthServiceApplicationTests.java",
            "services/auth-service/build.gradle.kts",
            "scripts/smoke-hosted-beta.sh",
        ):
            with self.subTest(path=path):
                required = self.required(path)
                self.assertNotIn(self.GUARDS, required)
                self.assertNotIn(self.DRILL, required)

    def test_the_gate_requires_the_guard_job_without_a_skip(self):
        expected = self.guard_job()
        self.assertEqual(expected.job, self.JOB)
        self.assertIsNone(expected.skip_reason)
        self.assertFalse(expected.advisory)
        runs = {self.GUARDS: run(9)}
        failures, _ = ci_gate.judge({self.GUARDS: [expected]}, runs, {9: [job(91, self.JOB, "skipped")]}, lambda _: "")
        self.assertEqual(failures, [f"{self.GUARDS} / {self.JOB}: skipped, and the policy allows no skip for it"])

    def test_each_suite_has_its_own_minimum_at_its_test_count(self):
        # One rule per suite, reading only that suite's evidence line, with the suite's current test count as
        # its floor: a suite that loses a test fails the gate, and one that gains a test must raise its floor.
        rules = self.guard_job().evidence
        labels = self.suite_labels()
        tests = self.recorded()["tests"]
        self.assertEqual(sorted(labels), sorted(self.SUITES))
        self.assertEqual(len(rules), len(self.SUITES))
        for suite in self.SUITES:
            with self.subTest(suite=suite):
                self.assertGreater(tests[suite], 0)
                line = self.evidence_log({suite: tests[suite]})
                (rule,) = [rule for rule in rules if ci_gate.evidence_count(line, rule["pattern"])]
                self.assertEqual(ci_gate.evidence_count(line, rule["pattern"]), tests[suite])
                self.assertEqual(rule["min"], tests[suite])

    def test_a_suite_that_runs_too_few_tests_fails_the_gate_on_its_own(self):
        expected = self.guard_job()
        floors = {}
        for suite in self.SUITES:
            line = self.evidence_log({suite: 1})
            floors[suite] = next(rule["min"] for rule in expected.evidence if ci_gate.evidence_count(line, rule["pattern"]))
        runs, jobs = {self.GUARDS: run(9)}, {9: [job(91, self.JOB)]}

        def judged(counts):
            return ci_gate.judge({self.GUARDS: [expected]}, runs, jobs, lambda _: self.evidence_log(counts))[0]

        self.assertEqual(judged(floors), [])
        for short in self.SUITES:
            with self.subTest(short=short):
                # The other suite runs far more than the old summed minimum (66) needed.
                counts = {suite: floors[suite] - 1 if suite == short else floors[suite] + 100 for suite in self.SUITES}
                failures = judged(counts)
                self.assertEqual(len(failures), 1, failures)
                self.assertIn(f" {floors[short] - 1}, below {floors[short]} ", failures[0])
                # A suite that printed no evidence line counts 0.
                missing = {suite: floors[suite] + 100 for suite in self.SUITES if suite != short}
                self.assertEqual(len(judged(missing)), 1)

    def test_every_file_the_suites_read_is_in_the_guard_filter(self):
        # #640: the filter must list every file the suites open, not only what a comment says they read.
        reads = self.recorded()["reads"]
        for known in ("services/auth-service/src/main/resources/application.yml",
                      "services/auth-service/src/main/java/com/parkio/auth/application/AccountErasureApplicationService.java",
                      "services/auth-service/src/test/resources/durable-erasure-evidence/v2/producer.json",
                      "services/gateway-service/src/main/resources/application.yml",
                      "scripts/lib/restore-erasure-ledger.py",
                      "scripts/lib/recovery_persist_protocol.py",
                      "docker/docker-compose.yml",
                      f".github/workflows/{self.GUARDS}"):
            self.assertTrue(known in reads, known)  # the recording sees the reads the suites are known to make
        patterns = ci_gate.pull_request_trigger(self.workflows[self.GUARDS])["paths"]
        missed = [path for path in reads if not ci_gate.filter_matches(path, patterns)]
        self.assertEqual(missed, [], f"the suites read files that {self.GUARDS}'s pull_request paths do not list")

    def test_every_file_the_preflight_suite_names_runs_it(self):
        # #640: test-restore-safe-preflight.sh greps the WP-06.2B script, which no workflow running it listed.
        runners = sorted(name for name, workflow in self.workflows.items()
                         if any(self.PREFLIGHT in step.get("run", "")
                                for job_ in workflow.get("jobs", {}).values() for step in job_.get("steps", [])))
        self.assertEqual(runners, ["observability-validation.yml", self.DRILL])
        named = sorted(set(re.findall(r"\$\{ROOT\}/([A-Za-z0-9_./-]+)",
                                      (ci_gate.ROOT / self.PREFLIGHT).read_text(encoding="utf-8"))))
        self.assertIn("scripts/staging/run-wp062b-restored-stack-verification.sh", named)
        for path in named:
            probe = f"{path}/x" if (ci_gate.ROOT / path).is_dir() else path
            with self.subTest(path=path):
                self.assertIn(self.DRILL, self.required(probe))
        required = self.required("scripts/staging/run-wp062b-restored-stack-verification.sh")
        for workflow in runners:
            self.assertIn(workflow, required)

    def test_the_guard_job_runs_the_suites_that_read_sources_as_the_drill_does(self):
        def scripts(workflow, job_id):
            return "\n".join(step.get("run", "") for step in self.workflows[workflow]["jobs"][job_id]["steps"])

        guards, drill = scripts(self.GUARDS, "source-guards"), scripts(self.DRILL, "procedure")
        self.assertIn('python3 "$2" 2>&1 | tee', guards)  # the guard job's `suite` runs each suite
        for suite in self.SUITES:
            with self.subTest(suite=suite):
                self.assertIn(suite, self.suite_labels())
                self.assertIn(f"python3 {suite}\n", drill)


if __name__ == "__main__":
    unittest.main()
