#!/usr/bin/env bash
# Local self-test of p3-readonly-gates.py (P3) on a synthetic host-like stack: a real cp-kafka 7.7.1 broker
# (KRaft, the production heap setting inherited by exec'd tools) with release topics, groups with and without
# committed offsets and groups that do not exist yet; PostGIS databases for gamification and parking; live and
# release git checkouts with the release's real production wrapper and web guard libraries (P3 only renders
# `config --quiet`); a preflight stand-in that prints secrets in its FAIL/WARN messages; an env file with secrets.
# S1: everything consumed -> all gates PASS. S2: unread score records without commits, a DLT record, a
# dead-lettered outbox row, a failing preflight, a divergent env copy and an unrenderable gateway model -> exactly
# those gates FAIL. Pass = expected lines, no secret/URL/e-mail printed, reports mode 600, and P3 changed no
# committed offset, log end, container or checkout. Cleans up after itself.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/p3-readonly-gates.py"
PIN=22f9699038cdae15ffff5dd7433c4a64aed0a951; REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
P=p3probe; NET=p3probe-net; W="$(mktemp -d)"
NAMES=(p3probe-kafka p3probe-postgres-gamification p3probe-postgres-parking)
cleanup() { docker rm -f "${NAMES[@]}" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; rm -rf "$W"; }
trap cleanup EXIT
for n in "${NAMES[@]}"; do [ -z "$(docker ps -aq --filter "name=^$n$")" ] || { echo "container $n exists"; exit 2; }; done
docker network create "$NET" >/dev/null
lbl() { echo --label "com.docker.compose.project=$P" --label "com.docker.compose.service=$1"; }
PGPW='Pw-Synthetic-91'; HOOK='https://alerts.invalid/hook/Synthetic-Secret-Token-77'
# --- checkouts
L="$W/live"; R="$W/release"
mkdir -p "$L/docker/prometheus/textfile" "$L/backups/2026-10-09T03-30-01Z"
touch "$L/backups/2026-10-09T03-30-01Z/COMPLETE"; echo live > "$L/README"
git -C "$L" init -q && git -C "$L" add -A && git -C "$L" -c user.name=t -c user.email=t@t commit -qm live
printf 'parkio_backup_last_timestamp_seconds{scope="azure-hosted-beta"} %s\n' "$(( $(date +%s) - 3600 ))" \
  > "$L/docker/prometheus/textfile/parkio_backup.prom"
ENV="$L/docker/.env.azure-hosted-beta"
printf 'PGPW=%s\nPARKIO_ALERT_SLACK_WEBHOOK_URL=%s\nPARKIO_ACME_EMAIL=ops-person@example.org\n' "$PGPW" "$HOOK" > "$ENV"
mkdir -p "$R/docker" "$R/scripts/lib"
for f in scripts/parkio-prod-compose.sh scripts/lib/web-map-guard.sh scripts/lib/web-conf-d-guard.sh \
         scripts/lib/web-api-endpoint-guard.sh; do git -C "$REPO" show "$PIN:$f" > "$R/$f"; done
chmod +x "$R/scripts/parkio-prod-compose.sh"
printf 'docker/docker-compose.yml\ndocker/docker-compose.azure-hosted-beta.yml\n' > "$R/docker/compose.production.files"
cat > "$R/docker/docker-compose.yml" <<'Y'
name: p3probe
services:
  gateway-service: {image: "busybox:latest", environment: {HOOK: "${PARKIO_ALERT_SLACK_WEBHOOK_URL}", PW: "${PGPW}"}}
Y
printf 'services: {}\n' > "$R/docker/docker-compose.azure-hosted-beta.yml"
printf 'services:\n  alertmanager: {image: "busybox:latest"}\n' > "$R/docker/docker-compose.civo-alertmanager.yml"
printf 'services:\n  gateway-service:\n    group_add: ["${P3TEST_GID:?missing}"]\n' > "$R/docker/docker-compose.waitlist-ops-inbox.yml"
cat > "$R/scripts/preflight-hosted-beta.sh" <<'SH'
#!/bin/sh
case " $* " in *" --skip-compose "*) ;; *) echo "stand-in: --skip-compose missing"; exit 2 ;; esac
case " $* " in *" --deployment-profile azure-hosted-beta "*) ;; *) echo "stand-in: profile missing"; exit 2 ;; esac
echo "=== Parkio hosted-beta preflight ==="
echo ""
echo "[Secrets]"
if [ "${P3TEST_PREFLIGHT:-pass}" = fail ]; then
  printf '  FAIL PARKIO_JWT_SECRET: value Pw-Synthetic-91 is a placeholder\n       fix: openssl rand -hex 32\n'
  printf '  WARN PARKIO_ALERT_WEBHOOK_URL: https://alerts.invalid/hook/Synthetic-Secret-Token-77 unreachable\n'
  printf '  FAIL PARKIO_ACME_EMAIL ops-person@example.org is not allowed\n'
