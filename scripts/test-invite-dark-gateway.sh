#!/usr/bin/env bash
#
# PROD-DEPLOY-01A-R3 / D1 regression tests.
#
# Two things must stay true for dark acceptance to be trustworthy:
#
#   1. The invite-production Compose model publishes gateway-service on
#      127.0.0.1:8080 and nothing else new. A future `ports: !reset []` (or a
#      reordered overlay list) must not be able to silently remove the dark
#      endpoint again — that is exactly how D1 shipped.
#   2. The dark gateway URL guard is an allowlist. A public Parkio hostname, an
#      alternative port, userinfo, or an off-host redirect must all fail closed,
#      because every one of those resolves somewhere that is not this runtime.
#
# The Compose-model assertions need a working `docker compose`. They are skipped
# when it is unavailable and REQUIRED when PARKIO_REQUIRE_COMPOSE_MODEL=1 (CI).
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# shellcheck source=lib/dark-gateway-url.sh
source "$ROOT/scripts/lib/dark-gateway-url.sh"

pass=0
fail=0
ok() { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

# --------------------------------------------------------------------------- #
# 1. Overlay ordering                                                          #
# --------------------------------------------------------------------------- #
echo "=== invite-production compose overlay ordering ==="

PARKIO_DEPLOYMENT_PROFILE=invite-production
# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"
parkio_configure_deployment_profile /dev/null >/dev/null 2>&1 || true

if [ "$PARKIO_DEPLOYMENT_PROFILE" != "invite-production" ]; then
  echo "ERROR: could not resolve the invite-production profile" >&2
  exit 2
fi

case "$PARKIO_COMPOSE_FILES" in
  *docker/docker-compose.invite-dark.yml*)
    ok "invite-dark overlay is part of the invite-production compose set"
    ;;
  *)
    bad "invite-dark overlay missing from the invite-production compose set"
    ;;
esac

# The `!override` only wins if it is merged after the hosted-beta `!reset []`.
hosted_beta_pos="${PARKIO_COMPOSE_FILES%%docker/docker-compose.hosted-beta.yml*}"
dark_pos="${PARKIO_COMPOSE_FILES%%docker/docker-compose.invite-dark.yml*}"
if [ "${#dark_pos}" -gt "${#hosted_beta_pos}" ]; then
  ok "invite-dark overlay is ordered after the hosted-beta overlay"
else
  bad "invite-dark overlay must come AFTER docker-compose.hosted-beta.yml (its ports: !reset [] would win)"
fi

if [ "$(parkio_default_gateway_url)" = "$PARKIO_DARK_GATEWAY_ALLOWED_URL" ]; then
  ok "invite-production default gateway URL is the dark endpoint"
else
  bad "invite-production default gateway URL is '$(parkio_default_gateway_url)', expected $PARKIO_DARK_GATEWAY_ALLOWED_URL"
fi

# --------------------------------------------------------------------------- #
# 2. Merged Compose model                                                      #
# --------------------------------------------------------------------------- #
echo
echo "=== merged compose model published ports ==="

compose_model_checked=0
# Linux CI has `docker`; a WSL workstation reaches Docker Desktop via docker.exe.
COMPOSE_BIN=""
for candidate in docker docker.exe; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" compose version >/dev/null 2>&1; then
    COMPOSE_BIN="$candidate"
    break
  fi
done

if [ -n "$COMPOSE_BIN" ]; then
  model="$(mktemp)"
  trap 'rm -f -- "$model"' EXIT
  # Image/build interpolation vars the deploy script normally exports. Values are
  # irrelevant here; only the resolved port bindings are under test.
  export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-sha-test}"
  export PARKIO_GIT_SHA="${PARKIO_GIT_SHA:-0000000000000000000000000000000000000000}"
  export PARKIO_IMAGE_CREATED="${PARKIO_IMAGE_CREATED:-1970-01-01T00:00:00Z}"
  export PARKIO_ENVIRONMENT="${PARKIO_ENVIRONMENT:-invite-production}"
  export WSLENV="${WSLENV:+$WSLENV:}PARKIO_IMAGE_TAG:PARKIO_GIT_SHA:PARKIO_IMAGE_CREATED:PARKIO_ENVIRONMENT"
  # shellcheck disable=SC2086
  if "$COMPOSE_BIN" compose --env-file docker/.env.invite-production.example \
      $PARKIO_COMPOSE_FILES config --format json > "$model" 2>/dev/null; then
    compose_model_checked=1
    python3 - "$model" <<'PY' && ok "merged model binds gateway to loopback and resolves Tempo to 1 GiB" || bad "merged model dark-runtime assertions failed"
