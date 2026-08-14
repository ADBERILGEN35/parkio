#!/usr/bin/env bash
# Isolated Prometheus → Alertmanager → Slack operator channel.
# Requires PARKIO_ALERT_SLACK_WEBHOOK_URL in the environment (GitHub Actions secret).
# Never prints, dumps, or writes the webhook value.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE=(docker compose -f "${ROOT}/docker/docker-compose.alerting-acceptance.yml" --project-name parkio-alert-operator)
PROM_URL="${PARKIO_ALERT_ACCEPT_PROM_URL:-http://127.0.0.1:19090}"
AM_URL="${PARKIO_ALERT_ACCEPT_AM_URL:-http://127.0.0.1:19093}"
METRICS_URL="${PARKIO_ALERT_ACCEPT_METRICS_URL:-http://127.0.0.1:18081}"
EVIDENCE="${PARKIO_ALERT_OPERATOR_EVIDENCE:-${ROOT}/docker/.alerting-acceptance-receipts/operator-evidence.txt}"
PYTHON="${PYTHON:-python3}"
SLACK_SETTLE_SECONDS="${PARKIO_ALERT_SLACK_SETTLE_SECONDS:-45}"

if [ -z "${PARKIO_ALERT_SLACK_WEBHOOK_URL:-}" ]; then
  echo "BLOCKED — OPERATOR NOTIFICATION SECRET NOT AVAILABLE" >&2
  exit 2
fi
export PARKIO_ALERT_SLACK_CHANNEL="${PARKIO_ALERT_SLACK_CHANNEL:-#parkio-alert}"
# Slack receiver takes precedence; do not also send to the catcher.
unset PARKIO_ALERT_WEBHOOK_URL || true
unset PARKIO_ALERT_WEBHOOK_SECRET || true

mkdir -p "$(dirname "${EVIDENCE}")"
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
  curl -fsS --max-time 10 "${PROM_URL}/api/v1/query" \
    --data-urlencode 'query=ALERTS{alertname="ParkioAlertingAcceptanceTest",alertstate="firing"}' \
    | "${PYTHON}" -c 'import json,sys; d=json.load(sys.stdin); raise SystemExit(0 if d.get("data",{}).get("result") else 1)'
}

prom_not_firing() {
  curl -fsS --max-time 10 "${PROM_URL}/api/v1/query" \
    --data-urlencode 'query=ALERTS{alertname="ParkioAlertingAcceptanceTest",alertstate="firing"}' \
    | "${PYTHON}" -c 'import json,sys; d=json.load(sys.stdin); raise SystemExit(0 if not d.get("data",{}).get("result") else 1)'
}

am_active() {
  curl -fsS --max-time 10 "${AM_URL}/api/v2/alerts" \
    | "${PYTHON}" -c 'import json,sys; alerts=json.load(sys.stdin); raise SystemExit(0 if any((a.get("labels") or {}).get("alertname")=="ParkioAlertingAcceptanceTest" and (a.get("status") or {}).get("state")=="active" for a in alerts) else 1)'
}

am_not_active() {
  curl -fsS --max-time 10 "${AM_URL}/api/v2/alerts" \
    | "${PYTHON}" -c 'import json,sys; alerts=json.load(sys.stdin); raise SystemExit(0 if not any((a.get("labels") or {}).get("alertname")=="ParkioAlertingAcceptanceTest" and (a.get("status") or {}).get("state")=="active" for a in alerts) else 1)'
}

am_critical_receiver() {
  curl -fsS --max-time 10 "${AM_URL}/api/v2/alerts" \
    | "${PYTHON}" -c 'import json,sys; alerts=json.load(sys.stdin); raise SystemExit(0 if any((a.get("labels") or {}).get("alertname")=="ParkioAlertingAcceptanceTest" and "critical" in (a.get("receivers") or []) for a in alerts) else 1)'
}

am_metric_sum() {
  local metric="$1"
  curl -fsS --max-time 10 "${AM_URL}/metrics" \
    | "${PYTHON}" -c '
import re,sys
metric=sys.argv[1]
total=0.0
found=False
for line in sys.stdin:
    if line.startswith("#"):
        continue
    if not line.startswith(metric):
        continue
    if "integration=\"slack\"" not in line:
        continue
    found=True
    total += float(line.split()[-1])
print(int(total) if found else 0)
' "${metric}"
}

log "start operator Slack acceptance (webhook value not logged)"
log "channel_env=${PARKIO_ALERT_SLACK_CHANNEL}"
"${COMPOSE[@]}" up -d --wait --wait-timeout 120

