#!/usr/bin/env python3
"""#418 / N-09c: path-filtered summary gate fails closed and treats intentional skips as pass."""
import os
import subprocess
import unittest
from pathlib import Path

GATE = Path(__file__).resolve().parent / "path_filtered_summary_gate.sh"


def run_gate(*, detect="success", run="true", work="success success"):
    env = {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "DETECT_RESULT": detect,
        "RUN": run,
        "WORK_RESULTS": work,
    }
    return subprocess.run(
        ["bash", str(GATE)],
        env=env,
        capture_output=True,
        text=True,
    ).returncode


class PathFilteredSummaryGateTest(unittest.TestCase):
    def test_in_scope_success_passes(self):
        self.assertEqual(run_gate(), 0)

    def test_out_of_scope_all_skipped_passes(self):
        self.assertEqual(run_gate(run="false", work="skipped skipped"), 0)

    def test_out_of_scope_non_skipped_fails(self):
        self.assertNotEqual(run_gate(run="false", work="skipped success"), 0)
        self.assertNotEqual(run_gate(run="false", work="failure skipped"), 0)

    def test_in_scope_failure_or_cancel_or_skip_fails(self):
        for result in ("failure", "cancelled", "skipped"):
            with self.subTest(result=result):
                self.assertNotEqual(run_gate(work=f"success {result}"), 0)
        self.assertNotEqual(run_gate(work=""), 0)

    def test_detect_must_succeed(self):
        for result in ("failure", "cancelled", "skipped", ""):
            with self.subTest(result=result):
                self.assertNotEqual(run_gate(detect=result), 0)

    def test_unknown_run_fails(self):
        self.assertNotEqual(run_gate(run="maybe"), 0)


if __name__ == "__main__":
    unittest.main()
