#!/usr/bin/env bash
#
# Parkio — operational recovery for transactional outbox dead-letter rows.
#
# Private operator tool: run from a shell with access to the service PostgreSQL
# containers. It exposes no public/admin HTTP endpoint and mutates only the
# selected service database. Payloads are hidden unless explicitly requested.
#
# Examples:
#   PARKIO_ENV_FILE=docker/.env PARKIO_OPERATOR=alice \
#     scripts/outbox-deadletter-recovery.sh list --service parking
#
#   PARKIO_OPERATOR=alice scripts/outbox-deadletter-recovery.sh details \
#     --service parking --id 00000000-0000-0000-0000-000000000000
#
#   PARKIO_OPERATOR=alice scripts/outbox-deadletter-recovery.sh retry \
#     --service parking --id 00000000-0000-0000-0000-000000000000 \
#     --reason "broker fixed" --yes
#
#   PARKIO_OPERATOR=alice scripts/outbox-deadletter-recovery.sh retry-bulk \
#     --service parking --event-type ParkingSpotCreated --limit 25 \
#     --reason "topic mapping fixed" --yes
#
#   PARKIO_OPERATOR=alice scripts/outbox-deadletter-recovery.sh acknowledge \
#     --service parking --id 00000000-0000-0000-0000-000000000000 \
#     --reason "obsolete event superseded by manual correction" --yes

set -euo pipefail

ACTION="${1:-}"
[ -n "${ACTION}" ] && shift || true

SERVICE=""
ROW_ID=""
LIMIT="50"
AGGREGATE_TYPE=""
EVENT_TYPE=""
AGGREGATE_ID=""
CREATED_AFTER=""
CREATED_BEFORE=""
REASON_LIKE=""
REASON=""
SHOW_PAYLOAD="0"
INCLUDE_ACK="0"
ASSUME_YES="0"
TARGET="${PARKIO_TARGET:-local}"
ENV_FILE="${PARKIO_ENV_FILE:-}"
MAX_RECOVERY_ATTEMPTS="${PARKIO_OUTBOX_RECOVERY_MAX_ATTEMPTS:-3}"

usage() {
  sed -n '2,34p' "$0"
  cat <<'EOF'

Actions:
  list          List open dead-letter rows (payload redacted)
  details       Show one row; add --show-payload only when needed
  retry         Move one open dead-letter row back to pending
  retry-bulk    Move up to --limit matching open rows back to pending
  acknowledge   Mark one open dead-letter row intentionally suppressed

Common filters:
  --service <name>          auth|user|parking|media|gamification|moderation|ai-validation
  --aggregate-type <type>   filter aggregate_type
  --event-type <type>       filter event_type
  --aggregate-id <uuid>     filter aggregate_id
  --created-after <iso>     filter created_at >= timestamp
  --created-before <iso>    filter created_at <= timestamp
  --reason-like <text>      filter last_failure_reason ILIKE %text%
  --limit <n>               list/bulk cap (default 50; max 100)
  --reason <text>           required for retry/acknowledge
  --yes                     required for mutations
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --service) SERVICE="${2:-}"; shift 2 ;;
    --service=*) SERVICE="${1#*=}"; shift ;;
    --id) ROW_ID="${2:-}"; shift 2 ;;
    --id=*) ROW_ID="${1#*=}"; shift ;;
    --limit) LIMIT="${2:-}"; shift 2 ;;
    --limit=*) LIMIT="${1#*=}"; shift ;;
    --aggregate-type) AGGREGATE_TYPE="${2:-}"; shift 2 ;;
    --aggregate-type=*) AGGREGATE_TYPE="${1#*=}"; shift ;;
    --event-type) EVENT_TYPE="${2:-}"; shift 2 ;;
    --event-type=*) EVENT_TYPE="${1#*=}"; shift ;;
    --aggregate-id) AGGREGATE_ID="${2:-}"; shift 2 ;;
    --aggregate-id=*) AGGREGATE_ID="${1#*=}"; shift ;;
    --created-after) CREATED_AFTER="${2:-}"; shift 2 ;;
    --created-after=*) CREATED_AFTER="${1#*=}"; shift ;;
    --created-before) CREATED_BEFORE="${2:-}"; shift 2 ;;
    --created-before=*) CREATED_BEFORE="${1#*=}"; shift ;;
    --reason-like) REASON_LIKE="${2:-}"; shift 2 ;;
    --reason-like=*) REASON_LIKE="${1#*=}"; shift ;;
    --reason) REASON="${2:-}"; shift 2 ;;
    --reason=*) REASON="${1#*=}"; shift ;;
    --show-payload) SHOW_PAYLOAD="1"; shift ;;
    --include-acknowledged) INCLUDE_ACK="1"; shift ;;
    --yes) ASSUME_YES="1"; shift ;;
    --target) TARGET="${2:-}"; shift 2 ;;
    --target=*) TARGET="${1#*=}"; shift ;;
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; usage >&2; exit 2 ;;
  esac
