#!/usr/bin/env bash
#
# Isolated restore failure-mode checks. Must exit non-zero on each bad input.
# Requires a running Postgres stack (same as restore-drill.sh).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${PARKIO_ENV_FILE:-${ROOT}/docker/.env}"
FAILED=0

expect_nonzero() {
  local label="$1"
  shift
  local rc=0
  "$@" >/dev/null 2>&1 || rc=$?
  if [ "${rc}" -eq 0 ]; then
    echo "FAIL: ${label} — expected non-zero exit, got 0" >&2
    FAILED=1
  else
    echo "OK: ${label} exited ${rc}"
  fi
}

echo "==> Restore drill failure modes"

expect_nonzero "missing dump" \
  "${ROOT}/scripts/verify-backup.sh" auth /tmp/parkio-restore-drill-missing.sql.gz --env-file "${ENV_FILE}"

expect_nonzero "unknown service" \
  "${ROOT}/scripts/verify-backup.sh" not-a-service /tmp/parkio-restore-drill-missing.sql.gz --env-file "${ENV_FILE}"

expect_nonzero "unknown restore-drill service" \
  "${ROOT}/scripts/restore-drill.sh" --env-file "${ENV_FILE}" --service not-a-service

BAD="$(mktemp "${TMPDIR:-/tmp}/parkio-bad-dump.XXXXXX.sql.gz")"
printf 'this is not a valid pg_dump\n' | gzip -c > "${BAD}"
expect_nonzero "truncated/corrupt dump" \
  "${ROOT}/scripts/verify-backup.sh" auth "${BAD}" --env-file "${ENV_FILE}"
rm -f "${BAD}"

# Encryption round-trip against parking: that DB has real Flyway/PostGIS tables
# after restore-drill. Empty services (auth with no app migrations) would fail
# verify-backup's ">=1 public table" check without weakening it.
ENC_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/parkio-enc-backup.XXXXXX")"
echo "==> Encrypted dump round-trip (CI passphrase, not production)"
if BACKUP_ENCRYPT_PASSPHRASE="parkio-ci-restore-drill-not-prod" \
  PARKIO_ENV_FILE="${ENV_FILE}" \
  BACKUP_DIR="${ENC_ROOT}" \
  BACKUP_DEST_DIR="${ENC_ROOT}/enc" \
  BACKUP_SKIP_MC_UPLOAD=1 \
  "${ROOT}/scripts/backup-databases.sh"; then
  ENC_DUMP="${ENC_ROOT}/enc/parking.sql.gz.enc"
  if [ ! -f "${ENC_DUMP}" ]; then
    echo "FAIL: encrypted parking dump missing" >&2
    FAILED=1
  elif ! BACKUP_ENCRYPT_PASSPHRASE="parkio-ci-restore-drill-not-prod" \
      PARKIO_ENV_FILE="${ENV_FILE}" \
      "${ROOT}/scripts/verify-backup.sh" parking "${ENC_DUMP}"; then
    echo "FAIL: encrypted dump did not verify" >&2
    FAILED=1
  else
    echo "OK: encrypted parking dump restored into disposable verify DB"
  fi
else
  echo "FAIL: encrypted backup-databases.sh failed" >&2
  FAILED=1
fi
rm -rf "${ENC_ROOT}"

if [ "${FAILED}" -ne 0 ]; then
  echo "RESULT: FAIL — one or more failure-mode checks did not fail closed." >&2
  exit 1
fi
echo "RESULT: PASS — missing/corrupt/unknown inputs fail closed; encryption round-trip OK."
