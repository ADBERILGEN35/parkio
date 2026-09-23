#!/usr/bin/env bash
# Narrow production deploy guard: reject the known CI synthetic MapTiler
# configuration. Mock CI image acceptance does not call this script.
#
# Usage:
#   ./scripts/guard-web-synthetic-map-deploy.sh \
#     --env-file docker/.env.azure-hosted-beta \
#     --pin-file docker/docker-compose.web-release-pin.yml
#
# Exit 0 = pass, 1 = blocked, 2 = usage.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE=""
PIN_FILE="$ROOT/docker/docker-compose.web-release-pin.yml"
SYNTHETIC_KEY="ci-web-build-security-synthetic"
BAD_DIGEST="sha256:8d9bfca43d577afd62d20f7ffe3fcb2566fbf3bce9dbd758368562aec641487d"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --pin-file) PIN_FILE="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

fail() {
  echo "ERROR: $1" >&2
  exit 1
}

if [ -n "$ENV_FILE" ]; then
  if [ ! -f "$ENV_FILE" ]; then
    fail "env file not found: $ENV_FILE"
  fi
  if grep -q "^VITE_MAPTILER_KEY=${SYNTHETIC_KEY}$" "$ENV_FILE"; then
    fail "VITE_MAPTILER_KEY is the known CI synthetic test map key; refusing deploy"
  fi
fi

if [ ! -f "$PIN_FILE" ]; then
  fail "web pin file not found: $PIN_FILE"
fi

if grep -q "$BAD_DIGEST" "$PIN_FILE"; then
  fail "web pin still names the synthetic-key image ${BAD_DIGEST}; refusing deploy"
fi

if grep -q "$SYNTHETIC_KEY" "$PIN_FILE"; then
  fail "web pin file mentions the CI synthetic MapTiler key; refusing deploy"
fi

echo "web-map-deploy-guard: PASS (synthetic CI map configuration not selected)"
exit 0