done

case "${ACTION}" in
  list|details|retry|retry-bulk|acknowledge) ;;
  *) echo "ERROR: action required: list|details|retry|retry-bulk|acknowledge" >&2; usage >&2; exit 2 ;;
esac

case "${TARGET}" in
  local|hosted-beta) ;;
  production)
    if [ "${PARKIO_CONFIRM_PRODUCTION:-}" != "I_UNDERSTAND" ]; then
      echo "REFUSING production recovery without PARKIO_CONFIRM_PRODUCTION=I_UNDERSTAND." >&2
      exit 3
    fi ;;
  *) echo "ERROR: invalid --target '${TARGET}'." >&2; exit 2 ;;
esac

if [ -n "${ENV_FILE}" ]; then
  if [ -f "${ENV_FILE}" ]; then
    set -a
    # shellcheck disable=SC1090
    . "${ENV_FILE}"
    set +a
  else
    echo "WARN: env file '${ENV_FILE}' not found; relying on current environment." >&2
  fi
fi

resolve() {
  case "$1" in
    auth) echo "parkio-postgres-auth:${POSTGRES_AUTH_USER:-parkio_auth}:${POSTGRES_AUTH_DB:-parkio_auth}" ;;
    user) echo "parkio-postgres-user:${POSTGRES_USER_USER:-parkio_user}:${POSTGRES_USER_DB:-parkio_user}" ;;
    parking) echo "parkio-postgres-parking:${POSTGRES_PARKING_USER:-parkio_parking}:${POSTGRES_PARKING_DB:-parkio_parking}" ;;
    media) echo "parkio-postgres-media:${POSTGRES_MEDIA_USER:-parkio_media}:${POSTGRES_MEDIA_DB:-parkio_media}" ;;
    gamification) echo "parkio-postgres-gamification:${POSTGRES_GAMIFICATION_USER:-parkio_gamification}:${POSTGRES_GAMIFICATION_DB:-parkio_gamification}" ;;
    moderation) echo "parkio-postgres-moderation:${POSTGRES_MODERATION_USER:-parkio_moderation}:${POSTGRES_MODERATION_DB:-parkio_moderation}" ;;
    ai-validation) echo "parkio-postgres-ai-validation:${POSTGRES_AIVALIDATION_USER:-parkio_aivalidation}:${POSTGRES_AIVALIDATION_DB:-parkio_aivalidation}" ;;
    *) return 1 ;;
  esac
}

if [ -z "${SERVICE}" ] || ! TRIPLE="$(resolve "${SERVICE}")"; then
  echo "ERROR: --service must be one of auth user parking media gamification moderation ai-validation." >&2
  exit 2
fi
IFS=":" read -r CONTAINER DB_USER DB_NAME <<< "${TRIPLE}"

if ! [[ "${LIMIT}" =~ ^[0-9]+$ ]] || [ "${LIMIT}" -lt 1 ] || [ "${LIMIT}" -gt 100 ]; then
  echo "ERROR: --limit must be an integer from 1 to 100." >&2
  exit 2
