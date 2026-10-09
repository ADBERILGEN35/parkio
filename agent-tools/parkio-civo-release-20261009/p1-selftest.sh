#!/usr/bin/env bash
# Local self-test of p1-release-facts.py (P1) on a synthetic host-like stack: four PostGIS databases
# (parking with a V17-style back-fill in UTC and one Istanbul-signature row, media with a duplicate,
# moderation with sentinel rows, gamification with dead-lettered outbox rows), a real cp-kafka 7.7.1
# broker (KRaft, the production heap setting inherited by exec'd tools) with parkio.dlt.user and a
# parkio.user group, a fake amtool, three source containers with json-file logging, live and release git
# checkouts, an env file holding secrets that must never be printed, and a local marketing site.
# Pass = every expected fact appears and no secret, URL or e-mail does. Cleans up after itself.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/p1-release-facts.py"
P=p1probe; NET=p1probe-net; W="$(mktemp -d)"; PORT=18731; SRV=""
NAMES=(p1probe-postgres-parking p1probe-postgres-media p1probe-postgres-moderation p1probe-postgres-gamification
       p1probe-kafka p1probe-alertmanager p1probe-gateway p1probe-auth p1probe-parking)
cleanup() { [ -n "$SRV" ] && kill "$SRV" 2>/dev/null || true
            docker rm -f "${NAMES[@]}" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true
            rm -rf "$W"; }
trap cleanup EXIT
for n in "${NAMES[@]}"; do [ -z "$(docker ps -aq --filter "name=^$n$")" ] || { echo "container $n exists"; exit 2; }; done
[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$P")" ] || { echo "project $P in use"; exit 2; }
docker network create "$NET" >/dev/null
SECRET_HOOK='https://alerts.invalid/hook/Synthetic-Secret-Token-77'
PGPW='Pw-Synthetic-91'
lbl() { echo --label "com.docker.compose.project=$P" --label "com.docker.compose.service=$1"; }
# --- live and release checkouts
L="$W/live"; R="$W/release"; mkdir -p "$L/docker/alertmanager" "$L/scripts/lib" "$L/docker/prometheus/textfile" \
  "$L/backups/2026-10-07T03-30-01Z" "$L/backups/2026-10-08T03-30-01Z"
echo 'echo render v1' > "$L/docker/alertmanager/render-config.sh"
for f in scripts/run-production-backup.sh scripts/backup-hosted-beta.sh scripts/lib/backup-common.sh \
         scripts/backup-databases.sh scripts/backup-minio.sh scripts/lib/backup-metrics.py; do echo "# $f" > "$L/$f"; done
git -C "$L" init -q && git -C "$L" add -A && git -C "$L" -c user.name=t -c user.email=t@t commit -qm live
echo 'echo render v1 plus local change' > "$L/docker/alertmanager/render-config.sh"
touch "$L/backups/2026-10-08T03-30-01Z/COMPLETE"
NOW="$(date +%s)"
cat > "$L/docker/prometheus/textfile/parkio_backup.prom" <<PROM
parkio_backup_last_success{scope="azure-hosted-beta"} 1
parkio_backup_offsite_last_success{scope="azure-hosted-beta"} 1
parkio_backup_production_mode{scope="azure-hosted-beta"} 1
parkio_backup_encryption_enabled{scope="azure-hosted-beta"} 1
parkio_backup_last_timestamp_seconds{scope="azure-hosted-beta"} $((NOW - 7200))
parkio_backup_last_success{scope="other"} 0
PROM
ENV="$L/docker/.env.azure-hosted-beta"
cat > "$ENV" <<ENVF
PARKIO_ACCOUNT_ERASURE_ENABLED=false
PARKIO_WAITLIST_CONSENT_REQUIRED=true
PARKIO_SLACK_BIZ_ENABLED=true
PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED=Not-A-Boolean-Value
PARKIO_ALERT_SLACK_WEBHOOK_URL=$SECRET_HOOK
PARKIO_ALERT_HEARTBEAT_URL=
PARKIO_ACME_EMAIL=ops-person@example.org
JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0 -Duser.timezone=UTC"
ENVF
mkdir -p "$R/docker/alertmanager"
echo 'echo render release' > "$R/docker/alertmanager/render-config.sh"
printf 'docker/docker-compose.yml\ndocker/docker-compose.azure-hosted-beta.yml\n' > "$R/docker/compose.production.files"
cat > "$R/docker/docker-compose.yml" <<'Y'
name: p1probe
x-logging: &default-logging
  driver: json-file
  options: {max-size: "10m", max-file: "5"}
