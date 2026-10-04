#!/usr/bin/env python3
"""Unit tests for scripts/ci/junit_summary.py on synthetic Gradle result folders."""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import junit_summary  # noqa: E402


def write(root: Path, module: str, task: str, name: str, tests: int, failures=0, errors=0, skipped=0):
    folder = root / module / "build" / "test-results" / task
    folder.mkdir(parents=True, exist_ok=True)
    (folder / f"TEST-{name}.xml").write_text(
        f'<testsuite name="{name}" tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}"/>',
        encoding="utf-8",
    )


class Summary(unittest.TestCase):
    def test_sums_suites_per_task_and_counts_modules(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root, "services/auth-service", "test", "A", 10, skipped=1)
            write(root, "services/auth-service", "test", "B", 5, failures=1)
            write(root, "services/user-service", "test", "C", 7)
            write(root, "services/user-service", "integrationTest", "D", 3)
            totals = junit_summary.summarize(root, "test")
            self.assertEqual(totals, {"tests": 22, "failures": 1, "errors": 0, "skipped": 1, "modules": 2})
            self.assertEqual(
                junit_summary.evidence_line("Unit test", totals),
                "Unit test evidence: 22 tests in 2 modules (1 failures, 0 errors, 1 skipped)",
            )

    def test_no_results_report_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            totals = junit_summary.summarize(Path(tmp), "test")
            self.assertEqual(junit_summary.evidence_line("Unit test", totals),
                             "Unit test evidence: 0 tests in 0 modules (0 failures, 0 errors, 0 skipped)")


if __name__ == "__main__":
    unittest.main()