fi
if ! [[ "${MAX_RECOVERY_ATTEMPTS}" =~ ^[0-9]+$ ]] || [ "${MAX_RECOVERY_ATTEMPTS}" -lt 1 ]; then
  echo "ERROR: PARKIO_OUTBOX_RECOVERY_MAX_ATTEMPTS must be a positive integer." >&2
  exit 2
fi
if [[ "${ACTION}" =~ ^(details|retry|acknowledge)$ ]] && [ -z "${ROW_ID}" ]; then
  echo "ERROR: ${ACTION} requires --id." >&2
  exit 2
fi
if [[ "${ACTION}" =~ ^(retry|retry-bulk|acknowledge)$ ]]; then
  if [ -z "${PARKIO_OPERATOR:-}" ]; then
    echo "ERROR: set PARKIO_OPERATOR for recovery audit." >&2
    exit 2
  fi
  if [ -z "${REASON}" ]; then
    echo "ERROR: mutations require --reason." >&2
    exit 2
  fi
  if [ "${ASSUME_YES}" != "1" ]; then
    echo "ERROR: mutation refused without --yes." >&2
    exit 2
  fi
fi

if ! docker inspect "${CONTAINER}" >/dev/null 2>&1; then
  echo "ERROR: container '${CONTAINER}' not found / not running." >&2
  exit 1
fi

psql_db() {
  docker exec -i "${CONTAINER}" psql -v ON_ERROR_STOP=1 -X -U "${DB_USER}" -d "${DB_NAME}" "$@"
}

FILTER_SQL=""
[ -n "${AGGREGATE_TYPE}" ] && FILTER_SQL="${FILTER_SQL} AND aggregate_type = :'aggregate_type'"
[ -n "${EVENT_TYPE}" ] && FILTER_SQL="${FILTER_SQL} AND event_type = :'event_type'"
[ -n "${AGGREGATE_ID}" ] && FILTER_SQL="${FILTER_SQL} AND aggregate_id = :'aggregate_id'::uuid"
[ -n "${CREATED_AFTER}" ] && FILTER_SQL="${FILTER_SQL} AND created_at >= :'created_after'::timestamptz"
[ -n "${CREATED_BEFORE}" ] && FILTER_SQL="${FILTER_SQL} AND created_at <= :'created_before'::timestamptz"
[ -n "${REASON_LIKE}" ] && FILTER_SQL="${FILTER_SQL} AND COALESCE(last_failure_reason, '') ILIKE '%' || :'reason_like' || '%'"

common_psql_args=(
  -v aggregate_type="${AGGREGATE_TYPE}"
  -v event_type="${EVENT_TYPE}"
  -v aggregate_id="${AGGREGATE_ID}"
  -v created_after="${CREATED_AFTER}"
  -v created_before="${CREATED_BEFORE}"
  -v reason_like="${REASON_LIKE}"
  -v row_id="${ROW_ID}"
  -v limit="${LIMIT}"
  -v operator="${PARKIO_OPERATOR:-readonly}"
  -v reason="${REASON}"
  -v max_recovery_attempts="${MAX_RECOVERY_ATTEMPTS}"
)

case "${ACTION}" in
  list)
    ack_filter="AND acknowledged_deadletter = false"
    [ "${INCLUDE_ACK}" = "1" ] && ack_filter=""
    psql_db "${common_psql_args[@]}" <<SQL
SELECT id, event_id, aggregate_type, aggregate_id, event_type, occurred_at, created_at,
       failure_count, recovery_attempt_count, last_failed_at,
       acknowledged_deadletter, left(coalesce(last_failure_reason, ''), 240) AS failure_reason
FROM outbox_events
WHERE dead_lettered = true ${ack_filter} ${FILTER_SQL}
ORDER BY created_at, id
LIMIT :'limit'::int;
SQL
    ;;
  details)
    payload_expr="'<redacted; rerun with --show-payload>' AS payload"
    [ "${SHOW_PAYLOAD}" = "1" ] && payload_expr="payload"
    psql_db "${common_psql_args[@]}" <<SQL
