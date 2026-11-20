#!/usr/bin/env bash
#
# PROD-MUNI-01 / M4 — Production municipal dark-pin gate (executable).
#
# Validates production profile / compose / env overlays keep municipal production
# controls dark. Does not change any policy values.
#
# Usage:
#   ./scripts/prod-muni-01/guard-production-dark-pins.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=lib/common.sh
source "$ROOT/scripts/prod-muni-01/lib/common.sh"

cd "$ROOT"
fail=0
ok() { prod_muni_pass "$1"; }
bad() { prod_muni_fail "$1" || true; fail=$((fail + 1)); }

echo "=== PROD-MUNI-01 M4 production dark-pin gate ==="

PROD_YML="services/parking-service/src/main/resources/application-prod.yml"
APP_YML="services/parking-service/src/main/resources/application.yml"
AZURE="docker/docker-compose.azure-hosted-beta.yml"
prod_muni_require_file "$PROD_YML"
prod_muni_require_file "$APP_YML"
prod_muni_require_file "$AZURE"

# application-prod.yml env-default pins must stay false (dark).
for pin in \
  'duplicate-presentation-enabled: ${PARKIO_MUNICIPAL_DISCOVERY_DUPLICATE_PRESENTATION_ENABLED:false}' \
  'provenance-publication-enabled: ${PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED:false}' \
  'quality-report-enabled: ${PARKIO_MUNICIPAL_OPS_QUALITY_REPORT_ENABLED:false}' \
  'source-mode-sla-enabled: ${PARKIO_MUNICIPAL_OPS_SOURCE_MODE_SLA_ENABLED:false}' \
  'district-coverage-enabled: ${PARKIO_MUNICIPAL_OPS_DISTRICT_COVERAGE_ENABLED:false}'
do
  if grep -qF "$pin" "$PROD_YML"; then
    ok "application-prod.yml pins $pin"
  else
    bad "application-prod.yml missing dark pin: $pin"
  fi
done

# Canonical application.yml: linking hard-false; OSM publication false; İZELMAN publication defaults false.
if grep -qE '^[[:space:]]*automatic-linking-enabled: false$' "$APP_YML"; then
  ok "application.yml automatic-linking-enabled hard false"
else
  bad "application.yml must hard-code automatic-linking-enabled: false"
fi

if grep -qE '^[[:space:]]*publication-enabled: false$' "$APP_YML"; then
  ok "application.yml OSM publication-enabled false"
else
  bad "application.yml OSM publication-enabled must be false"
fi

for iz in \
  'facility-publication-enabled: ${PARKIO_MUNICIPAL_IZELMAN_FACILITY_PUBLICATION_ENABLED:false}' \
  'roadside-publication-enabled: ${PARKIO_MUNICIPAL_IZELMAN_ROADSIDE_PUBLICATION_ENABLED:false}' \
  'tariff-publication-enabled: ${PARKIO_MUNICIPAL_IZELMAN_TARIFF_PUBLICATION_ENABLED:false}'
do
  if grep -qF "$iz" "$APP_YML"; then
    ok "application.yml İZELMAN $iz"
  else
    bad "application.yml missing İZELMAN dark default: $iz"
  fi
done

# Azure overlay: linking / İZELMAN / OSM publication stay false (hard or :-false).
for flag in \
  'PARKIO_MUNICIPAL_REGISTRY_AUTOMATIC_LINKING_ENABLED: "false"' \
  'PARKIO_MUNICIPAL_IZELMAN_AUTO_MATCH_ENABLED: "false"' \
  'PARKIO_MUNICIPAL_OSM_PUBLICATION_ENABLED: ${PARKIO_MUNICIPAL_OSM_PUBLICATION_ENABLED:-false}' \
  'PARKIO_MUNICIPAL_IZELMAN_FACILITY_PUBLICATION_ENABLED: ${PARKIO_MUNICIPAL_IZELMAN_FACILITY_PUBLICATION_ENABLED:-false}' \
  'PARKIO_MUNICIPAL_IZELMAN_ROADSIDE_PUBLICATION_ENABLED: ${PARKIO_MUNICIPAL_IZELMAN_ROADSIDE_PUBLICATION_ENABLED:-false}' \
  'PARKIO_MUNICIPAL_IZELMAN_TARIFF_PUBLICATION_ENABLED: ${PARKIO_MUNICIPAL_IZELMAN_TARIFF_PUBLICATION_ENABLED:-false}' \
  'PARKIO_MUNICIPAL_REGISTRY_REVIEWED_LINKING_ENABLED: ${PARKIO_MUNICIPAL_REGISTRY_REVIEWED_LINKING_ENABLED:-false}' \
  'PARKIO_MUNICIPAL_REGISTRY_CANDIDATE_GENERATION_ENABLED: ${PARKIO_MUNICIPAL_REGISTRY_CANDIDATE_GENERATION_ENABLED:-false}'
do
  if grep -qF "$flag" "$AZURE"; then
    ok "azure overlay $flag"
  else
    bad "azure overlay missing dark control: $flag"
  fi
done

# Web municipal discovery bake default remains false in image compose (production channel).
if grep -q 'VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED: ${VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED:-false}' \
  docker/docker-compose.images.yml; then
  ok "images compose municipal discovery default false"
else
  bad "docker-compose.images.yml municipal discovery must default false"
fi

# Env examples must not advertise production municipal on.
for envf in docker/.env.azure-hosted-beta.example docker/.env.hosted-beta.example; do
  if grep -qE '^VITE_APP_ENV=production$' "$envf" \
    && grep -qE '^VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true$' "$envf"; then
    bad "$envf pairs production app env with municipal=true"
  else
    ok "$envf does not pair production + municipal=true"
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "=== M4 FAILED ($fail) ===" >&2
  exit 1
fi
echo "=== M4 OK ==="
exit 0
