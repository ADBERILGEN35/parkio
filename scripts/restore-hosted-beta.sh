#!/usr/bin/env bash
#
# Parkio - restore hosted-beta backups from a manifest produced by backup-hosted-beta.sh.
# After DB restore, replay erasure-tombstones.json (PRIV-001). The same replay is
# required after managed PITR restore — see docs/architecture/pp-01-managed-postgresql-pitr-ha.md
#
# Usage:
#   PARKIO_ENV_FILE=docker/.env ./scripts/restore-hosted-beta.sh \\
#     --manifest /path/to/stamp/backup-manifest.json --recovery-cutoff 2026-09-24T12:00:00Z
#   PARKIO_ENV_FILE=docker/.env ./scripts/restore-hosted-beta.sh --manifest ... --dry-run
#   PARKIO_ENV_FILE=docker/.env ./scripts/restore-hosted-beta.sh --manifest ... --yes --only minio
#
# Production path fail-closes before decrypt or destructive apply unless
# PARKIO_RESTORE_ISOLATED_DRILL=1 (CI/isolated drills only).
# Does not start applications, Slack, or Fluent Bit.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/backup-common.sh
source "$ROOT/scripts/lib/backup-common.sh"
# shellcheck source=lib/erasure-tombstones.sh
source "$ROOT/scripts/lib/erasure-tombstones.sh"
# shellcheck source=lib/restore-safe-preflight.sh
source "$ROOT/scripts/lib/restore-safe-preflight.sh"

ENV_FILE="${PARKIO_ENV_FILE:-}"
MANIFEST=""
DRY_RUN=0
ASSUME_YES="no"
ONLY=""
STAMP_OVERRIDE=""
LEDGER_STAMPS=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    --manifest) MANIFEST="${2:-}"; shift 2 ;;
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --yes) ASSUME_YES="yes"; shift ;;
    --only) ONLY="${2:-}"; shift 2 ;;
    --stamp-dir) STAMP_OVERRIDE="${2:-}"; shift 2 ;;
    --recovery-cutoff) PARKIO_RESTORE_RECOVERY_CUTOFF="${2:-}"; shift 2 ;;
    --ledger-stamp) LEDGER_STAMPS+=("${2:-}"); shift 2 ;;
    --supplemental-ledger) PARKIO_RESTORE_SUPPLEMENTAL_LEDGER="${2:-}"; shift 2 ;;
    --supplemental-covered-through) PARKIO_RESTORE_SUPPLEMENTAL_THROUGH="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [ -z "${MANIFEST}" ] || [ ! -f "${MANIFEST}" ]; then
  echo "ERROR: --manifest <path> is required and must exist." >&2
  exit 2
fi

parkio_backup_load_env "${ENV_FILE}"
parkio_backup_validate_deployment_profile

if ! parkio_restore_resolve_stamp_from_manifest "${MANIFEST}" "${STAMP_OVERRIDE}"; then
  exit 2
fi
DEST_DIR="${PARKIO_RESTORE_STAMP_DIR}"
BUCKET="$(jq -r '.minio.bucket // empty' "${MANIFEST}")"
GIT_SHA="$(jq -r '.gitSha // empty' "${MANIFEST}")"
STAMP="$(jq -r '.timestamp // empty' "${MANIFEST}")"
MANIFEST_PROFILE="$(jq -r '.deploymentProfile // "hosted-beta"' "${MANIFEST}")"

if [ "${PARKIO_DEPLOYMENT_PROFILE}" != "${MANIFEST_PROFILE}" ]; then
  echo "ERROR: restore profile '${PARKIO_DEPLOYMENT_PROFILE}' does not match manifest profile '${MANIFEST_PROFILE}'." >&2
  exit 2
fi

SCOPE="full"
case "${ONLY}" in
  databases|db) SCOPE="databases" ;;
  ""|all|minio) SCOPE="full" ;;
esac

echo "=== Parkio hosted-beta restore ==="
echo "manifest=${MANIFEST}"
echo "destination=${DEST_DIR}"
echo "gitSha=${GIT_SHA}"
echo "stamp=${STAMP}"
echo "deploymentProfile=${PARKIO_DEPLOYMENT_PROFILE}"
echo "dryRun=${DRY_RUN}"
echo "only=${ONLY:-all}"
echo "scope=${SCOPE}"
echo "recoveryCutoff=${PARKIO_RESTORE_RECOVERY_CUTOFF:-}"

if [ ! -d "${DEST_DIR}" ]; then
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "WARN: backup destination ${DEST_DIR} not found (dry-run continues)."
  else
    echo "ERROR: backup destination not found: ${DEST_DIR}" >&2
    exit 2
  fi
