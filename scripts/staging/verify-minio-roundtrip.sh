#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/lib/safety-guards.sh"
source "${SCRIPT_DIR}/lib/evidence-common.sh"
assert_staging_safety
assert_destructive_opt_in

ENV_FILE="${PARKIO_ENV_FILE:-${ROOT}/docker/.env}"
if [ -f "${ENV_FILE}" ]; then set -a; # shellcheck disable=SC1090
  . "${ENV_FILE}"; set +a; fi

if ! docker inspect parkio-minio >/dev/null 2>&1; then
  echo "SKIP: parkio-minio not running" >&2; exit 0
fi

RUN_ID="${PARKIO_EVIDENCE_RUN_ID:-wp062-minio-$(date +%s)}"
SAFE_RUN_ID="$(printf '%s' "${RUN_ID}" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9-' '-')"
SRC_BUCKET="wp062-src-${SAFE_RUN_ID}"
DST_BUCKET="wp062-dst-${SAFE_RUN_ID}"
OBJ="synthetic/wp062-object.txt"
PAYLOAD="/tmp/wp062-payload-${RUN_ID}.txt"
BACKUP_ROOT="${PARKIO_EVIDENCE_DIR:-${ROOT}/build/operational-evidence/minio-${RUN_ID}}"
mkdir -p "${BACKUP_ROOT}"
BACKUP_DIR="$(cd "${BACKUP_ROOT}" && pwd)/minio-backup"

echo "wp062-synthetic-minio-payload-${RUN_ID}" > "${PAYLOAD}"
CHECKSUM_BEFORE="$(sha256sum "${PAYLOAD}" | awk '{print $1}')"
NETWORK="$(docker inspect parkio-minio --format '{{range $n,$_ := .NetworkSettings.Networks}}{{$n}}{{"\n"}}{{end}}' | grep -E 'backend|parkio' | head -1)"
MC_IMAGE="${MINIO_MC_IMAGE:-minio/mc:RELEASE.2024-09-16T17-43-14Z}"

docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
  -v "${PAYLOAD}:/payload:ro" \
  -e MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}" \
  -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:?set MINIO_ROOT_PASSWORD}" \
  -e SRC_BUCKET="${SRC_BUCKET}" -e OBJ="${OBJ}" \
  "${MC_IMAGE}" -c '
    set -eu
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
    mc mb -p "local/${SRC_BUCKET}"
    mc cp /payload "local/${SRC_BUCKET}/${OBJ}"
  '

mkdir -p "${BACKUP_DIR}"
docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
  -v "${BACKUP_DIR}:/backup" \
  -e MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}" \
  -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD}" \
  -e SRC_BUCKET="${SRC_BUCKET}" \
  "${MC_IMAGE}" -c '
    set -eu
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
    mc mirror --overwrite "local/${SRC_BUCKET}" /backup
  '

docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
  -v "${BACKUP_DIR}:/backup:ro" \
  -e MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}" \
  -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD}" \
  -e DST_BUCKET="${DST_BUCKET}" \
  "${MC_IMAGE}" -c '
    set -eu
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
    mc mb -p "local/${DST_BUCKET}"
    mc mirror --overwrite /backup "local/${DST_BUCKET}"
  '

RESTORE_DIR="/tmp/wp062-restored-${RUN_ID}"
mkdir -p "${RESTORE_DIR}"
docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
  -v "${RESTORE_DIR}:/out" \
  -e MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}" \
  -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD}" \
  -e DST_BUCKET="${DST_BUCKET}" -e OBJ="${OBJ}" \
  "${MC_IMAGE}" -c '
    set -eu
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
    mc cp "local/${DST_BUCKET}/${OBJ}" /out/wp062-object.txt
  '
CHECKSUM_AFTER="$(sha256sum "${RESTORE_DIR}/wp062-object.txt" | awk '{print $1}')"
if [ "${CHECKSUM_BEFORE}" != "${CHECKSUM_AFTER}" ]; then
  echo "FAIL: MinIO checksum mismatch" >&2; exit 1
fi

docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
  -e MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}" \
  -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD}" \
  -e SRC_BUCKET="${SRC_BUCKET}" -e DST_BUCKET="${DST_BUCKET}" \
  "${MC_IMAGE}" -c '
    set -eu
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
    mc rb --force "local/${SRC_BUCKET}" || true
    mc rb --force "local/${DST_BUCKET}" || true
  '

echo "OK MinIO round-trip checksum=${CHECKSUM_BEFORE}"
rm -f "${PAYLOAD}"
rm -rf "${RESTORE_DIR}"
