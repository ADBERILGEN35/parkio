#!/usr/bin/env bash
#
# PROD-DEPLOY-01A-R4 / D3 regression tests.
#
# D3: the dark runtime started Caddy. docker/caddy/Caddyfile enables automatic
# HTTPS against production Let's Encrypt for {$PARKIO_DOMAIN}/{$PARKIO_WEB_DOMAIN}/
# {$PARKIO_MEDIA_DOMAIN}, and the invite-production env renders those to the real
# api/app/media.parkio.dev — names that still point at the hosted-beta VM. A dark
# deploy would therefore have emitted public ACME orders for production hostnames
# it cannot validate, consuming the Let's Encrypt failed-validation budget that
# the PROD-DEPLOY-01B cutover depends on.
#
# What must stay true:
#
#   1. dark mode never starts an ACME client for a real Parkio hostname;
#   2. the guard fails closed if anyone puts Caddy back in the dark start set;
#   3. dark acceptance still targets 127.0.0.1:8080 only;
#   4. the production Caddy path keeps automatic HTTPS when the invite-dark
#      overlay is absent — dark isolation must not weaken the cutover;
#   5. no new published port appears, and nothing binds beyond loopback.
#
# Static/config assertions only: no DNS resolution, no sockets, no ACME contact.
# The merged-model assertions need `docker compose`; they are skipped when it is
# unavailable and REQUIRED when PARKIO_REQUIRE_COMPOSE_MODEL=1 (CI).
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

pass=0
fail=0
ok() { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

GUARD="$ROOT/scripts/assert-invite-dark-acme-isolation.sh"
ENV_EXAMPLE="docker/.env.invite-production.example"
CADDYFILE="docker/caddy/Caddyfile"

# --------------------------------------------------------------------------- #
# 1. Non-ACME runtime composition (dark or public-staged)                      #
# --------------------------------------------------------------------------- #
echo "=== non-ACME runtime excludes the ACME edge ==="

PARKIO_DEPLOYMENT_PROFILE=invite-production
# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"
parkio_configure_deployment_profile "$ENV_EXAMPLE" >/dev/null 2>&1 || true

if [ "$PARKIO_DEPLOYMENT_PROFILE" != "invite-production" ]; then
  echo "ERROR: could not resolve the invite-production profile" >&2
  exit 2
fi

if [[ " ${PARKIO_RUNTIME_SERVICES[*]} " == *" caddy "* ]]; then
  bad "caddy must not be in the invite-production runtime service list"
else
  ok "caddy is not started by the non-ACME runtime (public-staged example)"
fi

if [[ " ${PARKIO_REQUIRED_HEALTHY[*]} " == *" caddy "* ]]; then
  bad "caddy must not be required-healthy in dark mode (it is never started)"
else
  ok "caddy is not required-healthy in dark mode"
fi

# Explicit dark mode must still omit caddy from the start set.
PARKIO_INVITE_EDGE_MODE=dark
parkio_configure_deployment_profile "$ENV_EXAMPLE" >/dev/null 2>&1 || true
if [[ " ${PARKIO_RUNTIME_SERVICES[*]} " == *" caddy "* ]]; then
  bad "caddy must not be in the dark runtime service list"
else
  ok "caddy is not started by explicit dark mode"
fi
case "$PARKIO_COMPOSE_FILES" in
  *docker/docker-compose.invite-dark.yml*) ok "invite-dark overlay active in dark mode" ;;
  *) bad "invite-dark overlay missing when PARKIO_INVITE_EDGE_MODE=dark" ;;
esac

# Restore example profile for remaining checks (public-staged).
unset PARKIO_INVITE_EDGE_MODE
parkio_configure_deployment_profile "$ENV_EXAMPLE" >/dev/null 2>&1 || true

if [[ " ${PARKIO_DISABLED_SERVICES[*]} " == *" caddy "* ]]; then
  ok "caddy omission is declared, not silent"
else
  bad "caddy must appear in PARKIO_DISABLED_SERVICES so the omission is explicit"
fi

# The runtime list must stay explicit: an empty list makes compose start
# everything, which would resurrect Caddy without touching this file.
if [ "${#PARKIO_RUNTIME_SERVICES[@]}" -gt 0 ]; then
  ok "dark runtime uses an explicit service list"
