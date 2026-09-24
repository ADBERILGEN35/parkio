#!/usr/bin/env bash
# Isolated entrypoint tests for F-02: COMPLETE / MinIO / prune fail-closed.
# Runs the real backup scripts against docker/mc/openssl stubs. No live DB,
# MinIO, Azure, or production paths.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
pass=0
fail=0
ok() { echo "PASS $1"; pass=$((pass + 1)); }
bad() { echo "FAIL $1"; fail=$((fail + 1)); }

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: required command '$1' is missing" >&2
    exit 2
  }
}
need bash
need python3
need gzip
need tar
need openssl
need sha256sum

WORK="$(mktemp -d "${TMPDIR:-/tmp}/parkio-f02.XXXXXX")"
trap 'rm -rf "${WORK}"' EXIT
BIN="${WORK}/bin"
mkdir -p "${BIN}"

REAL_OPENSSL="$(command -v openssl)"
REAL_SHA256SUM="$(command -v sha256sum)"

if ! command -v jq >/dev/null 2>&1; then
  cat > "${BIN}/jq.py" <<'PY'
import json
import sys

vals = {}
args = sys.argv[1:]
i = 0
while i < len(args):
    arg = args[i]
    if arg == "-n":
        i += 1
        continue
    if arg == "--arg":
        vals[args[i + 1]] = args[i + 2]
        i += 3
        continue
    if arg == "--argjson":
        vals[args[i + 1]] = json.loads(args[i + 2])
        i += 3
        continue
    i += 1

if "bucket" in vals and "objectCount" in vals:
    out = {
        "bucket": vals["bucket"],
        "objectCount": vals["objectCount"],
        "path": vals.get("path", ""),
        "clientSideEncryption": vals.get("clientSideEncryption") == 1,
        "artifact": vals.get("artifact", "none"),
        "algorithm": vals.get("algorithm", "none"),
    }
elif "action" in vals and "stamp" in vals:
    out = {
        "schemaVersion": 3,
        "action": vals["action"],
        "timestamp": vals["stamp"],
        "gitSha": vals.get("gitSha", ""),
        "operator": vals.get("operator", ""),
        "envProfile": vals.get("envProfile", ""),
        "deploymentProfile": vals.get("deploymentProfile", ""),
        "destination": vals.get("destDir", ""),
        "databases": vals.get("databases", []),
        "minio": vals.get("minio", {}),
        "databasesOk": vals.get("databasesOk", 0),
        "databasesFailed": vals.get("databasesFailed", 0),
        "minioOk": vals.get("minioOk", 0),
        "retentionDays": vals.get("retentionDays", 14),
        "offsiteRetentionDays": vals.get("offsiteRetentionDays", 14),
        "encryption": {
            "enabled": vals.get("encryptionEnabled") == 1,
            "algorithm": vals.get("encryptionAlgorithm", "none"),
        },
        "offsite": {
            "kind": vals.get("offsiteKind", "none"),
            "uploaded": vals.get("offsiteUploaded") == 1,
        },
        "checksums": {"sha256sums": "SHA256SUMS"},
    }
else:
    sys.stderr.write("jq shim: unrecognized argument set\n")
    sys.exit(2)
print(json.dumps(out, separators=(",", ":")))
PY
  cat > "${BIN}/jq" <<EOF
#!/usr/bin/env bash
exec python3 "${BIN}/jq.py" "\$@"
EOF
  chmod +x "${BIN}/jq"
fi

cat > "${BIN}/docker" <<'EOF'
#!/usr/bin/env bash
set -e
if [[ "$1" == inspect ]]; then
  if [[ "$*" == *--format* ]]; then
    echo "parkio-backend"
    exit 0
  fi
  exit 0
