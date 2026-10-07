#!/usr/bin/env bash
#
# U02 measured disposable full-recovery drill (T3), through the isolated stage-4 path.
#
#   scripts/recovery-drill.sh --confirm-disposable --evidence DIR [--image-tag TAG] [--build]
#
# Synthetic data, disposable containers and generated credentials only: never a real backup,
# host, key or user. Every container, network and volume it creates is named parkio-rd-<run>-*
# or belongs to its isolated ticket, and only those are removed. Phases (UTC wall clock, in
# DIR/drill-report.json):
#   P0  setup       off-host evidence store (MinIO with object lock, own container and network,
#                   survives the host loss); primary stack: auth with durable recording into it,
#                   eight participants with live erasure (docker/docker-compose.recovery-drill.yml)
#   P1  seed        users A, B, C, D registered through auth's API; one synthetic row per
#                   participant and a media object each; A erased through DELETE /api/v1/account
#   P2  backup      scripts/backup-hosted-beta.sh (databases + MinIO, disposable passphrase)
#   P3  erasures    B erased; one checkpoint with the real ErasureCheckpointProducer (Gradle task
#                   recoveryDrillTool, test classpath only); C erased (pending above the
#                   checkpoint); the evidence store exported as a bundle
#   P4  host loss   the primary project removed with its volumes; the evidence store survives
#   P5  fresh env   scripts/restore-isolated-fixture.sh up --with-minio (internal network, ticket)
#   P6  negatives   missing, corrupt, gap and frontier-less evidence: restore-hosted-beta.sh exits
#                   3, nothing decrypted or applied, no trusted set and no gate
#   P7  apply       restore-hosted-beta.sh with the bundle: verified before decrypt, then applied
#   P8  replay      scripts/recovery-replay.sh up, run: attempt X with all participants (COMPLETE)
#   P9  checks      A, B, C erased in auth and absent from every participant, their media objects
#                   gone; D present everywhere; one SUCCESS restore ACK per user and participant
#   P10 expose      the gate refuses without a verdict, then opens on X's COMPLETE verdict
#   P11 isolation   attempt Y with media stopped times out (25) although X's media ACKs exist, and
#                   and retry the gate refuses Y's verdict; media restarted, Y rerun: COMPLETE
#   P12 cleanup     recovery apps, ticket targets, evidence store, primary leftovers removed and
#                   verified; the work directory (all disposable secrets) deleted
#
# RTO = end of P4 to end of P10. RPO(data) = host loss minus the backup stamp time.
# RPO(erasures) = durably recorded erasures the replay did not apply (expected 0). Not covered:
# Slack replay and the NR budget (#101/#103/#104 HOLD); no time-based coverage is claimed.
#
# --build builds the nine service images from this checkout, one at a time, tagged
# parkio/<service>:TAG (default drill-<commit>); without it the images must exist. Nothing is
# pushed. MINIO_IMAGE / MINIO_MC_IMAGE override the pinned MinIO images (local runs only).
# Exit 0 only when every check passed.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HELPER="${ROOT}/scripts/lib/recovery-drill.py"

usage() {
  sed -n '2,41p' "$0"
  exit 2
}

EVIDENCE=""
IMAGE_TAG=""
BUILD=0
CONFIRM=0
REPLAY_TIMEOUT=600
STOPPED_TIMEOUT=90

while [ "$#" -gt 0 ]; do
  case "$1" in
    --evidence) EVIDENCE="${2:-}"; shift 2 ;;
    --image-tag) IMAGE_TAG="${2:-}"; shift 2 ;;
    --build) BUILD=1; shift ;;
    --confirm-disposable) CONFIRM=1; shift ;;
    -h|--help) usage ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [ "${CONFIRM}" -ne 1 ]; then
  echo "ERROR: the drill creates and removes disposable containers; pass --confirm-disposable" >&2
  exit 2
fi
if [ -z "${EVIDENCE}" ]; then
  echo "ERROR: --evidence DIR is required" >&2
  exit 2
fi
mkdir -p "${EVIDENCE}"
if [ -n "$(ls -A "${EVIDENCE}")" ]; then
  echo "ERROR: --evidence must be an empty directory" >&2
  exit 2
fi
EVIDENCE="$(cd "${EVIDENCE}" && pwd)"
for tool in docker python3 openssl jq curl; do
  command -v "${tool}" >/dev/null 2>&1 || { echo "ERROR: ${tool} is required" >&2; exit 2; }
done
docker info >/dev/null 2>&1 || { echo "ERROR: docker daemon is not reachable" >&2; exit 2; }

RUN="$(date -u +%m%d%H%M)$(python3 -c 'import secrets; print(secrets.token_hex(2))')"
PRIMARY="parkio-rd-${RUN}-primary"
RECOVERY="parkio-rd-${RUN}-recovery"
OFFHOST="parkio-rd-${RUN}-offhost"
LABEL="parkio.recovery-drill=${RUN}"
GIT_SHA="$(git -C "${ROOT}" rev-parse HEAD)"
IMAGE_TAG="${IMAGE_TAG:-drill-${GIT_SHA:0:12}}"
export PARKIO_IMAGE_TAG="${IMAGE_TAG}" PARKIO_GIT_SHA="${GIT_SHA}"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/parkio-rd-${RUN}.XXXXXX")"
chmod 700 "${WORK}"
ENV_FILE="${WORK}/drill.env"
TRUST="${WORK}/erasure-trust.json"
STATE="${WORK}/recovery-state"
mkdir -p "${STATE}" "${EVIDENCE}/logs" "${EVIDENCE}/recovery"
LOGS="${EVIDENCE}/logs"
TICKET=""
STAMP_DIR=""
COMPLETED="false"

