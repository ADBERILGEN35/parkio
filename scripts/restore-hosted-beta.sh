#!/usr/bin/env bash
#
# Parkio - restore hosted-beta backups from a manifest produced by backup-hosted-beta.sh.
#
# Usage:
#   PARKIO_ENV_FILE=docker/.env ./scripts/restore-hosted-beta.sh --manifest backup-artifacts/backup-....json
#   PARKIO_ENV_FILE=docker/.env ./scripts/restore-hosted-beta.sh --manifest ... --dry-run
#   PARKIO_ENV_FILE=docker/.env ./scripts/restore-hosted-beta.sh --manifest ... --yes --only minio
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/backup-common.sh
source "$ROOT/scripts/lib/backup-common.sh"
# shellcheck source=lib/erasure-tombstones.sh
source "$ROOT/scripts/lib/erasure-tombstones.sh"

ENV_FILE="${PARKIO_ENV_FILE:-}"
MANIFEST=""
DRY_RUN=0
ASSUME_YES="no"
ONLY=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --manifest) MANIFEST="${2:-}"; shift 2 ;;
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --yes) ASSUME_YES="yes"; shift ;;
    --only) ONLY="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [ -z "${MANIFEST}" ] || [ ! -f "${MANIFEST}" ]; then
  echo "ERROR: --manifest <path> is required and must exist." >&2
  exit 2
fi

parkio_backup_load_env "${ENV_FILE}"
parkio_backup_validate_deployment_profile

DEST_DIR="$(jq -r .destination "${MANIFEST}")"
BUCKET="$(jq -r .minio.bucket "${MANIFEST}")"
GIT_SHA="$(jq -r .gitSha "${MANIFEST}")"
STAMP="$(jq -r .timestamp "${MANIFEST}")"
MANIFEST_PROFILE="$(jq -r '.deploymentProfile // "hosted-beta"' "${MANIFEST}")"

if [ "${PARKIO_DEPLOYMENT_PROFILE}" != "${MANIFEST_PROFILE}" ]; then
  echo "ERROR: restore profile '${PARKIO_DEPLOYMENT_PROFILE}' does not match manifest profile '${MANIFEST_PROFILE}'." >&2
  exit 2
fi

echo "=== Parkio hosted-beta restore ==="
echo "manifest=${MANIFEST}"
echo "destination=${DEST_DIR}"
echo "gitSha=${GIT_SHA}"
echo "stamp=${STAMP}"
echo "deploymentProfile=${PARKIO_DEPLOYMENT_PROFILE}"
echo "dryRun=${DRY_RUN}"
echo "only=${ONLY:-all}"

if [ ! -d "${DEST_DIR}" ]; then
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "WARN: backup destination ${DEST_DIR} not found (dry-run continues)."
  else
    echo "ERROR: backup destination not found: ${DEST_DIR}" >&2
    exit 2
  fi
fi

restore_databases() {
  local svc dump
  while IFS= read -r svc; do
    [ -z "${svc}" ] && continue
    svc="${svc//$'\r'/}"
    if [ "$DRY_RUN" -eq 1 ]; then
      echo "DRY-RUN: would restore ${svc}"
      continue
    fi
    dump=""
    for candidate in "${DEST_DIR}/${svc}.sql.gz.enc" "${DEST_DIR}/${svc}.sql.gz" "${DEST_DIR}/${svc}.sql"; do
      if [ -f "${candidate}" ]; then dump="${candidate}"; break; fi
    done
    if [ -z "${dump}" ]; then
      echo "ERROR: no dump for service '${svc}' under ${DEST_DIR}" >&2
      return 1
    fi
    echo "Restoring database '${svc}' from ${dump} ..."
    local args=(--yes)
    if [ -n "${ENV_FILE}" ]; then args+=(--env-file "${ENV_FILE}"); fi
    "${ROOT}/scripts/restore-database.sh" "${svc}" "${dump}" "${args[@]}"
  done < <(jq -r '.databases[]' "${MANIFEST}")
}

