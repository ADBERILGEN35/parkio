#!/usr/bin/env bash
#
# Isolated encrypted OFFSITE backup drill.
#
# Proves: local encrypted backup -> offsite upload -> delete local stamp ->
# pull to a new directory -> checksum verify -> restore all 10 DBs + MinIO
# from the retrieved copy only.
#
# Default offsite is an ephemeral second MinIO (S3/mc) on the compose network.
# Set BACKUP_OFFSITE_KIND=azure plus account/container for Azure Blob.
#
# Never targets hosted-beta or production. Never prints secrets.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=lib/backup-common.sh
source "${SCRIPT_DIR}/lib/backup-common.sh"
# shellcheck source=lib/restore-drill-services.sh
source "${SCRIPT_DIR}/lib/restore-drill-services.sh"

ENV_FILE="${PARKIO_ENV_FILE:-${ROOT}/docker/.env}"
STARTED_OFFSITE=0
OFFSITE_NAME="${BACKUP_OFFSITE_MINIO_CONTAINER:-parkio-offsite-minio}"
MC_IMAGE="${MINIO_MC_IMAGE:-minio/mc:RELEASE.2024-09-16T17-43-14Z}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

parkio_backup_load_env "${ENV_FILE}"

cleanup() {
  if [ "${STARTED_OFFSITE}" -eq 1 ]; then
    docker rm -f "${OFFSITE_NAME}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

echo "==> Encrypted offsite restore drill"

# 1) Seed fixtures + canaries (leave canaries for the encrypted orchestrator dump).
# Force non-production mode so this local seed dump does not require offsite yet.
BACKUP_PRODUCTION_MODE=0 PARKIO_ENV_FILE="${ENV_FILE}" \
  "${ROOT}/scripts/restore-drill.sh" --keep-backups --keep-canaries

# 2) Synthetic object in the product bucket (CI/isolated only).
NETWORK="$(parkio_backup_backend_network parkio-minio)"
if [ -z "${NETWORK}" ]; then
  echo "ERROR: parkio-minio network not found." >&2
  exit 1
fi
PAYLOAD="$(mktemp "${TMPDIR:-/tmp}/parkio-offsite-obj.XXXXXX")"
printf 'PROD-BACKUP-OFFSITE-01-%s\n' "$(date -u +%Y%m%d%H%M%S)" > "${PAYLOAD}"
OBJ_SHA="$(sha256sum "${PAYLOAD}" | awk '{print $1}')"
BUCKET="${MINIO_BUCKET:-parkio-media}"
docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
  -v "${PAYLOAD}:/payload:ro" \
  -e MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}" \
  -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:?set MINIO_ROOT_PASSWORD}" \
  -e BUCKET="${BUCKET}" \
  "${MC_IMAGE}" -c '
    set -eu
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
    mc mb -p "local/${BUCKET}" >/dev/null 2>&1 || true
    mc cp /payload "local/${BUCKET}/synthetic/offsite-drill-object.txt"
  '
rm -f "${PAYLOAD}"

