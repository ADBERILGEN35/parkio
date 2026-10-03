#!/usr/bin/env bash
#
# CL-F29.2 disposable check: scripts/backup-minio.sh, run against a real MinIO with the pinned
# mc image, leaves a plaintext mirror that belongs to the invoking user, grants no group/other
# permissions and can be deleted without root. Synthetic bucket only: the product bucket is
# never read. (When the invoking user is root, the ownership check is trivially true.)
#
# Usage:
#   PARKIO_ENV_FILE=docker/.env ./scripts/test-backup-minio-mirror-permissions.sh
# Needs a running MinIO container (PARKIO_MINIO_CONTAINER, default parkio-minio) on a Docker
# network named *backend* or *parkio* where it answers as "minio", like the compose stack.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${PARKIO_ENV_FILE:-}"
if [ -n "${ENV_FILE}" ] && [ -f "${ENV_FILE}" ]; then
  set -a
  # shellcheck disable=SC1090
  . "${ENV_FILE}"
  set +a
fi
# shellcheck source=lib/backup-common.sh
source "${ROOT}/scripts/lib/backup-common.sh"

MINIO_CONTAINER="${PARKIO_MINIO_CONTAINER:-parkio-minio}"
if ! docker inspect "${MINIO_CONTAINER}" >/dev/null 2>&1; then
  echo "ERROR: MinIO container '${MINIO_CONTAINER}' not found / not running." >&2
  exit 1
fi
NETWORK="$(parkio_backup_backend_network "${MINIO_CONTAINER}")"
if [ -z "${NETWORK}" ]; then
  echo "ERROR: could not resolve Docker network for ${MINIO_CONTAINER}." >&2
  exit 1
fi
MC_IMAGE="${MINIO_MC_IMAGE:-ghcr.io/adberilgen35/parkio/mc@sha256:456b1e641897329fc9491f9bc8b31df351d728af9a328bf5653707af62d0d6bf}"
export MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}"
export MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:?set MINIO_ROOT_PASSWORD}"

BUCKET="drill-mirror-perm-$(date -u +%Y%m%d%H%M%S)-$$"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/parkio-mirror-perm.XXXXXX")"
MC_ENV=(-e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD -e "BUCKET=${BUCKET}")

cleanup() {
  docker run --rm --network "${NETWORK}" --entrypoint /bin/sh "${MC_ENV[@]}" "${MC_IMAGE}" -c '
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1
    mc rb --force "local/${BUCKET}" >/dev/null 2>&1
    exit 0
  ' >/dev/null 2>&1 || true
  # A mirror written by an older backup-minio.sh is root-owned; remove it through a container.
  if ! rm -rf "${WORK}" 2>/dev/null; then
    docker run --rm --user 0 --entrypoint /bin/sh -v "${WORK}:/work" "${MC_IMAGE}" \
      -c 'rm -rf /work/* /work/.[!.]*' >/dev/null 2>&1 || true
    rm -rf "${WORK}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo "==> Seeding synthetic bucket ${BUCKET} (network=${NETWORK})"
docker run --rm --network "${NETWORK}" --entrypoint /bin/sh "${MC_ENV[@]}" "${MC_IMAGE}" -c '
  set -eu
  # MinIO can answer XMinioServerNotInitialized for a moment after start.
  i=0
  until mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1 \
    && mc mb -p "local/${BUCKET}" >/dev/null 2>&1; do
    i=$((i + 1))
    [ "$i" -lt 30 ] || { echo "MinIO not ready" >&2; exit 1; }
    sleep 1
  done
  echo synthetic-object-a | mc pipe "local/${BUCKET}/a.txt" >/dev/null
  echo synthetic-object-b | mc pipe "local/${BUCKET}/nested/dir/b.txt" >/dev/null
'

# No env file here: it could set MINIO_BUCKET back to the product bucket.
echo "==> scripts/backup-minio.sh (bucket=${BUCKET})"
COUNT="$(env -u PARKIO_ENV_FILE MINIO_BUCKET="${BUCKET}" PARKIO_MINIO_CONTAINER="${MINIO_CONTAINER}" \
  MINIO_MC_IMAGE="${MC_IMAGE}" "${ROOT}/scripts/backup-minio.sh" "${WORK}/stamp")"
TREE="${WORK}/stamp/minio"
MIRROR="${TREE}/${BUCKET}"

pass=0
fail=0
ok() { echo "PASS $1"; pass=$((pass + 1)); }
bad() { echo "FAIL $1"; fail=$((fail + 1)); }

if [ "${COUNT}" = "2" ] && [ "$(cat "${MIRROR}/a.txt" 2>/dev/null)" = "synthetic-object-a" ] \
  && [ "$(cat "${MIRROR}/nested/dir/b.txt" 2>/dev/null)" = "synthetic-object-b" ]; then
  ok "mirror holds both synthetic objects (count=${COUNT})"
else
  bad "mirror must hold both synthetic objects (count='${COUNT}')"
fi
foreign="$(find "${TREE}" ! -uid "$(id -u)" 2>/dev/null | wc -l)"
if [ "${foreign}" -eq 0 ]; then
  ok "every mirror entry belongs to uid $(id -u)"
else
  bad "${foreign} mirror entries belong to another user"
  find "${TREE}" ! -uid "$(id -u)" -printf '  %u %m %p\n' 2>/dev/null | head -5
fi
open_entries="$(find "${TREE}" -perm /077 2>/dev/null | wc -l)"
if [ "${open_entries}" -eq 0 ]; then
  ok "no mirror entry grants group/other permissions"
else
  bad "${open_entries} mirror entries grant group/other permissions"
  find "${TREE}" -perm /077 -printf '  %u %m %p\n' 2>/dev/null | head -5
fi
if rm -rf "${TREE}" 2>/dev/null && [ ! -e "${TREE}" ]; then
  ok "the invoking user deletes the mirror without root"
else
  bad "the invoking user could not delete the mirror"
fi

echo "=== MinIO backup mirror permissions: pass=${pass} fail=${fail} ==="
[ "${fail}" -eq 0 ]