APPS=(auth-service user-service parking-service media-service gamification-service notification-service
  moderation-service ai-validation-service analytics-service)
PG_SERVICES=(postgres-auth postgres-gateway postgres-user postgres-parking postgres-media postgres-gamification
  postgres-notification postgres-moderation postgres-analytics postgres-ai-validation)
PARTICIPANTS=(user parking media gamification notification moderation ai-validation analytics)
USERS=(a b c d)
declare -A UID_OF EMAIL_OF MEDIA_KEY_OF

fact() { printf '%s\t%s\n' "$1" "$2" >> "${EVIDENCE}/facts.tsv"; }
check() {
  printf '%s\t%s\t%s\n' "$1" "$2" "${3:-}" >> "${EVIDENCE}/checks.tsv"
  echo "  [$2] $1${3:+ ($3)}"
}
expect() {  # expect NAME ACTUAL WANTED
  if [ "$2" = "$3" ]; then check "$1" PASS "$2"; else check "$1" FAIL "got ${2:-<empty>}, want $3"; fi
}
PHASE=""
PHASE_T0=""
phase() {
  if [ -n "${PHASE}" ]; then
    printf '%s\t%s\t%s\n' "${PHASE}" "${PHASE_T0}" "$(date -u +%s.%N)" >> "${EVIDENCE}/phases.tsv"
  fi
  PHASE="${1:-}"
  PHASE_T0="$(date -u +%s.%N)"
  [ -z "${PHASE}" ] || echo "== ${PHASE} $(date -u +%FT%TZ)"
}
env_get() { sed -n "s/^$1=//p" "${ENV_FILE}" | head -1; }
random() { python3 -c 'import secrets; print(secrets.token_hex(24))'; }

compose_primary() {
  COMPOSE_PROJECT_NAME="${PRIMARY}" docker compose --project-name "${PRIMARY}" \
    --project-directory "${ROOT}/docker" --env-file "${ENV_FILE}" \
    -f "${ROOT}/docker/docker-compose.yml" -f "${ROOT}/docker/docker-compose.apps.yml" \
    -f "${ROOT}/docker/docker-compose.images.yml" -f "${ROOT}/docker/docker-compose.recovery-drill.yml" "$@"
}
replay() {
  "${ROOT}/scripts/recovery-replay.sh" "$1" --state "${STATE}" --env-file "${ENV_FILE}" --project "${RECOVERY}" \
    "${@:2}"
}
psql_primary() {  # psql_primary SERVICE DB  (SQL on stdin)
  docker exec -i "${PRIMARY}-postgres-$1" psql -X -q -v ON_ERROR_STOP=1 -At -U "$2" -d "$2"
}
psql_restored() {  # psql_restored DB  (SQL on stdin)
  docker exec -i "${ISO_PG}" psql -X -q -v ON_ERROR_STOP=1 -At -U "$1" -d "$1"
}
db_of() {
  case "$1" in ai-validation) echo parkio_aivalidation ;; *) echo "parkio_$1" ;; esac
}

cleanup() {
  local rc=$?
  local stopped_in="${PHASE}"
  set +e
  phase "P12-cleanup"
  if [ "${rc}" -ne 0 ]; then
    # Diagnosis of a failed run: the last lines of every application container of the run.
    mkdir -p "${LOGS}/containers"
    for project in "${PRIMARY}" "${RECOVERY}"; do
      docker ps -a --filter "label=com.docker.compose.project=${project}" --format '{{.Names}}' \
        | grep -E -e '-service-' | while read -r container; do
          docker logs --tail 300 "${container}" > "${LOGS}/containers/${container}.log" 2>&1
        done
    done
  fi
  if [ -f "${STATE}/recovery-apps.env" ]; then
    replay down > "${LOGS}/cleanup-recovery-apps.log" 2>&1
  fi
  docker ps -aq --filter "label=com.docker.compose.project=${RECOVERY}" | xargs -r docker rm -f -v >/dev/null 2>&1
  docker volume ls -q --filter "label=com.docker.compose.project=${RECOVERY}" | xargs -r docker volume rm >/dev/null 2>&1
  if [ -n "${TICKET}" ] && [ -f "${TICKET}" ]; then
    "${ROOT}/scripts/restore-isolated-fixture.sh" down --ticket "${TICKET}" > "${LOGS}/cleanup-ticket.log" 2>&1 \
      || echo "ticket teardown failed; see logs/cleanup-ticket.log" >&2
  fi
  docker ps -aq --filter "label=com.docker.compose.project=${PRIMARY}" | xargs -r docker rm -f -v >/dev/null 2>&1
  docker volume ls -q --filter "label=com.docker.compose.project=${PRIMARY}" | xargs -r docker volume rm >/dev/null 2>&1
  docker rm -f -v "${OFFHOST}" >/dev/null 2>&1
  for network in "${OFFHOST}" "${PRIMARY}-backend" "${PRIMARY}-observability" "${RECOVERY}-backend" \
      "${RECOVERY}-observability"; do
    docker network rm "${network}" >/dev/null 2>&1
  done
  local left
  left="$( { docker ps -a --format '{{.Names}}' --filter "name=parkio-rd-${RUN}";
             docker network ls --format '{{.Name}}' --filter "name=parkio-rd-${RUN}";
             docker volume ls --format '{{.Name}}' --filter "name=parkio-rd-${RUN}";
             [ -n "${ISO_PROJECT:-}" ] && docker ps -a --format '{{.Names}}' --filter "label=parkio.isolated.project=${ISO_PROJECT}";
             [ -n "${ISO_PROJECT:-}" ] && docker volume ls --format '{{.Name}}' --filter "label=parkio.isolated.project=${ISO_PROJECT}";
             [ -n "${ISO_PROJECT:-}" ] && docker network ls --format '{{.Name}}' --filter "label=parkio.isolated.project=${ISO_PROJECT}";
           } | sort -u | tr '\n' ' ')"
  if [ -z "${left// /}" ]; then
    check "cleanup: no container, network or volume of the run remains" PASS
  else
    check "cleanup: no container, network or volume of the run remains" FAIL "${left}"
  fi
  rm -rf "${WORK:?}"
  if [ ! -e "${WORK}" ]; then check "cleanup: work dir with every disposable secret deleted" PASS; fi
  phase ""
  fact completed "${COMPLETED}"
  [ "${rc}" -eq 0 ] || fact abortedIn "${stopped_in} (exit ${rc})"
  python3 "${HELPER}" report --evidence "${EVIDENCE}"
  local report_rc=$?
  if [ "${rc}" -ne 0 ]; then exit "${rc}"; fi
  exit "${report_rc}"
}
trap cleanup EXIT

