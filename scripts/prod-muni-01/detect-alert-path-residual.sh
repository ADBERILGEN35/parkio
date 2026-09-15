#!/usr/bin/env bash
#
# PROD-MUNI-01 / M7 — Alert-path residual detector (report only).
#
# Detects whether Azure hosted-beta disables Alertmanager / Loki / Promtail / Tempo.
# Does NOT enable observability. Never claims production alerting readiness.
#
# Usage:
#   ./scripts/prod-muni-01/detect-alert-path-residual.sh
#   ./scripts/prod-muni-01/detect-alert-path-residual.sh --json
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=lib/common.sh
source "$ROOT/scripts/prod-muni-01/lib/common.sh"
# shellcheck source=../lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"

cd "$ROOT"
JSON_OUT=0
[ "${1:-}" = "--json" ] && JSON_OUT=1

AZURE="docker/docker-compose.azure-hosted-beta.yml"
prod_muni_require_file "$AZURE"

parkio_configure_deployment_profile docker/.env.azure-hosted-beta.example

disabled_json="["
first=1
for svc in alertmanager loki promtail tempo; do
  state="unknown"
  if [[ " ${PARKIO_DISABLED_SERVICES[*]} " == *" $svc "* ]]; then
    state="disabled_by_profile"
  fi
  if grep -A2 "^  ${svc}:" "$AZURE" | grep -q 'azure-disabled-observability'; then
    state="disabled_by_profile"
  fi
  if [ "$first" -eq 1 ]; then first=0; else disabled_json+=","; fi
  disabled_json+="{\"service\":\"$svc\",\"state\":\"$state\"}"
done
disabled_json+="]"

# Exit 0 always for detector — residual is informational. Callers that require
# paging must treat residual=true as NOT production-alerting-ready.
residual=true
if [ "${#PARKIO_DISABLED_SERVICES[@]}" -eq 0 ]; then
  residual=false
fi

if [ "$JSON_OUT" -eq 1 ]; then
  cat <<EOF
{
  "artifact": "PROD-MUNI-01-M7",
  "residualAlertPath": $residual,
  "productionAlertingReady": false,
  "claim": "NEVER_CLAIM_PRODUCTION_ALERTING_READINESS",
  "disabledServices": $disabled_json,
  "profile": "azure-hosted-beta",
  "notes": "Alertmanager/Loki/Promtail/Tempo are on azure-disabled-observability. Municipal Prometheus rules may exist without paging delivery."
}
EOF
else
  echo "=== PROD-MUNI-01 M7 alert-path residual detector ==="
  echo "residualAlertPath=$residual"
  echo "productionAlertingReady=false"
  echo "claim=NEVER_CLAIM_PRODUCTION_ALERTING_READINESS"
  for svc in alertmanager loki promtail tempo; do
    echo "service=$svc state=disabled_by_profile"
  done
  echo "=== M7 OK (detector; residual recorded) ==="
fi
exit 0