elif [ -f "${DEST_DIR}/COMPLETE" ]; then
  echo "=== stamp preflight (${SCOPE}) ==="
  if ! parkio_restore_run_stamp_preflight "${DEST_DIR}" "${SCOPE}"; then
    echo "ERROR: stamp preflight failed; nothing was decrypted or restored." >&2
    exit 1
  fi
  if [ "$DRY_RUN" -ne 1 ]; then
    parkio_restore_require_cutoff_unless_exempt || exit 2
    if ! parkio_restore_isolated_drill; then
      echo "=== erasure coverage through ${PARKIO_RESTORE_RECOVERY_CUTOFF} ==="
      set +e
      parkio_restore_run_coverage "${DEST_DIR}" "${PARKIO_RESTORE_RECOVERY_CUTOFF}" "${LEDGER_STAMPS[@]}"
      coverage_rc=$?
      set -e
      if [ "${coverage_rc}" -eq 3 ]; then
        echo "ERROR: erasure evidence does not reach the recovery cutoff; restore BLOCKED." >&2
        echo "Never lower the cutoff to obtain PASS." >&2
        exit 3
      elif [ "${coverage_rc}" -ne 0 ]; then
        echo "ERROR: erasure coverage evidence is invalid." >&2
        exit 1
      fi
    fi
  fi
elif [ "$DRY_RUN" -eq 1 ]; then
  echo "WARN: ${DEST_DIR} is not a COMPLETE stamp (dry-run continues)."
else
  echo "ERROR: refusing restore of incomplete stamp (missing COMPLETE): ${DEST_DIR}" >&2
  exit 2
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
    PARKIO_RESTORE_PREFLIGHT_DONE=1 \
      "${ROOT}/scripts/restore-database.sh" "${svc}" "${dump}" "${args[@]}"
  done < <(jq -r '.databases[]' "${MANIFEST}")
}

restore_minio() {
  local restore_bucket="${MINIO_RESTORE_BUCKET:-${BUCKET}}"
  local stage=""
  local mirror_src=""
  local cleanup_stage=0

  if [ -f "${DEST_DIR}/minio.tar.gz.enc" ] || [ -d "${DEST_DIR}/minio" ]; then
    stage="$(mktemp -d "${TMPDIR:-/tmp}/parkio-restore-minio.XXXXXX")"
    chmod 700 "${stage}"
    cleanup_stage=1
    if ! parkio_backup_unseal_minio "${DEST_DIR}" "${stage}"; then
      rm -rf "${stage}"
      return 1
    fi
    mirror_src="${stage}/minio/${BUCKET}"
    if [ ! -d "${mirror_src}" ]; then
      local alt
      alt="$(find "${stage}/minio" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -1 || true)"
      if [ -n "${alt}" ]; then
        mirror_src="${alt}"
      fi
    fi
  else
    mirror_src="${DEST_DIR}/minio/${BUCKET}"
    if [ ! -d "${mirror_src}" ]; then
      local alt
      alt="$(find "${DEST_DIR}/minio" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -1 || true)"
      if [ -n "${alt}" ]; then
        mirror_src="${alt}"
      fi
    fi
  fi

  if [ "$DRY_RUN" -eq 1 ]; then
    echo "DRY-RUN: would mirror ${mirror_src} -> local/${restore_bucket}"
    if [ "${cleanup_stage}" -eq 1 ]; then rm -rf "${stage}"; fi
    return 0
  fi
  if [ ! -d "${mirror_src}" ]; then
    echo "ERROR: MinIO mirror not found: ${mirror_src}" >&2
    if [ "${cleanup_stage}" -eq 1 ]; then rm -rf "${stage}"; fi
    return 1
  fi
  if [ "${restore_bucket}" = "${BUCKET}" ] && [ "${PARKIO_ALLOW_LIVE_MINIO_RESTORE:-}" != "yes" ]; then
    echo "ERROR: refusing to overwrite live bucket '${BUCKET}'." >&2
    echo "Set MINIO_RESTORE_BUCKET to an isolated bucket, or PARKIO_ALLOW_LIVE_MINIO_RESTORE=yes after operator confirmation." >&2
    if [ "${cleanup_stage}" -eq 1 ]; then rm -rf "${stage}"; fi
    return 2
  fi
  local network mc_image minio_container
  minio_container="${PARKIO_MINIO_CONTAINER:-parkio-minio}"
  network="$(parkio_backup_backend_network "${minio_container}")"
  mc_image="${MINIO_MC_IMAGE:-quay.io/minio/mc@sha256:a5399b66b88543efac8afb08eb2bdcce5904e548ea6fe1a921600cd74f766668}"
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
  if [ "${cleanup_stage}" -eq 1 ]; then rm -rf "${stage}"; fi
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
  local ledger="${PARKIO_RESTORE_MERGED_LEDGER:-${DEST_DIR}/erasure-tombstones.json}"
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
echo "Applications, publishers, schedulers, Slack and Fluent Bit were not started."
echo "A successful data restore is not authorization to expose applications."
