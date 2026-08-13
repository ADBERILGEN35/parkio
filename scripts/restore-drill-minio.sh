#!/usr/bin/env bash
#
# Isolated MinIO backup → restore checksum drill.
# Uses synthetic buckets only. Never overwrites MINIO_BUCKET product objects.
# Fail-closed: MinIO must be running (no SKIP).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${PARKIO_ENV_FILE:-}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [ -n "${ENV_FILE}" ] && [ -f "${ENV_FILE}" ]; then
  set -a
  # shellcheck disable=SC1090
  . "${ENV_FILE}"
  set +a
fi

MINIO_CONTAINER="${PARKIO_MINIO_CONTAINER:-parkio-minio}"
if ! docker inspect "${MINIO_CONTAINER}" >/dev/null 2>&1; then
  echo "ERROR: MinIO container '${MINIO_CONTAINER}' not found / not running." >&2
  exit 1
fi

RUN_ID="restore-drill-minio-$(date -u +%Y%m%d%H%M%S)"
SRC_BUCKET="drill-src-${RUN_ID}"
DST_BUCKET="drill-dst-${RUN_ID}"
OBJ="synthetic/restore-drill-object.txt"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/parkio-minio-drill.XXXXXX")"
PAYLOAD="${WORK}/payload.txt"
BACKUP_DIR="${WORK}/mirror"
RESTORE_FILE="${WORK}/restored.txt"
printf 'PROD-RESTORE-DRILL-01-minio-%s\n' "${RUN_ID}" > "${PAYLOAD}"
CHECKSUM_BEFORE="$(sha256sum "${PAYLOAD}" | awk '{print $1}')"

# shellcheck source=lib/backup-common.sh
source "${ROOT}/scripts/lib/backup-common.sh"
NETWORK="$(parkio_backup_backend_network "${MINIO_CONTAINER}")"
if [ -z "${NETWORK}" ]; then
  echo "ERROR: could not resolve Docker network for ${MINIO_CONTAINER}." >&2
  exit 1
fi
MC_IMAGE="${MINIO_MC_IMAGE:-minio/mc:RELEASE.2024-09-16T17-43-14Z}"

cleanup() {
  docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
    -e MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}" \
    -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:?set MINIO_ROOT_PASSWORD}" \
    -e SRC_BUCKET="${SRC_BUCKET}" -e DST_BUCKET="${DST_BUCKET}" \
    "${MC_IMAGE}" -c '
      set +e
      mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
      mc rb --force "local/${SRC_BUCKET}" >/dev/null 2>&1
      mc rb --force "local/${DST_BUCKET}" >/dev/null 2>&1
      exit 0
    ' || true
  # mc mirror writes as root into the bind mount; delete via container.
  docker run --rm --user 0 --entrypoint /bin/sh \
    -v "${WORK}:/work" \
    "${MC_IMAGE}" -c 'rm -rf /work/*' >/dev/null 2>&1 || true
  rm -rf "${WORK}" 2>/dev/null || true
}
trap cleanup EXIT

echo "==> MinIO restore drill ${RUN_ID} (network=${NETWORK})"

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

docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
  -v "${WORK}:/out" \
  -e MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}" \
  -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD}" \
  -e DST_BUCKET="${DST_BUCKET}" -e OBJ="${OBJ}" \
  "${MC_IMAGE}" -c '
    set -eu
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
    mc cp "local/${DST_BUCKET}/${OBJ}" /out/restored.txt
  '

CHECKSUM_AFTER="$(sha256sum "${RESTORE_FILE}" | awk '{print $1}')"
if [ "${CHECKSUM_BEFORE}" != "${CHECKSUM_AFTER}" ]; then
  echo "FAIL: MinIO checksum mismatch before=${CHECKSUM_BEFORE} after=${CHECKSUM_AFTER}" >&2
  exit 1
fi

echo "RESULT: PASS — MinIO isolated round-trip sha256=${CHECKSUM_BEFORE}"