services:
  gateway-service: {image: "busybox:latest", logging: *default-logging}
  auth-service: {image: "busybox:latest", logging: *default-logging}
  parking-service: {image: "busybox:latest", logging: {driver: json-file, options: {max-size: "10m", max-file: "3"}}}
  alertmanager: {image: "busybox:latest", profiles: ["off"], environment: {HOOK: "${PARKIO_ALERT_SLACK_WEBHOOK_URL:-}"}}
Y
printf 'services: {}\n' > "$R/docker/docker-compose.azure-hosted-beta.yml"
printf 'services:\n  alertmanager:\n    profiles: !reset []\n' > "$R/docker/docker-compose.civo-alertmanager.yml"
git -C "$R" init -q && git -C "$R" add -A && git -C "$R" -c user.name=t -c user.email=t@t commit -qm release
# --- marketing site
mkdir -p "$W/site"
printf '<html><head><meta name="parkio-waitlist-mode" content="api"></head><body>x</body></html>\n' > "$W/site/index.html"
printf "const CONSENT_TEXT_VERSION = 'waitlist-consent-v1';\nconst p = {consentTextVersion: CONSENT_TEXT_VERSION};\n" > "$W/site/waitlist.js"
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$W/site" >/dev/null 2>&1 & SRV=$!
# --- containers
for db in parking media moderation gamification; do
  # shellcheck disable=SC2046
  docker run -d --name "p1probe-postgres-$db" --network "$NET" $(lbl "postgres-$db") -e POSTGRES_USER=pk \
    -e POSTGRES_PASSWORD="$PGPW" -e "POSTGRES_DB=parkio_$db" postgis/postgis:16-3.4 >/dev/null
done
# shellcheck disable=SC2046
docker run -d --name p1probe-kafka --network "$NET" --network-alias kafka $(lbl kafka) \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller -e CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093 -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092 \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
  -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=false -e "KAFKA_HEAP_OPTS=-Xms640m -Xmx640m" confluentinc/cp-kafka:7.7.1 >/dev/null
printf '#!/bin/sh\ncat <<EOF\nRouting tree:\n\342\224\224\342\224\200\342\224\200 default-route  receiver: warning\n    \342\224\234\342\224\200\342\224\200 {severity="critical"}  receiver: critical\n    \342\224\224\342\224\200\342\224\200 {alertname="X"}  receiver: %s\nEOF\n' "$SECRET_HOOK" > "$W/amtool"
chmod 755 "$W/amtool"; echo 'Etc/UTC' > "$W/timezone"
# shellcheck disable=SC2046
docker run -d --name p1probe-alertmanager $(lbl alertmanager) -v "$W/amtool:/usr/local/bin/amtool:ro" busybox:latest sleep 3600 >/dev/null
LOG=(--log-driver json-file --log-opt max-size=10m --log-opt max-file=5)
# shellcheck disable=SC2046
docker run -d --name p1probe-gateway $(lbl gateway-service) "${LOG[@]}" busybox:latest sleep 3600 >/dev/null
# shellcheck disable=SC2046
docker run -d --name p1probe-auth $(lbl auth-service) "${LOG[@]}" busybox:latest sleep 3600 >/dev/null
# shellcheck disable=SC2046
docker run -d --name p1probe-parking $(lbl parking-service) "${LOG[@]}" -e "JAVA_TOOL_OPTIONS=-Xmx1g -Duser.timezone=UTC" \
  -v "$W/timezone:/etc/timezone:ro" busybox:latest sleep 3600 >/dev/null
# --- data
for db in parking media moderation gamification; do
  for _ in $(seq 60); do docker exec "p1probe-postgres-$db" pg_isready -U pk -d "parkio_$db" >/dev/null 2>&1 && break; sleep 1; done
done
sleep 3
sql() { docker exec -i "p1probe-postgres-$1" psql -q -v ON_ERROR_STOP=1 -U pk -d "parkio_$1" -f - >/dev/null; }
sql parking <<'SQL'
create table flyway_schema_history (installed_rank int primary key, version varchar(50), description varchar(200),
  type varchar(20), script varchar(1000), checksum int, installed_by varchar(100), installed_on timestamp not null default now(),
  execution_time int, success boolean not null);
insert into flyway_schema_history select i, i::text, 'm' || i, 'SQL', 'V' || i || '__m.sql', 0, 'pk',
  timestamp '2026-07-01 10:00:00' + (i || ' hours')::interval, 1, true from generate_series(1, 40) i;
create table parking_sessions (id uuid primary key, status varchar(20), started_at timestamptz not null, last_confirmed_at timestamp);
insert into parking_sessions values
  (gen_random_uuid(), 'ACTIVE', '2026-07-21T09:00:00Z', '2026-07-21 09:00:00'),
  (gen_random_uuid(), 'COMPLETED', '2026-07-21T09:00:00Z', '2026-07-21 12:00:00'),
  (gen_random_uuid(), 'COMPLETED', '2026-07-21T08:00:00Z', '2026-07-21 10:15:00'),
  (gen_random_uuid(), 'ACTIVE', '2026-07-22T08:00:00Z', null);
