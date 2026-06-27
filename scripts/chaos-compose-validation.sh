#!/usr/bin/env bash
#
# Parkio — Docker Compose chaos validation for local/CI runtime hardening.
#
# This is an operational recovery drill, not a load test. It temporarily stops one
# dependency or app container at a time, restarts it, and waits for the full required
# stack to recover to healthy before moving to the next scenario.
#
# Usage:
#   PARKIO_CHAOS_CONFIRM=local-or-ci ./scripts/chaos-compose-validation.sh \
#     --env-file docker/.env.runtime-validation
#
# Optional:
#   --artifact-dir <dir>       default: chaos-validation-artifacts
#   --settle-seconds <n>       default: 10
#   --recovery-timeout <n>     default: 600

set -euo pipefail

ENV_FILE="${PARKIO_ENV_FILE:-docker/.env.hosted-beta.example}"
ARTIFACT_DIR="chaos-validation-artifacts"
SETTLE_SECONDS="10"
RECOVERY_TIMEOUT="600"
COMPOSE_FILES=(-f docker/docker-compose.yml -f docker/docker-compose.apps.yml -f docker/docker-compose.hosted-beta.yml)

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --artifact-dir) ARTIFACT_DIR="${2:-}"; shift 2 ;;
    --settle-seconds) SETTLE_SECONDS="${2:-}"; shift 2 ;;
    --recovery-timeout) RECOVERY_TIMEOUT="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [ "${PARKIO_CHAOS_CONFIRM:-}" != "local-or-ci" ]; then
  echo "ERROR: refusing to run chaos validation without PARKIO_CHAOS_CONFIRM=local-or-ci." >&2
  echo "       This script stops containers and is intended only for local/CI compose stacks." >&2
  exit 2
fi

if [ ! -f "${ENV_FILE}" ]; then
  echo "ERROR: env file not found: ${ENV_FILE}" >&2
  exit 2
fi

mkdir -p "${ARTIFACT_DIR}/logs"

compose() {
  docker compose --env-file "${ENV_FILE}" "${COMPOSE_FILES[@]}" "$@"
}

container_status() {
  local svc="$1" cid
  cid="$(compose ps -q "${svc}")"
  if [ -z "${cid}" ]; then
    echo "missing"
    return
  fi
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${cid}"
}

wait_healthy() {
  local svc="$1" deadline status
  deadline=$((SECONDS + RECOVERY_TIMEOUT))
  while true; do
    status="$(container_status "${svc}")"
    echo "${svc} status=${status}"
    if [ "${status}" = "healthy" ]; then
      return 0
    fi
    if [ "${SECONDS}" -ge "${deadline}" ]; then
      echo "ERROR: ${svc} did not recover to healthy within ${RECOVERY_TIMEOUT}s." >&2
      return 1
    fi
    sleep 10
  done
}

required_services=(
  kafka redis minio clamav caddy
  postgres-auth postgres-user postgres-parking postgres-media postgres-gamification
  postgres-notification postgres-moderation postgres-analytics postgres-ai-validation
  gateway-service auth-service user-service parking-service media-service
  gamification-service notification-service moderation-service ai-validation-service analytics-service
  prometheus grafana loki promtail alertmanager
)

wait_required_stack() {
  local failed
  while true; do
    failed=0
    : > "${ARTIFACT_DIR}/health.tsv"
    for svc in "${required_services[@]}"; do
      status="$(container_status "${svc}")"
      printf '%s\t%s\n' "${svc}" "${status}" | tee -a "${ARTIFACT_DIR}/health.tsv"
      if [ "${status}" != "healthy" ]; then
        failed=1
      fi
    done
    if [ "${failed}" -eq 0 ]; then
      return 0
    fi
    if [ "${SECONDS}" -ge "${STACK_DEADLINE}" ]; then
      echo "ERROR: required stack did not recover to healthy." >&2
      return 1
    fi
    sleep 10
  done
}

collect_artifacts() {
  local label="$1"
  compose ps > "${ARTIFACT_DIR}/compose-ps-${label}.txt" 2>&1 || true
  compose ps --format json > "${ARTIFACT_DIR}/compose-ps-${label}.json" 2>&1 || true
  for svc in $(compose config --services); do
    compose logs --no-color --timestamps "${svc}" > "${ARTIFACT_DIR}/logs/${label}-${svc}.log" 2>&1 || true
  done
}

run_scenario() {
  local label="$1" svc="$2"
  echo "== chaos scenario: ${label} (${svc}) =="
  collect_artifacts "before-${label}"
  compose stop "${svc}"
  sleep "${SETTLE_SECONDS}"
  printf '%s\t%s\n' "${svc}" "$(container_status "${svc}")" | tee -a "${ARTIFACT_DIR}/scenario-status.tsv"
  compose up -d "${svc}"
  wait_healthy "${svc}"
  STACK_DEADLINE=$((SECONDS + RECOVERY_TIMEOUT))
  wait_required_stack
  collect_artifacts "after-${label}"
}

echo "Rendering compose config ..."
compose config > "${ARTIFACT_DIR}/compose-config.yml"
compose config --quiet

STACK_DEADLINE=$((SECONDS + RECOVERY_TIMEOUT))
wait_required_stack

run_scenario "kafka-unavailable" "kafka"
run_scenario "redis-unavailable" "redis"
run_scenario "postgres-unavailable" "postgres-parking"
run_scenario "minio-unavailable" "minio"
run_scenario "gateway-unavailable" "gateway-service"
run_scenario "notification-service-unavailable" "notification-service"
run_scenario "analytics-service-unavailable" "analytics-service"

echo "PASS: compose chaos validation scenarios recovered to healthy."
