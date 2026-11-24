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

# --- #520 gcs offsite kind: chosen explicitly, checked at preflight, refused on unsafe input ---
# A gcloud stub that only logs: preflight must not run gcloud, and a refused upload must not reach it.
cat > "${BIN}/gcloud" <<'EOF'
#!/usr/bin/env bash
echo "gcloud $*" >> "${GCLOUD_LOG:-/dev/null}"
exit 0
EOF
chmod +x "${BIN}/gcloud"
export GCLOUD_LOG="${WORK}/gcloud.log"
GCS_OK_DEST=gs://parkio-synthetic-backups/stamps

# One preflight in a subshell with only the given offsite settings; prints its exit status.
gcs_preflight() {
  (
    unset BACKUP_OFFSITE_KIND BACKUP_MC_DEST BACKUP_AZURE_STORAGE_ACCOUNT BACKUP_AZURE_CONTAINER \
      BACKUP_GCS_DEST BACKUP_GCLOUD_BIN
    export BACKUP_PRODUCTION_MODE=1 BACKUP_ENCRYPT_PASSPHRASE="fu1-test-not-a-secret"
    for assignment in "$@"; do export "${assignment?}"; done
    status=0
    parkio_backup_preflight 2>/dev/null || status=$?
    echo "${status}"
  )
}

if [ "$(gcs_preflight BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_OK_DEST}")" = 0 ] \
  && [ "$(gcs_preflight BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_OK_DEST}/")" = 0 ] \
  && [ "$(gcs_preflight BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST=gs://parkio.synthetic_backups/a/b-c.d)" = 0 ] \
  && [ "$(gcs_preflight BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_OK_DEST}" BACKUP_GCLOUD_BIN="${BIN}/gcloud")" = 0 ]; then
  ok "production preflight accepts BACKUP_OFFSITE_KIND=gcs with a gs://<bucket>/<prefix> destination and gcloud"
else
  bad "production preflight must accept a valid gcs destination"
fi
kind_auto="$(unset BACKUP_OFFSITE_KIND BACKUP_MC_DEST BACKUP_AZURE_STORAGE_ACCOUNT BACKUP_AZURE_CONTAINER
  BACKUP_GCS_DEST="${GCS_OK_DEST}" parkio_backup_offsite_kind)"
auto_msg="$( (unset BACKUP_OFFSITE_KIND BACKUP_MC_DEST BACKUP_AZURE_STORAGE_ACCOUNT BACKUP_AZURE_CONTAINER
  export BACKUP_PRODUCTION_MODE=1 BACKUP_ENCRYPT_PASSPHRASE="fu1-test-not-a-secret" BACKUP_GCS_DEST="${GCS_OK_DEST}"
  parkio_backup_preflight) 2>&1 >/dev/null || true)"
if [ "${kind_auto}" = none ] && [ "$(gcs_preflight BACKUP_GCS_DEST="${GCS_OK_DEST}")" = 2 ] \
  && grep -q 'BACKUP_OFFSITE_KIND=gcs' <<< "${auto_msg}"; then
  ok "BACKUP_GCS_DEST alone selects no offsite: production preflight fails and names BACKUP_OFFSITE_KIND=gcs"
else
  bad "gcs must be chosen explicitly (kind without it: ${kind_auto}; message: ${auto_msg})"
fi
gcs_bad_dest=0
for dest in "" "gs://parkio-synthetic-backups" "gs://parkio-synthetic-backups/" "gs://-parkio/stamps" \
  "gs://parkio-synthetic-/stamps" "gs://parkio..synthetic/stamps" \
  "gs://parkio-synthetic-backups/stamps/**" "gs://parkio-synthetic-backups/./stamps" \
  "gs://parkio-synthetic-backups/stamps/.." "gs://parkio-synthetic-backups/st?mps" \
  "gs://parkio-synthetic-backups/[a]" "gs://parkio-synthetic-backups/stamps"$'\n'"x"; do
  for production in 1 0; do
    if [ "$(gcs_preflight BACKUP_PRODUCTION_MODE="${production}" BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${dest}")" != 2 ]; then
      gcs_bad_dest=$((gcs_bad_dest + 1))
      echo "  accepted BACKUP_GCS_DEST='${dest}' (production=${production})"
    fi
  done
done
if [ "${gcs_bad_dest}" -eq 0 ]; then
  ok "preflight refuses a missing or malformed BACKUP_GCS_DEST, in production mode and outside it"
else
  bad "preflight accepted ${gcs_bad_dest} malformed BACKUP_GCS_DEST values"
