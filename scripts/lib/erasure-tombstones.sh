#!/usr/bin/env bash
# Export / replay PRIV-001 erasure tombstones (auth_user_id + erased_at only).
# Live backups are never rewritten. Restore targets must replay this ledger
# before returning an environment to service.

parkio_erasure_psql() {
  local container="$1"
  local user="$2"
  local db="$3"
  shift 3
  if [ "${PARKIO_PG_MODE:-docker}" = "managed" ]; then
    : "${PARKIO_PG_HOST:?PARKIO_PG_HOST required when PARKIO_PG_MODE=managed}"
    local sslmode="${PARKIO_PG_SSLMODE:-require}"
    if [ "${sslmode}" = "disable" ]; then
      echo "ERROR: PARKIO_PG_SSLMODE=disable is not allowed." >&2
      return 2
    fi
    local pw="${PARKIO_PG_PASSWORD:-${POSTGRES_AUTH_PASSWORD:-}}"
    PGPASSWORD="${pw}" PGSSLMODE="${sslmode}" psql \
      -h "${PARKIO_PG_HOST}" -p "${PARKIO_PG_PORT:-5432}" \
      -U "${user}" -d "${db}" "$@"
    return $?
  fi
  docker exec "${container}" psql -U "${user}" -d "${db}" "$@"
}

parkio_export_erasure_tombstones() {
  local dest_dir="$1"
  local container="${2:-parkio-postgres-auth}"
  local user="${3:-${POSTGRES_AUTH_USER:-parkio_auth}}"
  local db="${4:-${POSTGRES_AUTH_DB:-parkio_auth}}"
  local out="${dest_dir}/erasure-tombstones.json"
  if ! parkio_erasure_psql "${container}" "${user}" "${db}" -tAc \
      "SELECT to_regclass('public.erased_user_tombstones')" 2>/dev/null | grep -q erased_user_tombstones; then
    printf '[]\n' > "${out}"
    echo "erasure tombstones: table absent (wrote empty ledger)"
    return 0
  fi
  parkio_erasure_psql "${container}" "${user}" "${db}" -tAc \
    "SELECT COALESCE(json_agg(json_build_object('authUserId', auth_user_id, 'erasedAt', erased_at) ORDER BY erased_at), '[]'::json)
     FROM erased_user_tombstones;" > "${out}"
  echo "erasure tombstones: exported $(wc -c < "${out}") bytes"
}

parkio_replay_erasure_tombstones() {
  local ledger="$1"
  local container="$2"
  local user="$3"
  local db="$4"
  if [ ! -f "${ledger}" ]; then
    if [ "${BACKUP_PRODUCTION_MODE:-0}" = "1" ] || [ "${PARKIO_RESTORE_REQUIRE_ERASURE_LEDGER:-0}" = "1" ]; then
      echo "ERROR: erasure ledger required but absent: ${ledger}" >&2
      return 1
    fi
    echo "erasure replay: no ledger file"
    return 0
  fi
  if ! parkio_erasure_psql "${container}" "${user}" "${db}" -tAc \
      "SELECT to_regclass('public.erased_user_tombstones')" 2>/dev/null | grep -q erased_user_tombstones; then
    if [ "${BACKUP_PRODUCTION_MODE:-0}" = "1" ] || [ "${PARKIO_RESTORE_REQUIRE_ERASURE_LEDGER:-0}" = "1" ]; then
      echo "ERROR: erasure ledger present but restore target has no tombstone table" >&2
      return 1
    fi
    echo "erasure replay: target has no tombstone table (skip)"
    return 0
  fi
  local payload
  payload="$(tr -d '\n' < "${ledger}" | sed "s/'/''/g")"
  parkio_erasure_psql "${container}" "${user}" "${db}" -v ON_ERROR_STOP=1 \
    -c "INSERT INTO erased_user_tombstones (auth_user_id, erased_at)
        SELECT (elem->>'authUserId')::uuid,
               COALESCE((elem->>'erasedAt')::timestamptz, now())
        FROM json_array_elements('${payload}'::json) AS elem
        ON CONFLICT (auth_user_id) DO NOTHING;
        UPDATE auth_users u
        SET status = 'ERASURE_IN_PROGRESS',
            status_changed_at = now(),
            session_epoch = COALESCE(session_epoch, 0) + 1
        WHERE u.status = 'ACTIVE'
          AND EXISTS (SELECT 1 FROM erased_user_tombstones t WHERE t.auth_user_id = u.id);"
}
