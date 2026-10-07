#!/usr/bin/env bash
# Isolated Prometheus → Alertmanager → webhook delivery + resolution proof.
# Does not touch hosted-beta. Does not print webhook secrets (none are used).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE=(docker compose -f "${ROOT}/docker/docker-compose.alerting-acceptance.yml" --project-name parkio-alert-accept)
PROM_URL="${PARKIO_ALERT_ACCEPT_PROM_URL:-http://127.0.0.1:19090}"
AM_URL="${PARKIO_ALERT_ACCEPT_AM_URL:-http://127.0.0.1:19093}"
METRICS_URL="${PARKIO_ALERT_ACCEPT_METRICS_URL:-http://127.0.0.1:18081}"
WEBHOOK_URL="${PARKIO_ALERT_ACCEPT_WEBHOOK_URL:-http://127.0.0.1:18080}"
RECEIPTS_DIR="${PARKIO_ALERT_ACCEPT_RECEIPTS_DIR:-${ROOT}/docker/.alerting-acceptance-receipts}"
EVIDENCE="${PARKIO_ALERT_ACCEPT_EVIDENCE:-${ROOT}/docker/.alerting-acceptance-receipts/evidence.txt}"
PYTHON="${PYTHON:-python3}"

export PARKIO_ALERT_ACCEPT_RECEIPTS_DIR="${RECEIPTS_DIR}"

mkdir -p "${RECEIPTS_DIR}"
: > "${RECEIPTS_DIR}/receipts.jsonl"
: > "${EVIDENCE}"

log() {
  printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "${EVIDENCE}"
}

