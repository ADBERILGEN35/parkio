#!/usr/bin/env bash
#
# Smart Return V1 real-stack smoke.
#
# This script intentionally uses the normal gateway API and existing real parking
# availability. It does not create fake spots and does not add a test-only backend hook.
#
# Required:
#   SMART_RETURN_SMOKE_TOKEN   bearer token for an ACTIVE test user
#   SMART_RETURN_SMOKE_USER_ID auth/user id for the same test user
#
# Optional:
#   PARKIO_API_BASE_URL        default http://localhost:8080/api/v1
#   SMART_RETURN_HOME_LAT      default 38.4237
#   SMART_RETURN_HOME_LNG      default 27.1428
#   SMART_RETURN_HOME_LABEL    default Smart Return smoke area
#   SMART_RETURN_RADIUS        default 1000
#   SMART_RETURN_WAIT_SECONDS  default 120
#   COMPOSE_FILES              default "-f docker/docker-compose.yml -f docker/docker-compose.apps.yml"

set -euo pipefail

API_BASE="${PARKIO_API_BASE_URL:-http://localhost:8080/api/v1}"
TOKEN="${SMART_RETURN_SMOKE_TOKEN:?SMART_RETURN_SMOKE_TOKEN is required}"
USER_ID="${SMART_RETURN_SMOKE_USER_ID:?SMART_RETURN_SMOKE_USER_ID is required}"
HOME_LAT="${SMART_RETURN_HOME_LAT:-38.4237}"
HOME_LNG="${SMART_RETURN_HOME_LNG:-27.1428}"
HOME_LABEL="${SMART_RETURN_HOME_LABEL:-Smart Return smoke area}"
RADIUS="${SMART_RETURN_RADIUS:-1000}"
WAIT_SECONDS="${SMART_RETURN_WAIT_SECONDS:-120}"
COMPOSE_FILES="${COMPOSE_FILES:--f docker/docker-compose.yml -f docker/docker-compose.apps.yml}"
ENV_FILE="${PARKIO_ENV_FILE:-}"

COMPOSE=(docker compose)
# shellcheck disable=SC2206
COMPOSE+=(${COMPOSE_FILES})

env_file_value() {
  key="$1"
  if [ -z "$ENV_FILE" ] || [ ! -f "$ENV_FILE" ]; then
    return 1
  fi
  grep -E "^${key}=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//'
}

POSTGRES_NOTIFICATION_USER_VALUE="${POSTGRES_NOTIFICATION_USER:-$(env_file_value POSTGRES_NOTIFICATION_USER || true)}"
POSTGRES_NOTIFICATION_DB_VALUE="${POSTGRES_NOTIFICATION_DB:-$(env_file_value POSTGRES_NOTIFICATION_DB || true)}"
POSTGRES_NOTIFICATION_USER_VALUE="${POSTGRES_NOTIFICATION_USER_VALUE:-parkio_notification}"
POSTGRES_NOTIFICATION_DB_VALUE="${POSTGRES_NOTIFICATION_DB_VALUE:-parkio_notification}"

auth_header=(-H "Authorization: Bearer ${TOKEN}")
json_header=(-H "Content-Type: application/json")

started_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
expected_return_at="$(date -u -d "+60 seconds" +"%Y-%m-%dT%H:%M:%SZ")"

echo "Checking Smart Return feature is reachable..."
curl -fsS "${auth_header[@]}" "${API_BASE}/users/me/smart-return" >/dev/null

echo "Checking real nearby parking availability exists near configured home area..."
nearby="$(curl -fsS "${auth_header[@]}" \
  "${API_BASE}/parking/spots/nearby?lat=${HOME_LAT}&lng=${HOME_LNG}&radius=${RADIUS}&limit=5")"
if [ "$nearby" = "[]" ]; then
  echo "No real nearby spots returned; refusing to fake Smart Return availability." >&2
  exit 4
fi

echo "Enabling Smart Return and setting saved home area..."
curl -fsS -X PUT "${auth_header[@]}" "${json_header[@]}" \
  "${API_BASE}/users/me/smart-return/settings" \
  --data "{\"enabled\":true,\"homeLatitude\":${HOME_LAT},\"homeLongitude\":${HOME_LNG},\"homeLabel\":\"${HOME_LABEL}\",\"defaultReturnTime\":\"18:30\",\"reminderLeadMinutes\":5}" \
  >/dev/null

echo "Answering left-by-car with a due return-check window..."
curl -fsS -X POST "${auth_header[@]}" "${json_header[@]}" \
  "${API_BASE}/users/me/smart-return/today/left-by-car" \
  --data "{\"expectedReturnAt\":\"${expected_return_at}\"}" \
  >/dev/null

echo "Waiting for notification-service scheduler to claim and complete the due check..."
deadline=$((SECONDS + WAIT_SECONDS))
count="0"
while [ "$SECONDS" -lt "$deadline" ]; do
  count="$("${COMPOSE[@]}" exec -T postgres-notification \
    psql -U "${POSTGRES_NOTIFICATION_USER_VALUE}" \
      -d "${POSTGRES_NOTIFICATION_DB_VALUE}" \
      -tAc "SELECT count(*) FROM notifications WHERE user_id = '${USER_ID}' AND type = 'SMART_RETURN_AVAILABLE' AND created_at >= '${started_at}'::timestamptz;")"
  count="$(echo "$count" | tr -d '[:space:]')"
  if [ "$count" = "1" ]; then
    break
  fi
  if [ "$count" != "0" ]; then
    echo "Expected exactly one Smart Return notification, found ${count}." >&2
    exit 5
  fi
  sleep 5
done

if [ "$count" != "1" ]; then
  echo "Timed out waiting for exactly one Smart Return notification row." >&2
  exit 6
fi

echo "Verifying duplicate notification was not produced after an additional scheduler interval..."
sleep 10
count_after="$("${COMPOSE[@]}" exec -T postgres-notification \
  psql -U "${POSTGRES_NOTIFICATION_USER_VALUE}" \
    -d "${POSTGRES_NOTIFICATION_DB_VALUE}" \
    -tAc "SELECT count(*) FROM notifications WHERE user_id = '${USER_ID}' AND type = 'SMART_RETURN_AVAILABLE' AND created_at >= '${started_at}'::timestamptz;")"
count_after="$(echo "$count_after" | tr -d '[:space:]')"
if [ "$count_after" != "1" ]; then
  echo "Duplicate Smart Return notification detected; count=${count_after}." >&2
  exit 7
fi

echo "Checking service logs for exact home coordinates..."
if "${COMPOSE[@]}" logs --no-color user-service notification-service parking-service \
  | grep -F "${HOME_LAT}" \
  | grep -F "${HOME_LNG}" >/dev/null; then
  echo "Exact home coordinates appeared in service logs." >&2
  exit 8
fi

echo "Smart Return real-stack smoke passed."