create table parking_spot_search_logs (id bigserial primary key, created_at timestamptz default now());
create table parking_spot_view_logs (id bigserial primary key, created_at timestamptz default now());
insert into parking_spot_search_logs (created_at) select now() from generate_series(1, 1000);
analyze;
alter database parkio_parking set timezone = 'UTC';
SQL
sql media <<'SQL'
create table media_files (id uuid primary key, owner_user_id uuid, checksum varchar(64), status varchar(20));
insert into media_files values
  (gen_random_uuid(), '11111111-1111-4111-8111-111111111111', 'aa', 'ACTIVE'),
  (gen_random_uuid(), '11111111-1111-4111-8111-111111111111', 'aa', 'ACTIVE'),
  (gen_random_uuid(), '22222222-2222-4222-8222-222222222222', 'aa', 'DELETED'),
  (gen_random_uuid(), '22222222-2222-4222-8222-222222222222', 'bb', 'ACTIVE');
analyze;
SQL
sql moderation <<'SQL'
create table user_reports (id uuid primary key, reporter_user_id uuid, target_type varchar(20), target_id uuid, reason varchar(40));
create table appeals (id uuid primary key, case_id uuid, appeal_user_id uuid);
insert into user_reports values
  (gen_random_uuid(), '00000000-0000-4000-8000-000000000001', 'USER', '33333333-3333-4333-8333-333333333333', 'SPAM'),
  (gen_random_uuid(), '00000000-0000-4000-8000-000000000001', 'USER', '33333333-3333-4333-8333-333333333333', 'SPAM'),
  (gen_random_uuid(), '44444444-4444-4444-8444-444444444444', 'USER', '33333333-3333-4333-8333-333333333333', 'SPAM');
insert into appeals values (gen_random_uuid(), '55555555-5555-4555-8555-555555555555', '44444444-4444-4444-8444-444444444444');
SQL
sql gamification <<'SQL'
create table outbox_events (id uuid primary key, event_type varchar(100), published boolean default false,
  dead_lettered boolean default false, acknowledged_deadletter boolean default false, created_at timestamptz default now());
insert into outbox_events (id, event_type, published, dead_lettered, acknowledged_deadletter) values
  (gen_random_uuid(), 'PointsEarned', false, true, false), (gen_random_uuid(), 'PointsEarned', false, true, false),
  (gen_random_uuid(), 'UserLevelChanged', false, true, true), (gen_random_uuid(), 'TrustScoreUpdated', false, false, false),
  (gen_random_uuid(), 'PointsEarned', true, false, false);
SQL
K=(docker exec -i -e "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m" p1probe-kafka)
for _ in $(seq 90); do "${K[@]}" kafka-broker-api-versions --bootstrap-server localhost:9092 >/dev/null 2>&1 && break; sleep 2; done
"${K[@]}" kafka-topics --bootstrap-server localhost:9092 --create --topic parkio.dlt.user --partitions 2 --replication-factor 1 >/dev/null
"${K[@]}" kafka-topics --bootstrap-server localhost:9092 --create --topic parkio.gamification.score --partitions 1 --replication-factor 1 >/dev/null
printf 'a\nb\nc\n' | "${K[@]}" kafka-console-producer --bootstrap-server localhost:9092 --topic parkio.dlt.user >/dev/null 2>&1
printf 'x\n' | "${K[@]}" kafka-console-producer --bootstrap-server localhost:9092 --topic parkio.gamification.score >/dev/null 2>&1
"${K[@]}" kafka-console-consumer --bootstrap-server localhost:9092 --topic parkio.gamification.score --group parkio.user \
  --from-beginning --max-messages 1 --timeout-ms 30000 >/dev/null 2>&1 || true
printf 'y\n' | "${K[@]}" kafka-console-producer --bootstrap-server localhost:9092 --topic parkio.gamification.score >/dev/null 2>&1
# --- run P1
git -C "$L" status --porcelain > "$W/live-before"; git -C "$R" status --porcelain > "$W/release-before"
P1_LIVE="$L" P1_RELEASE="$R" P1_ENV_FILE="$ENV" P1_PROJECT="$P" P1_OUT="$W/out" P1_PG_PREFIX=p1probe-postgres- \
  P1_PIN_ANY=1 P1_MARKETING_BASE="http://127.0.0.1:$PORT" P1_NR_BUDGET="$W/no-such/budget" \
  python3 -I "$TOOL" > "$W/tool.out" 2>&1 || { echo "tool exit $?"; }