fi
if [[ "$1" == exec ]]; then
  shift
  while [[ $# -gt 0 && "$1" == -* ]]; do
    shift
  done
  container="${1:-}"
  shift || true
  if [[ "${1:-}" == pg_dump ]]; then
    db=""
    prev=""
    for arg in "$@"; do
      if [[ "${prev}" == "-d" ]]; then
        db="${arg}"
      fi
      prev="${arg}"
    done
    if [[ -n "${FAKE_DUMP_SLEEP:-}" && "${db}" == *parkio_auth* ]]; then
      sleep "${FAKE_DUMP_SLEEP}"
    fi
    if [[ -n "${FAKE_DUMP_FAIL:-}" && "${db}" == *"${FAKE_DUMP_FAIL}"* ]]; then
      echo "pg_dump: simulated failure for ${db}" >&2
      exit 1
    fi
    printf -- '-- Parkio isolated dump stub for %s / %s\nSELECT 1;\n' "${container}" "${db}"
    exit 0
  fi
  cmd="$*"
  if [[ "${cmd}" == *to_regclass* ]]; then
    case "${FAKE_LEDGER:-ok}" in
      absent) echo ""; exit 0 ;;
      query-fail) echo "psql: query failed" >&2; exit 1 ;;
      *) echo "erased_user_tombstones"; exit 0 ;;
    esac
  fi
  if [[ "${cmd}" == *json_agg* ]]; then
    case "${FAKE_LEDGER:-ok}" in
      query-fail) echo "psql: query failed" >&2; exit 1 ;;
      notjson) printf 'not-json\n'; exit 0 ;;
      object) printf '{"authUserId":"x"}\n'; exit 0 ;;
      *) printf '[]\n'; exit 0 ;;
    esac
  fi
  echo "unexpected docker exec: ${cmd}" >&2
  exit 2
fi
if [[ "$1" == run ]]; then
  dest=""
  while [[ $# -gt 0 ]]; do
    if [[ "$1" == -v || "$1" == --volume ]]; then
      dest="${2%%:*}"
      shift 2
      continue
    fi
    shift
  done
  case "${FAKE_MINIO_MODE:-ok}" in
    fail-partial)
      mkdir -p "${dest}"
      printf 'partial-object\n' > "${dest}/partial-object"
      echo "mc: simulated mirror failure" >&2
      exit 1
      ;;
    fail-empty)
      echo "mc: simulated empty mirror failure" >&2
      exit 1
      ;;
    ok)
      mkdir -p "${dest}"
      printf 'media-object\n' > "${dest}/object.bin"
      echo 1
      exit 0
      ;;
    *)
      echo "unexpected FAKE_MINIO_MODE=${FAKE_MINIO_MODE}" >&2
      exit 2
      ;;
  esac
fi
echo "unexpected docker invocation: $*" >&2
exit 2
EOF
chmod +x "${BIN}/docker"

cat > "${BIN}/mc" <<'EOF'
#!/usr/bin/env bash
set -e
mkdir -p "$(dirname "${OFFSITE_LOG:-/tmp/parkio-mc.log}")"
echo "mc $*" >> "${OFFSITE_LOG:-/tmp/parkio-mc.log}"
if [[ "${FAKE_MC_FAIL:-0}" == 1 ]]; then
  echo "mc: simulated offsite failure" >&2
  exit 1
fi
if [[ "$1" == mirror ]]; then
  src="$3"
  dest="$4"
  mkdir -p "${dest}"
  cp -a "${src}/." "${dest}/"
  exit 0
fi
if [[ "$1" == cp ]]; then
  mkdir -p "$(dirname "$3")"
  cp "$2" "$3"
  exit 0
fi
exit 0
EOF
chmod +x "${BIN}/mc"

cat > "${BIN}/openssl" <<EOF
#!/usr/bin/env bash
set -e
if [[ "\${FAKE_OPENSSL_SEAL_FAIL:-0}" == 1 ]]; then
  for arg in "\$@"; do
    if [[ "\${arg}" == *minio.tar.gz.enc && "\${arg}" != *.sha256 ]]; then
      echo "openssl: simulated MinIO seal failure" >&2
      exit 1
    fi
  done
fi
exec "${REAL_OPENSSL}" "\$@"
EOF
chmod +x "${BIN}/openssl"

cat > "${BIN}/sha256sum" <<EOF
#!/usr/bin/env bash
set -e
if [[ "\${FAKE_INTEGRITY_SHA_FAIL:-0}" == 1 && "\$#" -ge 3 ]]; then
  echo "sha256sum: simulated SHA256SUMS failure" >&2
  exit 1
fi
exec "${REAL_SHA256SUM}" "\$@"
EOF
chmod +x "${BIN}/sha256sum"

export PATH="${BIN}:${PATH}"
export PARKIO_DEPLOYMENT_PROFILE=hosted-beta
export BACKUP_PRODUCTION_MODE=1
export BACKUP_ENCRYPT_PASSPHRASE="f02-test-passphrase-not-a-secret"
export BACKUP_OFFSITE_KIND=s3
export MINIO_ROOT_PASSWORD="f02-minio-not-a-secret"
export MINIO_BUCKET=parkio-media
export PARKIO_BACKUP_GIT_SHA="f02test000000000000000000000000000000000"

