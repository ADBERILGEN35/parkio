#!/usr/bin/env python3
"""Security CI's gates fail closed (U08). The tests run the steps from security-ci.yml itself: the summary
gate, the container job's report requirement, and the library pass's blocking exit code."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

WORKFLOW = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "security-ci.yml"
GATE = "Fail summary if a required gate failed"


def gate_script():
    steps = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))["jobs"]["summary"]["steps"]
    return next(step["run"] for step in steps if step.get("name") == GATE)


def run_gate(**results):
    env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "SECRETS_RESULT": "success", "CODEQL_RESULT": "skipped",
           "DEPENDENCIES_RESULT": "success", "SELECT_RESULT": "success", "SELECT_ANY": "true",
           "CONTAINER_RESULT": "success", **results}
    return subprocess.run(["bash", "-c", gate_script()], env=env, capture_output=True, text=True).returncode


class SummaryGateTest(unittest.TestCase):
    def test_every_gate_passing_passes(self):
        self.assertEqual(run_gate(), 0)

    def test_an_empty_selection_may_skip_the_container_scan(self):
        self.assertEqual(run_gate(SELECT_ANY="false", CONTAINER_RESULT="skipped"), 0)

    def test_a_skip_with_images_selected_fails(self):
        self.assertNotEqual(run_gate(SELECT_ANY="true", CONTAINER_RESULT="skipped"), 0)

    def test_a_failed_or_missing_selection_fails_even_when_the_scan_is_skipped(self):
        for select in ("failure", "cancelled", "skipped", ""):
            with self.subTest(select=select):
                self.assertNotEqual(run_gate(SELECT_RESULT=select, SELECT_ANY="", CONTAINER_RESULT="skipped"), 0)

    def test_a_failed_or_cancelled_scan_fails(self):
        for result in ("failure", "cancelled"):
            with self.subTest(result=result):
                self.assertNotEqual(run_gate(CONTAINER_RESULT=result), 0)

    def test_the_other_gates_still_fail_it(self):
        self.assertNotEqual(run_gate(SECRETS_RESULT="failure"), 0)
        self.assertNotEqual(run_gate(DEPENDENCIES_RESULT="failure"), 0)
        self.assertNotEqual(run_gate(CODEQL_RESULT="failure"), 0)


REPORTS = "Require every scan report"
LIBRARY = "Block on high and critical library findings with a fix"


def container_step(name):
    steps = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))["jobs"]["container-images"]["steps"]
    return next(step["run"] for step in steps if step.get("name") == name)


class ContainerScanStepsTest(unittest.TestCase):
    """#266 review N2: a missing report and S1's library findings must fail the container job."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)

    def run_step(self, name, path_prefix=None):
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "SERVICE": "x", "TRIVY_IMAGE": "trivy:test",
               "TRIVY_CACHE_DIR": str(self.dir), "GITHUB_WORKSPACE": str(self.dir)}
        if path_prefix:
            env["PATH"] = f"{path_prefix}:{env['PATH']}"
        return subprocess.run(["bash", "-c", container_step(name)], cwd=self.dir, env=env,
                              capture_output=True, text=True).returncode

    def test_every_report_present_passes_and_a_missing_or_empty_one_fails(self):
        names = ["trivy-image-x.txt", "trivy-image-x.sarif", "trivy-image-x-library.txt", "trivy-image-x-library.sarif"]

        def leave(present, empty=()):
            for old in self.dir.glob("trivy-image-*"):
                old.unlink()
            for name in present:
                (self.dir / name).write_text("" if name in empty else "report\n")
            return self.run_step(REPORTS)

        self.assertEqual(leave(names), 0)
        for missing in names:
            with self.subTest(missing=missing):
                self.assertNotEqual(leave([name for name in names if name != missing]), 0)
        self.assertNotEqual(leave(names, empty={"trivy-image-x-library.sarif"}), 0)

    def run_library_pass(self):
        # A fake docker plays Trivy with findings: it records its arguments, writes the report and exits
        # with --exit-code.
        bin_dir = self.dir / "bin"
        bin_dir.mkdir()
        fake = bin_dir / "docker"
        fake.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$@" >>calls.log\necho "--end--" >>calls.log\n'
                        'out=""; code=0\n'
                        'while [ $# -gt 0 ]; do case "$1" in --output) out="$2"; shift;; --exit-code) code="$2"; shift;; esac; shift; done\n'
                        'echo "HIGH finding with a fix" >"$out"\nexit "$code"\n')
        fake.chmod(0o755)
        code = self.run_step(LIBRARY, bin_dir)
        calls = [call.strip("\n").split("\n") for call in (self.dir / "calls.log").read_text().split("--end--\n") if call.strip()]
        return code, calls

    def test_library_findings_with_a_fix_fail_after_both_reports_are_written(self):
        code, _ = self.run_library_pass()

        self.assertNotEqual(code, 0)
        self.assertTrue((self.dir / "trivy-image-x-library.txt").is_file())
        self.assertTrue((self.dir / "trivy-image-x-library.sarif").is_file())

    def test_the_library_pass_keeps_the_s1_policy(self):
        """R2-2: library packages only, HIGH and CRITICAL with a fix, the ignore file, and SARIF blocks."""
        _, calls = self.run_library_pass()

        def value(call, flag):
            return call[call.index(flag) + 1] if flag in call else None

        self.assertEqual([value(call, "--format") for call in calls], ["table", "sarif"])
        for call in calls:
            with self.subTest(format=value(call, "--format")):
                self.assertEqual(value(call, "--pkg-types"), "library")
                self.assertEqual(value(call, "--severity"), "HIGH,CRITICAL")
                self.assertIn("--ignore-unfixed", call)
                self.assertEqual(value(call, "--ignorefile"), ".trivyignore.yaml")
                self.assertEqual(value(call, "--scanners"), "vuln")
        self.assertEqual([value(call, "--exit-code") for call in calls], ["0", "1"])

if __name__ == "__main__":
    unittest.main()
