#!/usr/bin/env bash
#
# CI-only synthetic fixture for restore drill 01 (.github/workflows/restore-drill-01-procedure.yml).
#
# Builds TWO real encrypted stamps with the production backup path
# (backup-hosted-beta.sh, BACKUP_PRODUCTION_MODE=1: encryption + offsite required),
# uploads them to an ephemeral MinIO "offsite", deletes the local copies and pulls them
# back with backup-offsite-pull.sh:
#
#   stamp S: schemas from every service's real Flyway SQL, synthetic rows, and an account
#            ERASE_AFTER that is still ACTIVE;
#   stamp L: taken after ERASE_AFTER was erased on the source (plus outbox changes), so
#            only L's ledger knows about that erasure.
#
# Identities are synthetic (@parkio.test, fixed UUIDs). Never run against a real stack.
#
# Usage (after the 10 postgres-* services and minio are up, as in backup-restore-drill.yml):
#   CI=true PARKIO_ENV_FILE=docker/.env BACKUP_ENCRYPT_PASSPHRASE=<synthetic> \
#     scripts/ci/restore-drill-01-fixture.sh --out DIR
# Writes DIR/stamps/<S>, DIR/stamps/<L> and DIR/expected.env.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../lib/backup-common.sh
source "${ROOT}/scripts/lib/backup-common.sh"

OUT=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --out) OUT="${2:-}"; shift 2 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done
[ -n "${OUT}" ] || { echo "ERROR: --out required" >&2; exit 2; }
if [ "${CI:-}" != "true" ] && [ "${PARKIO_RESTORE_FIXTURE_ALLOW:-}" != "1" ]; then
  echo "ERROR: CI-only fixture (set CI=true on a disposable runner)." >&2
  exit 2
fi
case "$(hostname)" in parkio-civo-prod*|vm-parkio-*) echo "ERROR: production host" >&2; exit 2 ;; esac
: "${BACKUP_ENCRYPT_PASSPHRASE:?synthetic passphrase required}"

ENV_FILE="${PARKIO_ENV_FILE:-${ROOT}/docker/.env}"
parkio_backup_load_env "${ENV_FILE}"
MC_IMAGE="${MINIO_MC_IMAGE:?MINIO_MC_IMAGE required}"
OFFSITE_NAME="parkio-rd01-offsite-minio"
mkdir -p "${OUT}/stamps"

KEEP_ID="00000000-0000-4000-a000-0000000001a1"
ERASE_BEFORE_ID="00000000-0000-4000-a000-0000000001b1"
ERASE_AFTER_ID="00000000-0000-4000-a000-0000000001c1"

