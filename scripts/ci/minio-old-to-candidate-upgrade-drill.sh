#!/usr/bin/env bash
# Isolated MinIO old→candidate digest upgrade + backup/restore drill (G05D).
# GitHub-hosted / local Docker only. Synthetic buckets. No production volumes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OLD_SERVER="${MINIO_OLD_IMAGE:-quay.io/minio/minio@sha256:cd04ea408e185cb50076ea1c3988d444119b19aaae15aab45387ccf14b2a2f86}"
NEW_SERVER="${MINIO_IMAGE:-quay.io/minio/minio@sha256:cf3dadcfa1fb0324f43958bad1abba986d53c4ecc04d4d50b46c7dcda28bd3cd}"
OLD_MC="${MINIO_OLD_MC_IMAGE:-quay.io/minio/mc@sha256:a5399b66b88543efac8afb08eb2bdcce5904e548ea6fe1a921600cd74f766668}"
NEW_MC="${MINIO_MC_IMAGE:-quay.io/minio/mc@sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727}"

RUN_ID="g05d-minio-$(date -u +%Y%m%d%H%M%S)-${RANDOM}"
PROJECT="parkio-${RUN_ID}"
NETWORK="${PROJECT}_net"
USER_NAME="${MINIO_ROOT_USER:-minioadmin}"
PASS="${MINIO_ROOT_PASSWORD:-g05d-minio-drill-password}"
BUCKET="drill-${RUN_ID}"
OBJ="synthetic/upgrade-object.txt"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/${RUN_ID}.XXXXXX")"
PAYLOAD="${WORK}/payload.txt"
BACKUP="${WORK}/mirror"
printf 'G05D-MINIO-UPGRADE-%s\n' "${RUN_ID}" > "${PAYLOAD}"
CHECKSUM="$(sha256sum "${PAYLOAD}" | awk '{print $1}')"

cleanup() {
  set +e
  docker rm -f "${PROJECT}-minio" >/dev/null 2>&1
  docker network rm "${NETWORK}" >/dev/null 2>&1
  docker volume rm "${PROJECT}-data" >/dev/null 2>&1
  rm -rf "${WORK}"
}
trap cleanup EXIT

echo "==> G05D MinIO digest compat ${RUN_ID}"
echo "OLD_SERVER=${OLD_SERVER}"
echo "NEW_SERVER=${NEW_SERVER}"
echo "checksum=${CHECKSUM}"

docker network create "${NETWORK}"
docker volume create "${PROJECT}-data" >/dev/null

wait_ready() {
  local mc_image="$1"
  local i
  for i in $(seq 1 60); do
    if docker run --rm --network "${NETWORK}" --entrypoint /bin/sh "${mc_image}" -c \
      "mc alias set local http://minio:9000 '${USER_NAME}' '${PASS}' >/dev/null 2>&1 && mc ready local" \
      >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "ERROR: MinIO not ready" >&2
  docker logs "${PROJECT}-minio" >&2 || true
  return 1
}

start_minio() {
  local image="$1"
  docker rm -f "${PROJECT}-minio" >/dev/null 2>&1 || true
  docker run -d --name "${PROJECT}-minio" --network "${NETWORK}" --network-alias minio \
    -e MINIO_ROOT_USER="${USER_NAME}" \
    -e MINIO_ROOT_PASSWORD="${PASS}" \
    -v "${PROJECT}-data:/data" \
    "${image}" server /data --console-address ":9001"
}

echo "==> 1) Populate with OLD server"
start_minio "${OLD_SERVER}"
wait_ready "${OLD_MC}"
docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
  -v "${PAYLOAD}:/payload:ro" \
  -e MINIO_ROOT_USER="${USER_NAME}" -e MINIO_ROOT_PASSWORD="${PASS}" \
  -e BUCKET="${BUCKET}" -e OBJ="${OBJ}" \
  "${OLD_MC}" -c '
    set -eu
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
    mc mb -p "local/${BUCKET}"
    mc cp /payload "local/${BUCKET}/${OBJ}"
    mc stat "local/${BUCKET}/${OBJ}"
  '

echo "==> 2) Backup via mc mirror (supported tooling path)"
mkdir -p "${BACKUP}"
docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
  -v "${BACKUP}:/backup" \
  -e MINIO_ROOT_USER="${USER_NAME}" -e MINIO_ROOT_PASSWORD="${PASS}" \
  -e BUCKET="${BUCKET}" \
  "${OLD_MC}" -c '
    set -eu
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
    mc mirror --overwrite "local/${BUCKET}" /backup
  '

echo "==> 3) In-place upgrade: stop OLD, start NEW on same volume"
docker rm -f "${PROJECT}-minio" >/dev/null
start_minio "${NEW_SERVER}"
wait_ready "${NEW_MC}"
docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
  -e MINIO_ROOT_USER="${USER_NAME}" -e MINIO_ROOT_PASSWORD="${PASS}" \
  -e BUCKET="${BUCKET}" -e OBJ="${OBJ}" -e EXPECT="${CHECKSUM}" \
  "${NEW_MC}" -c '
    set -eu
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
    mc stat "local/${BUCKET}/${OBJ}"
    mc cat "local/${BUCKET}/${OBJ}" | sha256sum | awk "{print \$1}" > /tmp/got
    got=$(cat /tmp/got)
    echo "post-upgrade checksum=$got expect=$EXPECT"
    test "$got" = "$EXPECT"
  '

echo "==> 4) Restore mirror into disposable bucket on NEW"
DST="drill-dst-${RUN_ID}"
docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
  -v "${BACKUP}:/backup:ro" \
  -e MINIO_ROOT_USER="${USER_NAME}" -e MINIO_ROOT_PASSWORD="${PASS}" \
  -e DST="${DST}" -e OBJ="${OBJ}" -e EXPECT="${CHECKSUM}" \
  "${NEW_MC}" -c '
    set -eu
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
    mc mb -p "local/${DST}"
    mc mirror --overwrite /backup "local/${DST}"
    mc cat "local/${DST}/${OBJ}" | sha256sum | awk "{print \$1}" > /tmp/got
    got=$(cat /tmp/got)
    echo "restore checksum=$got expect=$EXPECT"
    test "$got" = "$EXPECT"
    mc rb --force "local/${DST}" || true
  '

echo "==> 5) Downgrade probe (volume already written by NEW) — not assumed safe"
docker rm -f "${PROJECT}-minio" >/dev/null
DOWNGRADE_OK=0
if start_minio "${OLD_SERVER}" && wait_ready "${OLD_MC}"; then
  if docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
    -e MINIO_ROOT_USER="${USER_NAME}" -e MINIO_ROOT_PASSWORD="${PASS}" \
    -e BUCKET="${BUCKET}" -e OBJ="${OBJ}" \
    "${OLD_MC}" -c '
      set -eu
      mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
      mc stat "local/${BUCKET}/${OBJ}"
    '; then
    DOWNGRADE_OK=1
  fi
fi
if [[ "${DOWNGRADE_OK}" -eq 1 ]]; then
  echo "DOWNGRADE_RESULT=SUCCEEDED_ON_DISPOSABLE (not a production guarantee)"
else
  echo "DOWNGRADE_RESULT=FAILED_OR_UNSUPPORTED (expected; do not treat as safe rollback)"
fi

echo "OK G05D MinIO old→candidate upgrade + restore checksum=${CHECKSUM}"