fact runId "${RUN}"
fact gitSha "${GIT_SHA}"
fact imageTag "${IMAGE_TAG}"
fact dockerServer "$(docker version --format '{{.Server.Version}} {{.Server.Os}}/{{.Server.Arch}}')"
fact host "$(uname -sr) cpus=$(nproc) memGiB=$(awk '/MemTotal/ {printf "%.1f", $2/1048576}' /proc/meminfo)"

# ---------------------------------------------------------------------------------------------
phase "P0-setup"
# One PostgreSQL client for dump and restore: the isolated restore runs psql inside the target,
# which must carry PostGIS for parking (POSTGIS_IMAGE, PostgreSQL 16.4). The other databases run
# the same 16.4 release, so their pg_dump writes nothing that client cannot read (16.10 and later
# emit \restrict).
POSTGIS_IMAGE="$(sed -n 's/^POSTGIS_IMAGE=//p' "${ROOT}/docker/.env.example")"
POSTGRES_IMAGE="postgres:16.4-alpine"
python3 "${HELPER}" env-file --example "${ROOT}/docker/.env.example" --out "${ENV_FILE}" --jwt-key \
  --set "POSTGRES_IMAGE=${POSTGRES_IMAGE}" \
  ${MINIO_IMAGE:+--set "MINIO_IMAGE=${MINIO_IMAGE}"} \
  ${MINIO_MC_IMAGE:+--set "MINIO_MC_IMAGE=${MINIO_MC_IMAGE}"} \
  --set "PARKIO_TRACING_ENABLED=false" --set "PARKIO_MEDIA_SCANNER_ENABLED=false" \
  --set "PARKIO_ALERT_SLACK_WEBHOOK_URL=" --set "PARKIO_ALERT_WEBHOOK_URL=" \
  --set "BACKUP_DIR=${WORK}/backups" --set "BACKUP_PRODUCTION_MODE=0" \
  --set "BACKUP_ENCRYPT_PASSPHRASE=$(random)"
MINIO_IMAGE="$(env_get MINIO_IMAGE)"
MC_IMAGE="$(env_get MINIO_MC_IMAGE)"
MEDIA_BUCKET="$(env_get MINIO_BUCKET)"
fact postgresImage "${POSTGRES_IMAGE} (parking and the isolated target: ${POSTGIS_IMAGE})"
fact minioImage "${MINIO_IMAGE}"
fact mcImage "${MC_IMAGE}"
fact slackWebhookConfigured "no"

if [ "${BUILD}" -eq 1 ]; then
  # The overlay needs every DRILL_* value to render; a build reads none of them.
  export DRILL_OFFHOST_NETWORK=unused DRILL_TRUST_FILE="${TRUST}" DRILL_OFFHOST_MINIO=unused \
    DRILL_EVIDENCE_BUCKET=unused DRILL_OFFHOST_ACCESS_KEY=unused DRILL_OFFHOST_SECRET_KEY=unused \
    DRILL_PRODUCER_KEY_ID=unused
  for service in "${APPS[@]}"; do
    echo "  building ${service}"
    compose_primary build "${service}" > "${LOGS}/build-${service}.log" 2>&1
  done
fi
for service in "${APPS[@]}"; do
  docker image inspect "parkio/${service}:${IMAGE_TAG}" >/dev/null 2>&1 \
    || { echo "ERROR: image parkio/${service}:${IMAGE_TAG} is missing; run with --build" >&2; exit 2; }
  fact "image.${service}" "$(docker image inspect --format '{{.Id}}' "parkio/${service}:${IMAGE_TAG}")"
done
phase "P0-setup-stores"

export DRILL_OFFHOST_NETWORK="${OFFHOST}" DRILL_OFFHOST_MINIO="${OFFHOST}"
export DRILL_EVIDENCE_BUCKET="parkio-erasure-evidence" DRILL_OFFHOST_ACCESS_KEY="drill-offhost"
DRILL_OFFHOST_SECRET_KEY="$(random)"
export DRILL_OFFHOST_SECRET_KEY DRILL_PRODUCER_KEY_ID="drill-${RUN}-k1" DRILL_TRUST_FILE="${TRUST}"
docker network create --label "${LABEL}" "${OFFHOST}" >/dev/null
MINIO_ROOT_USER="${DRILL_OFFHOST_ACCESS_KEY}" MINIO_ROOT_PASSWORD="${DRILL_OFFHOST_SECRET_KEY}" \
  docker run -d --name "${OFFHOST}" --network "${OFFHOST}" --network-alias "${OFFHOST}" --label "${LABEL}" \
  -e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD -p 127.0.0.1::9000 "${MINIO_IMAGE}" server /data >/dev/null