sed "s#$W#<W>#g" "$W/tool.out"
fail=0
check() { if grep -qF -- "$1" "$W/tool.out"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
absent() { if grep -qF -- "$1" "$W/tool.out" "$W"/out/P1-facts-*.txt; then echo "FAIL leaked: $2"; fail=1; else echo "ok   not printed: $2"; fi; }
LIVEBLOB="$(git -C "$L" hash-object docker/alertmanager/render-config.sh)"
check "release $R @ "
check "duplicates blocking media V18 uq_media_files_owner_checksum_live: 1"
check "duplicates blocking moderation V14 uq_user_reports_reporter_target_reason: 0"
check "duplicates blocking moderation V14 uq_appeals_case_user: 0"
check "parking: PostgreSQL 16"
check "parking_sessions ~4 rows"
check "parking_spot_search_logs ~1000 rows"
check "gateway: query failed"
check "ALTER DATABASE/ROLE TimeZone settings: parkio_parking/* UTC"
check "parking_sessions.last_confirmed_at type today: timestamp without time zone"
check "flyway V17 installed_on (wall clock of the migrating session): 2026-07-02T03:00:00"
check "flyway V40 installed_on"
check "rows: total 4; with last_confirmed_at 3; ACTIVE 2"
check "equal to started_at as UTC wall clock (consistent with a UTC back-fill or app write): 1"
check "Europe/Istanbul back-fill signature (V41 repairs these only in an Istanbul session): 1"
check "signature of the server default zone (what V41 changes in a session in that zone): 0"
check "running parking container: TZ absent; JAVA_TOOL_OPTIONS user.timezone=UTC"
check "image files: /etc/timezone Etc/UTC; /etc/localtime -> not a link"
check "env file: TZ absent; JAVA_TOOL_OPTIONS user.timezone=UTC"
check "PARKIO_ACCOUNT_ERASURE_ENABLED: false"
check "PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED: <non-boolean>"
check "PARKIO_RESTORE_REPLAY_ENABLED: unset"
check "PARKIO_ALERT_SLACK_WEBHOOK_URL set; PARKIO_ALERT_WEBHOOK_URL absent; PARKIO_ALERT_HEARTBEAT_URL empty"
check "live render-config.sh blob $LIVEBLOB (MODIFIED; HEAD "
check "default-route  receiver: warning"
check "receiver: <url>"
check "scripts/lib/backup-common.sh: unmodified"
check "live backup writes an offsite receipt file: no"
check "parkio_backup_last_success 1; parkio_backup_offsite_last_success 1; parkio_backup_production_mode 1; parkio_backup_encryption_enabled 1; parkio_backup_last_timestamp_seconds age 2.0 h"
check "backup stamps: 2; newest 2026-10-08T03-30-01Z; COMPLETE yes; offsite receipt no"
check "gateway-service: live json-file/10m/5 | release model json-file/10m/5 | equal"
check "parking-service: live json-file/10m/5 | release model json-file/10m/3 | DIFFERENT"
check "free space NR budget filesystem:"
check "open dead-lettered gamification outbox rows: 2 (PointsEarned 2)"
check "unpublished, not dead-lettered gamification rows: 1"
check "parkio.dlt.user retained records: 3"
if grep -qE "group parkio.user lag per topic: parkio.gamification.score [12]$" "$W/tool.out"; then echo "ok   parkio.user lag reported"; else echo "FAIL parkio.user lag"; fail=1; fi
check "landing page: waitlist mode api; Content-Security-Policy header absent"
check "waitlist.js sends consentTextVersion: yes; carries waitlist-consent-v1: yes"
check "U12 part A totals: dead-lettered gamification rows 2; retained parkio.dlt.user records 3"
check "P1 COMPLETE (read-only)"
absent "alerts.invalid" "alert webhook URL"
absent "Synthetic-Secret-Token" "webhook token"
absent "$PGPW" "database password"
absent "ops-person" "ACME e-mail"
absent "Not-A-Boolean-Value" "non-boolean flag value"
mode="$(stat -c %a "$W"/out/P1-facts-*.txt)"; [ "$mode" = 600 ] && echo "ok   report mode 600" || { echo "FAIL report mode $mode"; fail=1; }
git -C "$L" status --porcelain | cmp -s - "$W/live-before" && echo "ok   live checkout status unchanged by the tool" || { echo "FAIL live checkout changed"; fail=1; }
git -C "$R" status --porcelain | cmp -s - "$W/release-before" && [ ! -s "$W/release-before" ] && echo "ok   release checkout clean and unchanged" || { echo "FAIL release checkout changed"; fail=1; }
echo "python $(python3 -c 'import sys; print(sys.version.split()[0])'), compose $(docker compose version --short), docker $(docker version --format '{{.Server.Version}}')"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