src() { docker exec -i "$1" psql -X -q -v ON_ERROR_STOP=1 -U "$2" -d "$3" "${@:4}"; }
svc_entry() { printf '%s\n' "${PARKIO_DB_SERVICES[@]}" | grep "^$1:"; }
cleanup() { docker rm -f "${OFFSITE_NAME}" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# ---- 1. real schemas + synthetic Flyway history -----------------------------------------
declare -A FLYWAY_HEAD
for entry in "${PARKIO_DB_SERVICES[@]}"; do
  IFS=":" read -r svc container role db <<< "${entry}"
  dir="${ROOT}/services/${svc}-service/src/main/resources/db/migration"
  src "${container}" "${role}" "${db}" -c "CREATE TABLE flyway_schema_history (
      installed_rank INT PRIMARY KEY, version VARCHAR(50), description VARCHAR(200) NOT NULL,
      type VARCHAR(20) NOT NULL, script VARCHAR(1000) NOT NULL, checksum INT,
      installed_by VARCHAR(100) NOT NULL, installed_on TIMESTAMP NOT NULL DEFAULT now(),
      execution_time INT NOT NULL, success BOOLEAN NOT NULL)"
  rank=0
  for f in $(ls "${dir}"/V*.sql | sort -V); do
    src "${container}" "${role}" "${db}" < "${f}" >/dev/null
    rank=$((rank + 1))
    base="$(basename "${f}")"
    version="$(printf '%s' "${base}" | sed -E 's/^V([0-9_.]+)__.*/\1/; s/_/./g')"
    src "${container}" "${role}" "${db}" -c "INSERT INTO flyway_schema_history
      (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
      VALUES (${rank}, '${version}', 'synthetic', 'SQL', '${base}', 0, '${role}', 0, true)"
    FLYWAY_HEAD[${svc}]="${version}"
  done
  echo "fixture: ${svc} ${rank} migrations applied (head ${FLYWAY_HEAD[${svc}]})"
done

# ---- 2. synthetic rows ------------------------------------------------------------------
IFS=":" read -r _ AUTH_C AUTH_U AUTH_D <<< "$(svc_entry auth)"
IFS=":" read -r _ USER_C USER_U USER_D <<< "$(svc_entry user)"
IFS=":" read -r _ PARK_C PARK_U PARK_D <<< "$(svc_entry parking)"
IFS=":" read -r _ GW_C GW_U GW_D <<< "$(svc_entry gateway)"

user_row() {
  printf "('%s','%s','\$2a\$10\$synthetic.restore.drill.hash.not.a.real.passwordxx','ACTIVE',0,now(),now(),TRUE,0)" "$1" "$2"
}
outbox_rows() { # count published
  local n="$1" published="$2" i
  for i in $(seq 1 "${n}"); do
    printf "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, occurred_at, published)
      VALUES (gen_random_uuid(), 'Synthetic', gen_random_uuid(), 'SyntheticEvent', '{}', now(), %s);\n" "${published}"
  done
}
erase() { # id — same effect as the PRIV-001 tombstone path used by restore-drill.sh
  src "${AUTH_C}" "${AUTH_U}" "${AUTH_D}" -c "
    INSERT INTO erased_user_tombstones (auth_user_id, erased_at) VALUES ('$1', now());
    UPDATE auth_users SET status = 'ERASURE_IN_PROGRESS', status_changed_at = now(),
           session_epoch = session_epoch + 1 WHERE id = '$1';"
}

src "${AUTH_C}" "${AUTH_U}" "${AUTH_D}" -c "INSERT INTO auth_users
  (id, email, password_hash, status, version, created_at, updated_at, email_verified, session_epoch) VALUES
  $(user_row "${KEEP_ID}" rd01-keep@parkio.test),
  $(user_row "${ERASE_BEFORE_ID}" rd01-erased-before@parkio.test),
  $(user_row "${ERASE_AFTER_ID}" rd01-erased-after@parkio.test)"
erase "${ERASE_BEFORE_ID}"
{ outbox_rows 2 false; outbox_rows 1 true; } | src "${AUTH_C}" "${AUTH_U}" "${AUTH_D}"
outbox_rows 1 false | src "${USER_C}" "${USER_U}" "${USER_D}"
outbox_rows 1 false | src "${PARK_C}" "${PARK_U}" "${PARK_D}"
src "${PARK_C}" "${PARK_U}" "${PARK_D}" -c "
  INSERT INTO parking_spots (id, owner_user_id, media_id, latitude, longitude, address_text, description,
    suitable_vehicle_types, parking_context, legal_status, status, expires_at, moderation_deadline_at)
  VALUES ('00000000-0000-4000-a000-0000000001d1', '${KEEP_ID}', '00000000-0000-4000-a000-0000000001d2',
    38.4192, 27.1287, 'Synthetic restore drill 01 spot', 'synthetic', 'SEDAN', 'STREET_PARKING',
    'LEGAL', 'PENDING_VALIDATION', NULL, now() + interval '1 day')"
src "${GW_C}" "${GW_U}" "${GW_D}" -c "
  INSERT INTO waitlist_ops_notification_outbox
    (id, event_type, dedup_key, occurred_at, status, attempts, next_attempt_at, exported_at, created_at) VALUES
    (gen_random_uuid(), 'waitlist.subscription_confirmed', md5('rd01-pending'), now(), 'PENDING', 0, now(), NULL, now()),
    (gen_random_uuid(), 'waitlist.subscription_confirmed', md5('rd01-exported'), now(), 'EXPORTED', 1, now(), now(), now())"

# ---- 3. synthetic object + ephemeral offsite MinIO -------------------------------------
NETWORK="$(parkio_backup_backend_network parkio-minio)"
[ -n "${NETWORK}" ] || { echo "ERROR: parkio-minio network not found" >&2; exit 1; }
BUCKET="${MINIO_BUCKET:-parkio-media}"
docker run --rm --network "${NETWORK}" --entrypoint /bin/sh \
  -e MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}" -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:?}" \
  -e BUCKET="${BUCKET}" "${MC_IMAGE}" -c '
    set -eu
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
    mc mb -p "local/${BUCKET}" >/dev/null 2>&1 || true
    echo synthetic-rd01 | mc pipe "local/${BUCKET}/synthetic/rd01.txt" >/dev/null'