# 3) Start ephemeral offsite MinIO when Azure is not configured.
KIND="$(parkio_backup_offsite_kind)"
if [ "${KIND}" = "none" ] || [ "${KIND}" = "s3" ]; then
  if [ -z "${BACKUP_MC_URL:-}" ] && [ -z "${BACKUP_MC_DEST:-}" ]; then
    if ! docker inspect "${OFFSITE_NAME}" >/dev/null 2>&1; then
      echo "Starting ephemeral offsite MinIO ${OFFSITE_NAME}"
      docker run -d --name "${OFFSITE_NAME}" --network "${NETWORK}" \
        -e MINIO_ROOT_USER="${BACKUP_MC_ACCESS_KEY:-offsiteadmin}" \
        -e MINIO_ROOT_PASSWORD="${BACKUP_MC_SECRET_KEY:-offsite-ci-not-prod-minio}" \
        minio/minio:RELEASE.2024-09-22T00-33-43Z server /data >/dev/null
      STARTED_OFFSITE=1
      ready=0
      for _ in $(seq 1 30); do
        if docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
          -e MC_URL="http://${OFFSITE_NAME}:9000" \
          -e MC_ACCESS="${BACKUP_MC_ACCESS_KEY:-offsiteadmin}" \
          -e MC_SECRET="${BACKUP_MC_SECRET_KEY:-offsite-ci-not-prod-minio}" \
          "${MC_IMAGE}" -c 'mc alias set offsite "$MC_URL" "$MC_ACCESS" "$MC_SECRET" >/dev/null && mc ready offsite' >/dev/null 2>&1; then
          ready=1
          break
        fi
        sleep 1
      done
      if [ "${ready}" -ne 1 ]; then
        echo "ERROR: ephemeral offsite MinIO did not become ready." >&2
        exit 1
      fi
    fi
    export BACKUP_OFFSITE_KIND=s3
    export BACKUP_MC_DEST="${BACKUP_MC_DEST:-offsite/parkio-backups}"
    export BACKUP_MC_URL="${BACKUP_MC_URL:-http://${OFFSITE_NAME}:9000}"
    export BACKUP_MC_ACCESS_KEY="${BACKUP_MC_ACCESS_KEY:-offsiteadmin}"
    export BACKUP_MC_SECRET_KEY="${BACKUP_MC_SECRET_KEY:-offsite-ci-not-prod-minio}"
    export BACKUP_OFFSITE_MINIO_CONTAINER="${OFFSITE_NAME}"
    export BACKUP_MC_DOCKER_NETWORK="${NETWORK}"
  fi
fi

export BACKUP_PRODUCTION_MODE="${BACKUP_PRODUCTION_MODE:-1}"
if [ -z "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then
  export BACKUP_ENCRYPT_PASSPHRASE="parkio-ci-restore-drill-not-prod"
fi

# 4) Canonical encrypted orchestrator -> offsite.
BACKUP_ROOT="${ROOT}/backups"
export PARKIO_ENV_FILE="${ENV_FILE}"
export BACKUP_DIR="${BACKUP_ROOT}"
echo "==> backup-hosted-beta.sh (production mode, encrypted, offsite)"
"${ROOT}/scripts/backup-hosted-beta.sh"
STAMP_DIR="$(ls -dt "${BACKUP_ROOT}"/*/ 2>/dev/null | head -1)"
STAMP_DIR="${STAMP_DIR%/}"
STAMP="$(basename "${STAMP_DIR}")"
if [ -z "${STAMP}" ] || [ ! -f "${STAMP_DIR}/COMPLETE" ]; then
  echo "ERROR: encrypted backup did not produce a COMPLETE stamp." >&2
  exit 1
fi
parkio_backup_assert_encrypted_dumps "${STAMP_DIR}"
echo "    local stamp=${STAMP}"

# 5) Confirm offsite listing has COMPLETE + dumps (via pull probe into a temp that we discard if list fails).
PROBE="$(mktemp -d "${TMPDIR:-/tmp}/parkio-offsite-probe.XXXXXX")"
if ! parkio_backup_offsite_pull "${PROBE}" "${STAMP}"; then
  echo "ERROR: offsite copy missing or incomplete." >&2
  rm -rf "${PROBE}"
  exit 1
fi
rm -rf "${PROBE}"

# 6) Simulate local loss.
echo "==> Removing local stamp ${STAMP_DIR}"
docker run --rm --user 0 --entrypoint /bin/sh \
  -v "${STAMP_DIR}:/wipe" \
  "${MC_IMAGE}" -c 'rm -rf /wipe/* /wipe/.[!.]*' >/dev/null 2>&1 || true
rm -rf "${STAMP_DIR}"
if [ -e "${STAMP_DIR}" ]; then
  echo "ERROR: local stamp still present after delete." >&2
  exit 1
fi

# 7) Pull into a NEW directory.
PULL="$(mktemp -d "${TMPDIR:-/tmp}/parkio-offsite-pull.XXXXXX")"
echo "==> Pulling ${STAMP} into ${PULL}"
parkio_backup_offsite_pull "${PULL}" "${STAMP}"

# 8) Decrypt/restore all 10 from retrieved copy only.
echo "==> Restoring 10 DBs from offsite-retrieved stamp"
PARKIO_ENV_FILE="${ENV_FILE}" BACKUP_ENCRYPT_PASSPHRASE="${BACKUP_ENCRYPT_PASSPHRASE}" \
  "${ROOT}/scripts/restore-drill.sh" --from-dir "${PULL}"

# 9) MinIO object from retrieved tree into an isolated bucket.
MIRROR="${PULL}/minio/${BUCKET}"
if [ ! -d "${MIRROR}" ]; then
  echo "ERROR: retrieved stamp has no MinIO tree ${MIRROR}" >&2
  exit 1
fi
DST_BUCKET="drill-offsite-$(echo "${STAMP}" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9' '-' | tr -s '-' | sed 's/-$//')"
docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
  -v "${MIRROR}:/restore:ro" \
  -e MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}" \
  -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD}" \
  -e DST_BUCKET="${DST_BUCKET}" \
  "${MC_IMAGE}" -c '
    set -eu
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
    mc mb -p "local/${DST_BUCKET}"
    mc mirror --overwrite /restore "local/${DST_BUCKET}"
  '
