#!/usr/bin/env python3
"""WP-06.2B.1 evidence consistency audit (read-only; writes audit report only)."""
from __future__ import annotations

import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path


def main() -> int:
    run_id = sys.argv[1] if len(sys.argv) > 1 else "wp062b-20260728211226"
    expected_head = (
        sys.argv[2]
        if len(sys.argv) > 2
        else "550848277748cf086a738c7135f26f1ff27ae9e8"
    )
    root = Path("build/operational-evidence") / run_id
    if not root.is_dir():
        print(json.dumps({"status": "FAILED", "issues": [f"missing_dir:{root}"]}))
        return 1

    issues: list[str] = []
    ok: list[str] = []

    if root.name != run_id:
        issues.append(f"dir_name_mismatch:{root.name}")
    else:
        ok.append("run_id_dir_match")

    summary = json.loads((root / "shared-staging-summary.json").read_text(encoding="utf-8"))
    env = json.loads((root / "environment-manifest.json").read_text(encoding="utf-8"))
    ds = json.loads((root / "datasource-repoint-report.json").read_text(encoding="utf-8"))
    cleanup = json.loads((root / "cleanup-report.json").read_text(encoding="utf-8"))
    gw = json.loads((root / "gateway-route-baseline.json").read_text(encoding="utf-8"))
    journeys = json.loads(
        (root / "critical-journeys-restored" / "summary.json").read_text(encoding="utf-8")
    )

    checks = [
        (summary.get("runId") == run_id, "summary.runId"),
        (summary.get("repositoryCommit") == expected_head, "summary.commit"),
        (
            summary.get("executionClassification") == "LOCAL_REPRESENTATIVE",
            "executionClassification",
        ),
        (summary.get("sharedStagingLabel") is False, "sharedStagingLabel_false"),
        (summary.get("status") == "SIGNOFF_REQUIRED", "status_SIGNOFF_REQUIRED"),
        (summary.get("signOffDecision") == "NOT_REVIEWED", "signOffDecision_NOT_REVIEWED"),
        (summary.get("wp063Eligible") is False, "wp063_not_eligible"),
        (
            summary.get("technicalStatus") == "APPLICATION_VERIFICATION_SUCCEEDED",
            "technical_status",
        ),
        (
            env.get("executionClassification") == "LOCAL_REPRESENTATIVE",
            "env.executionClassification",
        ),
        (
            str(env.get("restoreComposeProject", "")).startswith("parkio-wp062-")
            or str(env.get("sourceComposeProject", "")).startswith("parkio-wp062-"),
            "restore_project_prefix",
        ),
        (env.get("sourceDatabases") != env.get("restoreDatabases"), "db_names_differ"),
        (
            all("_drill_" in v for v in (env.get("restoreDatabases") or {}).values()),
            "restore_db_drill_pattern",
        ),
        (
            env.get("sourceMinioBucket") != env.get("restoreMinioBucket"),
            "minio_targets_differ",
        ),
        (bool(env.get("kafkaIsolation")), "kafka_isolation_recorded"),
        (bool(env.get("redisIsolation")), "redis_isolation_recorded"),
        (env.get("ports", {}).get("gateway") == 18080, "gateway_port_18080"),
        (ds.get("status") == "PASSED", "datasource_PASSED"),
        (
            all(s.get("pointsToRestoreDb") for s in (ds.get("services") or [])),
            "all_jdbc_restore",
        ),
        (
            journeys.get("status") == "APPLICATION_VERIFICATION_SUCCEEDED",
            "journeys_succeeded",
        ),
        (cleanup.get("status") == "CLEANED", "cleanup_CLEANED"),
        (cleanup.get("developerProjectUntouched") is True, "developer_untouched"),
        (gw.get("baseliningStatus") == "BASELINING_REQUIRED", "gateway_BASELINING_REQUIRED"),
        (gw.get("environment") == "LOCAL_REPRESENTATIVE", "gateway_env_local"),
    ]
    for passed, name in checks:
        (ok if passed else issues).append(name)

    mandatory_fail: list[str] = []
    for stage, meta in (journeys.get("stages") or {}).items():
        status = meta.get("status")
        if not meta.get("mandatory"):
            continue
        if status == "PASSED":
            continue
        if status == "NOT_APPLICABLE" and stage == "async_completion":
            continue
        mandatory_fail.append(f"{stage}:{status}")
    if mandatory_fail:
        issues.append("mandatory_failures:" + ",".join(mandatory_fail))
    else:
        ok.append("mandatory_journeys_passed")

    if env.get("sourceComposeProject") == env.get("restoreComposeProject"):
        ok.append("same_compose_project_two_phases_documented")

    report = {
        "validatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "runId": run_id,
        "expectedHead": expected_head,
        "status": "PASSED" if not issues else "FAILED",
        "ok": ok,
        "issues": issues,
        "artifactChecksums": {},
    }
    for rel in [
        "shared-staging-summary.json",
        "environment-manifest.json",
        "datasource-repoint-report.json",
        "cleanup-report.json",
        "critical-journeys-restored/summary.json",
        "gateway-route-baseline.json",
    ]:
        path = root / rel
        if path.exists():
            report["artifactChecksums"][rel] = hashlib.sha256(path.read_bytes()).hexdigest()

    out = root / "evidence-consistency-audit.json"
    out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "issues": issues, "ok_count": len(ok)}, indent=2))
    print("---ENV---")
    print(
        json.dumps(
            {
                k: env.get(k)
                for k in [
                    "executionClassification",
                    "sourceComposeProject",
                    "restoreComposeProject",
                    "sourceDatabases",
                    "restoreDatabases",
                    "sourceMinioBucket",
                    "restoreMinioBucket",
                    "kafkaIsolation",
                    "redisIsolation",
                    "ports",
                ]
            },
            indent=2,
        )
    )
    return 0 if not issues else 2


if __name__ == "__main__":
    raise SystemExit(main())