fi
mkdir -p "${WORK}/not-gcloud"
printf '#!/bin/sh\nexit 0\n' > "${WORK}/not-gcloud/gcloud"
if [ "$(gcs_preflight BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_OK_DEST}" BACKUP_GCLOUD_BIN="${WORK}/missing/gcloud")" = 2 ] \
  && [ "$(gcs_preflight BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_OK_DEST}" BACKUP_GCLOUD_BIN=gcloud)" = 2 ] \
  && [ "$(gcs_preflight BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_OK_DEST}" BACKUP_GCLOUD_BIN="${WORK}/not-gcloud")" = 2 ] \
  && [ "$(gcs_preflight BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_OK_DEST}" BACKUP_GCLOUD_BIN="${WORK}/not-gcloud/gcloud")" = 2 ] \
  && [ "$(cd "${BIN}" && gcs_preflight BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_OK_DEST}" BACKUP_GCLOUD_BIN=gcloud)" = 2 ]; then
  ok "preflight refuses a BACKUP_GCLOUD_BIN that is missing, relative (even if it resolves), a directory or not executable"
else
  bad "preflight must refuse a BACKUP_GCLOUD_BIN that is not an executable absolute path"
fi

# The real upload (re-sourced: the simulation above replaced parkio_backup_offsite_upload) refuses
# what it cannot upload as one flat, create-only stamp, before any gcloud call.
gcs_upload() { # stamp-dir [stamp]; prints the exit status
  (
    # shellcheck source=lib/backup-common.sh
    source "${ROOT}/scripts/lib/backup-common.sh"
    export BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_OK_DEST}"
    status=0
    parkio_backup_offsite_upload "$1" "" "${2:-$(basename "$1")}" > /dev/null 2>&1 || status=$?
    echo "${status}"
  )
}
fake_seal() { printf 'stamp=%s\nsha256sums=%064d\n' "$(basename "$1")" 0 > "$1/COMPLETE"; }
up="${WORK}/gcs-upload"
mkdir -p "${up}/unsealed" "${up}/nested/sub" "${up}/spaced" "${up}/flat"
printf 'x\n' > "${up}/unsealed/data.bin"
printf 'x\n' > "${up}/nested/sub/data.bin"
printf 'x\n' > "${up}/spaced/a b.bin"
printf 'x\n' > "${up}/flat/data.bin"
fake_seal "${up}/nested"
fake_seal "${up}/spaced"
fake_seal "${up}/flat"
: > "${GCLOUD_LOG}"
if [ "$(gcs_upload "${up}/unsealed")" != 0 ] && [ "$(gcs_upload "${up}/nested")" != 0 ] \
  && [ "$(gcs_upload "${up}/spaced")" != 0 ] && [ "$(gcs_upload "${up}/flat" "../escape")" != 0 ] \
  && [ "$(gcs_upload "${up}/flat" "2026-01-01T00-00-00Z/x")" != 0 ] && [ ! -s "${GCLOUD_LOG}" ]; then
  ok "gcs upload refuses an unsealed stamp, a nested file, an unsafe name or stamp, before calling gcloud"
else
  bad "gcs upload must refuse unsafe input before calling gcloud ($(wc -l < "${GCLOUD_LOG}") calls)"
fi
gcs_read() { # list|pull stamp; prints the exit status
  (
    export BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_OK_DEST}"
    status=0
    if [ "$1" = list ]; then
      parkio_backup_offsite_list "$2" > /dev/null 2>&1 || status=$?
    else
      parkio_backup_offsite_pull "${up}/pulled-$$" "$2" > /dev/null 2>&1 || status=$?
    fi
    echo "${status}"
  )
}
: > "${GCLOUD_LOG}"
if [ "$(gcs_read list '*')" != 0 ] && [ "$(gcs_read list '2026-01-01T00-00-00Z/..')" != 0 ] \
  && [ "$(gcs_read list '..')" != 0 ] && [ "$(gcs_read pull '..')" != 0 ] && [ "$(gcs_read pull 'a/b')" != 0 ] \
  && [ ! -s "${GCLOUD_LOG}" ]; then
  ok "gcs listing and pull refuse a stamp that is not one safe path segment, before calling gcloud"
else
  bad "gcs listing and pull must refuse an unsafe stamp before calling gcloud ($(wc -l < "${GCLOUD_LOG}") calls)"
fi

echo
echo "=== backup production fail-closed: pass=${pass} fail=${fail} ==="
if [ "${fail}" -ne 0 ]; then
  exit 1
fi