else
  bad "dark runtime service list is empty; compose would start every service including caddy"
fi

# --------------------------------------------------------------------------- #
# 2. Dark acceptance endpoint is unchanged by this remediation                 #
# --------------------------------------------------------------------------- #
echo
echo "=== dark acceptance endpoint ==="

if [ "$(parkio_default_gateway_url)" = "http://127.0.0.1:8080" ]; then
  ok "dark gateway smoke still targets 127.0.0.1:8080"
else
  bad "dark gateway URL is '$(parkio_default_gateway_url)', expected http://127.0.0.1:8080"
fi

if [[ " ${PARKIO_RUNTIME_SERVICES[*]} " == *" gateway-service "* ]]; then
  ok "gateway-service is still in the dark runtime"
else
  bad "gateway-service must remain in the dark runtime; it is the dark endpoint"
fi

# --------------------------------------------------------------------------- #
# 3. Production TLS path preserved for PROD-DEPLOY-01B                         #
# --------------------------------------------------------------------------- #
echo
echo "=== production Caddy path preserved ==="

if grep -Eq '^[[:space:]]*auto_https[[:space:]]+off' "$CADDYFILE"; then
  bad "$CADDYFILE disables automatic HTTPS globally; the cutover would lose TLS"
else
  ok "production Caddyfile keeps automatic HTTPS"
fi

for var in PARKIO_DOMAIN PARKIO_WEB_DOMAIN PARKIO_MEDIA_DOMAIN; do
  if grep -q "{\$$var}" "$CADDYFILE"; then
    ok "production Caddyfile still serves {\$$var}"
  else
    bad "production Caddyfile no longer serves {\$$var}"
  fi
done

# Caddy must still be a real service in the model so PROD-DEPLOY-01B can start
# it; dark isolation is a runtime-set decision, not a deletion.
if grep -Eq '^[[:space:]]{2}caddy:' docker/docker-compose.hosted-beta.yml; then
  ok "caddy service definition is intact for the cutover"
else
  bad "caddy service definition was removed; PROD-DEPLOY-01B has no public edge"
fi

# The hosted-beta (non-dark) profile must still run Caddy.
(
  parkio_configure_deployment_profile /dev/null >/dev/null 2>&1 || true
  PARKIO_DEPLOYMENT_PROFILE=hosted-beta
  parkio_configure_deployment_profile /dev/null >/dev/null 2>&1 || true
  # hosted-beta uses an empty runtime list, i.e. "start everything", so Caddy
  # runs there exactly as before.
  if [ "${#PARKIO_RUNTIME_SERVICES[@]}" -eq 0 ]; then
    exit 0
  fi
  [[ " ${PARKIO_RUNTIME_SERVICES[*]} " == *" caddy "* ]]
) && ok "non-dark profile still starts caddy" || bad "non-dark profile no longer starts caddy"

# Re-resolve the dark profile for the remaining checks.
PARKIO_DEPLOYMENT_PROFILE=invite-production
parkio_configure_deployment_profile "$ENV_EXAMPLE" >/dev/null 2>&1 || true

# --------------------------------------------------------------------------- #
# 4. The guard itself fails closed                                             #
# --------------------------------------------------------------------------- #
echo
echo "=== guard behaviour ==="

if "$GUARD" --env-file "$ENV_EXAMPLE" >/dev/null 2>&1; then
  ok "guard passes on the isolated non-ACME configuration (public-staged)"
else
  bad "guard rejects the current (isolated) non-ACME configuration"
fi

# The guard must not READ the test-harness flag PARKIO_REQUIRE_COMPOSE_MODEL.
# It did in the first R4 attempt: the CI step exports that flag for the test
# suites, `deploy-invite-production.sh --dry-run` runs inside the same step, and
# the guard inherited it — turning a sandbox model that was never meant to
# resolve into a hard deploy failure. Matching a variable EXPANSION (not a
# mention) keeps the explanatory comment in the guard legal.
if grep -Eq '\$\{?PARKIO_REQUIRE_COMPOSE_MODEL' "$GUARD"; then
  bad "guard reads the test-harness flag PARKIO_REQUIRE_COMPOSE_MODEL; a dry-run deploy would inherit it"
