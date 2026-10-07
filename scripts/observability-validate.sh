#!/usr/bin/env bash
# Validate Prometheus config/rules, Alertmanager render, and promtool unit tests.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROM_IMAGE="${PROMETHEUS_IMAGE:-prom/prometheus:v2.54.1}"
AM_IMAGE="${ALERTMANAGER_IMAGE:-prom/alertmanager:v0.27.0}"

promtool() {
  docker run --rm --entrypoint /bin/promtool \
    -v "${ROOT}/docker/prometheus:/etc/prometheus:ro" \
    -w /etc/prometheus \
    "${PROM_IMAGE}" \
    "$@"
}

echo "==> promtool check config"
promtool check config /etc/prometheus/prometheus.yml
promtool check config /etc/prometheus/alerting-acceptance/prometheus.yml

echo "==> promtool check rules (all top-level rule files)"
shopt -s nullglob
for f in "${ROOT}/docker/prometheus/"*.yml; do
  base="$(basename "$f")"
  if [ "$base" = "prometheus.yml" ]; then
    continue
  fi
  echo "    check rules ${base}"
  promtool check rules "/etc/prometheus/${base}"
done

echo "==> promtool test rules"
promtool test rules /etc/prometheus/tests/alerts.test.yml
promtool test rules /etc/prometheus/tests/backup-invite.test.yml
promtool test rules /etc/prometheus/tests/backup-missing-telemetry.test.yml
promtool test rules /etc/prometheus/tests/municipal-source-health.test.yml
promtool test rules /etc/prometheus/tests/operational-readiness-availability.test.yml
promtool test rules /etc/prometheus/tests/blackbox-exporter.test.yml
promtool test rules /etc/prometheus/tests/alert-delivery.test.yml
promtool test rules /etc/prometheus/tests/heartbeat.test.yml

echo "==> Alertmanager templates reject unsupported | default"
if grep -E '\|[[:space:]]*default\b' "${ROOT}/docker/alertmanager/render-config.sh"; then
  echo "ERROR: render-config.sh uses Go-template | default (not in Alertmanager v0.27.0)" >&2
  exit 1
fi

echo "==> production path refuses a missing receiver"
if docker run --rm \
  -v "${ROOT}/docker/alertmanager:/etc/alertmanager:ro" \
  -e PARKIO_ALERT_REQUIRE_RECEIVER=true \
  -e PARKIO_ALERTMANAGER_VALIDATE_ONLY=1 \
  --entrypoint /bin/sh \
  "${AM_IMAGE}" \
  -c '/etc/alertmanager/render-config.sh'; then
  echo "ERROR: required receiver was missing and render-config.sh exited 0" >&2
  exit 1
fi

echo "==> production path accepts a placeholder Slack receiver"
docker run --rm \
  -v "${ROOT}/docker/alertmanager:/etc/alertmanager:ro" \
  -e PARKIO_ALERT_REQUIRE_RECEIVER=true \
  -e PARKIO_ALERT_SLACK_WEBHOOK_URL=https://example.invalid/hooks/test \
  -e PARKIO_ALERT_SLACK_CHANNEL='#test' \
  -e PARKIO_ALERTMANAGER_VALIDATE_ONLY=1 \
  --entrypoint /bin/sh \
  "${AM_IMAGE}" \
  -c '/etc/alertmanager/render-config.sh && amtool check-config /tmp/alertmanager.yml && grep -q "slack_configs:" /tmp/alertmanager.yml'

echo "==> Alertmanager check-config (null receiver / no webhook)"
docker run --rm \
  -v "${ROOT}/docker/alertmanager:/etc/alertmanager:ro" \
  -e PARKIO_ALERTMANAGER_VALIDATE_ONLY=1 \
  --entrypoint /bin/sh \
  "${AM_IMAGE}" \
  -c '/etc/alertmanager/render-config.sh && amtool check-config /tmp/alertmanager.yml'

echo "==> Alertmanager check-config (generic webhook, example.invalid)"
docker run --rm \
  -v "${ROOT}/docker/alertmanager:/etc/alertmanager:ro" \
  -e PARKIO_ALERT_WEBHOOK_URL=https://example.invalid/hooks/test \
  -e PARKIO_ALERTMANAGER_VALIDATE_ONLY=1 \
  --entrypoint /bin/sh \
  "${AM_IMAGE}" \
  -c '/etc/alertmanager/render-config.sh && amtool check-config /tmp/alertmanager.yml'

echo "==> Alertmanager check-config (Slack placeholder, example.invalid)"
docker run --rm \
  -v "${ROOT}/docker/alertmanager:/etc/alertmanager:ro" \
  -e PARKIO_ALERT_SLACK_WEBHOOK_URL=https://example.invalid/hooks/test \
  -e PARKIO_ALERT_SLACK_CHANNEL='#test' \
  -e PARKIO_ALERTMANAGER_VALIDATE_ONLY=1 \
  --entrypoint /bin/sh \
  "${AM_IMAGE}" \
  -c '/etc/alertmanager/render-config.sh && amtool check-config /tmp/alertmanager.yml'