for _ in $(seq 1 60); do
  docker exec "${OFFHOST}" curl -fsS http://localhost:9000/minio/health/ready >/dev/null 2>&1 && break
  sleep 1
done
MC_HOST_off="http://${DRILL_OFFHOST_ACCESS_KEY}:${DRILL_OFFHOST_SECRET_KEY}@${OFFHOST}:9000" \
  docker run --rm --network "${OFFHOST}" -e MC_HOST_off -e "BUCKET=${DRILL_EVIDENCE_BUCKET}" \
  --entrypoint /bin/sh "${MC_IMAGE}" -c 'mc mb --with-lock "off/${BUCKET}"' > "${LOGS}/offhost-bucket.log" 2>&1
OFFHOST_PORT="$(docker port "${OFFHOST}" 9000/tcp | head -1 | sed 's/.*://')"

compose_primary up -d --wait --no-build "${PG_SERVICES[@]}" redis kafka minio > "${LOGS}/primary-infra.log" 2>&1
compose_primary up --no-build minio-setup >> "${LOGS}/primary-infra.log" 2>&1
PRIMARY_IDENTITY="$(echo "SELECT 'postgresql:' || system_identifier || ':' || current_database() FROM pg_control_system();" \
  | psql_primary auth parkio_auth)"
fact primaryAuthIdentity "${PRIMARY_IDENTITY}"
python3 "${HELPER}" trust --identity "${PRIMARY_IDENTITY}" --key-id "${DRILL_PRODUCER_KEY_ID}" --out "${TRUST}"
# The auth container (uid 10001) reads the bind-mounted file; the work dir itself stays 700.
chmod 644 "${TRUST}"
compose_primary up -d --wait --no-build "${APPS[@]}" > "${LOGS}/primary-apps.log" 2>&1
AUTH_CONTAINER="$(compose_primary ps -q auth-service)"
AUTH_PG_PORT="$(docker port "${PRIMARY}-postgres-auth" 5432/tcp | head -1 | sed 's/.*://')"

# ---------------------------------------------------------------------------------------------
phase "P1-seed"
# auth's password policy wants upper case, lower case and a digit.
DRILL_USER_PASSWORD="Drill-$(random | cut -c1-32)-7"
DRILL_GATEWAY_SECRET="$(env_get PARKIO_GATEWAY_INTERNAL_SECRET)"
export DRILL_USER_PASSWORD DRILL_GATEWAY_SECRET
for user in "${USERS[@]}"; do
  email="drill-${RUN}-${user}@recovery-drill.parkio.invalid"
  EMAIL_OF[${user}]="${email}"
  python3 "${HELPER}" register --container "${AUTH_CONTAINER}" --email "${email}"
  UID_OF[${user}]="$(echo "SELECT id FROM auth_users WHERE email = '${email}';" | psql_primary auth parkio_auth)"
  # Production exposes no verification bypass; the drill verifies its synthetic users in the
  # database, as scripts/seed-real-e2e.sh does.
  echo "UPDATE auth_users SET status = 'ACTIVE', email_verified = TRUE, email_verified_at = now()
        WHERE id = '${UID_OF[${user}]}';" | psql_primary auth parkio_auth
  fact "user.${user}" "${UID_OF[${user}]}"
done
for _ in $(seq 1 90); do
  profiles="$(echo "SELECT count(*) FROM user_profiles WHERE auth_user_id IN
    ('${UID_OF[a]}', '${UID_OF[b]}', '${UID_OF[c]}', '${UID_OF[d]}');" | psql_primary user parkio_user)"
  [ "${profiles}" = "4" ] && break
  sleep 2
done
expect "seed: user-service created a profile for each user from UserRegistered" "${profiles}" "4"

for user in "${USERS[@]}"; do
  uid="${UID_OF[${user}]}"
  psql_primary parking parkio_parking <<SQL
INSERT INTO parking_spot_search_logs (id, searcher_user_id, latitude, longitude, radius_meters, result_count)
VALUES (gen_random_uuid(), '${uid}', 41.0, 29.0, 500, 0);
SQL
  echo "INSERT INTO trust_scores (user_id) VALUES ('${uid}');" | psql_primary gamification parkio_gamification
  echo "INSERT INTO notification_preferences (user_id) VALUES ('${uid}') ON CONFLICT (user_id) DO NOTHING;" \
    | psql_primary notification parkio_notification
  psql_primary analytics parkio_analytics <<SQL
INSERT INTO analytics_events (id, source_event_id, metric_type, user_id, occurred_at)
VALUES (gen_random_uuid(), gen_random_uuid(), 'DRILL_SYNTHETIC', '${uid}', now());
SQL
  psql_primary moderation parkio_moderation <<SQL
WITH opened AS (
  INSERT INTO moderation_cases (id, target_type, target_id, reason, severity, status, owner_user_id)
  VALUES (gen_random_uuid(), 'USER', '${uid}', 'SPAM', 'LOW', 'OPEN', '${uid}') RETURNING id)
INSERT INTO user_violations (id, user_id, case_id, reason, severity, action)
SELECT gen_random_uuid(), '${uid}', id, 'SPAM', 'LOW', 'WARNING' FROM opened;
SQL
  psql_primary ai-validation parkio_aivalidation <<SQL
INSERT INTO ai_validation_results (id, media_id, requested_by_user_id, status, empty_space_confidence,
  legal_risk_score, image_quality_score, ai_confidence)
