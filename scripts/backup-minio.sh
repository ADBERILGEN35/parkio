#!/usr/bin/env bash
#
# Parkio - mirror the MinIO media bucket for hosted-beta backup.
#
# Usage:
#   PARKIO_ENV_FILE=docker/.env ./scripts/backup-minio.sh /var/backups/parkio/<stamp>
#   PARKIO_ENV_FILE=docker/.env ./scripts/backup-minio.sh /var/backups/parkio/<stamp> --dry-run
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/backup-common.sh
source "$ROOT/scripts/lib/backup-common.sh"

ENV_FILE="${PARKIO_ENV_FILE:-}"
# The destination argument and --dry-run come from the command line only. The env file is sourced
# below as shell code under set -a, and backup-hosted-beta.sh hands this script the env file it ran
# with. So they are parsed into private read-only names and become DEST_DIR / DRY_RUN after it: a
# DEST_DIR or DRY_RUN line there is ignored with a WARN, and a line that assigns arg_dest_dir or
# arg_dry_run stops this step (exit 1) before it mirrors anything.
arg_dest_dir=""
arg_dry_run=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --dry-run) arg_dry_run=1; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *)
      if [ -z "${arg_dest_dir}" ]; then arg_dest_dir="$1"; shift
      else echo "ERROR: unexpected argument '$1'" >&2; exit 2
      fi ;;
  esac
done
readonly arg_dest_dir arg_dry_run

if [ -z "${arg_dest_dir}" ]; then
  echo "Usage: $0 <destination-dir> [--dry-run] [--env-file <path>]" >&2
  exit 2
fi

parkio_backup_load_env "${ENV_FILE}"
if [ -n "${DRY_RUN+set}" ]; then
  echo "WARN: ignoring DRY_RUN from the environment or env file; only --dry-run counts." >&2
fi
if [ -n "${DEST_DIR+set}" ]; then
  echo "WARN: ignoring DEST_DIR from the environment or env file; only the destination argument counts." >&2
fi
DRY_RUN="${arg_dry_run}"
DEST_DIR="${arg_dest_dir}"

BUCKET="${MINIO_BUCKET:-parkio-media}"
MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:?set MINIO_ROOT_PASSWORD in env}"
MC_IMAGE="${MINIO_MC_IMAGE:-ghcr.io/adberilgen35/parkio/mc@sha256:456b1e641897329fc9491f9bc8b31df351d728af9a328bf5653707af62d0d6bf}"
MIRROR_DEST="${DEST_DIR}/minio/${BUCKET}"

MINIO_CONTAINER="${PARKIO_MINIO_CONTAINER:-parkio-minio}"
if ! docker inspect "${MINIO_CONTAINER}" >/dev/null 2>&1; then
  echo "ERROR: MinIO container '${MINIO_CONTAINER}' not found." >&2
  exit 1
fi

NETWORK="$(parkio_backup_backend_network "${MINIO_CONTAINER}")"
if [ -z "${NETWORK}" ]; then
  echo "ERROR: could not resolve backend Docker network for ${MINIO_CONTAINER}." >&2
  exit 1
fi

echo "MinIO backup -> ${MIRROR_DEST} (bucket=${BUCKET}, container=${MINIO_CONTAINER}, network=${NETWORK}, dryRun=${DRY_RUN})" >&2

if [ "$DRY_RUN" -eq 1 ]; then
  echo "DRY-RUN: would mirror local/${BUCKET} to ${MIRROR_DEST}" >&2
  echo "0"
  exit 0
fi

# The plaintext mirror stays private until parkio_backup_seal_minio encrypts and removes it
# (CL-F29.2): mc runs as the invoking user with umask 077, so the tree belongs to whoever runs
# the backup and grants nothing to group or other, and that user can delete it without root.
# MC_CONFIG_DIR gives mc a writable config directory for a user without a home in the image.
umask 077
mkdir -p "${MIRROR_DEST}"
chmod 700 "${DEST_DIR}/minio" "${MIRROR_DEST}"

# The credentials reach mc on stdin: `mc alias set ALIAS URL` reads the access and secret key from it. They are never
# a process argument: an `-e NAME=value` or a positional key sits in /proc/<pid>/cmdline of the host docker CLI or of
# mc for the whole mirror, readable by every local account (credential incident 2026-10-13, MinIO review M1).
# printf is a shell builtin, so the pipe adds no process with the values in its argv.
printf '%s\n%s\n' "${MINIO_ROOT_USER:-minioadmin}" "${MINIO_ROOT_PASSWORD}" | docker run --rm -i \
  --user "$(id -u):$(id -g)" \
  --network "${NETWORK}" \
  -v "${MIRROR_DEST}:/backup" \
  -e "MC_CONFIG_DIR=/tmp/.mc" \
  -e "BUCKET=${BUCKET}" \
  --entrypoint /bin/sh \
  "${MC_IMAGE}" \
  -c '
    set -eu
    umask 077
    mc alias set local http://minio:9000 >/dev/null
    mc mirror --overwrite --quiet "local/${BUCKET}" /backup >/dev/null
    mc ls --recursive "local/${BUCKET}" | wc -l
  '