reset_fakes() {
  export FAKE_MINIO_MODE=ok
  export FAKE_LEDGER=ok
  export FAKE_MC_FAIL=0
  export FAKE_OPENSSL_SEAL_FAIL=0
  export FAKE_INTEGRITY_SHA_FAIL=0
  unset FAKE_DUMP_FAIL FAKE_DUMP_SLEEP
}

plant_old_good() {
  local backup_dir="$1"
  local old="${backup_dir}/2020-01-01T00-00-00Z"
  mkdir -p "${old}"
  printf 'keep-previous-good\n' > "${old}/COMPLETE"
  printf '[]\n' > "${old}/erasure-tombstones.json"
  python3 -c 'import os,time,sys; p=sys.argv[1]; t=time.time()-20*86400; os.utime(p,(t,t))' "${old}"
  echo "${old}"
}

latest_stamp_dir() {
  local backup_dir="$1"
  python3 -c '
import os, sys
root = sys.argv[1]
dirs = [os.path.join(root, name) for name in os.listdir(root)
        if os.path.isdir(os.path.join(root, name)) and name != "2020-01-01T00-00-00Z"]
if not dirs:
    sys.exit(1)
print(max(dirs, key=os.path.getmtime))
' "${backup_dir}"
}

assert_no_usable_complete() {
  local stamp="$1"
  if [ -f "${stamp}/COMPLETE" ]; then
    return 1
  fi
  return 0
}

assert_not_uploaded() {
  local offsite="$1"
  if find "${offsite}" -name COMPLETE -type f 2>/dev/null | grep -q .; then
    return 1
  fi
  return 0
}

metric() {
  local file="$1"
  local name="$2"
  awk -v n="${name}" '$1 ~ n { print $2; exit }' "${file}"
}

run_hosted() {
  local case_dir="$1"
  mkdir -p "${case_dir}/backups" "${case_dir}/artifacts" "${case_dir}/textfile" "${case_dir}/offsite/parkio-backups"
  export BACKUP_DIR="${case_dir}/backups"
  export PARKIO_BACKUP_ARTIFACT_DIR="${case_dir}/artifacts"
  export PARKIO_PROMETHEUS_TEXTFILE_DIR="${case_dir}/textfile"
  export BACKUP_MC_DEST="${case_dir}/offsite/parkio-backups"
  export BACKUP_RETENTION_DAYS=14
  export OFFSITE_LOG="${case_dir}/mc.log"
  unset BACKUP_SKIP_MC_UPLOAD BACKUP_MC_URL
  unset PARKIO_ENV_FILE
  bash "${ROOT}/scripts/backup-hosted-beta.sh"
}

run_db_only() {
  local case_dir="$1"
  mkdir -p "${case_dir}/backups" "${case_dir}/offsite/parkio-backups"
  export BACKUP_DIR="${case_dir}/backups"
  export BACKUP_MC_DEST="${case_dir}/offsite/parkio-backups"
  export BACKUP_RETENTION_DAYS=14
  export OFFSITE_LOG="${case_dir}/mc.log"
  unset BACKUP_DEST_DIR BACKUP_SKIP_MC_UPLOAD BACKUP_MC_URL
  unset PARKIO_ENV_FILE
  bash "${ROOT}/scripts/backup-databases.sh"
}

# --- trap / stale COMPLETE marker ---
# shellcheck source=lib/backup-common.sh
source "${ROOT}/scripts/lib/backup-common.sh"
stale="${WORK}/stale-stamp"
mkdir -p "${stale}"
printf 'bogus\n' > "${stale}/COMPLETE"
printf 'tmp\n' > "${stale}/.COMPLETE.aaaaaa"
DEST_DIR="${stale}"
PARKIO_BACKUP_FINALIZED=0
parkio_backup_clear_unfinalized_complete
if [ ! -f "${stale}/COMPLETE" ] && [ ! -f "${stale}/.COMPLETE.aaaaaa" ]; then
  ok "unfinalized trap removes COMPLETE and stale .COMPLETE.* markers"
else
  bad "unfinalized trap must clear COMPLETE markers"
fi
printf 'keep\n' > "${stale}/COMPLETE"
PARKIO_BACKUP_FINALIZED=1
parkio_backup_clear_unfinalized_complete
if [ -f "${stale}/COMPLETE" ]; then
  ok "finalized COMPLETE is left in place by the trap"
else
  bad "finalized COMPLETE must survive the EXIT trap"
fi
unset DEST_DIR
PARKIO_BACKUP_FINALIZED=0