else
  ok "guard does not read the test-harness flag PARKIO_REQUIRE_COMPOSE_MODEL"
fi

# Under that flag, with no model obtainable, the guard must still pass on an
# isolated configuration — this is the exact case that broke CI.
if PARKIO_REQUIRE_COMPOSE_MODEL=1 "$GUARD" --env-file "$ENV_EXAMPLE" \
     --model /nonexistent/model.json >/dev/null 2>&1; then
  ok "guard passes under PARKIO_REQUIRE_COMPOSE_MODEL with no renderable model"
else
  bad "guard fails under PARKIO_REQUIRE_COMPOSE_MODEL with no renderable model (the R4 CI regression)"
fi

# --require-model must fail closed when no model can be produced, so a real
# deploy can never silently skip the merged-model proof.
if PARKIO_REQUIRE_DARK_ACME_MODEL=1 "$GUARD" --env-file "$ENV_EXAMPLE" \
     --model /nonexistent/model.json >/dev/null 2>&1; then
  bad "--require-model accepted a missing merged model"
else
  ok "--require-model fails closed when the merged model is unavailable"
fi

# A real (non-dry-run) deploy must pass --require-model; a dry run must not.
if grep -q 'acme_guard_args+=(--require-model)' scripts/deploy-invite-production.sh &&
   grep -q 'if \[ "$DRY_RUN" -ne 1 \]; then' scripts/deploy-invite-production.sh; then
  ok "real deploy demands the merged-model proof, dry-run does not"
else
  bad "deploy script must pass --require-model for a real deploy only"
fi

# Negative: put caddy back into the start set and require a non-zero exit. The
# stub library mirrors the real one so the guard's own logic is what is tested.
stub_dir="$(mktemp -d)"
trap 'rm -rf -- "$stub_dir"' EXIT
model_stub="$stub_dir/model.json"
cat > "$model_stub" <<'JSON'
{
  "services": {
    "caddy": {
      "image": "caddy:2.8-alpine",
      "environment": {"PARKIO_DOMAIN": "api.parkio.dev"},
      "ports": [{"mode": "ingress", "target": 443, "published": "443", "protocol": "tcp"}]
    },
    "gateway-service": {
      "image": "parkio/gateway-service:test",
      "ports": [{"mode": "ingress", "host_ip": "127.0.0.1", "target": 8080, "published": "8080", "protocol": "tcp"}]
    }
  }
}
JSON

if PARKIO_START_SET="gateway-service caddy" \
   PARKIO_PUBLIC_HOSTS="api.parkio.dev app.parkio.dev media.parkio.dev" \
   python3 "$ROOT/scripts/lib/check_dark_acme_model.py" "$model_stub" >/dev/null 2>&1; then
  bad "model check accepted caddy in the dark start set"
else
  ok "model check rejects an ACME edge in the dark start set"
fi

if PARKIO_START_SET="gateway-service" \
   PARKIO_PUBLIC_HOSTS="api.parkio.dev app.parkio.dev media.parkio.dev" \
   python3 "$ROOT/scripts/lib/check_dark_acme_model.py" "$model_stub" >/dev/null 2>&1; then
  ok "model check accepts a dark start set without the ACME edge"
else
  bad "model check rejected a clean dark start set"
fi

# An empty start set means "compose starts everything", which silently restores
# the ACME edge. That must fail too.
if PARKIO_START_SET="" \
   PARKIO_PUBLIC_HOSTS="api.parkio.dev" \
   python3 "$ROOT/scripts/lib/check_dark_acme_model.py" "$model_stub" >/dev/null 2>&1; then
  bad "model check accepted an empty start set"
else
  ok "model check rejects an empty start set"
fi

# A non-loopback publish in the dark start set must fail.
wildcard_stub="$stub_dir/wildcard.json"
cat > "$wildcard_stub" <<'JSON'
{
  "services": {
    "gateway-service": {
      "image": "parkio/gateway-service:test",
      "ports": [{"mode": "ingress", "target": 8080, "published": "8080", "protocol": "tcp"}]
    }
  }
}
JSON
if PARKIO_START_SET="gateway-service" PARKIO_PUBLIC_HOSTS="api.parkio.dev" \
   python3 "$ROOT/scripts/lib/check_dark_acme_model.py" "$wildcard_stub" >/dev/null 2>&1; then
  bad "model check accepted a wildcard publish in dark mode"