restore_minio() {
  local restore_bucket="${MINIO_RESTORE_BUCKET:-${BUCKET}}"
  local mirror_src="${DEST_DIR}/minio/${BUCKET}"
  if [ ! -d "${mirror_src}" ]; then
    # retrieved stamps store the bucket tree under minio/<bucket>
    local alt
    alt="$(find "${DEST_DIR}/minio" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -1 || true)"
    if [ -n "${alt}" ]; then
      mirror_src="${alt}"
    fi
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "DRY-RUN: would mirror ${mirror_src} -> local/${restore_bucket}"
    return 0
  fi
  if [ ! -d "${mirror_src}" ]; then
    echo "ERROR: MinIO mirror not found: ${mirror_src}" >&2
    return 1
  fi
  if [ "${restore_bucket}" = "${BUCKET}" ] && [ "${PARKIO_ALLOW_LIVE_MINIO_RESTORE:-}" != "yes" ]; then
    echo "ERROR: refusing to overwrite live bucket '${BUCKET}'." >&2
    echo "Set MINIO_RESTORE_BUCKET to an isolated bucket, or PARKIO_ALLOW_LIVE_MINIO_RESTORE=yes after operator confirmation." >&2
    return 2
  fi
  local network mc_image minio_container
  minio_container="${PARKIO_MINIO_CONTAINER:-parkio-minio}"
  network="$(parkio_backup_backend_network "${minio_container}")"
  mc_image="${MINIO_MC_IMAGE:-minio/mc:RELEASE.2024-09-16T17-43-14Z}"
  docker run --rm \
    --network "${network}" \
    --entrypoint /bin/sh \
    -v "${mirror_src}:/restore:ro" \
    -e "MINIO_ROOT_USER=${MINIO_ROOT_USER:-minioadmin}" \
    -e "MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD:?set MINIO_ROOT_PASSWORD}" \
    -e "BUCKET=${restore_bucket}" \
    "${mc_image}" \
    -c '
      set -eu
      mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
      mc mb -p "local/${BUCKET}" >/dev/null 2>&1 || true
      mc mirror --overwrite /restore "local/${BUCKET}"
    '
  echo "MinIO restore completed from ${mirror_src} -> ${restore_bucket}"
}

if [ "${ASSUME_YES}" != "yes" ] && [ "$DRY_RUN" -ne 1 ]; then
  echo "*** DESTRUCTIVE: this overwrites live databases and MinIO objects. ***"
  printf "Type RESTORE to proceed: "
  read -r reply
  if [ "${reply}" != "RESTORE" ]; then
    echo "Aborted." >&2
    exit 1
  fi
fi

replay_erasure_ledger() {
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "DRY-RUN: would replay ${DEST_DIR}/erasure-tombstones.json into auth"
    return 0
  fi
  local ledger="${DEST_DIR}/erasure-tombstones.json"
  PARKIO_RESTORE_REQUIRE_ERASURE_LEDGER="${PARKIO_RESTORE_REQUIRE_ERASURE_LEDGER:-1}" \
    parkio_replay_erasure_tombstones "${ledger}" \
      "${PARKIO_POSTGRES_AUTH_CONTAINER:-parkio-postgres-auth}" \
      "${POSTGRES_AUTH_USER:-parkio_auth}" \
      "${POSTGRES_AUTH_DB:-parkio_auth}"
  echo "Erasure ledger replayed; do not serve traffic until auth POST /internal/erasure/replay (or Kafka) finishes participant erase."
}

case "${ONLY}" in
  ""|all)
    restore_databases
    replay_erasure_ledger
    restore_minio
    ;;
  databases|db)
    restore_databases
    replay_erasure_ledger
    ;;
  minio)
    restore_minio
    ;;
  *)
    echo "ERROR: --only must be all, databases, or minio" >&2
    exit 2
    ;;
esac

echo "Restore completed."