log "baseline health"
curl -fsS --max-time 10 "${PROM_URL}/-/healthy" >/dev/null
curl -fsS --max-time 10 "${AM_URL}/-/healthy" >/dev/null
curl -fsS -X POST "${METRICS_URL}/disarm" >/dev/null
sleep 8
if prom_firing; then
  log "FAIL synthetic firing before arm"
  exit 1
fi
log "baseline ParkioAlertingAcceptanceTest inactive; Prometheus and Alertmanager healthy"

NOTIFY_BEFORE="$(am_metric_sum alertmanager_notifications_total || echo 0)"
FAILED_BEFORE="$(am_metric_sum alertmanager_notifications_failed_total || echo 0)"
log "slack_notifications_total_before=${NOTIFY_BEFORE} failed_before=${FAILED_BEFORE}"

log "arm synthetic alert"
ARM_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
curl -fsS -X POST "${METRICS_URL}/arm" >/dev/null
log "synthetic armed at ${ARM_AT}"

wait_for "ParkioAlertingAcceptanceTest firing in Prometheus" 90 prom_firing
PROM_FIRE_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "prometheus firing at ${PROM_FIRE_AT}"

wait_for "Alertmanager active ParkioAlertingAcceptanceTest" 60 am_active
wait_for "Alertmanager routed to critical receiver" 30 am_critical_receiver
AM_FIRE_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "alertmanager firing at ${AM_FIRE_AT} receiver=critical"

log "wait ${SLACK_SETTLE_SECONDS}s for Slack delivery (group_wait + notify)"
sleep "${SLACK_SETTLE_SECONDS}"
NOTIFY_AFTER_FIRE="$(am_metric_sum alertmanager_notifications_total || echo 0)"
FAILED_AFTER_FIRE="$(am_metric_sum alertmanager_notifications_failed_total || echo 0)"
log "slack_notifications_total_after_fire=${NOTIFY_AFTER_FIRE} failed_after_fire=${FAILED_AFTER_FIRE}"
if [ "${FAILED_AFTER_FIRE}" -gt "${FAILED_BEFORE}" ]; then
  log "FAIL Alertmanager recorded Slack notification failures"
  exit 1
fi
if [ "${NOTIFY_AFTER_FIRE}" -le "${NOTIFY_BEFORE}" ]; then
  log "FAIL Alertmanager did not increment Slack notification success counter"
  exit 1
fi
SLACK_FIRE_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "alertmanager slack notify success after firing at ${SLACK_FIRE_AT}"
log "HUMAN_GATE: reply FIRING RECEIVED after checking #parkio-alert"

log "disarm synthetic alert"
DISARM_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
curl -fsS -X POST "${METRICS_URL}/disarm" >/dev/null
log "synthetic disarmed at ${DISARM_AT}"

wait_for "ParkioAlertingAcceptanceTest inactive in Prometheus" 90 prom_not_firing
PROM_CLEAR_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "prometheus inactive at ${PROM_CLEAR_AT}"

wait_for "Alertmanager ParkioAlertingAcceptanceTest not active" 90 am_not_active
AM_RESOLVE_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "alertmanager resolved at ${AM_RESOLVE_AT}"

log "wait ${SLACK_SETTLE_SECONDS}s for Slack resolved notification"
sleep "${SLACK_SETTLE_SECONDS}"
NOTIFY_AFTER_RESOLVE="$(am_metric_sum alertmanager_notifications_total || echo 0)"
FAILED_AFTER_RESOLVE="$(am_metric_sum alertmanager_notifications_failed_total || echo 0)"
log "slack_notifications_total_after_resolve=${NOTIFY_AFTER_RESOLVE} failed_after_resolve=${FAILED_AFTER_RESOLVE}"
if [ "${FAILED_AFTER_RESOLVE}" -gt "${FAILED_AFTER_FIRE}" ]; then
  log "FAIL Alertmanager recorded Slack notification failures on resolve"
  exit 1
fi
if [ "${NOTIFY_AFTER_RESOLVE}" -le "${NOTIFY_AFTER_FIRE}" ]; then
  log "FAIL Alertmanager did not increment Slack notification counter on resolve"
  exit 1
fi
SLACK_RESOLVE_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "alertmanager slack notify success after resolve at ${SLACK_RESOLVE_AT}"
log "HUMAN_GATE: reply RESOLVED RECEIVED after checking #parkio-alert"

if prom_firing; then
  log "FAIL synthetic still firing after disarm"
  exit 1
fi

log "OPERATOR_SLACK_NOTIFY_PASS (human confirmation still required)"
echo "OPERATOR_SLACK_NOTIFY_PASS"
