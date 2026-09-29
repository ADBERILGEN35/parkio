#!/usr/bin/env bash
#
# PROD-DEPLOY-01A-R11H — quarantined evidence / exit-precedence regressions.
#
# These fixtures reproduce the masking defect from run 32961566948 where an
# early deploy failure (before deploy-artifacts/invite-production existed)
# was replaced by FileNotFoundError from the artifact scanner.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FINALIZE="$ROOT/scripts/finalize-invite-production-quarantined-evidence.sh"
SCANNER="$ROOT/scripts/assert-invite-production-artifacts-safe.py"
STAGE="$ROOT/scripts/stage-invite-production-release.sh"
WORKFLOW="$ROOT/.github/workflows/invite-production-deploy.yml"

pass=0
fail=0
ok() { echo "PASS: $*"; pass=$((pass + 1)); }
bad() { echo "FAIL: $*"; fail=$((fail + 1)); }
check() {
  local name="$1"
  shift
  if eval "$@"; then ok "$name"; else bad "$name"; fi
}

TMP="$(mktemp -d "${TMPDIR:-/tmp}/r11h-evidence.XXXXXX")"
trap 'rm -rf -- "$TMP"' EXIT

ENV_FILE="$TMP/input.env"
cat >"$ENV_FILE" <<'EOF'
POSTGRES_PASSWORD=SECRET_SENTINEL_DB_PASSWORD_01234567
PARKIO_ALERT_SLACK_WEBHOOK_URL=https://hooks.slack.com/services/SECRET_SENTINEL_SLACK_URL/fixture/fixture
EOF
chmod 600 "$ENV_FILE"

echo "== R11H-1: early deploy failure before evidence directory =="
ART_MISSING="$TMP/missing-artifacts"
JOB_LOG="$TMP/early-fail.log"
cat >"$JOB_LOG" <<'EOF'
Running invite-production preflight...
Invite-production preflight passed.
=== Parkio invite-production deploy ===
Staging stable runtime release...
ERROR: runtime root is unreachable because its parent is not traversable: /opt/parkio
EOF
set +e
out="$("$FINALIZE" \
  --deploy-status 3 \
  --env-file "$ENV_FILE" \
  --artifact-dir "$ART_MISSING" \
  --job-log "$JOB_LOG" \
  --log-prefix '[invite-deploy] ' 2>"$TMP/early-fail.err")"
rc=$?
set -e
{
  printf '%s\n' "$out"
  cat "$TMP/early-fail.err"
} >"$TMP/early-fail.all"
check "early failure preserves deploy exit 3" "[ \"$rc\" = 3 ]"
check "primary failure text is user-visible" \
  "printf '%s\n' \"$out\" | grep -q 'runtime root is unreachable'"
check "no FileNotFoundError masking" \
  "! grep -q 'FileNotFoundError' \"$TMP/early-fail.all\""
check "evidenceDirectoryPresent=false" \
  "grep -q 'evidenceDirectoryPresent=false' \"$TMP/early-fail.all\""
check "artifactScanSkippedReason emitted" \
  "grep -q 'artifactScanSkippedReason=deploy_failed_before_evidence_creation' \"$TMP/early-fail.all\""
check "PRIMARY_DEPLOY_FAILURE classified" \
  "grep -q 'failureClassification=PRIMARY_DEPLOY_FAILURE' \"$TMP/early-fail.all\""
check "prefixed safe log released" \
  "printf '%s\n' \"$out\" | grep -q '^\[invite-deploy\] ERROR: runtime root is unreachable'"

echo "== R11H-2: deploy failure + secondary scanner failure =="
ART_BAD="$TMP/bad-artifacts"
mkdir -p "$ART_BAD"
printf 'leaked=%s\n' 'SECRET_SENTINEL_DB_PASSWORD_01234567' >"$ART_BAD/leak.txt"
JOB_LOG2="$TMP/dual-fail.log"
printf 'PRIMARY_DEPLOY_LINE deploy boom\n' >"$JOB_LOG2"
set +e
out2="$("$FINALIZE" \
  --deploy-status 3 \
  --env-file "$ENV_FILE" \
  --artifact-dir "$ART_BAD" \
  --job-log "$JOB_LOG2" \
  --log-prefix '[invite-deploy] ' 2>"$TMP/dual-fail.err")"
rc2=$?
set -e
{
  printf '%s\n' "$out2"
  cat "$TMP/dual-fail.err"
} >"$TMP/dual-fail.all"
check "dual failure preserves deploy exit 3" "[ \"$rc2\" = 3 ]"
check "dual failure reports PRIMARY_DEPLOY_FAILURE" \
  "grep -q 'PRIMARY_DEPLOY_FAILURE' \"$TMP/dual-fail.all\""
check "dual failure reports SECONDARY_EVIDENCE_FAILURE" \
  "grep -q 'SECONDARY_EVIDENCE_FAILURE' \"$TMP/dual-fail.all\""
check "dual failure still releases primary log text" \
  "printf '%s\n' \"$out2\" | grep -q 'PRIMARY_DEPLOY_LINE'"
check "scanner sentinel not printed" \
  "! grep -q 'SECRET_SENTINEL_DB_PASSWORD_01234567' \"$TMP/dual-fail.all\""

