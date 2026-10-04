#!/usr/bin/env python3
"""Print one line of test evidence from Gradle's JUnit XML results.

    <label> evidence: N tests in M modules (F failures, E errors, S skipped)

It reads every build/test-results/<task>/*.xml under the root. It only reports: the CI gate
(scripts/ci/ci_gate.py) reads this line from the job log and fails a job that ran zero tests.

Usage: junit_summary.py --label "Unit test" --task test [--root .]
"""
from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def summarize(root: Path, task: str) -> dict[str, int]:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "modules": 0}
    modules: set[Path] = set()
    for report in sorted(root.glob(f"**/build/test-results/{task}/*.xml")):
        if "node_modules" in report.parts:
            continue
        suites = ET.parse(report).getroot()
        for suite in [suites] if suites.tag == "testsuite" else suites.iter("testsuite"):
            for key in ("tests", "failures", "errors", "skipped"):
                totals[key] += int(suite.get(key, "0") or 0)
        modules.add(report.parent.parent.parent.parent)
    totals["modules"] = len(modules)
    return totals


def evidence_line(label: str, totals: dict[str, int]) -> str:
    return (
        f"{label} evidence: {totals['tests']} tests in {totals['modules']} modules "
        f"({totals['failures']} failures, {totals['errors']} errors, {totals['skipped']} skipped)"
    )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--label", required=True)
    parser.add_argument("--task", required=True, help="Gradle test task, e.g. test or integrationTest")
    parser.add_argument("--root", default=".")
    args = parser.parse_args(argv)
    print(evidence_line(args.label, summarize(Path(args.root), args.task)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