else
  ok "model check rejects a non-loopback publish in dark mode"
fi

# --------------------------------------------------------------------------- #
# 5. Merged model: no ACME client, no new public port                          #
# --------------------------------------------------------------------------- #
echo
echo "=== merged compose model ==="

compose_bin=""
for candidate in docker docker.exe; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" compose version >/dev/null 2>&1; then
    compose_bin="$candidate"
    break
  fi
done

model_checked=0
if [ -n "$compose_bin" ]; then
  model="$stub_dir/merged.json"
  export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-sha-test}"
  export PARKIO_GIT_SHA="${PARKIO_GIT_SHA:-0000000000000000000000000000000000000000}"
  export PARKIO_IMAGE_CREATED="${PARKIO_IMAGE_CREATED:-1970-01-01T00:00:00Z}"
  export PARKIO_IMAGE_VERSION="${PARKIO_IMAGE_VERSION:-0.0.1-SNAPSHOT}"
  export WSLENV="${WSLENV:+$WSLENV:}PARKIO_IMAGE_TAG:PARKIO_GIT_SHA:PARKIO_IMAGE_CREATED:PARKIO_IMAGE_VERSION"
  # shellcheck disable=SC2086
  if "$compose_bin" compose --env-file "$ENV_EXAMPLE" $PARKIO_COMPOSE_FILES \
      config --format json > "$model" 2>/dev/null; then
    model_checked=1
    if PARKIO_START_SET="${PARKIO_RUNTIME_SERVICES[*]}" \
       PARKIO_PUBLIC_HOSTS="api.parkio.dev app.parkio.dev media.parkio.dev" \
       python3 "$ROOT/scripts/lib/check_dark_acme_model.py" "$model" >/dev/null 2>&1 &&
       "$GUARD" --env-file "$ENV_EXAMPLE" --model "$model" --require-model >/dev/null 2>&1; then
      ok "merged non-ACME model starts no ACME client and publishes loopback only"
    else
      bad "merged non-ACME model still permits a public ACME path or a non-loopback publish"
    fi

    # Caddy must remain defined in the model (cutover) while staying out of the
    # start set (dark). Both halves matter.
    if python3 - "$model" <<'PY'
import json, sys
services = json.load(open(sys.argv[1])).get("services", {})
if isinstance(services, list):
    services = {s["name"]: s for s in services}
sys.exit(0 if "caddy" in services else 1)
PY
    then
      ok "caddy remains defined in the model for PROD-DEPLOY-01B"
    else
      bad "caddy disappeared from the merged model; the cutover has no public edge"
    fi

    # No published port outside the dark start set may be introduced either.
    if python3 - "$model" "${PARKIO_RUNTIME_SERVICES[*]}" <<'PY'
import json, sys
services = json.load(open(sys.argv[1])).get("services", {})
if isinstance(services, list):
    services = {s["name"]: s for s in services}
start = set(sys.argv[2].split())
published = []
for name in sorted(start):
    for p in (services.get(name, {}).get("ports") or []):
        if isinstance(p, dict):
            published.append((name, p.get("host_ip") or "", str(p.get("published"))))
expected = [("gateway-service", "127.0.0.1", "8080")]
extra = [p for p in published if p not in expected and p[1] != "127.0.0.1"]
if extra:
    print(f"unexpected non-loopback publishes: {extra}", file=sys.stderr)
    sys.exit(1)
sys.exit(0)
PY
    then
      ok "no new public port introduced by the dark runtime"
    else
      bad "dark runtime introduced a public port"
    fi
  fi
fi

if [ "$model_checked" -eq 0 ]; then
  if [ "${PARKIO_REQUIRE_COMPOSE_MODEL:-0}" = "1" ]; then
    bad "merged-model assertions are required (PARKIO_REQUIRE_COMPOSE_MODEL=1) but could not run"
  else
    echo "SKIP: docker compose unavailable"
  fi
fi

# --------------------------------------------------------------------------- #
echo
echo "invite-dark ACME isolation: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
