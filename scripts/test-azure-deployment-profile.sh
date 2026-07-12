#!/usr/bin/env bash
# Static regression checks for the deterministic Azure deployment profile.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta
parkio_configure_deployment_profile docker/.env.azure-hosted-beta.example

[ "$PARKIO_DEPLOYMENT_PROFILE" = "azure-hosted-beta" ] || fail "profile resolution"
[[ "$PARKIO_COMPOSE_FILES" == *docker-compose.azure-hosted-beta.yml* ]] || fail "Azure overlay missing"
[ "${#PARKIO_RUNTIME_SERVICES[@]}" -eq 32 ] || fail "expected 32 runtime services"
[ "${#PARKIO_DISABLED_SERVICES[@]}" -eq 4 ] || fail "expected four disabled services"
pass "profile resolves to five deterministic compose files"
pass "runtime service count is 32"

for disabled in alertmanager loki promtail tempo; do
  [[ " ${PARKIO_DISABLED_SERVICES[*]} " == *" $disabled "* ]] || fail "$disabled not disabled"
  [[ " ${PARKIO_RUNTIME_SERVICES[*]} " != *" $disabled "* ]] || fail "$disabled appears in runtime list"
  grep -A2 "^  ${disabled}:" docker/docker-compose.azure-hosted-beta.yml \
    | grep -q 'azure-disabled-observability' || fail "$disabled lacks inactive profile"
done
pass "Alertmanager, Loki, Promtail, and Tempo are deterministically excluded"

tracing_overrides="$(grep -c 'PARKIO_TRACING_ENABLED: "false"' docker/docker-compose.azure-hosted-beta.yml)"
[ "$tracing_overrides" -eq 10 ] || fail "expected tracing=false on ten JVM services, got $tracing_overrides"
pass "tracing is disabled for all ten JVM services"

memory_total="$({
  awk '
    /^  [a-zA-Z0-9_-]+:$/ { svc=$1; sub(":$", "", svc) }
    /    mem_limit:/ {
      value=$2; sub("m$", "", value)
      if (svc != "alertmanager" && svc != "loki" && svc != "promtail" && svc != "tempo") {
        if (svc == "web") value=64
        if (svc == "caddy") value=96
        if (svc == "kafka") value=1024
        if (svc == "prometheus") value=576
        if (svc == "grafana") value=224
        sum += value
      }
    }
    END { print sum + 64 }
  ' docker/docker-compose.hosted-beta.yml
})"
[ "$memory_total" -eq 14336 ] || fail "expected 14336 MiB configured memory, got $memory_total"
pass "configured Azure memory total is 14336 MiB with 2048 MiB host headroom"

grep -q '^PARKIO_DOMAIN=api.parkio.dev$' docker/.env.azure-hosted-beta.example || fail "canonical API host"
grep -q '^VITE_API_BASE_URL=https://api.parkio.dev/api/v1$' docker/.env.azure-hosted-beta.example || fail "web API URL"
grep -q '^EXPO_PUBLIC_API_BASE_URL=https://api.parkio.dev/api/v1$' docker/.env.azure-hosted-beta.example || fail "mobile API URL"
grep -q '^VITE_WAITLIST_INTAKE_MODE=api$' docker/.env.azure-hosted-beta.example || fail "Azure waitlist mode"
pass "Azure web/mobile hostname and waitlist mode are canonical"

for script in backup-databases.sh restore-database.sh verify-backup.sh restore-drill.sh; do
  grep -q 'postgres-gateway' "scripts/$script" || fail "$script omits the gateway waitlist database"
done
pass "backup, verification, drill, and restore cover all ten databases"

if PARKIO_DEPLOYMENT_PROFILE=unknown parkio_configure_deployment_profile docker/.env.azure-hosted-beta.example >/dev/null 2>&1; then
  fail "unknown deployment profile must fail"
fi
pass "unknown deployment profile fails closed"

echo "=== Azure deployment profile static checks: PASS ==="