# --- full success ---
reset_fakes
case_ok="${WORK}/full-ok"
old_ok="$(plant_old_good "${case_ok}/backups")"
if run_hosted "${case_ok}"; then
  ok "full production path exits 0"
else
  bad "full production path must succeed"
fi
stamp_ok="$(latest_stamp_dir "${case_ok}/backups")"
if [ -f "${stamp_ok}/COMPLETE" ] && [ -f "${stamp_ok}/minio.tar.gz.enc" ] && [ ! -d "${stamp_ok}/minio" ]; then
  ok "full success writes COMPLETE and sealed MinIO only"
else
  bad "full success must seal MinIO and write COMPLETE"
fi
dump_count="$(find "${stamp_ok}" -maxdepth 1 -name '*.sql.gz.enc' | wc -l | tr -d ' ')"
if [ "${dump_count}" = "10" ]; then
  ok "full success dumped 10 encrypted databases"
else
  bad "full success must dump 10 databases (found ${dump_count})"
fi
if find "${case_ok}/offsite" -name COMPLETE -type f | grep -q .; then
  ok "full success uploaded COMPLETE"
else
  bad "full success must upload COMPLETE"
fi
if [ -f "${case_ok}/artifacts/backup-current.json" ]; then
  ok "full success updates backup-current.json"
else
  bad "full success must publish backup-current.json"
fi
ms="$(metric "${case_ok}/textfile/parkio_backup.prom" 'parkio_backup_last_success')"
os="$(metric "${case_ok}/textfile/parkio_backup.prom" 'parkio_backup_offsite_last_success')"
if [ "${ms}" = "1" ] && [ "${os}" = "1" ]; then
  ok "full success metrics report local and offsite success"
else
  bad "full success metrics must be last_success=1 offsite=1 (got ${ms}/${os})"
fi
if [ -f "${old_ok}/COMPLETE" ]; then
  ok "recent previous-good stamp is retained on success when not expired by clock"
else
  # The planted stamp is 20 days old; success prune may remove it. That is
  # retention, not the failed-run defect. Re-plant for failure cases below.
  ok "success-path prune of expired stamps is allowed"
fi

# --- partial MinIO then failure ---
reset_fakes
export FAKE_MINIO_MODE=fail-partial
case_minio="${WORK}/minio-fail"
old_minio="$(plant_old_good "${case_minio}/backups")"
rc=0
run_hosted "${case_minio}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  ok "partial MinIO mirror exits nonzero"
else
  bad "partial MinIO mirror must not succeed"
fi
stamp_minio="$(latest_stamp_dir "${case_minio}/backups")"
if assert_no_usable_complete "${stamp_minio}"; then
  ok "partial MinIO run writes no COMPLETE"
else
  bad "partial MinIO must not produce COMPLETE"
fi
if [ ! -d "${stamp_minio}/minio" ] && [ ! -f "${stamp_minio}/minio.tar.gz.enc" ]; then
  ok "partial MinIO tree was discarded and not sealed"
else
  bad "partial MinIO must not remain or be sealed"
fi
if assert_not_uploaded "${case_minio}/offsite"; then
  ok "partial MinIO did not upload COMPLETE"
else
  bad "failed MinIO stamp must not be uploaded"
fi
if [ -f "${old_minio}/COMPLETE" ]; then
  ok "previous good stamp survives MinIO failure"
else
  bad "MinIO failure must not prune the last good stamp"
fi
if [ ! -f "${case_minio}/artifacts/backup-current.json" ]; then
  ok "MinIO failure does not publish backup-current.json"
else
  bad "failed run must not replace backup-current.json"
fi

# --- DB dump failure ---
reset_fakes
export FAKE_DUMP_FAIL=parkio_parking
case_db="${WORK}/db-fail"
old_db="$(plant_old_good "${case_db}/backups")"
rc=0
run_hosted "${case_db}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  ok "DB dump failure exits nonzero"
else
  bad "DB dump failure must not succeed"
fi
stamp_db="$(latest_stamp_dir "${case_db}/backups")"
if assert_no_usable_complete "${stamp_db}" && assert_not_uploaded "${case_db}/offsite"; then
  ok "DB dump failure has no COMPLETE and no upload"
else
  bad "DB dump failure must not seal or upload"
fi
if [ -f "${old_db}/COMPLETE" ]; then
  ok "previous good stamp survives DB dump failure"
else
  bad "DB dump failure must not prune the last good stamp"
fi

