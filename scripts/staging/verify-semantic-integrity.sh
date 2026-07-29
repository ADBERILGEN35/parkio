#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/lib/safety-guards.sh"
assert_staging_safety
ENV_FILE="${PARKIO_ENV_FILE:-docker/.env}"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
if [ -f "${ROOT_DIR}/${ENV_FILE}" ]; then set -a; # shellcheck disable=SC1090
  . "${ROOT_DIR}/${ENV_FILE}"; set +a; fi
container="${PARKIO_POSTGRES_PARKING_CONTAINER:-parkio-postgres-parking}"
user="${POSTGRES_PARKING_USER:-parkio_parking}"
db="${PARKIO_SEMANTIC_DB:-${POSTGRES_PARKING_DB:-parkio_parking}}"
if ! docker inspect "${container}" >/dev/null 2>&1; then echo "SKIP: parking postgres unavailable"; exit 0; fi
scalar() { docker exec -i "$1" psql -tA -U "$2" -d "$3" -c "$4" | tr -d '[:space:]'; }
if [ "$(scalar "${container}" "${user}" "${db}" "SELECT count(*) FROM pg_extension WHERE extname = 'postgis';")" != "1" ]; then
  echo "WARN: postgis extension missing on ${db}"
fi
if [ "$(scalar "${container}" "${user}" "${db}" "SELECT to_regclass('public.parking_spots') IS NOT NULL;")" = "t" ]; then
  spatial="$(scalar "${container}" "${user}" "${db}" "SELECT ST_DWithin(ST_SetSRID(ST_MakePoint(29.0,41.0),4326)::geography, ST_SetSRID(ST_MakePoint(29.001,41.0),4326)::geography, 5000);")"
  [ "${spatial}" = "t" ] || { echo "FAIL PostGIS predicate"; exit 1; }
  echo "OK PostGIS semantic query"
  cnt="$(scalar "${container}" "${user}" "${db}" "SELECT count(*) FROM parking_spots;")"
  echo "OK parking_spots readable rows=${cnt}"
  owner_col="$(scalar "${container}" "${user}" "${db}" "SELECT count(*) FROM information_schema.columns WHERE table_name='parking_spots' AND column_name='owner_user_id';")"
  [ "${owner_col}" = "1" ] || { echo "FAIL parking_spots.owner_user_id missing"; exit 1; }
  echo "OK parking_spots.owner_user_id present"
fi
if [ "$(scalar "${container}" "${user}" "${db}" "SELECT to_regclass('public.outbox_events') IS NOT NULL;")" = "t" ]; then
  echo "OK outbox_events present"
fi
for tbl in trust_ledger pending_reward_ledger fraud_ledger decision_audit outcome_history calibration_report; do
  if [ "$(scalar "${container}" "${user}" "${db}" "SELECT to_regclass('public.${tbl}') IS NOT NULL;")" = "t" ]; then
    cnt="$(scalar "${container}" "${user}" "${db}" "SELECT count(*) FROM ${tbl};")"
    echo "OK ${tbl} rows=${cnt}"
  else
    echo "INFO: ${tbl} absent (empty drill DB or pre-WP-05 schema)"
  fi
done
echo "semantic integrity completed"