# Heartbeat routing (U06): render with the given env and print the receiver amtool picks for the labels.
am_route() {
  local envargs=()
  while [ "$1" != "--" ]; do
    envargs+=(-e "$1")
    shift
  done
  shift
  docker run --rm \
    -v "${ROOT}/docker/alertmanager:/etc/alertmanager:ro" \
    "${envargs[@]}" \
    -e PARKIO_ALERTMANAGER_VALIDATE_ONLY=1 \
    --entrypoint /bin/sh \
    "${AM_IMAGE}" \
    -c "/etc/alertmanager/render-config.sh && amtool check-config /tmp/alertmanager.yml >/dev/null && amtool config routes test --config.file=/tmp/alertmanager.yml $*" \
    | tail -n 1
}

expect_route() {
  local want="$1"
  shift
  local got
  got="$(am_route "$@")"
  if [ "${got}" != "${want}" ]; then
    echo "ERROR: expected receiver '${want}', amtool picked '${got}' for: $*" >&2
    exit 1
  fi
  echo "    ${*##*-- } -> ${got}"
}

SLACK_ENV=(PARKIO_ALERT_SLACK_WEBHOOK_URL=https://example.invalid/hooks/test 'PARKIO_ALERT_SLACK_CHANNEL=#test')
HEARTBEAT_ENV=(PARKIO_ALERT_HEARTBEAT_URL=https://example.invalid/ping/test)

echo "==> heartbeat routing: without a heartbeat URL, Watchdog ends at the null receiver"
expect_route null -- alertname=Watchdog severity=heartbeat
expect_route null "${SLACK_ENV[@]}" -- alertname=Watchdog severity=heartbeat
expect_route critical "${SLACK_ENV[@]}" -- alertname=GatewayDown severity=critical
expect_route warning "${SLACK_ENV[@]}" -- alertname=HostDiskSpaceLow severity=warning

echo "==> heartbeat routing: with a heartbeat URL, only Watchdog goes to the heartbeat receiver"
expect_route heartbeat "${SLACK_ENV[@]}" "${HEARTBEAT_ENV[@]}" -- alertname=Watchdog severity=heartbeat
expect_route critical "${SLACK_ENV[@]}" "${HEARTBEAT_ENV[@]}" -- alertname=GatewayDown severity=critical
expect_route warning "${SLACK_ENV[@]}" "${HEARTBEAT_ENV[@]}" -- alertname=HostDiskSpaceLow severity=warning
expect_route heartbeat PARKIO_ALERT_WEBHOOK_URL=https://example.invalid/hooks/test "${HEARTBEAT_ENV[@]}" \
  PARKIO_ALERT_HEARTBEAT_SECRET=not-a-secret -- alertname=Watchdog severity=heartbeat

echo "==> heartbeat routing: a heartbeat alone leaves every operator route on the null receiver"
expect_route heartbeat "${HEARTBEAT_ENV[@]}" -- alertname=Watchdog severity=heartbeat
expect_route null "${HEARTBEAT_ENV[@]}" -- alertname=GatewayDown severity=critical
expect_route null "${HEARTBEAT_ENV[@]}" -- alertname=HostDiskSpaceLow severity=warning

echo "==> heartbeat receiver: send_resolved false, URL rendered once, operator receiver still required"
docker run --rm \
  -v "${ROOT}/docker/alertmanager:/etc/alertmanager:ro" \
  -e PARKIO_ALERT_SLACK_WEBHOOK_URL=https://example.invalid/hooks/test \
  -e PARKIO_ALERT_SLACK_CHANNEL='#test' \
  -e PARKIO_ALERT_HEARTBEAT_URL=https://example.invalid/ping/test \
  -e PARKIO_ALERT_HEARTBEAT_REPEAT=90s \
  -e PARKIO_ALERTMANAGER_VALIDATE_ONLY=1 \
  --entrypoint /bin/sh \
  "${AM_IMAGE}" \
  -c '/etc/alertmanager/render-config.sh && amtool check-config /tmp/alertmanager.yml >/dev/null \
      && grep -A3 "name: \"heartbeat\"" /tmp/alertmanager.yml | grep -q "send_resolved: false" \
      && [ "$(grep -c example.invalid/ping/test /tmp/alertmanager.yml)" = 1 ] \
      && grep -q "repeat_interval: 90s" /tmp/alertmanager.yml \
      && ! grep -q "example.invalid/ping/test.*hooks" /tmp/alertmanager.yml'
if docker run --rm \
  -v "${ROOT}/docker/alertmanager:/etc/alertmanager:ro" \
  -e PARKIO_ALERT_REQUIRE_RECEIVER=true \
  -e PARKIO_ALERT_HEARTBEAT_URL=https://example.invalid/ping/test \
  -e PARKIO_ALERTMANAGER_VALIDATE_ONLY=1 \
  --entrypoint /bin/sh \
  "${AM_IMAGE}" \
  -c '/etc/alertmanager/render-config.sh'; then
  echo "ERROR: a heartbeat URL alone satisfied PARKIO_ALERT_REQUIRE_RECEIVER" >&2
  exit 1
fi

echo "==> Alertmanager check-config (isolated acceptance config)"
docker run --rm \
  -v "${ROOT}/docker/prometheus/alerting-acceptance:/etc/alertmanager:ro" \
  --entrypoint /bin/amtool \
  "${AM_IMAGE}" \
  check-config /etc/alertmanager/alertmanager.yml

echo "Observability validation passed."