import json
import sys

model = json.load(open(sys.argv[1]))
services = model.get("services", {})
if isinstance(services, list):
    services = {s["name"]: s for s in services}

gateway = services.get("gateway-service", {})
ports = gateway.get("ports") or []
published = [
    (p.get("host_ip") or p.get("hostIp"), str(p.get("published")), str(p.get("target")))
    for p in ports
    if isinstance(p, dict)
]

errors = []
if published != [("127.0.0.1", "8080", "8080")]:
    errors.append(f"gateway-service published ports are {published}, expected [('127.0.0.1','8080','8080')]")

tempo_memory = services.get("tempo", {}).get("mem_limit")
if str(tempo_memory) != str(1024 * 1024 * 1024):
    errors.append(f"tempo mem_limit is {tempo_memory}, expected {1024 * 1024 * 1024} bytes")

# Nothing anywhere in the model may bind a wildcard address on 8080, and no
# internal/admin service may become non-loopback because of this overlay.
INTERNAL = {
    "redis", "kafka", "minio", "minio-setup", "clamav", "prometheus", "grafana",
    "alertmanager", "loki", "promtail", "tempo", "node-exporter", "kafka-exporter",
    "blackbox-exporter", "auth-service", "user-service", "parking-service",
    "media-service", "gamification-service", "notification-service",
    "moderation-service", "ai-validation-service", "analytics-service",
}
for name, cfg in services.items():
    for p in cfg.get("ports") or []:
        if not isinstance(p, dict):
            continue
        host_ip = p.get("host_ip") or p.get("hostIp") or ""
        pub = str(p.get("published"))
        if pub == "8080" and host_ip not in ("127.0.0.1",):
            errors.append(f"{name} publishes 8080 on '{host_ip or '0.0.0.0'}' (must be loopback only)")
        if name in INTERNAL and host_ip not in ("127.0.0.1",):
            errors.append(f"internal service {name} publishes {pub} on '{host_ip or '0.0.0.0'}'")

if errors:
    for e in errors:
        print(f"  {e}", file=sys.stderr)
    sys.exit(1)
PY
  else
    echo "SKIP: docker compose could not resolve the invite-production model here"
  fi
else
  echo "SKIP: docker compose unavailable"
fi

if [ "$compose_model_checked" -eq 0 ] && [ "${PARKIO_REQUIRE_COMPOSE_MODEL:-0}" = "1" ]; then
  bad "compose model assertions are required (PARKIO_REQUIRE_COMPOSE_MODEL=1) but could not run"
fi

# Static backstop so the intent is pinned even where docker is unavailable.
if grep -Eq '^\s*-\s*"127\.0\.0\.1:8080:8080"' docker/docker-compose.invite-dark.yml; then
  ok "overlay declares the loopback bind literally"
else
  bad "overlay must declare a literal 127.0.0.1:8080:8080 bind"
fi
if grep -Eq '0\.0\.0\.0:8080|"8080:8080"|\[::\]:8080' docker/docker-compose.invite-dark.yml; then
  bad "overlay must never declare a wildcard 8080 bind"
else
  ok "overlay declares no wildcard bind"
fi
overlay_services="$(grep -E '^  [a-z0-9-]+:' docker/docker-compose.invite-dark.yml | sed -E 's/^  ([a-z0-9-]+):.*/\1/' | sort)"
if [ "$overlay_services" = $'auth-service\ngateway-service\nparking-service\ntempo' ]; then
  ok "overlay touches only auth-service, gateway-service, parking-service, and Tempo"
