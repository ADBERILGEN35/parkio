#!/usr/bin/env bash
# Parkio invite-production preflight.
#
# Runs the existing hosted-beta secret/domain checks, then enforces the first
# controlled-invite invariants: managed PostgreSQL only, verify-full TLS,
# production backup mode, erasure enabled, and the approved feature-flag matrix.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${PARKIO_ENV_FILE:-docker/.env.invite-production}"
SKIP_COMPOSE=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --skip-compose) SKIP_COMPOSE=1; shift ;;
    -h|--help)
      sed -n '2,8p' "$0"
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument '$1'" >&2
      exit 2
      ;;
  esac
done

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: env file not found: $ENV_FILE" >&2
  exit 2
fi

env_get() {
  grep "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- \
    | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}

expect_eq() {
  local key="$1"
  local expected="$2"
  local actual
  actual="$(env_get "$key")"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL $key: expected '$expected' but found '${actual:-<unset>}'" >&2
    return 1
  fi
  return 0
}

expect_nonempty() {
  local key="$1"
  local actual
  actual="$(env_get "$key")"
  if [ -z "$actual" ]; then
    echo "FAIL $key: value is required" >&2
    return 1
  fi
  return 0
}

expect_empty() {
  local key="$1"
  local actual
  actual="$(env_get "$key")"
  if [ -n "$actual" ]; then
    echo "FAIL $key: expected empty value for the web-only invite boundary" >&2
    return 1
  fi
  return 0
}

expect_secret_ready() {
  local key="$1"
  local actual
  actual="$(env_get "$key")"
  if [ -z "$actual" ]; then
    echo "FAIL $key: secret is required" >&2
    return 1
  fi
  if printf '%s' "$actual" | grep -qiE 'REPLACE_ME|PLACEHOLDER|CHANGE_ME|DUMMY|TODO'; then
    echo "FAIL $key: placeholder secret is not allowed" >&2
    return 1
  fi
  return 0
}

FAILURES=0

echo "=== Parkio invite-production preflight ==="
echo "envFile=$ENV_FILE"

base_args=(--env-file "$ENV_FILE")
if [ "$SKIP_COMPOSE" -eq 1 ]; then
  base_args+=(--skip-compose)
fi

if ! "$ROOT/scripts/preflight-hosted-beta.sh" "${base_args[@]}" --deployment-profile invite-production; then
  echo "ERROR: base hosted-environment preflight failed." >&2
  exit 3
fi

echo
echo "[Invite-production invariants]"

expect_eq PARKIO_DEPLOYMENT_PROFILE invite-production || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_ENVIRONMENT invite-production || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_PG_MODE managed || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_PG_SSLMODE verify-full || FAILURES=$((FAILURES + 1))
expect_eq SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE 4 || FAILURES=$((FAILURES + 1))
expect_eq SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE 1 || FAILURES=$((FAILURES + 1))
expect_eq BACKUP_PRODUCTION_MODE 1 || FAILURES=$((FAILURES + 1))
expect_eq BACKUP_AZURE_CONTAINER invite-production-backups || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_ACCOUNT_ERASURE_ENABLED true || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_PUSH_DELIVERY_ENABLED false || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_PUSH_DELIVERY_PROVIDER noop || FAILURES=$((FAILURES + 1))
expect_empty PARKIO_EXPO_ACCESS_TOKEN || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_DOMAIN api.parkio.dev || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_WEB_DOMAIN app.parkio.dev || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MEDIA_DOMAIN media.parkio.dev || FAILURES=$((FAILURES + 1))
expect_eq VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED true || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MUNICIPAL_ENABLED true || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MUNICIPAL_MANUAL_SYNC_ENABLED false || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MUNICIPAL_IZUM_ENABLED true || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MUNICIPAL_IZUM_SCHEDULER_ENABLED true || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MUNICIPAL_ISPARK_ENABLED true || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MUNICIPAL_ISPARK_SCHEDULER_ENABLED true || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MUNICIPAL_ANPARK_ENABLED false || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MUNICIPAL_ANPARK_SCHEDULER_ENABLED false || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MUNICIPAL_KONYA_ENABLED false || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MUNICIPAL_KONYA_SCHEDULER_ENABLED false || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MUNICIPAL_KAYSERI_ENABLED false || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MUNICIPAL_KAYSERI_SCHEDULER_ENABLED false || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MUNICIPAL_OSM_IMPORT_ENABLED false || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MUNICIPAL_OSM_SCHEDULER_ENABLED false || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_MUNICIPAL_OSM_PUBLICATION_ENABLED false || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_SPA_RECOMMENDATIONS_ENABLED true || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_SPA_RANKING_ENABLED true || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_SPA_RANKING_STRATEGY DETERMINISTIC_V1 || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_SPA_RANKING_SHADOW_ENABLED false || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_SPA_RANKING_SHADOW_SAMPLE_RATE 0.0 || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_SPA_RANKING_EVALUATION_ENABLED false || FAILURES=$((FAILURES + 1))
expect_eq PARKIO_SPA_RANKING_EVALUATION_ROLLUP_ENABLED false || FAILURES=$((FAILURES + 1))

expect_nonempty PARKIO_PG_HOST || FAILURES=$((FAILURES + 1))
expect_nonempty PARKIO_PG_SSLROOTCERT || FAILURES=$((FAILURES + 1))
expect_nonempty PARKIO_PG_JDBC_QUERY || FAILURES=$((FAILURES + 1))

for service in AUTH GATEWAY USER PARKING MEDIA GAMIFICATION NOTIFICATION MODERATION ANALYTICS AIVALIDATION; do
  expect_secret_ready "POSTGRES_${service}_MIGRATION_PASSWORD" || FAILURES=$((FAILURES + 1))
done

if ! env_get PARKIO_PG_JDBC_QUERY | grep -q 'sslmode=verify-full'; then
  echo "FAIL PARKIO_PG_JDBC_QUERY: must include sslmode=verify-full" >&2
  FAILURES=$((FAILURES + 1))
fi
if ! env_get PARKIO_PG_JDBC_QUERY | grep -q 'sslrootcert='; then
  echo "FAIL PARKIO_PG_JDBC_QUERY: must include sslrootcert= for hostname-validated trust" >&2
  FAILURES=$((FAILURES + 1))
fi
if env_get PARKIO_PG_HOST | grep -Eq '^(localhost|127\.0\.0\.1|0\.0\.0\.0)$'; then
  echo "FAIL PARKIO_PG_HOST: must be the private Azure Flexible Server hostname, never localhost/IP literals" >&2
  FAILURES=$((FAILURES + 1))
fi

if [ "$FAILURES" -ne 0 ]; then
  echo "Invite-production preflight failed with $FAILURES invariant error(s)." >&2
  exit 1
fi

echo "Invite-production preflight passed."
