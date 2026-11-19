#!/usr/bin/env bash
# Focused failure-injection for FU-1: production backups must not write COMPLETE
# or call offsite when a dump or erasure-ledger export fails.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/backup-common.sh
source "${ROOT}/scripts/lib/backup-common.sh"
# shellcheck source=lib/erasure-tombstones.sh
source "${ROOT}/scripts/lib/erasure-tombstones.sh"

pass=0
fail=0
ok() { echo "PASS $1"; pass=$((pass + 1)); }
bad() { echo "FAIL $1"; fail=$((fail + 1)); }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/parkio-fu1.XXXXXX")"
trap 'rm -rf "${WORK}"' EXIT
BIN="${WORK}/bin"
mkdir -p "${BIN}"

cat > "${BIN}/docker" <<'EOF'
#!/usr/bin/env bash
set -e
cmd="$*"
if [[ "${cmd}" == *to_regclass* ]]; then
  case "${FAKE_TOMBSTONE_TABLE:-present}" in
    absent) echo ""; exit 0 ;;
    fail) echo "psql: query failed" >&2; exit 1 ;;
    *) echo "erased_user_tombstones"; exit 0 ;;
  esac
fi
if [[ "${cmd}" == *json_agg* ]]; then
  case "${FAKE_TOMBSTONE_QUERY:-ok}" in
    fail) echo "psql: query failed" >&2; exit 1 ;;
    notjson) printf 'not-json\n'; exit 0 ;;
    object) printf '{"authUserId":"x"}\n'; exit 0 ;;
    *) printf '[]\n'; exit 0 ;;
  esac
fi
echo "unexpected docker invocation: ${cmd}" >&2
exit 2
EOF
chmod +x "${BIN}/docker"
export PATH="${BIN}:${PATH}"
export FAKE_TOMBSTONE_TABLE=present
export FAKE_TOMBSTONE_QUERY=ok
export BACKUP_PRODUCTION_MODE=0

stamp_ok="${WORK}/good"
stamp_bad="${WORK}/bad"
mkdir -p "${stamp_ok}" "${stamp_bad}"
printf '[]\n' > "${stamp_ok}/erasure-tombstones.json"

# --- ledger validate ---
parkio_erasure_ledger_validate "${stamp_ok}/erasure-tombstones.json" \
  && ok "valid empty JSON array is accepted" \
  || bad "valid empty JSON array must be accepted"
printf '{"no":"array"}\n' > "${stamp_bad}/erasure-tombstones.json"
if parkio_erasure_ledger_validate "${stamp_bad}/erasure-tombstones.json" 2>/dev/null; then
  bad "non-array ledger must fail"
else
  ok "non-array ledger fails closed"
fi

# --- COMPLETE gate ---
parkio_backup_allow_complete "${stamp_ok}" 0 \
  && ok "COMPLETE allowed when dumps ok and ledger is an array" \
  || bad "COMPLETE must be allowed for a valid stamp"
if parkio_backup_allow_complete "${stamp_ok}" 3 2>/dev/null; then
  bad "COMPLETE must be refused when DB_FAILED>0"
else
  ok "COMPLETE refused when DB_FAILED>0"
fi
if parkio_backup_allow_complete "${WORK}/missing-dir" 0 2>/dev/null; then
  bad "COMPLETE must be refused when ledger file is missing"
else
  ok "COMPLETE refused when ledger file is missing"
fi
if parkio_backup_allow_complete "${stamp_bad}" 0 2>/dev/null; then
  bad "COMPLETE must be refused when ledger is not an array"
else
  ok "COMPLETE refused when ledger is not an array"
fi

# Existing COMPLETE in another stamp is not touched by the gate.
keep="${WORK}/existing-valid"
mkdir -p "${keep}"
printf '[]\n' > "${keep}/erasure-tombstones.json"
printf 'stamp=keep\nsha256sums=abc\n' > "${keep}/COMPLETE"
if ! parkio_backup_allow_complete "${stamp_bad}" 0 2>/dev/null; then
  if [ -f "${keep}/COMPLETE" ]; then
    ok "existing COMPLETE stamp left untouched"
  else
    bad "must not delete an existing valid COMPLETE"
  fi
fi

# --- export: table absent ---
export_dir="${WORK}/export"
mkdir -p "${export_dir}"
export FAKE_TOMBSTONE_TABLE=absent BACKUP_PRODUCTION_MODE=1
if parkio_export_erasure_tombstones "${export_dir}" 2>/dev/null; then
  bad "production export must fail when tombstone table is absent"
else
  ok "production export fails when tombstone table is absent"
fi
[ ! -s "${export_dir}/erasure-tombstones.json" ] \
  && ok "production absent-table path does not write a success ledger" \
  || bad "production absent-table must not leave a usable ledger"

export FAKE_TOMBSTONE_TABLE=absent BACKUP_PRODUCTION_MODE=0
rm -f "${export_dir}/erasure-tombstones.json"
if parkio_export_erasure_tombstones "${export_dir}"; then
  ok "dev export writes [] when table is absent"
else
  bad "dev export must tolerate an absent table"
