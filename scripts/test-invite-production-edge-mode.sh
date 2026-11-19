#!/usr/bin/env bash
# CI guard: invite-production edge mode contract (PROD-DEPLOY-01B-01).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

bash "$ROOT/scripts/validate-invite-production-edge.sh" --env-file docker/.env.invite-production.example --mode dark
bash "$ROOT/scripts/validate-invite-production-edge.sh" --env-file docker/.env.invite-production.example --mode public

# Gateway runtime identity must resolve for dark, public-staged, and public
# candidate (PROD-DEPLOY-01B-02D). This is the regression for the 01B-02C
# local/unknown smoke failure.
bash "$ROOT/scripts/assert-invite-production-runtime-identity.sh" \
  --env-file docker/.env.invite-production.example

# Explicit CLOSED registration + public actuator false (PROD-DEPLOY-01B-03B).
bash "$ROOT/scripts/assert-invite-production-public-surface.sh" \
  --env-file docker/.env.invite-production.example

# HSTS staging contract in Caddyfile
grep -q 'PARKIO_HSTS_HEADER_VALUE' docker/caddy/Caddyfile

# Public smoke harness must remain opt-in
if bash "$ROOT/scripts/public-invite-smoke.sh" 2>/dev/null; then
  echo "ERROR: public smoke must fail without PARKIO_PUBLIC_SMOKE_CONFIRM" >&2
  exit 1
fi

echo "invite_production_edge_mode_tests=PASS"