VALUES (gen_random_uuid(), gen_random_uuid(), '${uid}', 'COMPLETED', 50, 10, 80, 70);
SQL
  key="media/${uid}/$(python3 -c 'import uuid; print(uuid.uuid4())').png"
  MEDIA_KEY_OF[${user}]="${key}"
  content="drill-${RUN}-${user}"
  MC_HOST_src="http://$(env_get MINIO_ROOT_USER):$(env_get MINIO_ROOT_PASSWORD)@minio:9000" \
    docker run --rm -i --network "${PRIMARY}-backend" -e MC_HOST_src -e "TARGET=src/${MEDIA_BUCKET}/${key}" \
    --entrypoint /bin/sh "${MC_IMAGE}" -c 'mc pipe "${TARGET}"' <<< "${content}" > /dev/null
  size="$(printf '%s\n' "${content}" | wc -c | tr -d ' ')"
  sha="$(printf '%s\n' "${content}" | sha256sum | cut -d' ' -f1)"
  psql_primary media parkio_media <<SQL
INSERT INTO media_files (id, owner_user_id, bucket_name, object_key, content_type, file_size, checksum, status)
VALUES (gen_random_uuid(), '${uid}', '${MEDIA_BUCKET}', '${key}', 'image/png', ${size}, '${sha}', 'READY');
SQL
done
check "seed: one synthetic row per participant and one media object per user" PASS "users=4"

erase() {  # erase USER: DELETE /api/v1/account, then wait until COMPLETE and DURABLY_RECORDED
  local user="$1" uid="${UID_OF[$1]}" state=""
  python3 "${HELPER}" login --container "${AUTH_CONTAINER}" --email "${EMAIL_OF[${user}]}" \
    --token-file "${WORK}/token"
  python3 "${HELPER}" delete --container "${AUTH_CONTAINER}" --token-file "${WORK}/token" > /dev/null
  rm -f "${WORK}/token"
  for _ in $(seq 1 120); do
    state="$(echo "SELECT status || '/' || COALESCE(durable_recording_status, '-') FROM erasure_requests
      WHERE auth_user_id = '${uid}' ORDER BY requested_at DESC LIMIT 1;" | psql_primary auth parkio_auth)"
    [ "${state}" = "COMPLETE/DURABLY_RECORDED" ] && break
    sleep 2
  done
  expect "erasure of ${user^^}: live erasure COMPLETE and durably recorded off-host" "${state}" \
    "COMPLETE/DURABLY_RECORDED"
}
erase a

# ---------------------------------------------------------------------------------------------
phase "P2-backup"
BACKUP_PASSPHRASE_SET="$(env_get BACKUP_ENCRYPT_PASSPHRASE | wc -c)"
[ "${BACKUP_PASSPHRASE_SET}" -gt 1 ]
PARKIO_ENV_FILE="${ENV_FILE}" PARKIO_PG_CONTAINER_PREFIX="${PRIMARY}" PARKIO_MINIO_CONTAINER="${PRIMARY}-minio" \
  PARKIO_BACKUP_ARTIFACT_DIR="${WORK}/backup-artifacts" PARKIO_PROMETHEUS_TEXTFILE_DIR="${WORK}/textfile" \
  "${ROOT}/scripts/backup-hosted-beta.sh" --operator recovery-drill > "${LOGS}/backup.log" 2>&1
STAMP_DIR="$(find "${WORK}/backups" -mindepth 1 -maxdepth 1 -type d | head -1)"
expect "backup: the stamp is COMPLETE" "$([ -f "${STAMP_DIR}/COMPLETE" ] && echo yes)" "yes"
STAMP="$(basename "${STAMP_DIR}")"
fact backupStamp "${STAMP}"
fact backupManifestTimestamp "$(jq -r '.timestamp' "${STAMP_DIR}/backup-manifest.json")"
cp "${STAMP_DIR}/backup-manifest.json" "${EVIDENCE}/backup-manifest.json"
expect "backup: the erasure ledger holds A's tombstone" \
  "$(jq -r --arg id "${UID_OF[a]}" '[.[] | select(.authUserId == $id)] | length' "${STAMP_DIR}/erasure-tombstones.json")" "1"

# ---------------------------------------------------------------------------------------------
phase "P3-erasures-after-backup"
erase b
drill_tool() {
  DRILL_STORE_ENDPOINT="http://127.0.0.1:${OFFHOST_PORT}" DRILL_STORE_BUCKET="${DRILL_EVIDENCE_BUCKET}" \
    DRILL_STORE_ACCESS_KEY="${DRILL_OFFHOST_ACCESS_KEY}" DRILL_STORE_SECRET_KEY="${DRILL_OFFHOST_SECRET_KEY}" \
    DRILL_STORE_RETENTION_MODE=GOVERNANCE DRILL_STORE_RETENTION=PT2H \
    DRILL_JDBC_URL="jdbc:postgresql://127.0.0.1:${AUTH_PG_PORT}/parkio_auth" DRILL_JDBC_USER=parkio_auth \
    DRILL_JDBC_PASSWORD="$(env_get POSTGRES_AUTH_PASSWORD)" \
    "${ROOT}/gradlew" -p "${ROOT}" --console=plain -q :services:auth-service:recoveryDrillTool --args="$*"
}
drill_tool checkpoint > "${LOGS}/checkpoint.log" 2>&1
check "checkpoint published by the real ErasureCheckpointProducer" PASS "$(tail -1 "${LOGS}/checkpoint.log")"
erase c
drill_tool export-bundle "${WORK}/bundle.json" > "${LOGS}/export-bundle.log" 2>&1
cp "${WORK}/bundle.json" "${EVIDENCE}/erasure-evidence-bundle.json"
check "evidence store exported as a bundle" PASS "$(tail -1 "${LOGS}/export-bundle.log")"