docker run -d --name "${OFFSITE_NAME}" --network "${NETWORK}" \
  -e MINIO_ROOT_USER=offsiteadmin -e MINIO_ROOT_PASSWORD=offsite-ci-not-prod-minio \
  "${MINIO_IMAGE:?MINIO_IMAGE required}" server /data >/dev/null
for _ in $(seq 1 30); do
  if docker run --rm --network "${NETWORK}" --entrypoint /bin/sh "${MC_IMAGE}" -c \
      "mc alias set o http://${OFFSITE_NAME}:9000 offsiteadmin offsite-ci-not-prod-minio >/dev/null && mc ready o" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
export BACKUP_PRODUCTION_MODE=1 BACKUP_OFFSITE_KIND=s3 BACKUP_MC_DEST=offsite/parkio-backups
export BACKUP_MC_URL="http://${OFFSITE_NAME}:9000" BACKUP_MC_ACCESS_KEY=offsiteadmin
export BACKUP_MC_SECRET_KEY=offsite-ci-not-prod-minio BACKUP_OFFSITE_MINIO_CONTAINER="${OFFSITE_NAME}"
export BACKUP_MC_DOCKER_NETWORK="${NETWORK}" PARKIO_ENV_FILE="${ENV_FILE}"
BACKUP_ROOT="$(mktemp -d)"
export BACKUP_DIR="${BACKUP_ROOT}"

newest_stamp() { ls -1t "${BACKUP_ROOT}" | head -1; }

# ---- 4. stamp S --------------------------------------------------------------------------
"${ROOT}/scripts/backup-hosted-beta.sh" --operator restore-drill-01-ci
STAMP_S="$(newest_stamp)"

# ---- 5. changes after S: an erasure and outbox progress -------------------------------------
erase "${ERASE_AFTER_ID}"
src "${AUTH_C}" "${AUTH_U}" "${AUTH_D}" -c "UPDATE outbox_events SET published = true"
outbox_rows 2 false | src "${USER_C}" "${USER_U}" "${USER_D}"
src "${GW_C}" "${GW_U}" "${GW_D}" -c "UPDATE waitlist_ops_notification_outbox SET status='EXPORTED', exported_at=now()"
sleep 2  # stamp names have one-second resolution

# ---- 6. stamp L --------------------------------------------------------------------------
"${ROOT}/scripts/backup-hosted-beta.sh" --operator restore-drill-01-ci
STAMP_L="$(newest_stamp)"
[ "${STAMP_S}" != "${STAMP_L}" ] || { echo "ERROR: stamps collide" >&2; exit 1; }

# ---- 7. lose the local copies; retrieve both from offsite ---------------------------------
rm -rf "${BACKUP_ROOT:?}"/*
for stamp in "${STAMP_S}" "${STAMP_L}"; do
  "${ROOT}/scripts/backup-offsite-pull.sh" --stamp "${stamp}" --dest "${OUT}/stamps/${stamp}"
done
rm -rf "${BACKUP_ROOT}"

# ---- 8. expectations (synthetic values only) ----------------------------------------------
{
  echo "STAMP_S=${STAMP_S}"
  echo "STAMP_L=${STAMP_L}"
  echo "KEEP_ID=${KEEP_ID}"
  echo "ERASE_BEFORE_ID=${ERASE_BEFORE_ID}"
  echo "ERASE_AFTER_ID=${ERASE_AFTER_ID}"
  echo "EXPECT_OUTBOX_UNPUBLISHED_AUTH=2"      # as of S, not L
  echo "EXPECT_OUTBOX_UNPUBLISHED_USER=1"
  echo "EXPECT_OUTBOX_UNPUBLISHED_PARKING=1"
  echo "EXPECT_WAITLIST_PENDING=1"
  echo "EXPECT_WAITLIST_EXPORTED=1"
  for svc in "${!FLYWAY_HEAD[@]}"; do
    echo "FLYWAY_HEAD_${svc//-/_}=${FLYWAY_HEAD[${svc}]}"
  done
} > "${OUT}/expected.env"
echo "fixture: S=${STAMP_S} L=${STAMP_L}"