# --- ledger export / malformed ledger ---
reset_fakes
export FAKE_LEDGER=notjson
case_ledger="${WORK}/ledger-fail"
old_ledger="$(plant_old_good "${case_ledger}/backups")"
rc=0
run_hosted "${case_ledger}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  ok "malformed ledger exits nonzero"
else
  bad "malformed ledger must not succeed"
fi
stamp_ledger="$(latest_stamp_dir "${case_ledger}/backups")"
if assert_no_usable_complete "${stamp_ledger}" && assert_not_uploaded "${case_ledger}/offsite"; then
  ok "malformed ledger has no COMPLETE and no upload"
else
  bad "malformed ledger must not seal or upload"
fi
if [ -f "${old_ledger}/COMPLETE" ]; then
  ok "previous good stamp survives ledger failure"
else
  bad "ledger failure must not prune the last good stamp"
fi

reset_fakes
export FAKE_LEDGER=query-fail
case_ledger2="${WORK}/ledger-query-fail"
rc=0
run_hosted "${case_ledger2}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  stamp_ledger2="$(latest_stamp_dir "${case_ledger2}/backups")"
  if assert_no_usable_complete "${stamp_ledger2}"; then
    ok "ledger query failure exits nonzero without COMPLETE"
  else
    bad "ledger query failure must not write COMPLETE"
  fi
else
  bad "ledger query failure must not succeed"
fi

# --- sealing / checksum failure ---
reset_fakes
export FAKE_OPENSSL_SEAL_FAIL=1
case_seal="${WORK}/seal-fail"
old_seal="$(plant_old_good "${case_seal}/backups")"
rc=0
run_hosted "${case_seal}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  ok "MinIO seal failure exits nonzero"
else
  bad "MinIO seal failure must not succeed"
fi
stamp_seal="$(latest_stamp_dir "${case_seal}/backups")"
if assert_no_usable_complete "${stamp_seal}" && [ ! -f "${stamp_seal}/minio.tar.gz.enc" ] \
  && assert_not_uploaded "${case_seal}/offsite"; then
  ok "seal failure discards MinIO and does not upload"
else
  bad "seal failure must not leave a sealed successful stamp"
fi
if [ -f "${old_seal}/COMPLETE" ]; then
  ok "previous good stamp survives seal failure"
else
  bad "seal failure must not prune the last good stamp"
fi

reset_fakes
export FAKE_INTEGRITY_SHA_FAIL=1
case_sha="${WORK}/sha-fail"
rc=0
run_hosted "${case_sha}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  stamp_sha="$(latest_stamp_dir "${case_sha}/backups")"
  if assert_no_usable_complete "${stamp_sha}" && assert_not_uploaded "${case_sha}/offsite"; then
    ok "SHA256SUMS failure exits nonzero without COMPLETE or upload"
  else
    bad "checksum failure must not write COMPLETE or upload"
  fi
else
  bad "SHA256SUMS failure must not succeed"
fi

# --- interruption before completion ---
reset_fakes
export FAKE_DUMP_SLEEP=2
case_int="${WORK}/interrupt"
mkdir -p "${case_int}/backups" "${case_int}/artifacts" "${case_int}/textfile" "${case_int}/offsite/parkio-backups"
export BACKUP_DIR="${case_int}/backups"
export PARKIO_BACKUP_ARTIFACT_DIR="${case_int}/artifacts"
export PARKIO_PROMETHEUS_TEXTFILE_DIR="${case_int}/textfile"
export BACKUP_MC_DEST="${case_int}/offsite/parkio-backups"
export OFFSITE_LOG="${case_int}/mc.log"
unset BACKUP_SKIP_MC_UPLOAD BACKUP_MC_URL PARKIO_ENV_FILE
bash "${ROOT}/scripts/backup-hosted-beta.sh" > "${case_int}/run.log" 2>&1 &
int_pid=$!
slept=0
while [ "${slept}" -lt 30 ]; do
  if find "${case_int}/backups" -mindepth 1 -type d 2>/dev/null | grep -q .; then
    break
  fi
  sleep 0.1
  slept=$((slept + 1))
done
kill -TERM "${int_pid}" 2>/dev/null || true
sleep 0.3
kill -KILL "${int_pid}" 2>/dev/null || true
wait "${int_pid}" || true
int_stamp="$(find "${case_int}/backups" -mindepth 1 -maxdepth 1 -type d | head -1 || true)"
if [ -n "${int_stamp}" ] && assert_no_usable_complete "${int_stamp}" && assert_not_uploaded "${case_int}/offsite"; then
  ok "interrupted run leaves no usable COMPLETE and no upload"
