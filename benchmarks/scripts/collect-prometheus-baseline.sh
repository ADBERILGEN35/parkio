#!/usr/bin/env bash
set -euo pipefail

PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
OUT_DIR="${1:-benchmarks/reports/prometheus-baseline}"

mkdir -p "$OUT_DIR"

query() {
  local name="$1"
  local expr="$2"
  local encoded
  encoded="$(python3 -c 'import sys, urllib.parse; print(urllib.parse.urlencode({"query": sys.argv[1]}))' "$expr")"
  curl -fsS "${PROMETHEUS_URL}/api/v1/query?${encoded}" > "${OUT_DIR}/${name}.json"
}

query "http-avg-by-service" 'sum by (service, uri) (rate(http_server_requests_seconds_sum[5m])) / clamp_min(sum by (service, uri) (rate(http_server_requests_seconds_count[5m])), 0.001)'
query "http-max-by-service" 'max by (service, uri) (http_server_requests_seconds_max)'
query "http-p95-by-service-if-histograms-enabled" 'histogram_quantile(0.95, sum by (service, uri, le) (rate(http_server_requests_seconds_bucket[5m])))'
query "http-error-rate-by-service" 'sum by (service, status) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))'
query "hikari-pending" 'max by (service, pool) (hikaricp_connections_pending)'
query "hikari-active" 'max by (service, pool) (hikaricp_connections_active)'
query "jvm-heap-used" 'sum by (service) (jvm_memory_used_bytes{area="heap"})'
query "jvm-gc-pause-max" 'max by (service, action, cause) (jvm_gc_pause_seconds_max)'
query "jvm-gc-pause-p95-if-histograms-enabled" 'histogram_quantile(0.95, sum by (service, le) (rate(jvm_gc_pause_seconds_bucket[5m])))'
query "kafka-consumer-lag" 'max by (consumergroup, topic) (kafka_consumergroup_lag{consumergroup=~".*-service",topic=~"parkio\\..+"})'
query "outbox-oldest-unpublished-age" 'max by (service) (parkio_outbox_oldest_unpublished_age_seconds)'
query "outbox-publish-avg" 'sum by (service) (rate(parkio_outbox_publish_duration_seconds_sum[5m])) / clamp_min(sum by (service) (rate(parkio_outbox_publish_duration_seconds_count[5m])), 0.001)'
query "outbox-publish-max" 'max by (service) (parkio_outbox_publish_duration_seconds_max)'
query "outbox-publish-p95-if-histograms-enabled" 'histogram_quantile(0.95, sum by (service, le) (rate(parkio_outbox_publish_duration_seconds_bucket[5m])))'
query "outbox-batch-size-avg" 'sum by (service) (rate(parkio_outbox_batch_size_sum[5m])) / clamp_min(sum by (service) (rate(parkio_outbox_batch_size_count[5m])), 0.001)'

printf 'Prometheus baseline written to %s\n' "$OUT_DIR"