else
  bad "overlay may touch only auth-service, gateway-service, parking-service, and Tempo (found: $(tr '\n' ' ' <<<"$overlay_services"))"
fi

# --------------------------------------------------------------------------- #
# 3. Dark gateway URL allowlist                                                #
# --------------------------------------------------------------------------- #
echo
echo "=== dark gateway URL guard ==="

accepts() {
  if parkio_validate_dark_gateway_url "$1" 2>/dev/null; then
    ok "accepts $2"
  else
    bad "should accept $2"
  fi
}
rejects() {
  if parkio_validate_dark_gateway_url "$1" 2>/dev/null; then
    bad "should REJECT $2"
  else
    ok "rejects $2"
  fi
}

accepts "http://127.0.0.1:8080" "the dark endpoint"
accepts "http://127.0.0.1:8080/" "the dark endpoint with a trailing slash"

rejects "" "an empty URL"
rejects "https://api.parkio.dev" "the public API host"
rejects "https://api.parkio.dev/" "the public API host with a trailing slash"
rejects "https://api.parkio.dev:443" "the public API host with an explicit port"
rejects "http://api.parkio.dev" "the public API host over http"
rejects "https://app.parkio.dev" "the public SPA host"
rejects "https://media.parkio.dev" "the public media host"
rejects "http://localhost:8080" "localhost (resolution can vary)"
rejects "http://127.0.0.1:80" "the wrong port (80)"
rejects "http://127.0.0.1:8081" "the wrong port (8081)"
rejects "https://127.0.0.1:8080" "the wrong scheme"
rejects "http://[::1]:8080" "an IPv6 form"
rejects "http://127.0.0.1:8080/api/v1" "an unexpected path prefix"
rejects "http://user:pass@127.0.0.1:8080" "userinfo"
rejects "http://127.0.0.1:8080#frag" "a fragment"
rejects "http://127.0.0.1:8080?x=1" "a query string"
rejects "http://10.0.0.5:8080" "an arbitrary RFC1918 target"
rejects "http://169.254.169.254/metadata" "the cloud metadata endpoint"
rejects "https://evil.example.com" "an arbitrary external URL"
rejects "http://127.0.0.1:8080 http://evil.example.com" "an embedded second URL"

# --------------------------------------------------------------------------- #
# 4. Redirect containment                                                      #
# --------------------------------------------------------------------------- #
echo
echo "=== redirect containment ==="

redirect_ok() {
  if parkio_assert_dark_redirect_target "$1" 2>/dev/null; then
    ok "allows redirect to $2"
  else
    bad "should allow redirect to $2"
  fi
}
redirect_bad() {
  if parkio_assert_dark_redirect_target "$1" 2>/dev/null; then
    bad "should REJECT redirect to $2"
  else
    ok "rejects redirect to $2"
  fi
}

redirect_ok "" "no redirect at all"
redirect_ok "/actuator/health" "a relative path on the dark endpoint"
redirect_ok "http://127.0.0.1:8080/actuator/health" "an absolute URL on the dark endpoint"

redirect_bad "https://api.parkio.dev/actuator/health" "the public API host"
redirect_bad "https://app.parkio.dev" "the public SPA host"
redirect_bad "http://127.0.0.1:9090" "another local port"
redirect_bad "http://evil.example.com" "an external host"

# Smoke must never hand -L to curl, or the containment check above is moot.
if grep -nE 'curl[^|]*\s-[a-zA-Z]*L' scripts/smoke-hosted-beta.sh | grep -v '^\s*#' | grep -q .; then
  bad "smoke-hosted-beta.sh must not follow redirects (-L)"
else
  ok "smoke never passes -L to curl"
fi
if grep -q -- '--max-redirs 0' scripts/smoke-hosted-beta.sh; then
  ok "smoke pins --max-redirs 0"
else
  bad "smoke must pin --max-redirs 0"
fi

echo
echo "=== invite dark gateway tests: pass=$pass fail=$fail ==="
if [ "$fail" -ne 0 ]; then
  exit 1
fi
