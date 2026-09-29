#!/usr/bin/env bash
#
# Finalize a quarantined invite-production deploy/rollback/dry-run step.
#
# Contract (PROD-DEPLOY-01A-R11H):
#   1. Preserve the primary deploy exit code.
#   2. Secret-scan the quarantined log before any user-visible release.
#   3. Scan evidence only when the evidence directory exists.
#   4. If deploy failed before evidence creation, skip artifact scan with
#      explicit metadata (never FileNotFoundError-mask the deploy failure).
#   5. If deploy succeeded but evidence is absent, fail deterministically.
#   6. If both deploy and scanner fail, report BOTH; final exit remains the
#      deploy status (PRIMARY_DEPLOY_FAILURE + SECONDARY_EVIDENCE_FAILURE).
#   7. Never print the raw quarantined log when the secret scan fails closed.
#
# Usage:
#   finalize-invite-production-quarantined-evidence.sh \
#     --deploy-status <rc> \
#     --env-file <path> \
#     --artifact-dir <path> \
#     --job-log <path> \
#     [--log-prefix <prefix>]

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCANNER="$ROOT/scripts/assert-invite-production-artifacts-safe.py"
PYTHON_BIN="${PARKIO_PYTHON_BIN:-python3}"

DEPLOY_STATUS=""
ENV_FILE=""
ARTIFACT_DIR=""
JOB_LOG=""
LOG_PREFIX="[invite-deploy] "

while [ "$#" -gt 0 ]; do
  case "$1" in
    --deploy-status) DEPLOY_STATUS="${2:-}"; shift 2 ;;
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --artifact-dir) ARTIFACT_DIR="${2:-}"; shift 2 ;;
    --job-log) JOB_LOG="${2:-}"; shift 2 ;;
    --log-prefix) LOG_PREFIX="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

[ -n "$DEPLOY_STATUS" ] || { echo "ERROR: --deploy-status is required" >&2; exit 2; }
[[ "$DEPLOY_STATUS" =~ ^[0-9]+$ ]] || {
  echo "ERROR: --deploy-status must be an integer (got '$DEPLOY_STATUS')" >&2
  exit 2
}
[ -n "$ENV_FILE" ] || { echo "ERROR: --env-file is required" >&2; exit 2; }
[ -n "$ARTIFACT_DIR" ] || { echo "ERROR: --artifact-dir is required" >&2; exit 2; }
[ -n "$JOB_LOG" ] || { echo "ERROR: --job-log is required" >&2; exit 2; }
[ -f "$SCANNER" ] || { echo "ERROR: scanner missing: $SCANNER" >&2; exit 2; }

log_scan_rc=0
artifact_scan_rc=0
secondary_reported=0

report_secondary() {
  local rc="$1"
  local detail="$2"
  secondary_reported=1
  echo "SECONDARY_EVIDENCE_FAILURE: $detail (exit=$rc)" >&2
  if [ "$DEPLOY_STATUS" -ne 0 ]; then
    echo "primaryFailureClassification=PRIMARY_DEPLOY_FAILURE"
    echo "secondaryFailureClassification=SECONDARY_EVIDENCE_FAILURE"
  else
    echo "failureClassification=SECONDARY_EVIDENCE_FAILURE"
  fi
}

if [ ! -f "$JOB_LOG" ]; then
  echo "ERROR: quarantined job log missing before finalize: $JOB_LOG" >&2
  if [ "$DEPLOY_STATUS" -ne 0 ]; then
    report_secondary 2 "quarantined job log missing"
    exit "$DEPLOY_STATUS"
  fi
  exit 2
fi

set +e
"$PYTHON_BIN" "$SCANNER" --env-file "$ENV_FILE" "$JOB_LOG"
log_scan_rc=$?
set -e
if [ "$log_scan_rc" -ne 0 ]; then
  report_secondary "$log_scan_rc" "quarantined log secret scan failed; refusing unsafe log release"
else
  # Safe release: prefix only. Raw bytes stay on tmpfs until cleanup.
  sed "s/^/${LOG_PREFIX}/" "$JOB_LOG"
fi

if [ -d "$ARTIFACT_DIR" ]; then
  echo "evidenceDirectoryPresent=true"
  set +e
  "$PYTHON_BIN" "$SCANNER" --env-file "$ENV_FILE" "$ARTIFACT_DIR"
  artifact_scan_rc=$?
  set -e
  if [ "$artifact_scan_rc" -ne 0 ]; then
    report_secondary "$artifact_scan_rc" "artifact evidence secret scan failed"
  fi
else
  echo "evidenceDirectoryPresent=false"
  if [ "$DEPLOY_STATUS" -eq 0 ]; then
    echo "ERROR: deploy succeeded but required evidence directory is absent: $ARTIFACT_DIR" >&2
    echo "failureClassification=MISSING_EVIDENCE_AFTER_SUCCESS" >&2
    artifact_scan_rc=2
    report_secondary "$artifact_scan_rc" "evidence directory missing after successful deploy"
  else
    echo "artifactScanSkippedReason=deploy_failed_before_evidence_creation"
    echo "failureClassification=PRIMARY_DEPLOY_FAILURE"
  fi
fi

if [ "$DEPLOY_STATUS" -ne 0 ]; then
  echo "primaryDeployStatus=$DEPLOY_STATUS"
  if [ "$secondary_reported" -eq 0 ]; then
    echo "failureClassification=PRIMARY_DEPLOY_FAILURE"
  fi
  exit "$DEPLOY_STATUS"
fi

if [ "$log_scan_rc" -ne 0 ]; then
  exit "$log_scan_rc"
fi
if [ "$artifact_scan_rc" -ne 0 ]; then
  exit "$artifact_scan_rc"
fi
exit 0
