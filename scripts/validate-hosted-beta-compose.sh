#!/usr/bin/env bash
# Static validation: render the full hosted-beta compose stack without starting containers.
#
# Usage (from repo root):
#   ./scripts/validate-hosted-beta-compose.sh
#   PARKIO_ENV_FILE=docker/.env ./scripts/validate-hosted-beta-compose.sh
#
# Exits non-zero if compose interpolation or merge fails.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${PARKIO_ENV_FILE:-docker/.env.hosted-beta.example}"
# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"

cd "$ROOT"

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: env file not found: $ENV_FILE" >&2
  exit 2
fi

export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-sha-validate}"
export PARKIO_GIT_SHA="${PARKIO_GIT_SHA:-validate}"
export PARKIO_IMAGE_CREATED="${PARKIO_IMAGE_CREATED:-1970-01-01T00:00:00Z}"

# hosted-beta.example may leave VITE_API_BASE_URL as a template; derive from PARKIO_DOMAIN when unset.
if [ -z "${VITE_API_BASE_URL:-}" ]; then
  domain="$(grep -E '^PARKIO_DOMAIN=' "$ENV_FILE" | head -1 | cut -d= -f2- || true)"
  if [ -n "$domain" ] && [[ "$domain" != *example.com* ]]; then
    export VITE_API_BASE_URL="https://${domain}/api/v1"
  else
    export VITE_API_BASE_URL="${VITE_API_BASE_URL:-https://api.beta.example.com/api/v1}"
  fi
fi

parkio_configure_deployment_profile "$ENV_FILE"

# Files consumed by Docker/nginx/Grafana/blackbox/bash must be UTF-8 (no UTF-16):
# a UTF-16 Dockerfile, nginx.conf, dashboard JSON or shell script fails at runtime
# with parse errors even though compose interpolation succeeds.
ENCODING_CHECK_FILES=(
  frontend/apps/web/Dockerfile
  frontend/apps/web/nginx.conf
  docker/blackbox/blackbox.yml
  docker/grafana/provisioning/dashboards/*.json
  scripts/*.sh
  .github/workflows/*.yml
)
encoding_errors=0
for pattern in "${ENCODING_CHECK_FILES[@]}"; do
  for f in $pattern; do
    [ -f "$f" ] || continue
    # UTF-16 (with or without BOM) shows up as NUL bytes in the first 200 bytes.
    if [ "$(head -c 200 "$f" | tr -cd '\0' | wc -c)" -gt 0 ]; then
      echo "ERROR: $f is not UTF-8 (contains NUL bytes — UTF-16?); Docker/nginx/bash cannot parse it" >&2
      encoding_errors=$((encoding_errors + 1))
    fi
  done
done
if [ "$encoding_errors" -gt 0 ]; then
  echo "ERROR: $encoding_errors file(s) with broken encoding" >&2
  exit 3
fi
echo "OK: config/script file encodings are UTF-8"

echo "=== validate-hosted-beta-compose ==="
echo "envFile=$ENV_FILE"
echo "deploymentProfile=$PARKIO_DEPLOYMENT_PROFILE"
echo "imageTag=$PARKIO_IMAGE_TAG"
echo "viteApi=$VITE_API_BASE_URL"
echo "composeFiles=$PARKIO_COMPOSE_FILES"
echo "runtimeServices=${PARKIO_RUNTIME_SERVICES[*]:-all}"
echo "disabledServices=${PARKIO_DISABLED_SERVICES[*]:-none}"

parkio_compose "$ENV_FILE" config --quiet
echo "OK: compose config rendered"

if [ "$PARKIO_DEPLOYMENT_PROFILE" = "azure-hosted-beta" ]; then
  rendered="$(mktemp)"
  trap 'rm -f "$rendered"' EXIT
  parkio_compose "$ENV_FILE" config --format json > "$rendered"

  [ "${#PARKIO_RUNTIME_SERVICES[@]}" -eq 32 ] || {
    echo "ERROR: Azure runtime service count must be 32, got ${#PARKIO_RUNTIME_SERVICES[@]}" >&2
    exit 4
  }
  for svc in "${PARKIO_RUNTIME_SERVICES[@]}"; do
    jq -e --arg svc "$svc" '.services[$svc] != null' "$rendered" >/dev/null || {
      echo "ERROR: Azure runtime service '$svc' missing from rendered config" >&2
      exit 4
    }
    jq -e --arg svc "$svc" '.services[$svc].platform == "linux/amd64"' "$rendered" >/dev/null || {
      echo "ERROR: Azure runtime service '$svc' does not enforce linux/amd64" >&2
      exit 4
    }
  done

  for svc in "${PARKIO_DISABLED_SERVICES[@]}"; do
    jq -e --arg svc "$svc" '.services[$svc].profiles | index("azure-disabled-observability") != null' "$rendered" >/dev/null || {
      echo "ERROR: disabled service '$svc' is not guarded by the Azure disabled profile" >&2
      exit 4
    }
  done

  for svc in gateway-service auth-service user-service parking-service media-service \
    gamification-service notification-service moderation-service ai-validation-service analytics-service; do
    jq -e --arg svc "$svc" '.services[$svc].environment.PARKIO_TRACING_ENABLED == "false"' "$rendered" >/dev/null || {
      echo "ERROR: tracing is not disabled for '$svc'" >&2
      exit 4
    }
  done

  total_memory=0
  for svc in "${PARKIO_RUNTIME_SERVICES[@]}"; do
    limit="$(jq -r --arg svc "$svc" '.services[$svc].mem_limit // 0' "$rendered")"
    total_memory=$((total_memory + limit))
  done
  max_memory=$((14 * 1024 * 1024 * 1024))
  [ "$total_memory" -le "$max_memory" ] || {
    echo "ERROR: Azure configured memory total $total_memory exceeds 14 GiB target $max_memory" >&2
    exit 4
  }

  jq -e '
    [.services | to_entries[] as $service | $service.value.ports[]? |
      select((.host_ip // "") != "127.0.0.1") |
      select(
        $service.key != "caddy" or
        (((.published | tostring) == "80" or (.published | tostring) == "443") | not)
      )
    ] | length == 0
  ' "$rendered" >/dev/null || {
    echo "ERROR: rendered Azure profile exposes a non-Caddy port beyond loopback" >&2
    exit 4
  }

  jq -e '
    [.services.caddy.ports[] | select((.host_ip // "") != "127.0.0.1") | .published | tostring]
    | unique | sort == ["443", "80"]
  ' "$rendered" >/dev/null || {
    echo "ERROR: Caddy must be the only public service and publish exactly 80 and 443" >&2
    exit 4
  }

  jq -e '.services.prometheus.command | index("--storage.tsdb.retention.time=7d") != null' "$rendered" >/dev/null
  jq -e '.services.grafana.depends_on | keys == ["prometheus"]' "$rendered" >/dev/null
  echo "OK: Azure runtime services=32 disabled=4 memoryBytes=$total_memory publicPorts=80,443 tracing=false"
fi
