#!/usr/bin/env bash
# Production backup wrapper for cron (no secrets; env file sourced by backup scripts).
# Avoid putting '%' in /etc/cron.d lines — cron treats '%' specially.
set -euo pipefail
cd /opt/parkio
export PARKIO_ENV_FILE="${PARKIO_ENV_FILE:-docker/.env.azure-hosted-beta}"
export BACKUP_PRODUCTION_MODE="${BACKUP_PRODUCTION_MODE:-1}"
# Optional fire marker for certification (path via env, no cron %).
if [ -n "${PARKIO_BACKUP_FIRE_MARKER:-}" ]; then
  date -u +'SCHED_FIRE_%Y%m%dT%H%M%SZ' >> "${PARKIO_BACKUP_FIRE_MARKER}" || true
fi
exec ./scripts/backup-hosted-beta.sh