fi

# --- export: query failure ---
export FAKE_TOMBSTONE_TABLE=present FAKE_TOMBSTONE_QUERY=fail BACKUP_PRODUCTION_MODE=1
rm -f "${export_dir}/erasure-tombstones.json"
if parkio_export_erasure_tombstones "${export_dir}" 2>/dev/null; then
  bad "production export must fail when the tombstone query fails"
else
  ok "production export fails when the tombstone query fails"
fi
[ ! -f "${export_dir}/erasure-tombstones.json" ] \
  && ok "failed query does not leave a ledger file" \
  || bad "failed query must remove the incomplete ledger"

export FAKE_TOMBSTONE_QUERY=ok BACKUP_PRODUCTION_MODE=0
if parkio_export_erasure_tombstones "${export_dir}"; then
  ok "dev export succeeds when the query returns a JSON array"
else
  bad "dev export should succeed for a valid empty query"
fi

# --- backup-databases.sh || true is gone ---
if grep -E 'parkio_export_erasure_tombstones .*\| *true' "${ROOT}/scripts/backup-databases.sh"; then
  bad "backup-databases.sh must not ignore ledger export failures"
else
  ok "backup-databases.sh no longer ignores ledger export failures"
fi
grep -q 'parkio_backup_allow_complete' "${ROOT}/scripts/backup-hosted-beta.sh" \
  && ok "hosted-beta gates COMPLETE on allow_complete" \
  || bad "hosted-beta must call parkio_backup_allow_complete"

# --- hosted-beta dry path: refuse COMPLETE/offsite when ledger missing ---
# Simulate the gate the orchestrator uses; do not invoke Azure/S3.
sim="${WORK}/sim-stamp"
mkdir -p "${sim}"
OFFSITE_CALLED=0
parkio_backup_offsite_upload() { OFFSITE_CALLED=1; return 0; }
if parkio_backup_allow_complete "${sim}" 0 2>/dev/null; then
  parkio_backup_write_stamp_integrity "${sim}" "sim"
  parkio_backup_offsite_upload "${sim}" "" "sim"
fi
[ "${OFFSITE_CALLED}" -eq 0 ] && [ ! -f "${sim}/COMPLETE" ] \
  && ok "missing ledger: no COMPLETE and no offsite call" \
  || bad "missing ledger must not seal or upload"

printf '[]\n' > "${sim}/erasure-tombstones.json"
if parkio_backup_allow_complete "${sim}" 1 2>/dev/null; then
  parkio_backup_write_stamp_integrity "${sim}" "sim"
  parkio_backup_offsite_upload "${sim}" "" "sim"
fi
[ "${OFFSITE_CALLED}" -eq 0 ] && [ ! -f "${sim}/COMPLETE" ] \
  && ok "DB_FAILED>0: no COMPLETE and no offsite call" \
  || bad "dump failure must not seal or upload"

if parkio_backup_allow_complete "${stamp_ok}" 0 0 2>/dev/null; then
  bad "COMPLETE must be refused when minioOk=0"
else
  ok "COMPLETE refused when minioOk=0"
fi
if parkio_backup_allow_complete "${stamp_ok}" 0 1 2>/dev/null; then
  bad "COMPLETE must be refused when MinIO artifact is missing"
else
  ok "COMPLETE refused when MinIO artifact is missing"
fi
mkdir -p "${stamp_ok}/minio"
if parkio_backup_allow_complete "${stamp_ok}" 0 1; then
  ok "COMPLETE allowed when minioOk=1 and plaintext MinIO tree exists"
else
  bad "COMPLETE must be allowed for a valid DB+MinIO stamp"
fi
rm -rf "${stamp_ok}/minio"
touch "${stamp_ok}/minio.tar.gz.enc"
export BACKUP_ENCRYPT_PASSPHRASE="fu1-test-not-a-secret"
if parkio_backup_allow_complete "${stamp_ok}" 0 1; then
  ok "COMPLETE allowed when minioOk=1 and MinIO is sealed"
else
  bad "COMPLETE must be allowed for a sealed MinIO stamp"
fi
mkdir -p "${stamp_ok}/minio"
if parkio_backup_allow_complete "${stamp_ok}" 0 1 2>/dev/null; then
  bad "COMPLETE must be refused when plaintext MinIO remains after seal"
else
  ok "COMPLETE refused when sealed stamp still has plaintext MinIO"
fi
unset BACKUP_ENCRYPT_PASSPHRASE
rm -f "${stamp_ok}/minio.tar.gz.enc"
rm -rf "${stamp_ok}/minio"
grep -q 'parkio_backup_allow_complete "${DEST_DIR}" "${DB_FAILED}" "${MINIO_OK}"' \
  "${ROOT}/scripts/backup-hosted-beta.sh" \
  && ok "hosted-beta passes MINIO_OK into allow_complete" \
  || bad "hosted-beta must gate COMPLETE on MINIO_OK"

echo
echo "=== backup production fail-closed: pass=${pass} fail=${fail} ==="
if [ "${fail}" -ne 0 ]; then
  exit 1
fi