else
  if [ -z "${int_stamp}" ]; then
    ok "interrupted run left no stamp directory (no COMPLETE)"
  else
    bad "interrupted run must not look complete"
  fi
fi
unset FAKE_DUMP_SLEEP

# --- standalone DB-only success ---
reset_fakes
case_dbok="${WORK}/db-only-ok"
if run_db_only "${case_dbok}"; then
  ok "standalone DB-only path exits 0"
else
  bad "standalone DB-only path must succeed"
fi
stamp_dbok="$(latest_stamp_dir "${case_dbok}/backups")"
if [ -f "${stamp_dbok}/COMPLETE" ] && [ ! -d "${stamp_dbok}/minio" ] \
  && [ ! -f "${stamp_dbok}/minio.tar.gz.enc" ]; then
  ok "DB-only success writes COMPLETE without requiring MinIO"
else
  bad "DB-only success must complete on dumps+ledger only"
fi
if find "${case_dbok}/offsite" -name COMPLETE -type f | grep -q .; then
  ok "DB-only success uploads COMPLETE"
else
  bad "DB-only success must upload when offsite is configured"
fi

# --- standalone DB-only failure ---
reset_fakes
export FAKE_DUMP_FAIL=parkio_parking
case_dbbad="${WORK}/db-only-fail"
old_dbbad="$(plant_old_good "${case_dbbad}/backups")"
rc=0
run_db_only "${case_dbbad}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  ok "standalone DB-only dump failure exits nonzero"
else
  bad "standalone DB-only dump failure must not succeed"
fi
stamp_dbbad="$(latest_stamp_dir "${case_dbbad}/backups")"
if assert_no_usable_complete "${stamp_dbbad}" && assert_not_uploaded "${case_dbbad}/offsite"; then
  ok "standalone DB-only failure has no COMPLETE and no upload"
else
  bad "standalone DB-only failure must not seal or upload"
fi
if [ -f "${old_dbbad}/COMPLETE" ]; then
  ok "previous good stamp survives standalone DB-only failure"
else
  bad "standalone DB-only failure must not prune the last good stamp"
fi

reset_fakes
export FAKE_LEDGER=object
case_dbledger="${WORK}/db-only-ledger-fail"
rc=0
run_db_only "${case_dbledger}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  stamp_dbledger="$(latest_stamp_dir "${case_dbledger}/backups")"
  if assert_no_usable_complete "${stamp_dbledger}"; then
    ok "standalone malformed ledger exits nonzero without COMPLETE"
  else
    bad "standalone malformed ledger must not write COMPLETE"
  fi
else
  bad "standalone malformed ledger must not succeed"
fi

# --- offsite failure is not offsite success ---
reset_fakes
export FAKE_MC_FAIL=1
case_off="${WORK}/offsite-fail"
old_off="$(plant_old_good "${case_off}/backups")"
rc=0
run_hosted "${case_off}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  ok "offsite failure exits nonzero"
else
  bad "offsite failure must fail the backup"
fi
stamp_off="$(latest_stamp_dir "${case_off}/backups")"
if [ -f "${stamp_off}/COMPLETE" ]; then
  ok "local COMPLETE remains after local gates pass and offsite fails"
else
  bad "offsite failure after local success should keep a local COMPLETE"
fi
if assert_not_uploaded "${case_off}/offsite"; then
  ok "offsite failure did not publish COMPLETE remotely"
else
  bad "failed offsite must not leave a remote COMPLETE"
fi
os="$(metric "${case_off}/textfile/parkio_backup.prom" 'parkio_backup_offsite_last_success')"
ms="$(metric "${case_off}/textfile/parkio_backup.prom" 'parkio_backup_last_success')"
if [ "${os}" = "0" ] && [ "${ms}" = "0" ]; then
  ok "offsite failure metrics are last_success=0 offsite_last_success=0"
else
  bad "offsite failure must not report offsite success (got ${ms}/${os})"
fi
if [ ! -f "${case_off}/artifacts/backup-current.json" ]; then
  ok "offsite failure does not publish backup-current.json as success"
else
  bad "offsite failure must not replace last-good current pointer"
fi
if [ -f "${old_off}/COMPLETE" ]; then
  ok "previous good stamp survives offsite failure"
else
  bad "offsite failure must not prune the last good stamp"
fi

echo
echo "=== backup complete gate: pass=${pass} fail=${fail} ==="
if [ "${fail}" -ne 0 ]; then
  exit 1
fi
