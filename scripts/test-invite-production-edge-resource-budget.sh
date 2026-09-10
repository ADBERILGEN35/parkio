#!/usr/bin/env bash
# CI guard: resolved-compose edge resource budgets (PROD-DEPLOY-01B-02).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  if [ "${PARKIO_REQUIRE_COMPOSE_MODEL:-0}" = "1" ]; then
    echo "ERROR: docker compose is required for edge resource budget assertions" >&2
    exit 2
  fi
  echo "SKIP: docker compose unavailable for edge resource budget assertions"
  exit 0
fi

export PARKIO_REQUIRE_COMPOSE_MODEL=1
bash "$ROOT/scripts/assert-invite-production-edge-resource-budget.sh" \
  --env-file docker/.env.invite-production.example \
  --candidate-sha "$(git rev-parse HEAD)"

node --test scripts/lib/assert-invite-production-compose-ports.test.mjs
node --test scripts/test-invite-production-edge-resource-budget-wiring.test.mjs

echo "invite_production_edge_resource_budget_tests=PASS"