# ---------------------------------------------------------------------------------------------
phase "P4-host-loss"
compose_primary down -v --remove-orphans --timeout 20 > "${LOGS}/host-loss.log" 2>&1
expect "host loss: no primary container or volume remains" \
  "$( { docker ps -aq --filter "label=com.docker.compose.project=${PRIMARY}";
        docker volume ls -q --filter "label=com.docker.compose.project=${PRIMARY}"; } | wc -l | tr -d ' ')" "0"
expect "host loss: the off-host evidence store survives" \
  "$(docker inspect -f '{{.State.Running}}' "${OFFHOST}")" "true"

# ---------------------------------------------------------------------------------------------
phase "P5-fresh-environment"
TICKET="$(TMPDIR="${WORK}" POSTGRES_IMAGE="${POSTGIS_IMAGE}" MINIO_IMAGE="${MINIO_IMAGE}" \
  "${ROOT}/scripts/restore-isolated-fixture.sh" up --stamp "${STAMP_DIR}" --with-minio 2> "${LOGS}/ticket.log")"
ISO_PROJECT="$(jq -r '.project' "${TICKET}")"
ISO_PG="$(jq -r '.postgres.auth.containerName' "${TICKET}")"
TARGET_IDENTITY="$(jq -r '.postgres.auth.databaseIdentity' "${TICKET}")"
ISO_NETWORK="$(jq -r '.network.name' "${TICKET}")"
ISO_BUCKET="$(jq -r '.minio.bucket' "${TICKET}")"
fact isolatedProject "${ISO_PROJECT}"
fact targetIdentity "${TARGET_IDENTITY}"
expect "fresh environment: the ticket pins the source bucket from the manifest" \
  "$(jq -r '.minio.sourceBucket' "${TICKET}")" "${MEDIA_BUCKET}"
expect "fresh environment: the isolated network has no route out" \
  "$(docker network inspect -f '{{.Internal}}' "${ISO_NETWORK}")" "true"

iso_mc() {  # mc against the ticket's MinIO, from a container on its internal network
  local resolved
  resolved="$(python3 "${ROOT}/scripts/lib/restore-isolated-inspect.py" --ticket "${TICKET}" --stamp "${STAMP_DIR}" \
    --resolve-minio)"
  MC_HOST_iso="$(python3 -c 'import json, sys; m = json.load(sys.stdin); print("http://%s:%s@%s:9000" % (m["user"], m["password"], m["aliasHost"]))' <<< "${resolved}")" \
    docker run --rm --network "${ISO_NETWORK}" -e MC_HOST_iso --entrypoint mc "${MC_IMAGE}" "$@"
}
untouched() {  # what the isolated targets hold: tables of auth and parking, and MinIO buckets
  local tables
  tables="$(echo "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public';" | psql_restored parkio_auth)"
  tables="${tables}+$(echo "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public';" \
    | psql_restored parkio_parking)"
  echo "tables=${tables} buckets=$( { iso_mc ls iso/ 2>/dev/null || true; } | wc -l | tr -d ' ')"
}
BASELINE="$(untouched)"
fact isolatedTargetsBeforeRestore "${BASELINE}"

# ---------------------------------------------------------------------------------------------
phase "P6-negative-variants"
python3 "${HELPER}" variants --bundle "${WORK}/bundle.json" --out "${WORK}/variants" > /dev/null
restore() {  # restore BUNDLE RECOVERY_DIR ATTEMPT  -> exit code of restore-hosted-beta.sh
  local rc=0
  PARKIO_ENV_FILE="${ENV_FILE}" "${ROOT}/scripts/restore-hosted-beta.sh" --manifest "${STAMP_DIR}/backup-manifest.json" \
    --yes --recovery-cutoff "${BACKUP_CUTOFF}" --isolated-fixture --isolated-ticket "${TICKET}" \
    --erasure-evidence "$1" --erasure-trust "${TRUST}" --recovery-attempt "$3" --recovery-dir "$2" \
    < /dev/null || rc=$?
  return "${rc}"
}
# The time-based ledger check is unchanged: the cutoff is the backup's own time, which its
# snapshot reaches. Erasures after the backup are covered by the evidence bundle, by sequence only.
BACKUP_CUTOFF="$(python3 -c 'import sys; from datetime import datetime; print(datetime.strptime(sys.argv[1], "%Y-%m-%dT%H-%M-%SZ").strftime("%Y-%m-%dT%H:%M:%SZ"))' "${STAMP}")"
for variant in missing corrupt gap frontier-missing; do
  directory="${WORK}/recovery-${variant}"
  mkdir -p "${directory}"
  bundle="${WORK}/variants/${variant}.json"
  [ "${variant}" != "missing" ] || bundle="${WORK}/variants/does-not-exist.json"
  rc=0
  restore "${bundle}" "${directory}" "$(python3 -c 'import uuid; print(uuid.uuid4())')" \
    > "${LOGS}/negative-${variant}.log" 2>&1 || rc=$?
  state="rc=${rc} $(untouched) set=$([ -e "${directory}/trusted-erasure-set.json" ] && echo yes || echo no) gate=$([ -e "${directory}/expose-gate.json" ] && echo yes || echo no)"
  expect "negative ${variant}: BLOCKED (exit 3) with nothing decrypted or applied" "${state}" \
    "rc=3 ${BASELINE} set=no gate=no"
done