fi
echo "[Domains]"
echo "  SKIP compose render (--skip-compose)"
echo ""
if [ "${P3TEST_PREFLIGHT:-pass}" = fail ]; then
  echo "=== PREFLIGHT: FAIL — 2 failure(s), 1 warning(s), 11 check(s) passed ==="; exit 1
fi
echo "=== PREFLIGHT: PASS — 13 check(s) passed, 0 warning(s) ==="
SH
printf 'docker/.env.*\n' > "$R/.gitignore"
git -C "$R" init -q && git -C "$R" add -A && git -C "$R" -c user.name=t -c user.email=t@t commit -qm release
cp "$ENV" "$R/docker/.env.azure-hosted-beta"
# --- containers
for db in gamification parking; do
  # shellcheck disable=SC2046
  docker run -d --name "p3probe-postgres-$db" --network "$NET" $(lbl "postgres-$db") -e POSTGRES_USER=pk \
    -e POSTGRES_PASSWORD="$PGPW" -e "POSTGRES_DB=parkio_$db" postgis/postgis:16-3.4 >/dev/null
done
# shellcheck disable=SC2046
docker run -d --name p3probe-kafka --network "$NET" --network-alias kafka $(lbl kafka) \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller -e CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093 -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092 \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
  -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=false -e "KAFKA_HEAP_OPTS=-Xms640m -Xmx640m" confluentinc/cp-kafka:7.7.1 >/dev/null
for db in gamification parking; do
  for _ in $(seq 60); do docker exec "p3probe-postgres-$db" pg_isready -U pk -d "parkio_$db" >/dev/null 2>&1 && break; sleep 1; done
done
sleep 3
sql() { docker exec -i "p3probe-postgres-$1" psql -q -v ON_ERROR_STOP=1 -U pk -d "parkio_$1" -f - >/dev/null; }
sql gamification <<'SQL'
create table outbox_events (id uuid primary key, event_type varchar(100), published boolean default false,
  dead_lettered boolean default false, acknowledged_deadletter boolean default false, created_at timestamptz default now());
insert into outbox_events (id, event_type, published) values (gen_random_uuid(), 'PointsEarned', true);
SQL
sql parking <<'SQL'
create table parking_sessions (id uuid primary key, status varchar(20), started_at timestamptz not null, last_confirmed_at timestamp);
SQL
K=(docker exec -i -e "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m" p3probe-kafka)
for _ in $(seq 90); do "${K[@]}" kafka-broker-api-versions --bootstrap-server localhost:9092 >/dev/null 2>&1 && break; sleep 2; done
topic() { "${K[@]}" kafka-topics --bootstrap-server localhost:9092 --create --topic "$1" --partitions "$2" --replication-factor 1 >/dev/null; }
topic parkio.gamification.score 6; topic parkio.moderation.action 3; topic parkio.auth.user 1
topic parkio.privacy.erasure 2; topic parkio.dlt.user 3; topic parkio.dlt.media 1; topic other.unrelated 1
produce() { printf "$2" | "${K[@]}" kafka-console-producer --bootstrap-server localhost:9092 --topic "$1" >/dev/null 2>&1; }
consume() {  # group topic count
  "${K[@]}" kafka-console-consumer --bootstrap-server localhost:9092 --topic "$2" --group "$1" --from-beginning \
    --max-messages "$3" --timeout-ms 20000 >/dev/null 2>&1 || true
}
produce parkio.auth.user 'a\nb\n'; produce other.unrelated 'z\n'
consume parkio.user parkio.auth.user 2
produce parkio.auth.user 'c\n'          # pending after a commit: read on restart, not a replay
produce parkio.moderation.action 'm\n'
for g in parkio.auth parkio.gamification parkio.notification parkio.parking parkio.user; do
  consume "$g" parkio.moderation.action 1; done          # each group reads the record and commits its partitions
