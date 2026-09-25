#!/usr/bin/env python3
"""Summarize complete, unsuppressed installed-image Trivy reports."""

import json
import sys
from collections import Counter
from pathlib import Path

out = Path(sys.argv[1])
order = ("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN")
rows = []
counts = {}
for stage in ("baseline-builder", "baseline-runtime", "builder", "runtime"):
    report = json.loads((out / f"ci-{stage}-scan.json").read_text())
    findings = [
        (result.get("Target", ""), vuln)
        for result in report.get("Results", [])
        for vuln in result.get("Vulnerabilities") or []
    ]
    count = Counter(vuln.get("Severity", "UNKNOWN") for _, vuln in findings)
    counts[stage] = count
    rows.append(f"| {stage} | " + " | ".join(str(count[s]) for s in order) + " |")
    details = [
        {
            "target": target,
            "package": vuln.get("PkgName"),
            "installed": vuln.get("InstalledVersion"),
            "vulnerability": vuln.get("VulnerabilityID"),
            "severity": vuln.get("Severity", "UNKNOWN"),
            "fixed_version": vuln.get("FixedVersion") or None,
            "scanner_fix_status": "fixed_version_available"
            if vuln.get("FixedVersion")
            else "no_scanner_fix_listed",
        }
        for target, vuln in findings
    ]
    (out / f"ci-{stage}-findings.json").write_text(
        json.dumps(details, indent=2, sort_keys=True) + "\n"
    )

summary = (
    "\n### Installed image vulnerability scans\n\n"
    "| Stage | CRITICAL | HIGH | MEDIUM | LOW | UNKNOWN |\n"
    "| --- | ---: | ---: | ---: | ---: | ---: |\n"
    + "\n".join(rows)
    + "\n\nSame CI Trivy DB snapshot for baseline and candidate; no suppressions or --ignore-unfixed. "
    "Full findings and fix versions are in the artifact.\n"
)
(out / "scan-summary.md").write_text(summary)
print(summary)

original_high = {
    ("CVE-2026-14257", "brace-expansion"),
    ("CVE-2026-69152", "brace-expansion"),
    ("CVE-2026-69192", "ip-address"),
    ("CVE-2026-73566", "tar"),
}
baseline_high = {
    (item["vulnerability"], item["package"])
    for item in json.loads((out / "ci-baseline-builder-findings.json").read_text())
}
candidate_high = {
    (item["vulnerability"], item["package"])
    for item in json.loads((out / "ci-builder-findings.json").read_text())
}
original_status = {
    f"{cve} {package}": {
        "in_current_baseline_db": (cve, package) in baseline_high,
        "in_candidate_db": (cve, package) in candidate_high,
    }
    for cve, package in sorted(original_high)
}
(out / "original-four-high-status.json").write_text(
    json.dumps(original_status, indent=2, sort_keys=True) + "\n"
)
if not original_high.issubset(baseline_high):
    sys.exit("same-DB baseline did not reproduce all four original HIGH records")

# A newer DB can add findings. Report and fail any builder CRITICAL/HIGH.
if counts["builder"]["CRITICAL"] or counts["builder"]["HIGH"]:
    sys.exit("candidate builder has CRITICAL/HIGH findings requiring review")
if sum(counts["runtime"].values()):
    sys.exit("runtime image has vulnerability findings requiring review")