GOT="$(mktemp "${TMPDIR:-/tmp}/parkio-offsite-got.XXXXXX")"
docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
  -v "$(dirname "${GOT}"):/out" \
  -e MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}" \
  -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD}" \
  -e DST_BUCKET="${DST_BUCKET}" \
  -e OUT_NAME="$(basename "${GOT}")" \
  "${MC_IMAGE}" -c '
    set -eu
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
    mc cp "local/${DST_BUCKET}/synthetic/offsite-drill-object.txt" "/out/${OUT_NAME}"
  '
GOT_SHA="$(sha256sum "${GOT}" | awk '{print $1}')"
docker run --rm --user 0 --entrypoint /bin/sh \
  -v "$(dirname "${GOT}"):/out" \
  -e OUT_NAME="$(basename "${GOT}")" \
  "${MC_IMAGE}" -c 'rm -f "/out/${OUT_NAME}"' >/dev/null 2>&1 || true
rm -f "${GOT}" 2>/dev/null || true
docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
  -e MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}" \
  -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD}" \
  -e DST_BUCKET="${DST_BUCKET}" \
  "${MC_IMAGE}" -c '
    set +e
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
    mc rb --force "local/${DST_BUCKET}" >/dev/null 2>&1
    exit 0
  ' || true
if [ "${GOT_SHA}" != "${OBJ_SHA}" ]; then
  echo "FAIL: MinIO offsite object checksum mismatch." >&2
  exit 1
fi
echo "    MinIO retrieved object sha256=${GOT_SHA}"

# 10) Failure modes (fail-closed).
echo "==> Offsite failure modes"
FAILED=0
expect_nonzero() {
  local label="$1"
  shift
  local rc=0
  "$@" >/dev/null 2>&1 || rc=$?
  if [ "${rc}" -eq 0 ]; then
    echo "FAIL: ${label} — expected non-zero" >&2
    FAILED=1
  else
    echo "OK: ${label} exited ${rc}"
  fi
}

expect_nonzero "missing offsite stamp" \
  env PARKIO_ENV_FILE="${ENV_FILE}" BACKUP_OFFSITE_KIND="${BACKUP_OFFSITE_KIND}" \
    BACKUP_MC_DEST="${BACKUP_MC_DEST:-}" BACKUP_MC_URL="${BACKUP_MC_URL:-}" \
    BACKUP_MC_ACCESS_KEY="${BACKUP_MC_ACCESS_KEY:-}" BACKUP_MC_SECRET_KEY="${BACKUP_MC_SECRET_KEY:-}" \
    BACKUP_AZURE_STORAGE_ACCOUNT="${BACKUP_AZURE_STORAGE_ACCOUNT:-}" \
    BACKUP_AZURE_CONTAINER="${BACKUP_AZURE_CONTAINER:-}" \
    "${ROOT}/scripts/backup-offsite-pull.sh" --stamp missing-stamp-does-not-exist --dest "${TMPDIR:-/tmp}/parkio-missing-$$"