# ---------------------------------------------------------------------------------------------
phase "P7-apply"
ATTEMPT_X="$(python3 -c 'import uuid; print(uuid.uuid4())')"
RECOVERY_X="${WORK}/recovery-x"
mkdir -p "${RECOVERY_X}"
# The parking dump creates the PostGIS extension, which only a superuser may do. The isolated
# target's parking role gets it for the restore alone, as the primary's database owner has it.
echo 'ALTER ROLE parkio_parking SUPERUSER;' | docker exec -i "${ISO_PG}" psql -X -q -v ON_ERROR_STOP=1 -U postgres -d postgres
restore "${WORK}/bundle.json" "${RECOVERY_X}" "${ATTEMPT_X}" > "${LOGS}/restore.log" 2>&1
echo 'ALTER ROLE parkio_parking NOSUPERUSER;' | docker exec -i "${ISO_PG}" psql -X -q -v ON_ERROR_STOP=1 -U postgres -d postgres
expect "apply: no service role of the isolated target is a superuser after the restore" \
  "$(echo "SELECT count(*) FROM pg_roles WHERE rolsuper AND rolname LIKE 'parkio\_%';" \
    | docker exec -i "${ISO_PG}" psql -X -q -At -U postgres -d postgres)" "0"
fact attemptX "${ATTEMPT_X}"
fact coverage "$(jq -r '.coverage.statement' "${RECOVERY_X}/trusted-erasure-set.json")"
expect "apply: the expose gate is CLOSED after the restore" "$(jq -r '.state' "${RECOVERY_X}/expose-gate.json")" "CLOSED"
expect "apply: the trusted set holds A, B and C" \
  "$(jq -r '[.erasureSet.entries[].authUserId] | sort | join(",")' "${RECOVERY_X}/trusted-erasure-set.json")" \
  "$(printf '%s\n' "${UID_OF[a]}" "${UID_OF[b]}" "${UID_OF[c]}" | sort | paste -sd, -)"
expect "apply: B and C are back in the restored copy before the replay" \
  "$(echo "SELECT count(*) FROM auth_users WHERE status = 'ACTIVE' AND id IN ('${UID_OF[b]}', '${UID_OF[c]}');" \
    | psql_restored parkio_auth)" "2"

# ---------------------------------------------------------------------------------------------
phase "P8-replay"
replay up --ticket "${TICKET}" --stamp "${STAMP_DIR}" > "${LOGS}/recovery-apps-up.log" 2>&1
expect "replay: no recovery container publishes a port" \
  "$(docker ps --filter "label=com.docker.compose.project=${RECOVERY}" --format '{{.Ports}}' | grep -c -- '->' || true)" "0"
rc=0
replay run --ticket "${TICKET}" --stamp "${STAMP_DIR}" --recovery-dir "${RECOVERY_X}" --trust "${TRUST}" \
  --timeout-seconds "${REPLAY_TIMEOUT}" > "${LOGS}/replay-x.log" 2>&1 || rc=$?
VERDICT_X="${RECOVERY_X}/replay-verdict-${ATTEMPT_X}.json"
expect "replay X: COMPLETE (exit 0)" "rc=${rc} $(jq -r '.status' "${VERDICT_X}")" "rc=0 COMPLETE"

# ---------------------------------------------------------------------------------------------
phase "P9-checks"
for user in a b c d; do
  uid="${UID_OF[${user}]}"
  if [ "${user}" = "d" ]; then want_auth="ACTIVE/t"; want_rows=1; want_object=1; else want_auth="ERASED/f"; want_rows=0; want_object=0; fi
  expect "auth: ${user^^}" "$(echo "SELECT status || '/' || (email = '${EMAIL_OF[${user}]}')::text::char FROM auth_users WHERE id = '${uid}';" \
    | psql_restored parkio_auth)" "${want_auth}"
  rows=""
  for participant in "${PARTICIPANTS[@]}"; do
    case "${participant}" in
      user) sql="SELECT count(*) FROM user_profiles WHERE auth_user_id = '${uid}'" ;;
      parking) sql="SELECT count(*) FROM parking_spot_search_logs WHERE searcher_user_id = '${uid}'" ;;
      media) sql="SELECT count(*) FROM media_files WHERE owner_user_id = '${uid}' AND status <> 'DELETED'" ;;
      gamification) sql="SELECT count(*) FROM trust_scores WHERE user_id = '${uid}'" ;;
      notification) sql="SELECT count(*) FROM notification_preferences WHERE user_id = '${uid}'" ;;
      moderation) sql="SELECT (SELECT count(*) FROM user_violations WHERE user_id = '${uid}')
                     + (SELECT count(*) FROM moderation_cases WHERE target_id = '${uid}' OR owner_user_id = '${uid}')" ;;
      ai-validation) sql="SELECT count(*) FROM ai_validation_results WHERE requested_by_user_id = '${uid}'" ;;
      analytics) sql="SELECT count(*) FROM analytics_events WHERE user_id = '${uid}'" ;;
    esac
    count="$(echo "${sql};" | psql_restored "$(db_of "${participant}")")"
    [ "${participant}" != "moderation" ] || count=$(( count > 0 ? 1 : 0 ))
    rows="${rows}${participant}=${count} "
  done
  want=""
  for participant in "${PARTICIPANTS[@]}"; do want="${want}${participant}=${want_rows} "; done
  expect "participants: ${user^^}" "${rows% }" "${want% }"
  objects="$( { iso_mc ls --versions "iso/${ISO_BUCKET}/${MEDIA_KEY_OF[${user}]}" 2>/dev/null || true; } \
    | grep -c . || true)"
  expect "media object versions in the restored bucket: ${user^^}" "${objects}" "${want_object}"
done
acks="$(echo "SELECT service_name || '=' || count(*) FROM erasure_restore_acks
  WHERE recovery_attempt_id = '${ATTEMPT_X}' AND status = 'SUCCESS' GROUP BY service_name ORDER BY service_name;" \
  | psql_restored parkio_auth | paste -sd' ' -)"
