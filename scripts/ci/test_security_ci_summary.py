#!/usr/bin/env python3
"""Security CI's summary gate fails closed (U08): it runs the gate step from security-ci.yml itself."""
import os
import subprocess
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


if __name__ == "__main__":
    unittest.main()
