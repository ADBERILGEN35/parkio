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

echo "==> Alertmanager check-config (isolated acceptance config)"
docker run --rm \
  -v "${ROOT}/docker/prometheus/alerting-acceptance:/etc/alertmanager:ro" \
  --entrypoint /bin/amtool \
  "${AM_IMAGE}" \
  check-config /etc/alertmanager/alertmanager.yml

echo "Observability validation passed."
