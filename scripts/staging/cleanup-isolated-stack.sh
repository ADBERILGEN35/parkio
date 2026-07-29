#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/lib/safety-guards.sh"

if [ -z "${COMPOSE_PROJECT_NAME:-}" ]; then
  echo "SKIP: no COMPOSE_PROJECT_NAME — nothing to clean"
  exit 0
fi
assert_compose_project_isolated

ENV_FILE="${PARKIO_ENV_FILE:-${ROOT_DIR}/docker/.env}"
COMPOSE=(docker compose --project-name "${COMPOSE_PROJECT_NAME}")
if [ -f "${ENV_FILE}" ]; then
  COMPOSE+=(--env-file "${ENV_FILE}")
fi
COMPOSE+=(-f "${ROOT_DIR}/docker/docker-compose.yml")
if [ -f "${ROOT_DIR}/docker/docker-compose.apps.yml" ]; then
  COMPOSE+=(-f "${ROOT_DIR}/docker/docker-compose.apps.yml")
fi
if [ -f "${ROOT_DIR}/docker/docker-compose.restored-application-verification.yml" ]; then
  COMPOSE+=(-f "${ROOT_DIR}/docker/docker-compose.restored-application-verification.yml")
elif [ -f "${ROOT_DIR}/docker/docker-compose.staging-verification.yml" ]; then
  COMPOSE+=(-f "${ROOT_DIR}/docker/docker-compose.staging-verification.yml")
fi

"${COMPOSE[@]}" down -v --remove-orphans 2>/dev/null || true
echo "cleaned compose project ${COMPOSE_PROJECT_NAME}"