SELECT id, event_id, aggregate_type, aggregate_id, event_type, occurred_at, created_at,
       trace_id, published, dead_lettered, failure_count, last_failure_reason, last_failed_at,
       recovery_attempt_count, acknowledged_deadletter, acknowledged_at, acknowledged_by,
       acknowledged_reason, last_recovery_action, last_recovery_at, last_recovery_by,
       last_recovery_reason, ${payload_expr}
FROM outbox_events
WHERE id = :'row_id'::uuid;

SELECT action, operator_id, reason, dry_run, previous_failure_count,
       previous_dead_lettered, previous_acknowledged, created_at
FROM outbox_recovery_audit
WHERE outbox_event_id = :'row_id'::uuid
ORDER BY created_at DESC
LIMIT 20;
SQL
    ;;
  retry|retry-bulk)
    id_filter=""
    [ "${ACTION}" = "retry" ] && id_filter="AND id = :'row_id'::uuid"
    psql_db "${common_psql_args[@]}" <<SQL
WITH target AS (
    SELECT id, event_id, failure_count, dead_lettered, acknowledged_deadletter
    FROM outbox_events
    WHERE dead_lettered = true
      AND acknowledged_deadletter = false
      AND recovery_attempt_count < :'max_recovery_attempts'::int
      ${id_filter} ${FILTER_SQL}
    ORDER BY created_at, id
    LIMIT :'limit'::int
    FOR UPDATE
), audit AS (
    INSERT INTO outbox_recovery_audit (
        id, outbox_event_id, event_id, action, operator_id, reason, dry_run,
        previous_failure_count, previous_dead_lettered, previous_acknowledged
    )
    SELECT ('00000000-0000-4000-8000-' || substr(md5(random()::text || clock_timestamp()::text), 1, 12))::uuid, id, event_id, 'RETRY', :'operator', :'reason', false,
           failure_count, dead_lettered, acknowledged_deadletter
    FROM target
    RETURNING outbox_event_id
), updated AS (
    UPDATE outbox_events o
       SET dead_lettered = false,
           published = false,
           failure_count = 0,
           last_failure_reason = NULL,
           last_failed_at = NULL,
           recovery_attempt_count = recovery_attempt_count + 1,
           last_recovery_action = 'RETRY',
           last_recovery_at = NOW(),
           last_recovery_by = :'operator',
           last_recovery_reason = :'reason'
      FROM target
     WHERE o.id = target.id
     RETURNING o.id, o.event_id, o.aggregate_type, o.event_type, o.recovery_attempt_count
)
SELECT * FROM updated ORDER BY id;
SQL
    ;;
  acknowledge)
    psql_db "${common_psql_args[@]}" <<SQL
WITH target AS (
    SELECT id, event_id, failure_count, dead_lettered, acknowledged_deadletter
    FROM outbox_events
    WHERE id = :'row_id'::uuid
      AND dead_lettered = true
      AND acknowledged_deadletter = false
    FOR UPDATE
), audit AS (
    INSERT INTO outbox_recovery_audit (
        id, outbox_event_id, event_id, action, operator_id, reason, dry_run,
        previous_failure_count, previous_dead_lettered, previous_acknowledged
    )
    SELECT ('00000000-0000-4000-8000-' || substr(md5(random()::text || clock_timestamp()::text), 1, 12))::uuid, id, event_id, 'ACKNOWLEDGE', :'operator', :'reason', false,
           failure_count, dead_lettered, acknowledged_deadletter
    FROM target
    RETURNING outbox_event_id
), updated AS (
    UPDATE outbox_events o
       SET acknowledged_deadletter = true,
           acknowledged_at = NOW(),
           acknowledged_by = :'operator',
           acknowledged_reason = :'reason',
           last_recovery_action = 'ACKNOWLEDGE',
           last_recovery_at = NOW(),
           last_recovery_by = :'operator',
           last_recovery_reason = :'reason'
      FROM target
     WHERE o.id = target.id
     RETURNING o.id, o.event_id, o.aggregate_type, o.event_type, o.acknowledged_deadletter
)
SELECT * FROM updated ORDER BY id;
SQL
    ;;
esac