echo "== R11H-3: success + missing evidence directory =="
JOB_LOG3="$TMP/success-missing.log"
printf 'DRY-RUN: would build images\n' >"$JOB_LOG3"
set +e
out3="$("$FINALIZE" \
  --deploy-status 0 \
  --env-file "$ENV_FILE" \
  --artifact-dir "$TMP/still-missing" \
  --job-log "$JOB_LOG3" \
  --log-prefix '[invite-dry-run] ' 2>"$TMP/success-missing.err")"
rc3=$?
set -e
{
  printf '%s\n' "$out3"
  cat "$TMP/success-missing.err"
} >"$TMP/success-missing.all"
check "success+missing evidence fails" "[ \"$rc3\" -ne 0 ]"
check "success+missing classified" \
  "grep -q 'MISSING_EVIDENCE_AFTER_SUCCESS' \"$TMP/success-missing.all\""

echo "== R11H-4: success path still scans evidence and releases log =="
ART_OK="$TMP/ok-artifacts"
mkdir -p "$ART_OK"
printf '{"gitSha":"abc","ok":true}\n' >"$ART_OK/manifest.json"
JOB_LOG4="$TMP/success.log"
printf 'Invite-production preflight passed.\nManifest written: ok\n' >"$JOB_LOG4"
set +e
out4="$("$FINALIZE" \
  --deploy-status 0 \
  --env-file "$ENV_FILE" \
  --artifact-dir "$ART_OK" \
  --job-log "$JOB_LOG4" \
  --log-prefix '[invite-dry-run] ' 2>"$TMP/success.err")"
rc4=$?
set -e
{
  printf '%s\n' "$out4"
  cat "$TMP/success.err"
} >"$TMP/success.all"
check "success path exits 0" "[ \"$rc4\" = 0 ]"
check "success path evidenceDirectoryPresent=true" \
  "grep -q 'evidenceDirectoryPresent=true' \"$TMP/success.all\""
check "success path releases sanitized log" \
  "printf '%s\n' \"$out4\" | grep -q '^\[invite-dry-run\] Manifest written'"

echo "== R11H-5: scanner still hard-fails on truly missing required path =="
set +e
python3 "$SCANNER" --env-file "$ENV_FILE" "$TMP/does-not-exist" >"$TMP/scanner-missing.out" 2>"$TMP/scanner-missing.err"
scan_rc=$?
set -e
check "direct scanner still errors on missing path" "[ \"$scan_rc\" -ne 0 ]"
check "direct scanner raises FileNotFoundError when invoked incorrectly" \
  "grep -Eq 'FileNotFoundError|evidence path does not exist' \"$TMP/scanner-missing.err\" \"$TMP/scanner-missing.out\""

echo "== R11H-6: workflow wiring uses finalize helper =="
check "deploy step calls finalize helper" \
  "grep -q 'finalize-invite-production-quarantined-evidence.sh' \"$WORKFLOW\""
check "production deploy step routes through finalize" \
  "awk '/Deploy exact commit with quarantined log/,/name: Cleanup per-job/' \"$WORKFLOW\" | grep -q finalize-invite-production-quarantined-evidence"
check "deploy step does not call artifact scanner directly" \
  "! awk '/Deploy exact commit with quarantined log/,/name: Cleanup per-job/' \"$WORKFLOW\" | grep -q 'assert-invite-production-artifacts-safe.py'"
check "dry-run routes through finalize" \
  "grep -c 'finalize-invite-production-quarantined-evidence.sh' \"$WORKFLOW\" | grep -Eq '^[3-9]|[1-9][0-9]'"

echo "== R11H-7: staging diagnoses non-traversable parent =="
PARENT="$TMP/parkio-base"
RUNTIME="$PARENT/invite-production"
install -d -m 0755 "$PARENT"
install -d -m 0755 "$RUNTIME" "$RUNTIME/releases"
# Drop traverse for everyone except owner; run staging as a different fake uid is hard
# on Windows/CI without root. Instead assert the diagnostic branch exists and the
# installer still widens /opt/parkio to 0755.
check "stage script diagnoses non-traversable parent" \
  "grep -q 'parent is not traversable' \"$STAGE\""
check "runtime-root installer widens /opt/parkio to 0755" \
  "grep -q 'chmod 0755 \"\$PARKIO_BASE\"' \"$ROOT/scripts/azure/install-invite-production-runtime-root.sh\""

# Local synthetic: make parent 0700 owned by current user, then ... we can still
# traverse as owner. Use a subdirectory the script checks via dirname.
# Verify the check order: PARENT_ROOT x-bit test precedes -d RUNTIME_ROOT.
check "stage checks parent traverse before existence" \
  "awk '/PARENT_ROOT=/,/runtime root does not exist/' \"$STAGE\" | grep -q 'not traversable'"

echo "== R11H-8: finalize script is committed executable =="
mode="$(git -C "$ROOT" ls-files -s -- scripts/finalize-invite-production-quarantined-evidence.sh | awk '{print $1}')"
# Before first add, mode may be empty; after add+chmod it must be 100755.
if [ -z "$mode" ]; then
  if [ -x "$FINALIZE" ]; then ok "finalize is locally executable (pending git add)"; else bad "finalize not executable"; fi
else
  check "finalize is committed executable (got ${mode:-missing})" "[ \"$mode\" = 100755 ]"
fi

echo
echo "R11H evidence regressions: pass=$pass fail=$fail"
[ "$fail" -eq 0 ]