expect "restore ACKs of X: one SUCCESS per user from auth and each participant" "${acks}" \
  "ai-validation=3 analytics=3 auth=3 gamification=3 media=3 moderation=3 notification=3 parking=3 user=3"
missing_x="$(jq '[.participants[], .auth] | map(.missing + .failed) | add' "${VERDICT_X}")"
fact erasuresNotReplayed "${missing_x}"

# ---------------------------------------------------------------------------------------------
phase "P10-expose"
gate() { python3 "${ROOT}/scripts/lib/recovery-expose-gate.py" "$@"; }
rc=0
gate open --recovery-dir "${RECOVERY_X}" --verdict "${RECOVERY_X}/no-verdict.json" --operator recovery-drill \
  > "${LOGS}/gate-no-verdict.log" 2>&1 || rc=$?
expect "expose: refused without a verdict, gate stays CLOSED" "rc=${rc} $(jq -r '.state' "${RECOVERY_X}/expose-gate.json")" "rc=3 CLOSED"
gate open --recovery-dir "${RECOVERY_X}" --verdict "${VERDICT_X}" --operator recovery-drill > "${LOGS}/gate-open.log" 2>&1
expect "expose: OPEN on X's COMPLETE verdict" "$(jq -r '.state' "${RECOVERY_X}/expose-gate.json")" "OPEN"
cp "${RECOVERY_X}/trusted-erasure-set.json" "${EVIDENCE}/recovery/trusted-erasure-set-x.json"
cp "${VERDICT_X}" "${EVIDENCE}/recovery/replay-verdict-x.json"
cp "${RECOVERY_X}/expose-gate.json" "${EVIDENCE}/recovery/expose-gate-x.json"

# ---------------------------------------------------------------------------------------------
phase "P11-isolation-and-retry"
ATTEMPT_Y="$(python3 -c 'import uuid; print(uuid.uuid4())')"
RECOVERY_Y="${WORK}/recovery-y"
mkdir -p "${RECOVERY_Y}"
fact attemptY "${ATTEMPT_Y}"
# The same verifier the restore runs, for a second attempt on the same restored copy.
python3 "${ROOT}/scripts/lib/recovery-evidence.py" verify --bundle "${WORK}/bundle.json" --trust "${TRUST}" \
  --attempt "${ATTEMPT_Y}" --dataset "${STAMP}" --target-identity "${TARGET_IDENTITY}" \
  --out "${RECOVERY_Y}/trusted-erasure-set.json" > "${LOGS}/verify-y.log" 2>&1
compose_recovery() {
  set -a
  # shellcheck disable=SC1091
  . "${STATE}/recovery-apps.env"
  set +a
  RECOVERY_DIR="${STATE}" RECOVERY_TRUST_FILE="${STATE}/recovery-apps.env" COMPOSE_PROJECT_NAME="${RECOVERY}" \
    docker compose --project-name "${RECOVERY}" --project-directory "${ROOT}/docker" --env-file "${ENV_FILE}" \
    -f "${ROOT}/docker/docker-compose.yml" -f "${ROOT}/docker/docker-compose.apps.yml" \
    -f "${ROOT}/docker/docker-compose.images.yml" -f "${ROOT}/docker/docker-compose.recovery-drill-isolated.yml" "$@"
}
compose_recovery stop media-service > "${LOGS}/media-stop.log" 2>&1
rc=0
replay run --ticket "${TICKET}" --stamp "${STAMP_DIR}" --recovery-dir "${RECOVERY_Y}" --trust "${TRUST}" \
  --timeout-seconds "${STOPPED_TIMEOUT}" > "${LOGS}/replay-y-media-stopped.log" 2>&1 || rc=$?
VERDICT_Y="${RECOVERY_Y}/replay-verdict-${ATTEMPT_Y}.json"
expect "isolation: Y with media stopped TIMEOUT (25), media missing for every user" \
  "rc=${rc} $(jq -r '.status + " media.missing=" + (.participants.media.missing | tostring)' "${VERDICT_Y}")" \
  "rc=25 TIMEOUT media.missing=3"
expect "isolation: X's media ACKs exist but do not count for Y" \
  "$(echo "SELECT count(*) FROM erasure_restore_acks WHERE service_name = 'media' AND status = 'SUCCESS'
    AND recovery_attempt_id = '${ATTEMPT_X}';" | psql_restored parkio_auth)" "3"
cp "${VERDICT_Y}" "${EVIDENCE}/recovery/replay-verdict-y-timeout.json"
rc=0
gate open --recovery-dir "${RECOVERY_X}" --verdict "${VERDICT_Y}" --operator recovery-drill \
  > "${LOGS}/gate-other-attempt.log" 2>&1 || rc=$?
expect "expose: X's gate refuses Y's verdict" "rc=${rc}" "rc=3"
compose_recovery start media-service > "${LOGS}/media-start.log" 2>&1
for _ in $(seq 1 90); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$(compose_recovery ps -q media-service)")" = "healthy" ] && break
  sleep 2
done
rc=0
replay run --ticket "${TICKET}" --stamp "${STAMP_DIR}" --recovery-dir "${RECOVERY_Y}" --trust "${TRUST}" \
  --timeout-seconds "${REPLAY_TIMEOUT}" > "${LOGS}/replay-y-retry.log" 2>&1 || rc=$?
expect "retry: Y rerun after media restarted is COMPLETE (exit 0)" "rc=${rc} $(jq -r '.status' "${VERDICT_Y}")" "rc=0 COMPLETE"
cp "${VERDICT_Y}" "${EVIDENCE}/recovery/replay-verdict-y-retry.json"
COMPLETED="true"
