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

# Regression: Docker Compose v2.24+ (Azure host runs Engine 29.x / Compose v5)
# omits inactive-profile services from the resolved model, so validation must
# not read `.services[<svc>].profiles` from `config --format json`. Reproduce
# that behavior with fixtures: the profile list still declares the disabled
# profile, while the default active service set contains only the 32 runtime
# services (the four disabled services are absent entirely).
grep -q 'profiles | index("azure-disabled-observability")' scripts/validate-hosted-beta-compose.sh \
  && fail "validate-hosted-beta-compose.sh still asserts profiles from the rendered JSON (incompatible with Compose v2.24+)"
profiles_fixture='azure-disabled-observability'
active_fixture="$(printf '%s\n' "${PARKIO_RUNTIME_SERVICES[@]}")"
for disabled in alertmanager loki promtail tempo; do
  echo "$active_fixture" | grep -qx "$disabled" && fail "fixture must reproduce inactive-profile omission for $disabled"
done
parkio_validate_azure_disabled_services "$profiles_fixture" "$active_fixture" >/dev/null 2>&1 \
  || fail "disabled-service validation must accept the Compose v2.24+ rendered model"
pass "disabled-service validation accepts a rendered model without inactive-profile services"

parkio_validate_azure_disabled_services '' "$active_fixture" >/dev/null 2>&1 \
  && fail "validation must fail when the azure-disabled-observability profile is missing"
parkio_validate_azure_disabled_services 'some-other-profile' "$active_fixture" >/dev/null 2>&1 \
  && fail "validation must fail when only unrelated profiles exist"
pass "validation fails closed when the disabled profile no longer exists"

for disabled in alertmanager loki promtail tempo; do
  parkio_validate_azure_disabled_services "$profiles_fixture" "$(printf '%s\n%s\n' "$active_fixture" "$disabled")" >/dev/null 2>&1 \
    && fail "validation must fail when $disabled is active in the default compose model"
done
pass "validation fails closed when a disabled service becomes active by default"

if (PARKIO_RUNTIME_SERVICES+=(tempo); parkio_validate_azure_disabled_services "$profiles_fixture" "$active_fixture" >/dev/null 2>&1); then
  fail "validation must fail when a disabled service enters the explicit Azure runtime target"
fi
pass "validation fails closed when a disabled service enters the runtime target"

tracing_overrides="$(grep -c 'PARKIO_TRACING_ENABLED: "false"' docker/docker-compose.azure-hosted-beta.yml)"
[ "$tracing_overrides" -eq 10 ] || fail "expected tracing=false on ten JVM services, got $tracing_overrides"
pass "tracing is disabled for all ten JVM services"

for flag in \
  PARKIO_MUNICIPAL_REGISTRY_CANDIDATE_GENERATION_ENABLED \
  PARKIO_MUNICIPAL_REGISTRY_REVIEW_API_ENABLED \
  PARKIO_MUNICIPAL_REGISTRY_REVIEWED_LINKING_ENABLED
do
  grep -q "$flag: \${$flag:-false}" docker/docker-compose.azure-hosted-beta.yml \
    || fail "parking-service missing default-false mapping for $flag"
done
grep -q 'PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED: ${PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED:-true}' \
  docker/docker-compose.azure-hosted-beta.yml \
  || fail "provenance publication must default true on Azure hosted-beta (DATA-WP-11)"
grep -q 'PARKIO_MUNICIPAL_REGISTRY_AUTOMATIC_LINKING_ENABLED: "false"' docker/docker-compose.azure-hosted-beta.yml \
  || fail "automatic linking must be hard-coded false in Azure compose"
pass "registry flags are mapped on parking-service with safe defaults"

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
