#!/usr/bin/env bash
#
# Parkio — nightly per-database backup for the hosted-beta single-VPS stack.
#
# Dumps each service's PostgreSQL database (database-per-service) with pg_dump via
# `docker exec`, gzips it, optionally encrypts it (AES-256), optionally uploads it to a
# remote S3-compatible bucket via the MinIO client `mc`, and prunes old local backups.
#
# It connects over the container's local socket (the official postgres image trusts local
# connections), so no DB password is needed here.
#
# Usage:
#   # load the same .env the stack uses, then run:
#   PARKIO_ENV_FILE=docker/.env ./scripts/backup-databases.sh
#
# Schedule nightly via cron on the VPS, e.g. (03:30, log to syslog):
#   30 3 * * * cd /opt/parkio && PARKIO_ENV_FILE=docker/.env ./scripts/backup-databases.sh >> /var/log/parkio-backup.log 2>&1
#
# Restore a single database with the companion script (handles gzip/encryption + safety prompt):
#   ./scripts/restore-database.sh auth /var/backups/parkio/<stamp>/auth.sql.gz
# Verify a dump is restorable without touching live data (restores into a throwaway DB):
#   ./scripts/verify-backup.sh auth /var/backups/parkio/<stamp>/auth.sql.gz
#
# Manual restore equivalent (example, auth) from a plain .sql.gz:
#   gunzip -c /var/backups/parkio/<stamp>/auth.sql.gz \
#     | docker exec -i parkio-postgres-auth psql -U parkio_auth -d parkio_auth
#
# NOTE: this protects against data loss on a single VPS. It is NOT a substitute for managed
# Postgres PITR/HA, which is required before public production.

set -euo pipefail

# Optionally load an env file (so POSTGRES_*_USER/DB, BACKUP_* are available).
ENV_FILE="${PARKIO_ENV_FILE:-}"
if [ -n "${ENV_FILE}" ]; then
  if [ -f "${ENV_FILE}" ]; then
    set -a
    # shellcheck disable=SC1090
    . "${ENV_FILE}"
    set +a
  else
    echo "WARN: PARKIO_ENV_FILE='${ENV_FILE}' not found; relying on current environment." >&2
  fi
fi

BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
ENCRYPT_PASSPHRASE="${BACKUP_ENCRYPT_PASSPHRASE:-}"
MC_DEST="${BACKUP_MC_DEST:-}"

STAMP="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
DEST_DIR="${BACKUP_DIR}/${STAMP}"
mkdir -p "${DEST_DIR}"

# service:container:user:db  (defaults match docker/.env.example)
SERVICES=(
  "auth:parkio-postgres-auth:${POSTGRES_AUTH_USER:-parkio_auth}:${POSTGRES_AUTH_DB:-parkio_auth}"
  "user:parkio-postgres-user:${POSTGRES_USER_USER:-parkio_user}:${POSTGRES_USER_DB:-parkio_user}"
  "parking:parkio-postgres-parking:${POSTGRES_PARKING_USER:-parkio_parking}:${POSTGRES_PARKING_DB:-parkio_parking}"
  "media:parkio-postgres-media:${POSTGRES_MEDIA_USER:-parkio_media}:${POSTGRES_MEDIA_DB:-parkio_media}"
  "gamification:parkio-postgres-gamification:${POSTGRES_GAMIFICATION_USER:-parkio_gamification}:${POSTGRES_GAMIFICATION_DB:-parkio_gamification}"
  "notification:parkio-postgres-notification:${POSTGRES_NOTIFICATION_USER:-parkio_notification}:${POSTGRES_NOTIFICATION_DB:-parkio_notification}"
  "moderation:parkio-postgres-moderation:${POSTGRES_MODERATION_USER:-parkio_moderation}:${POSTGRES_MODERATION_DB:-parkio_moderation}"
  "analytics:parkio-postgres-analytics:${POSTGRES_ANALYTICS_USER:-parkio_analytics}:${POSTGRES_ANALYTICS_DB:-parkio_analytics}"
  "ai-validation:parkio-postgres-ai-validation:${POSTGRES_AIVALIDATION_USER:-parkio_aivalidation}:${POSTGRES_AIVALIDATION_DB:-parkio_aivalidation}"
)

echo "Parkio DB backup -> ${DEST_DIR} (encrypt=$([ -n "${ENCRYPT_PASSPHRASE}" ] && echo yes || echo no), mc_dest=${MC_DEST:-none})"

failures=0
for entry in "${SERVICES[@]}"; do
  IFS=":" read -r name container user db <<< "${entry}"

  if ! docker inspect "${container}" >/dev/null 2>&1; then
    echo "  SKIP ${name}: container '${container}' not found" >&2
    failures=$((failures + 1))
    continue
  fi

  out="${DEST_DIR}/${name}.sql.gz"
  printf '  %-14s ' "${name}"

  # pg_dump (plain SQL, restore-friendly) | gzip [ | openssl encrypt ]
  if [ -n "${ENCRYPT_PASSPHRASE}" ]; then
    out="${out}.enc"
    if docker exec "${container}" pg_dump -U "${user}" -d "${db}" --no-owner --clean --if-exists \
      | gzip -9 \
      | openssl enc -aes-256-cbc -pbkdf2 -salt -pass env:BACKUP_ENCRYPT_PASSPHRASE > "${out}" \
      && [ -s "${out}" ]; then
      echo "OK -> $(basename "${out}") ($(du -h "${out}" | cut -f1))"
    else
      echo "FAILED (dump errored or empty)" >&2; failures=$((failures + 1)); rm -f "${out}"
    fi
  else
    if docker exec "${container}" pg_dump -U "${user}" -d "${db}" --no-owner --clean --if-exists \
      | gzip -9 > "${out}" \
      && [ -s "${out}" ]; then
      echo "OK -> $(basename "${out}") ($(du -h "${out}" | cut -f1))"
    else
      echo "FAILED (dump errored or empty)" >&2; failures=$((failures + 1)); rm -f "${out}"
    fi
  fi
done

# Optional off-box upload to S3-compatible storage via the MinIO client.
if [ -n "${MC_DEST}" ]; then
  if command -v mc >/dev/null 2>&1; then
    echo "Uploading ${DEST_DIR} -> ${MC_DEST}/${STAMP}"
    mc cp --recursive "${DEST_DIR}" "${MC_DEST}/${STAMP}" || { echo "WARN: mc upload failed" >&2; failures=$((failures + 1)); }
  else
    echo "WARN: BACKUP_MC_DEST set but 'mc' not installed; keeping local copy only." >&2
  fi
fi

# Prune local backups older than the retention window.
if [ -d "${BACKUP_DIR}" ]; then
  find "${BACKUP_DIR}" -mindepth 1 -maxdepth 1 -type d -mtime "+${RETENTION_DAYS}" -exec rm -rf {} + 2>/dev/null || true
fi

if [ "${failures}" -ne 0 ]; then
  echo "Backup completed with ${failures} failure(s)." >&2
  exit 1
fi
echo "Backup completed successfully."
