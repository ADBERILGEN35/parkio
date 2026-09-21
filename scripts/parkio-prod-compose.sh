#!/usr/bin/env bash
# Durable production compose wrapper for parkio-civo-prod.
# Always includes GMP release pins so normal ops retain digest pins.
#
# Usage (from repo root or any cwd):
#   PARKIO_ENV_FILE=docker/.env.azure-hosted-beta \
#     ./scripts/parkio-prod-compose.sh up -d --no-build --no-deps gateway-service
#
# Optional mechanical recovery overlay (host-local prior tags; not a security fix):
#   PARKIO_GMP_RECOVERY=1 ./scripts/parkio-prod-compose.sh up -d --no-build --no-deps <service>
#
# Do not commit production .env files. Cron quoting on
# PARKIO_MUNICIPAL_OCCUPANCY_RETENTION_CRON must remain double-quoted in the env file.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${PARKIO_ENV_FILE:-$ROOT/docker/.env.azure-hosted-beta}"
FILES_LIST="${PARKIO_COMPOSE_FILES_LIST:-$ROOT/docker/compose.production.files}"
ARGS=()
while IFS= read -r line || [ -n "$line" ]; do
  [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
  ARGS+=(-f "$ROOT/$line")
done < "$FILES_LIST"
# Optional recovery: PARKIO_GMP_RECOVERY=1 appends host-local prior-image overlay when present.
if [ "${PARKIO_GMP_RECOVERY:-0}" = "1" ]; then
  recovery="$ROOT/docker/docker-compose.gmp-recovery-prior.yml"
  if [ ! -f "$recovery" ]; then
    echo "ERROR: PARKIO_GMP_RECOVERY=1 but missing $recovery" >&2
    exit 2
  fi
  ARGS+=(-f "$recovery")
fi
cd "$ROOT"
exec docker compose --env-file "$ENV_FILE" "${ARGS[@]}" "$@"