cleanup() {
  log "cleanup: compose down"
  "${COMPOSE[@]}" down --remove-orphans --volumes >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_for() {
  local desc="$1"
  local timeout="$2"
  shift 2
  local start now
  start="$(date +%s)"
  while true; do
    if "$@"; then
      log "PASS ${desc}"
      return 0
    fi
    now="$(date +%s)"
    if [ $((now - start)) -ge "${timeout}" ]; then
      log "FAIL timeout waiting for: ${desc}"
      return 1
    fi
    sleep 5
  done
}

prom_firing() {
  local name="$1"
  curl -fsS --max-time 10 "${PROM_URL}/api/v1/query" \
    --data-urlencode "query=ALERTS{alertname=\"${name}\",alertstate=\"firing\"}" \
    | "${PYTHON}" -c 'import json,sys; d=json.load(sys.stdin); raise SystemExit(0 if d.get("data",{}).get("result") else 1)'
}

prom_not_firing() {
  local name="$1"
  curl -fsS --max-time 10 "${PROM_URL}/api/v1/query" \
    --data-urlencode "query=ALERTS{alertname=\"${name}\",alertstate=\"firing\"}" \
    | "${PYTHON}" -c 'import json,sys; d=json.load(sys.stdin); raise SystemExit(0 if not d.get("data",{}).get("result") else 1)'
}

prom_parking_down_firing() {
  curl -fsS --max-time 10 "${PROM_URL}/api/v1/query" \
    --data-urlencode 'query=ALERTS{alertname=~"CoreServiceDown|ServiceDown",service="parking-service",alertstate="firing"}' \
    | "${PYTHON}" -c 'import json,sys; d=json.load(sys.stdin); raise SystemExit(0 if d.get("data",{}).get("result") else 1)'
}

prom_parking_down_clear() {
  curl -fsS --max-time 10 "${PROM_URL}/api/v1/query" \
    --data-urlencode 'query=ALERTS{alertname=~"CoreServiceDown|ServiceDown",service="parking-service",alertstate="firing"}' \
    | "${PYTHON}" -c 'import json,sys; d=json.load(sys.stdin); raise SystemExit(0 if not d.get("data",{}).get("result") else 1)'
}

am_has_alert() {
  local name="$1"
  curl -fsS --max-time 10 "${AM_URL}/api/v2/alerts" \
    | "${PYTHON}" -c 'import json,sys; name=sys.argv[1]; alerts=json.load(sys.stdin); raise SystemExit(0 if any((a.get("labels") or {}).get("alertname")==name for a in alerts) else 1)' "${name}"
}

webhook_has() {
  local name="$1"
  local status="$2"
  curl -fsS --max-time 10 "${WEBHOOK_URL}/received?alertname=${name}&status=${status}" \
    | "${PYTHON}" -c 'import json,sys; items=json.load(sys.stdin); raise SystemExit(0 if items else 1)'
}

webhook_has_service_down() {
  curl -fsS --max-time 10 "${WEBHOOK_URL}/received?status=firing" \
    | "${PYTHON}" -c 'import json,sys; items=json.load(sys.stdin); names={a.get("alertname") for it in items for a in it.get("alerts",[])}; raise SystemExit(0 if names & {"CoreServiceDown","ServiceDown"} else 1)'
}

# Heartbeat (U06, CL-F04): deliveries of the always-firing Watchdog to the heartbeat catcher.
heartbeat_count() {
  curl -fsS --max-time 10 "${WEBHOOK_URL}/heartbeats" \
    | "${PYTHON}" -c 'import json,sys; print(json.load(sys.stdin).get("count", 0))'
}

heartbeat_at_least() {
  [ "$(heartbeat_count)" -ge "$1" ]
}

heartbeat_only_watchdog() {
  curl -fsS --max-time 10 "${WEBHOOK_URL}/heartbeats" \
    | "${PYTHON}" -c 'import json,sys; items=json.load(sys.stdin).get("items",[]); raise SystemExit(0 if items and all(it.get("group_status")=="firing" and it.get("alertnames")==["Watchdog"] for it in items) else 1)'
}

operator_has_watchdog() {
  curl -fsS --max-time 10 "${WEBHOOK_URL}/received" \
    | "${PYTHON}" -c 'import json,sys; items=json.load(sys.stdin); raise SystemExit(0 if any(it.get("alertname")=="Watchdog" or any(a.get("alertname")=="Watchdog" for a in it.get("alerts",[])) for it in items) else 1)'
}

am_ready() {
  curl -fsS --max-time 5 -o /dev/null "${AM_URL}/-/ready"
}

log "start isolated alerting acceptance"
# Catcher path must not inherit a Slack webhook from the runner environment.
# Slack takes precedence in render-config.sh and would skip the in-compose catcher.
unset PARKIO_ALERT_SLACK_WEBHOOK_URL || true
export PARKIO_ALERT_WEBHOOK_URL="http://alerting-webhook:8080/webhook"
unset PARKIO_ALERT_WEBHOOK_SECRET || true
# Heartbeat (U06): the in-compose catcher's /heartbeat endpoint, never an external switch.
unset PARKIO_ALERT_HEARTBEAT_SECRET || true
export PARKIO_ALERT_HEARTBEAT_URL="http://alerting-webhook:8080/heartbeat"
export PARKIO_ALERT_HEARTBEAT_REPEAT="${PARKIO_ALERT_HEARTBEAT_REPEAT:-20s}"
export PARKIO_ALERT_GROUP_WAIT="${PARKIO_ALERT_GROUP_WAIT:-5s}"
export PARKIO_ALERT_GROUP_WAIT_CRITICAL="${PARKIO_ALERT_GROUP_WAIT_CRITICAL:-5s}"
export PARKIO_ALERT_GROUP_INTERVAL="${PARKIO_ALERT_GROUP_INTERVAL:-10s}"
export PARKIO_ALERT_RESOLVE_TIMEOUT="${PARKIO_ALERT_RESOLVE_TIMEOUT:-30s}"
"${COMPOSE[@]}" up -d --wait --wait-timeout 120

log "baseline: synthetic disarmed"
curl -fsS -X POST "${METRICS_URL}/disarm" >/dev/null
sleep 8
if prom_firing ParkioAlertingAcceptanceTest; then
  log "FAIL synthetic firing before arm"
  exit 1
fi
log "baseline ParkioAlertingAcceptanceTest inactive"

log "arm synthetic alert"
ARM_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
curl -fsS -X POST "${METRICS_URL}/arm" >/dev/null
log "synthetic armed at ${ARM_AT}"

wait_for "ParkioAlertingAcceptanceTest firing in Prometheus" 90 prom_firing ParkioAlertingAcceptanceTest
wait_for "Alertmanager has ParkioAlertingAcceptanceTest" 60 am_has_alert ParkioAlertingAcceptanceTest
wait_for "operator webhook received firing ParkioAlertingAcceptanceTest" 90 webhook_has ParkioAlertingAcceptanceTest firing
FIRE_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "synthetic firing delivered at ${FIRE_AT} (armed ${ARM_AT})"

log "disarm synthetic alert"
DISARM_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
curl -fsS -X POST "${METRICS_URL}/disarm" >/dev/null
log "synthetic disarmed at ${DISARM_AT}"

wait_for "ParkioAlertingAcceptanceTest inactive in Prometheus" 90 prom_not_firing ParkioAlertingAcceptanceTest
wait_for "operator webhook received resolved ParkioAlertingAcceptanceTest" 90 webhook_has ParkioAlertingAcceptanceTest resolved
RESOLVE_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "synthetic resolved delivered at ${RESOLVE_AT}"

log "simulate parking-service scrape failure (stop probe)"
STOP_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
"${COMPOSE[@]}" stop alerting-parking-probe
log "parking probe stopped at ${STOP_AT}"

wait_for "CoreServiceDown or ServiceDown firing for parking-service" 180 prom_parking_down_firing
wait_for "operator webhook received parking service-down firing" 90 webhook_has_service_down

log "restore parking-service scrape target"
"${COMPOSE[@]}" start alerting-parking-probe
for _i in $(seq 1 30); do
  if curl -fsS --max-time 3 "${PROM_URL}/api/v1/query" --data-urlencode 'query=up{job="parkio-services",service="parking-service"}' \
    | "${PYTHON}" -c 'import json,sys; d=json.load(sys.stdin); vals=d.get("data",{}).get("result",[]); raise SystemExit(0 if vals and float(vals[0]["value"][1])==1 else 1)'; then
    break
  fi
  sleep 2
done

wait_for "parking service-down no longer firing" 180 prom_parking_down_clear
RESTORE_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "parking probe restored; service-down inactive at ${RESTORE_AT}"

if prom_firing ParkioAlertingAcceptanceTest; then
  log "FAIL synthetic still firing after restore"
  exit 1
fi

# Delivery failures (U06, CL-F04): the alerts about alert delivery itself.
prom_firing_where() {
  local name="$1"
  local matcher="$2"
  curl -fsS --max-time 10 "${PROM_URL}/api/v1/query" \
    --data-urlencode "query=ALERTS{alertname=\"${name}\",alertstate=\"firing\",${matcher}}" \
    | "${PYTHON}" -c 'import json,sys; d=json.load(sys.stdin); raise SystemExit(0 if d.get("data",{}).get("result") else 1)'
}
webhook_rejected_any() {
  curl -fsS --max-time 10 "${WEBHOOK_URL}/rejected" \
    | "${PYTHON}" -c 'import json,sys; raise SystemExit(0 if json.load(sys.stdin).get("rejected", 0) > 0 else 1)'
}

for name in AlertmanagerNotificationsFailing PrometheusNotificationsFailing; do
  if prom_firing "${name}"; then
    log "FAIL ${name} firing while the receiver and Alertmanager are healthy"
    exit 1
  fi
done
log "healthy delivery: no delivery-failure alert firing"

# Heartbeat (U06, CL-F04): the always-firing Watchdog reaches only the heartbeat receiver.
log "heartbeat: wait for periodic Watchdog deliveries (repeat ${PARKIO_ALERT_HEARTBEAT_REPEAT})"
wait_for "at least 3 heartbeat deliveries" 150 heartbeat_at_least 3
if ! heartbeat_only_watchdog; then
  log "FAIL heartbeat receiver got something other than a firing Watchdog"
  exit 1
fi
if operator_has_watchdog; then
  log "FAIL Watchdog reached the operator receiver"
  exit 1
fi
HB_PERIODIC="$(heartbeat_count)"
log "heartbeat delivered periodically: ${HB_PERIODIC} Watchdog deliveries to the heartbeat receiver, none to the operator receiver"

log "receiver failure: the operator webhook now answers 503"
curl -fsS -X POST "${WEBHOOK_URL}/control/fail" >/dev/null
curl -fsS -X POST "${METRICS_URL}/arm" >/dev/null
wait_for "operator webhook rejected a delivery" 90 webhook_rejected_any
wait_for "AlertmanagerNotificationsFailing firing for integration=webhook" 240 \
  prom_firing_where AlertmanagerNotificationsFailing 'integration="webhook"'
log "receiver failure alert firing (AlertmanagerNotificationsFailing, integration=webhook)"

log "Alertmanager unreachable: stop it"
"${COMPOSE[@]}" stop alerting-alertmanager
sleep 10
HB_AM_STOPPED="$(heartbeat_count)"
# The rule holds for 5m, so this takes a little over five minutes.
wait_for "PrometheusNotificationsFailing firing" 600 prom_firing PrometheusNotificationsFailing
log "alertmanager unreachable alert firing (PrometheusNotificationsFailing)"

# Heartbeat (U06): no Alertmanager, no heartbeat.
HB_AM_LATER="$(heartbeat_count)"
if [ "${HB_AM_LATER}" -ne "${HB_AM_STOPPED}" ]; then
  log "FAIL heartbeat kept arriving while Alertmanager was stopped (${HB_AM_STOPPED} -> ${HB_AM_LATER})"
  exit 1
fi
log "heartbeat halted while Alertmanager is stopped (count ${HB_AM_STOPPED} unchanged over the outage)"

log "restart Alertmanager: the heartbeat must resume"
"${COMPOSE[@]}" start alerting-alertmanager
wait_for "Alertmanager ready after restart" 90 am_ready
wait_for "heartbeat resumed after Alertmanager restart" 120 heartbeat_at_least $((HB_AM_LATER + 1))
log "heartbeat resumed after Alertmanager restart"

log "stop Prometheus: the heartbeat must halt once Alertmanager times the Watchdog alert out"
"${COMPOSE[@]}" stop alerting-prometheus
# resolve_timeout (30s) plus one heartbeat period, then the count must stay frozen for two more periods.
sleep 60
HB_PROM_STOPPED="$(heartbeat_count)"
sleep 45
HB_PROM_LATER="$(heartbeat_count)"
if [ "${HB_PROM_LATER}" -ne "${HB_PROM_STOPPED}" ]; then
  log "FAIL heartbeat kept arriving after Prometheus was stopped (${HB_PROM_STOPPED} -> ${HB_PROM_LATER})"
  exit 1
fi
log "heartbeat halted after Prometheus stopped (count ${HB_PROM_STOPPED} unchanged over 45s)"
if operator_has_watchdog; then
  log "FAIL Watchdog reached the operator receiver"
  exit 1
fi

log "evidence written (credentials not included): ${EVIDENCE}"
echo "ALERTING_ACCEPTANCE_PASS"
