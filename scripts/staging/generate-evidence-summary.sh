#!/usr/bin/env bash
set -euo pipefail
OVERALL="${1:-1}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/lib/evidence-common.sh"

STATUS="FAILED"
if [ "${OVERALL}" -eq 0 ]; then
  if [ -f "${PARKIO_EVIDENCE_DIR}/critical_journeys.json" ] \
    && grep -q APPLICATION_VERIFICATION_SUCCEEDED "${PARKIO_EVIDENCE_DIR}/critical_journeys.json" 2>/dev/null; then
    STATUS="APPLICATION_VERIFICATION_SUCCEEDED"
  elif [ -f "${PARKIO_EVIDENCE_DIR}/semantic_integrity.json" ] \
    && grep -q SEMANTIC_VERIFICATION_SUCCEEDED "${PARKIO_EVIDENCE_DIR}/semantic_integrity.json" 2>/dev/null; then
    STATUS="SEMANTIC_VERIFICATION_SUCCEEDED"
  else
    STATUS="PARTIALLY_VERIFIED"
  fi
else
  if [ -f "${PARKIO_EVIDENCE_DIR}/critical_journeys.json" ] \
    && grep -q FAILED "${PARKIO_EVIDENCE_DIR}/critical_journeys.json" 2>/dev/null; then
    STATUS="FAILED"
  elif [ -f "${PARKIO_EVIDENCE_DIR}/restore_drill.json" ] \
    && grep -q RESTORE_SUCCEEDED "${PARKIO_EVIDENCE_DIR}/restore_drill.json" 2>/dev/null; then
    STATUS="PARTIALLY_VERIFIED"
  fi
fi

commit="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
started="${PARKIO_EVIDENCE_RUN_STARTED_AT:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
completed="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

python3 - <<PY "${PARKIO_EVIDENCE_DIR}" "${EVIDENCE_SCHEMA_VERSION}" "${PARKIO_EVIDENCE_RUN_ID}" "${commit}" "${PARKIO_ENVIRONMENT_TYPE:-unknown}" "${started}" "${completed}" "${STATUS}" "${OVERALL}"
import json, glob, os, sys
evidence_dir, schema_ver, run_id, commit, env_type, started, completed, status, overall = sys.argv[1:10]
stage_names = [
    "prerequisites", "safety_guards", "compose_validation", "restore_drill",
    "semantic_integrity", "minio_roundtrip", "wp05_replay", "critical_journeys",
]
stages = {}
for name in stage_names:
    path = os.path.join(evidence_dir, f"{name}.json")
    if os.path.isfile(path):
        doc = json.load(open(path, encoding="utf-8"))
        stages[name] = {
            "status": doc.get("status", "UNKNOWN"),
            "startedAt": doc.get("startedAt", ""),
            "completedAt": doc.get("completedAt", ""),
        }
summary = {
    "evidenceSchemaVersion": schema_ver,
    "runId": run_id,
    "repositoryCommit": commit,
    "environmentType": env_type,
    "startedAt": started,
    "completedAt": completed,
    "status": status,
    "stages": stages,
    "syntheticDataMarker": True,
    "rpoRtoClassification": "NOT_REPRESENTATIVE",
    "warnings": [] if overall == "0" else ["one_or_more_mandatory_stages_failed"],
    "blockers": [],
    "verificationResults": {
        "semanticIntegrity": stages.get("semantic_integrity", {}).get("status", "NOT_RUN"),
        "restoreDrill": stages.get("restore_drill", {}).get("status", "NOT_RUN"),
        "minioRoundtrip": stages.get("minio_roundtrip", {}).get("status", "NOT_RUN"),
        "wp05Replay": stages.get("wp05_replay", {}).get("status", "NOT_RUN"),
        "criticalJourneys": stages.get("critical_journeys", {}).get("status", "NOT_RUN"),
    },
}
with open(os.path.join(evidence_dir, "summary.json"), "w", encoding="utf-8") as fh:
    json.dump(summary, fh, indent=2)
    fh.write("\n")
PY

cat > "${PARKIO_EVIDENCE_DIR}/prr-evidence-summary.md" <<EOF
# WP-06.2A Evidence Summary
- Run ID: ${PARKIO_EVIDENCE_RUN_ID}
- Commit: ${commit}
- Environment: ${PARKIO_ENVIRONMENT_TYPE:-unknown}
- Status: ${STATUS}
- Synthetic data only; NOT_REPRESENTATIVE for production RPO/RTO.
EOF