offsets_snapshot() {
  "${K[@]}" kafka-consumer-groups --bootstrap-server localhost:9092 --describe --all-groups 2>/dev/null \
    | awk 'NF >= 6 && $3 ~ /^[0-9]+$/ {print $1, $2, $3, $4, $5}' | sort
}
fail=0
ok() { echo "ok   $1"; }
bad() { echo "FAIL $1"; fail=1; }
check() { if grep -qF -- "$2" "$1"; then ok "$(basename "$1"): $2"; else bad "$(basename "$1"): $2"; fi; }
rc_is() { [ "$(cat "$1.rc")" = "$2" ] && ok "$(basename "$1") exit $2" || bad "$(basename "$1") exit $(cat "$1.rc"), want $2"; }
p3() {  # output-file [extra env assignments]
  local out="$1"; shift
  env "$@" P3_LIVE="$L" P3_RELEASE="$R" P3_ENV_FILE="$ENV" P3_PROJECT="$P" P3_OUT="$W/out" P3_PIN_ANY=1 \
    P3_PG_PREFIX=p3probe-postgres- P3_NR_BUDGET="$W/no-such/budget" python3 -I "$TOOL" > "$out" 2>&1 \
    && echo 0 > "$out.rc" || echo $? > "$out.rc"
}
# --- S1: everything consumed
offsets_snapshot > "$W/off0"; git -C "$L" status --porcelain > "$W/live0"
p3 "$W/s1.out" P3TEST_GID=987
sed "s#$W#<W>#g" "$W/s1.out"
rc_is "$W/s1.out" 0
check "$W/s1.out" "PASS release checkout clean"
check "$W/s1.out" "PASS no divergent env-file copy in the release checkout: docker/.env.azure-hosted-beta present, mode"
check "$W/s1.out" "identical to the live env file"
check "$W/s1.out" "  [Secrets]"
check "$W/s1.out" "SKIP compose render (--skip-compose)"
check "$W/s1.out" "PASS preflight (azure-hosted-beta, env validation): exit 0; === PREFLIGHT: PASS - 13 check(s) passed, 0 warning(s) ==="
check "$W/s1.out" "PASS release wrapper model renders (canonical): exit 0"
check "$W/s1.out" "PASS release wrapper model renders (gateway with the waitlist overlay): exit 0"
check "$W/s1.out" "offsets.retention.minutes: 10080"
check "$W/s1.out" "parkio.user (D7 user): Empty, 0 member(s)"
check "$W/s1.out" "parkio.auth.user: p0 3/1  (1 partitions, 1 committed; retained 3; restart reads 1)"
check "$W/s1.out" "(3 partitions, 3 committed; retained 1; restart reads 0)"
check "$W/s1.out" "parkio.gamification.score: p0 0/0*  p1 0/0*  p2 0/0*  p3 0/0*  p4 0/0*  p5 0/0*  (6 partitions, 0 committed; retained 0; restart reads 0)"
check "$W/s1.out" "parkio.media.erasure (D6a media): absent on the broker (a new consumer would read every retained record)"
check "$W/s1.out" "parkio.privacy.erasure: p0 0/0*  p1 0/0*"
check "$W/s1.out" "parkio.parking.spot: topic absent (no partition, nothing to read)"
check "$W/s1.out" "DLT topics (retained records per partition): parkio.dlt.media p0 0; parkio.dlt.user p0 0 p1 0 p2 0"
check "$W/s1.out" "restart reads 1 (pending after a commit 1, from the log start 0)"
check "$W/s1.out" "other groups on the broker (not recreated by this release): none"
check "$W/s1.out" "PASS no release group would re-read retained records from the log start: none"
check "$W/s1.out" "gamification outbox: open dead-lettered 0; unpublished, not dead-lettered 0"
check "$W/s1.out" "PASS U12-A: dead-lettered 0, DLT 0 and nothing for user-service to re-read on the score topic"
check "$W/s1.out" "last_confirmed_at type timestamp without time zone; rows 0; with last_confirmed_at 0; Istanbul signature 0"
check "$W/s1.out" "PASS V41: no Istanbul-signature rows: V41 in a UTC session changes no value"
check "$W/s1.out" "newest backup 2026-10-09T03-30-01Z"
check "$W/s1.out" "backup metrics (azure-hosted-beta): parkio_backup_last_timestamp_seconds age 1.0 h"
check "$W/s1.out" "gates passed 9/9"
check "$W/s1.out" "P3 COMPLETE (read-only)"
grep -q "other.unrelated" "$W/s1.out" && bad "unrelated topic reported" || ok "unrelated topic not reported"
offsets_snapshot | cmp -s - "$W/off0" && ok "S1 changed no committed offset or log end" || bad "S1 changed offsets"
# --- S2: failing variant
produce parkio.gamification.score 'k1\nk2\n'; produce parkio.dlt.user 'poison\n'
sql gamification <<'SQL'
insert into outbox_events (id, event_type, dead_lettered) values (gen_random_uuid(), 'PointsEarned', true);
SQL
sql parking <<'SQL'
insert into parking_sessions values (gen_random_uuid(), 'ACTIVE', '2026-07-21T09:00:00Z', '2026-07-21 12:00:00');
SQL
printf 'PGPW=other\n' > "$R/docker/.env.azure-hosted-beta"
offsets_snapshot > "$W/off1"
p3 "$W/s2.out" P3TEST_PREFLIGHT=fail
sed "s#$W#<W>#g" "$W/s2.out" | sed -n '/^A\. /,/^C\. /p;/^SUMMARY/,$p'
rc_is "$W/s2.out" 1
check "$W/s2.out" "FAIL no divergent env-file copy in the release checkout"
check "$W/s2.out" "DIFFERENT from the live env file"
check "$W/s2.out" "    FAIL PARKIO_JWT_SECRET"
check "$W/s2.out" "    WARN PARKIO_ALERT_WEBHOOK_URL"
check "$W/s2.out" "    FAIL PARKIO_ACME_EMAIL"
check "$W/s2.out" "FAIL preflight (azure-hosted-beta, env validation): exit 1; === PREFLIGHT: FAIL - 2 failure(s), 1 warning(s), 11 check(s) passed ==="
check "$W/s2.out" "PASS release wrapper model renders (canonical): exit 0"
check "$W/s2.out" "FAIL release wrapper model renders (gateway with the waitlist overlay): exit "
check "$W/s2.out" "P3TEST_GID"
check "$W/s2.out" "2/2*"
check "$W/s2.out" "FAIL no release group would re-read retained records from the log start: decision needed: parkio.analytics parkio.gamification.score p"
check "$W/s2.out" "parkio.user parkio.gamification.score p"
check "$W/s2.out" "parkio.dlt.user p0"
check "$W/s2.out" "open dead-lettered 1"
check "$W/s2.out" "FAIL U12-A: decision needed per row/record"
check "$W/s2.out" "rows 1; with last_confirmed_at 1; Istanbul signature 1"
check "$W/s2.out" "FAIL V41: no Istanbul-signature rows: 1 row(s) would stay 3 h off after V41 in UTC (decision before D6b)"
check "$W/s2.out" "gates passed 3/9"
check "$W/s2.out" "P3 COMPLETE (read-only); a gate needs a decision, see FAIL lines"
offsets_snapshot | cmp -s - "$W/off1" && ok "S2 changed no committed offset or log end" || bad "S2 changed offsets"
# --- global properties
for f in "$W"/s*.out "$W"/out/*; do
  for secret in "$PGPW" Synthetic-Secret-Token alerts.invalid ops-person example.org; do
    if grep -qF -- "$secret" "$f"; then bad "$(basename "$f") leaks $secret"; fi
  done
done
ok "no secret, URL or e-mail in any output or report (checked 5 values)"
modes="$(stat -c %a "$W"/out/* | sort -u | tr '\n' ' ')"; [ "$modes" = "600 " ] && ok "reports mode 600" || bad "report modes $modes"
python3 -c 'import json,sys; [json.load(open(p)) for p in sys.argv[1:]]' "$W"/out/*.json && ok "JSON records parse" || bad "JSON records"
python3 - "$W"/out/*.json <<'PY' && ok "JSON holds every partition with start, end, committed and restart reads" || bad "JSON partitions"
import json, sys
recs = [json.load(open(p)) for p in sys.argv[1:]]
for r in recs:
    rows = r["kafka"]["groups"]["parkio.user"]["topics"]["parkio.gamification.score"]
    assert len(rows) == 6 and all({"logStart", "logEnd", "committed", "restartReads", "retained"} <= set(x) for x in rows)
PY
git -C "$L" status --porcelain | cmp -s - "$W/live0" && ok "live checkout unchanged" || bad "live checkout changed"
[ -z "$(git -C "$R" status --porcelain)" ] && ok "release checkout clean (env copy is ignored)" || bad "release checkout changed"
echo "python $(python3 -c 'import sys; print(sys.version.split()[0])'), compose $(docker compose version --short), docker $(docker version --format '{{.Server.Version}}'), kafka cp-7.7.1"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
