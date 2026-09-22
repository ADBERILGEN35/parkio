#!/usr/bin/env bash
# Isolated waitlist Slack e2e: real gateway container(s) → shared inbox →
# slack_biz consumer → durable SQLite queue → worker → MOCK Slack.
#
# Usage:
#   E2E_GATEWAY_IMAGE=<PR-head gateway image> \
#   E2E_PREVIOUS_GATEWAY_IMAGE=<currently pinned production gateway image> \
#   EVIDENCE_DIR=<dir> scripts/slack_biz/waitlist-e2e/run-e2e.sh
#
# Synthetic data only. No real webhook, no email provider (logging sender),
# internal Docker network. Tears the stack down (with volumes) on exit.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
: "${E2E_GATEWAY_IMAGE:?set E2E_GATEWAY_IMAGE}"
: "${E2E_PREVIOUS_GATEWAY_IMAGE:?set E2E_PREVIOUS_GATEWAY_IMAGE}"
EVIDENCE_DIR="${EVIDENCE_DIR:-$HERE/.evidence}"
mkdir -p "$EVIDENCE_DIR"
export E2E_GATEWAY_IMAGE
export E2E_HASH_SECRET="e2e-only-waitlist-hash-secret-0123456789ab"
CF=(docker compose -f docker-compose.waitlist-e2e.yml)
LOG="$EVIDENCE_DIR/e2e-run.log"
: > "$LOG"
PASS=0; FAIL=0
RESULTS=()

log() { printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$LOG"; }
result() { # id status detail
  RESULTS+=("$1|$2|$3")
  if [ "$2" = PASS ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
  log "RESULT $1 $2 — $3"
}
cleanup() {
  "${CF[@]}" logs --no-color gateway-service > "$EVIDENCE_DIR/gateway.log" 2>&1 || true
  "${CF[@]}" logs --no-color relay-consumer relay-worker > "$EVIDENCE_DIR/relay.log" 2>&1 || true
  if [ "${E2E_KEEP:-0}" != 1 ]; then "${CF[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

psql_q() { "${CF[@]}" exec -T postgres-gateway psql -U parkio_gateway -d parkio_gateway -tAq -c "$1" | tr -d '\r'; }
hmac() { python3 -c 'import hmac,hashlib,sys;print(hmac.new(sys.argv[1].encode(),sys.argv[2].encode(),hashlib.sha256).hexdigest())' "$E2E_HASH_SECRET" "$1"; }
gw_post() { "${CF[@]}" exec -T gateway-service curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d "$2" "http://localhost:8080$1"; }
mock_count() { "${CF[@]}" exec -T mock-slack sh -c 'wc -l < /data/requests.jsonl' | tr -d ' \r'; }
relay_py() { "${CF[@]}" exec -T relay-worker python -c "$1" | tr -d '\r'; }
relay_status() { relay_py "import sqlite3;c=sqlite3.connect('/state/slack_biz.sqlite3');print(','.join(f'{s}={n}' for s,n in c.execute('select status,count(*) from delivery_queue group by status order by status')))"; }
inbox_ls() { "${CF[@]}" exec -T relay-worker sh -c 'ls -ln /inbox/*.json 2>/dev/null' | tr -d '\r'; }
wait_for() { # desc timeout cmd expected
  local desc="$1" t="$2" cmd="$3" want="$4" got=""
  for _ in $(seq 1 "$t"); do
    got="$(eval "$cmd" 2>/dev/null)"
    [ "$got" = "$want" ] && return 0
    sleep 1
  done
  log "timeout waiting for $desc (want=$want got=$got)"
  return 1
}
gateway_healthy() {
  wait_for "gateway readiness" 180 "\"\${CF[@]}\" exec -T gateway-service curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health/readiness" 200
}

# Creates a PENDING subscriber through the real submit API, then pins a known
# synthetic verification token (only its HMAC is stored, as in production).
new_subscriber() { # label → echoes token
  local label="$1" email="e2e.${1}@example.test" token="e2e-token-${1}-$RANDOM$RANDOM"
  local now; now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  local code; code="$(gw_post /api/v1/waitlist "{\"email\":\"$email\",\"consentTimestamp\":\"$now\",\"source\":\"parkio.dev-landing\",\"locale\":\"tr\",\"city\":\"E2E-City-$label\"}")"
  [ "$code" = 202 ] || { log "submit $label failed http=$code"; return 1; }
  psql_q "UPDATE waitlist_interest SET verification_token_hash='$(hmac "$token")' WHERE email_hash='$(hmac "$email")'" >/dev/null
  echo "$token"
}
confirm() { gw_post /api/v1/waitlist/confirm "{\"token\":\"$1\"}"; }
outbox() { psql_q "SELECT status||':'||count(*) FROM waitlist_ops_notification_outbox GROUP BY status ORDER BY status" | paste -sd, -; }

set_gateway() { # image ops_enabled
  E2E_GATEWAY_IMAGE="$1" E2E_GATEWAY_OPS_ENABLED="$2" "${CF[@]}" up -d --no-deps --force-recreate gateway-service >>"$LOG" 2>&1
  gateway_healthy
}
set_relay_enabled() {
  E2E_RELAY_ENABLED="$1" "${CF[@]}" up -d --no-deps --force-recreate relay-consumer relay-worker >>"$LOG" 2>&1
}

###########################################################################
log "images: gateway=$E2E_GATEWAY_IMAGE previous=$E2E_PREVIOUS_GATEWAY_IMAGE"
docker image inspect "$E2E_GATEWAY_IMAGE" --format 'gateway image id={{.Id}} revision={{index .Config.Labels "org.opencontainers.image.revision"}}' | tee -a "$LOG"
docker image inspect "$E2E_PREVIOUS_GATEWAY_IMAGE" --format 'previous image id={{.Id}} revision={{index .Config.Labels "org.opencontainers.image.revision"}}' | tee -a "$LOG"
"${CF[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${CF[@]}" up -d >>"$LOG" 2>&1
gateway_healthy || { result E00 FAIL "gateway not ready"; exit 1; }
log "postgres: $(psql_q 'SHOW server_version')  flyway head: $(psql_q "SELECT max(version::int) FROM flyway_schema_history WHERE success")"

# E01 UID / permission model
gw_id="$("${CF[@]}" exec -T gateway-service id | tr -d '\r')"
relay_id="$("${CF[@]}" exec -T relay-worker id | tr -d '\r')"
dir_perm="$("${CF[@]}" exec -T relay-worker stat -c '%u:%g %a' /inbox | tr -d '\r')"
state_perm="$("${CF[@]}" exec -T relay-worker stat -c '%u:%g %a' /state | tr -d '\r')"
log "gateway: $gw_id | relay: $relay_id | inbox: $dir_perm | state: $state_perm"
if [[ "$gw_id" == uid=10001* && "$gw_id" == *10500* && "$relay_id" == uid=10002* && "$dir_perm" == "10002:10500 2770" && "$state_perm" == "10002:10002 700" ]]; then
  # gateway may not read relay state
  if "${CF[@]}" exec -T gateway-service sh -c 'ls /state' >/dev/null 2>&1; then
    result E01 FAIL "gateway can see relay state"
  else
    result E01 PASS "gateway uid 10001 (+grp 10500) writes inbox 2770; relay uid 10002 owns 0700 state"
  fi
else
  result E01 FAIL "unexpected ids/perms"
fi

# E02 committed confirmation end-to-end
TA="$(new_subscriber a)"
code="$(confirm "$TA")"
if [ "$code" = 202 ] && wait_for "slack post A" 60 mock_count 1 && wait_for "outbox exported" 30 outbox "EXPORTED:1"; then
  body="$("${CF[@]}" exec -T mock-slack sh -c 'head -1 /data/requests.jsonl' | tr -d '\r')"
  echo "$body" > "$EVIDENCE_DIR/slack-payload-1.json"
  python3 -c 'import json,sys;print(json.loads(json.loads(sys.argv[1])["body"])["text"])' "$body" > "$EVIDENCE_DIR/slack-message-1.txt"
  if python3 -c 'import json,sys;t=json.loads(json.loads(sys.argv[1])["body"])["text"];sys.exit(0 if "Bekleme listesi aboneliği onaylandı" in t and "env=`e2e`" in t else 1)' "$body"; then
    result E02 PASS "confirm 202 → outbox EXPORTED → relay $(relay_status) → 1 mock Slack post"
  else
    result E02 FAIL "unexpected Slack text"
  fi
else
  result E02 FAIL "code=$code mock=$(mock_count) outbox=$(outbox)"
fi

# E03 duplicate confirmation
code="$(confirm "$TA")"; sleep 8
if [ "$code" = 202 ] && [ "$(outbox)" = "EXPORTED:1" ] && [ "$(mock_count)" = 1 ]; then
  result E03 PASS "repeat confirm 202, outbox rows=1, Slack posts=1"
else
  result E03 FAIL "code=$code outbox=$(outbox) mock=$(mock_count)"
fi

# E04 relay restart recovery (+ exported file ownership)
"${CF[@]}" stop relay-consumer relay-worker >>"$LOG" 2>&1
TB="$(new_subscriber b)"; confirm "$TB" >/dev/null
sleep 8
files="$("${CF[@]}" run --rm --no-deps --entrypoint sh relay-worker -c 'ls -ln /inbox/*.json' 2>/dev/null | tr -d '\r')"
log "inbox while relay stopped: $files"
"${CF[@]}" start relay-consumer relay-worker >>"$LOG" 2>&1
if echo "$files" | grep -Eq ' 10001 +10500 ' && wait_for "slack post B" 60 mock_count 2; then
  result E04 PASS "file written as 10001:10500 while relay down; delivered after relay restart"
else
  result E04 FAIL "files=[$files] mock=$(mock_count)"
fi

# E05 crash between file export and marking EXPORTED (state replay + restart)
psql_q "UPDATE waitlist_ops_notification_outbox SET status='PENDING', exported_at=NULL, next_attempt_at=now() WHERE status='EXPORTED'" >/dev/null
"${CF[@]}" restart gateway-service >>"$LOG" 2>&1
gateway_healthy
if wait_for "re-export marked" 60 outbox "EXPORTED:2"; then
  sleep 6
  sup="$(relay_py "import sqlite3;c=sqlite3.connect('/state/slack_biz.sqlite3');print(c.execute('select count(*) from delivery_queue').fetchone()[0])")"
  if [ "$(mock_count)" = 2 ] && [ "$sup" = 2 ]; then
    result E05 PASS "2 rows re-exported after simulated crash; relay suppressed by dedupKey; posts still 2"
  else
    result E05 FAIL "mock=$(mock_count) queue_rows=$sup"
  fi
else
  result E05 FAIL "outbox=$(outbox)"
fi

# E06 hard kill (SIGKILL) with a committed PENDING row → recovered after restart
E2E_POLL_INTERVAL=PT25S set_gateway "$E2E_GATEWAY_IMAGE" true
ok=0
for try in 1 2 3; do
  TC="$(new_subscriber c$try)"; confirm "$TC" >/dev/null
  "${CF[@]}" kill -s KILL gateway-service >>"$LOG" 2>&1
  st="$(outbox)"
  log "after SIGKILL try=$try outbox=$st"
  if [[ "$st" == *PENDING:1* ]]; then ok=1; break; fi
  "${CF[@]}" start gateway-service >>"$LOG" 2>&1; gateway_healthy
done
"${CF[@]}" start gateway-service >>"$LOG" 2>&1; gateway_healthy
if [ $ok = 1 ] && wait_for "post after kill recovery" 90 mock_count 3; then
  result E06 PASS "SIGKILL left committed PENDING row; exported + delivered after restart"
else
  result E06 FAIL "ok=$ok mock=$(mock_count) outbox=$(outbox)"
fi
set_gateway "$E2E_GATEWAY_IMAGE" true

# E07 relay disabled → queued, nothing sent; re-enable → delivered
set_relay_enabled false
TD="$(new_subscriber d)"; confirm "$TD" >/dev/null
sleep 12
st="$(relay_status)"
if [ "$(mock_count)" = 3 ] && [[ "$st" == *queued=1* ]]; then
  set_relay_enabled true
  if wait_for "post after relay enable" 60 mock_count 4; then
    result E07 PASS "relay disabled: queued=1 posts unchanged; enabled → delivered"
  else result E07 FAIL "not delivered after enable"; fi
else
  result E07 FAIL "mock=$(mock_count) relay=$st"
  set_relay_enabled true
fi

# E08 gateway disabled → no outbox row, no file, confirmation still works
set_gateway "$E2E_GATEWAY_IMAGE" false
before="$(outbox)"
TE="$(new_subscriber e)"; code="$(confirm "$TE")"; sleep 10
stE="$(psql_q "SELECT status FROM waitlist_interest WHERE email_hash='$(hmac e2e.e@example.test)'")"
if [ "$code" = 202 ] && [ "$stE" = CONFIRMED ] && [ "$(outbox)" = "$before" ] && [ -z "$(inbox_ls)" ] && [ "$(mock_count)" = 4 ]; then
  result E08 PASS "ops disabled: confirm 202/CONFIRMED, outbox unchanged ($before), inbox empty, posts 4"
else
  result E08 FAIL "code=$code status=$stE outbox=$(outbox) mock=$(mock_count)"
fi

# E09 rollback to the previous production gateway artifact on the V4 schema
set_gateway "$E2E_PREVIOUS_GATEWAY_IMAGE" true
rb=$?
prev_log="$("${CF[@]}" logs --no-color gateway-service 2>&1 | grep -i -E 'flyway|schema|migrat' | tail -8)"
echo "$prev_log" > "$EVIDENCE_DIR/rollback-previous-gateway-flyway.log"
TF="$(new_subscriber f)"; code="$(confirm "$TF")"
stF="$(psql_q "SELECT status FROM waitlist_interest WHERE email_hash='$(hmac e2e.f@example.test)'")"
hist="$(psql_q "SELECT version||':'||success FROM flyway_schema_history ORDER BY installed_rank" | paste -sd, -)"
if [ $rb = 0 ] && [ "$code" = 202 ] && [ "$stF" = CONFIRMED ] && [ "$hist" = "1:true,2:true,3:true,4:true" ]; then
  result E09 PASS "previous image healthy on V4 schema, confirm works, history untouched ($hist)"
else
  result E09 FAIL "rb=$rb code=$code status=$stF hist=$hist"
fi

# E10 roll forward again
set_gateway "$E2E_GATEWAY_IMAGE" true && result E10 PASS "PR image healthy again after rollback" || result E10 FAIL "roll-forward not ready"

# E11 privacy scan of everything persisted/logged/sent
"${CF[@]}" logs --no-color gateway-service relay-consumer relay-worker > "$EVIDENCE_DIR/all-container.log" 2>&1
"${CF[@]}" exec -T mock-slack cat /data/requests.jsonl > "$EVIDENCE_DIR/slack-requests.jsonl"
relay_py "import sqlite3,json;c=sqlite3.connect('/state/slack_biz.sqlite3');[print(json.dumps(list(r))) for t in ('delivery_queue','dedup','dlt','metrics') for r in c.execute('select * from '+t)]" > "$EVIDENCE_DIR/relay-state-dump.txt"
"${CF[@]}" exec -T relay-worker sh -c 'for f in /inbox/.acked/* /inbox/*.json; do [ -f "$f" ] && cat "$f" && echo; done' > "$EVIDENCE_DIR/inbox-files.txt" 2>/dev/null
psql_q "SELECT id::text||' '||email_hash FROM waitlist_interest" > "$EVIDENCE_DIR/.subscriber-ids.tmp"
leaks=0
for f in slack-requests.jsonl relay-state-dump.txt inbox-files.txt all-container.log; do
  for needle in "@example.test" "e2e-token-" "E2E-City" "$TA" "$TB"; do
    grep -q -- "$needle" "$EVIDENCE_DIR/$f" && { log "LEAK $needle in $f"; leaks=$((leaks+1)); }
  done
  while read -r id eh; do
    [ -n "$id" ] || continue
    grep -q -- "$id" "$EVIDENCE_DIR/$f" && { log "LEAK subscriber-id in $f"; leaks=$((leaks+1)); }
    grep -q -- "$eh" "$EVIDENCE_DIR/$f" && [ "$f" != all-container.log ] && { log "LEAK email-hash in $f"; leaks=$((leaks+1)); }
  done < "$EVIDENCE_DIR/.subscriber-ids.tmp"
done
grep -q 'not-a-real-webhook' "$EVIDENCE_DIR/all-container.log" "$EVIDENCE_DIR/relay-state-dump.txt" && { log "LEAK webhook url"; leaks=$((leaks+1)); }
keys="$(python3 -c '
import json,sys
ks=set()
for line in open(sys.argv[1]):
    ks|=set(json.loads(json.loads(line)["body"]))
print(",".join(sorted(ks)))' "$EVIDENCE_DIR/slack-requests.jsonl")"
rm -f "$EVIDENCE_DIR/.subscriber-ids.tmp"
if [ $leaks = 0 ] && [ "$keys" = "mrkdwn,text,username" ]; then
  result E11 PASS "no email/token/city/subscriber-id/email-hash/webhook in Slack payloads, relay state, inbox or logs; Slack keys=$keys"
else
  result E11 FAIL "leaks=$leaks keys=$keys"
fi

log "SUMMARY PASS=$PASS FAIL=$FAIL"
{
  echo "| id | status | detail |"; echo "|---|---|---|"
  for r in "${RESULTS[@]}"; do IFS='|' read -r a b c <<<"$r"; echo "| $a | $b | $c |"; done
  echo; echo "PASS=$PASS FAIL=$FAIL"
} > "$EVIDENCE_DIR/e2e-summary.md"
[ $FAIL = 0 ]