WRONG_PULL="$(mktemp -d "${TMPDIR:-/tmp}/parkio-wrong-key.XXXXXX")"
cp -a "${PULL}/." "${WRONG_PULL}/"
ENC="$(ls "${WRONG_PULL}"/parking.sql.gz.enc 2>/dev/null || true)"
if [ -n "${ENC}" ]; then
  expect_nonzero "wrong encryption key" \
    env BACKUP_ENCRYPT_PASSPHRASE="definitely-not-the-drill-passphrase" PARKIO_ENV_FILE="${ENV_FILE}" \
      "${ROOT}/scripts/verify-backup.sh" parking "${ENC}"
  expect_nonzero "missing encryption key" \
    env -u BACKUP_ENCRYPT_PASSPHRASE PARKIO_ENV_FILE="${ENV_FILE}" \
      "${ROOT}/scripts/verify-backup.sh" parking "${ENC}"
  # corrupt encrypted artifact
  dd if=/dev/zero of="${ENC}" bs=32 count=1 conv=notrunc >/dev/null 2>&1 || true
  expect_nonzero "corrupted encrypted dump" \
    env BACKUP_ENCRYPT_PASSPHRASE="${BACKUP_ENCRYPT_PASSPHRASE}" PARKIO_ENV_FILE="${ENV_FILE}" \
      "${ROOT}/scripts/verify-backup.sh" parking "${ENC}"
fi
rm -rf "${WRONG_PULL}"

if [ "${BACKUP_OFFSITE_KIND}" = "s3" ] && [ -n "${BACKUP_MC_URL:-}" ]; then
  expect_nonzero "invalid offsite credentials" \
    env PARKIO_ENV_FILE="${ENV_FILE}" BACKUP_OFFSITE_KIND=s3 \
      BACKUP_MC_DEST="${BACKUP_MC_DEST}" BACKUP_MC_URL="${BACKUP_MC_URL}" \
      BACKUP_MC_ACCESS_KEY="${BACKUP_MC_ACCESS_KEY}" BACKUP_MC_SECRET_KEY="wrong-offsite-secret" \
      "${ROOT}/scripts/backup-offsite-pull.sh" --stamp "${STAMP}" --dest "${TMPDIR:-/tmp}/parkio-badcred-$$"
  expect_nonzero "offsite destination unavailable" \
    env PARKIO_ENV_FILE="${ENV_FILE}" BACKUP_OFFSITE_KIND=s3 \
      BACKUP_MC_DEST="${BACKUP_MC_DEST}" BACKUP_MC_URL="http://parkio-offsite-does-not-exist:9000" \
      BACKUP_MC_ACCESS_KEY="${BACKUP_MC_ACCESS_KEY}" BACKUP_MC_SECRET_KEY="${BACKUP_MC_SECRET_KEY}" \
      "${ROOT}/scripts/backup-offsite-pull.sh" --stamp "${STAMP}" --dest "${TMPDIR:-/tmp}/parkio-unavail-$$"
fi

MISMATCH="$(mktemp -d "${TMPDIR:-/tmp}/parkio-mismatch.XXXXXX")"
cp -a "${PULL}/." "${MISMATCH}/"
printf 'tampered\n' >> "${MISMATCH}/SHA256SUMS"
expect_nonzero "checksum mismatch" \
  env PARKIO_ENV_FILE="${ENV_FILE}" bash -c "cd '${MISMATCH}' && sha256sum -c SHA256SUMS --strict"

expect_nonzero "production mode without passphrase" \
  env BACKUP_PRODUCTION_MODE=1 BACKUP_ENCRYPT_PASSPHRASE= BACKUP_MC_DEST=offsite/parkio-backups \
    PARKIO_ENV_FILE="${ENV_FILE}" "${ROOT}/scripts/backup-hosted-beta.sh" --dry-run

# dry-run still calls preflight? It calls load+validate+preflight then dry-run exit.
# empty passphrase with prod mode should fail at preflight before dry-run work.

if [ "${FAILED}" -ne 0 ]; then
  echo "RESULT: FAIL — offsite failure modes did not fail closed." >&2
  exit 1
fi

echo "RESULT: PASS — encrypted offsite backup retrieved and restored (10 DBs + MinIO)."
echo "offsite_kind=$(parkio_backup_offsite_kind) stamp=${STAMP}"
