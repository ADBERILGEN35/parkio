#!/usr/bin/env bash
#
# Public-cutover deployment contract for invite-production (PROD-DEPLOY-01B-03E-A1).
#
# Validates the resolved public+acme=true profile before Caddy may start:
#   - edge=public, acme=true, registration=closed
#   - invite-public-staged overlay absent, caddy in runtime
#   - gateway internal-only, HSTS/CORS/actuator contracts
#   - production Caddyfile routing and persistence volumes intact
#
# Usage:
#   assert-invite-public-cutover-deploy.sh [--env-file FILE] [--model FILE]
#                                          [--require-model] [--skip-dns]
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${PARKIO_ENV_FILE:-docker/.env.invite-production}"
MODEL=""
REQUIRE_MODEL="${PARKIO_REQUIRE_PUBLIC_CUTOVER_MODEL:-0}"
SKIP_DNS=0
CADDYFILE="docker/caddy/Caddyfile"
HOSTED_BETA_COMPOSE="docker/docker-compose.hosted-beta.yml"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --model) MODEL="${2:-}"; shift 2 ;;
    --require-model) REQUIRE_MODEL=1; shift ;;
    --skip-dns) SKIP_DNS=1; shift ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"
# shellcheck source=lib/invite-edge-mode.sh
source "$ROOT/scripts/lib/invite-edge-mode.sh"
# shellcheck source=lib/invite-deploy-profile.sh
source "$ROOT/scripts/lib/invite-deploy-profile.sh"

errors=0
note() { echo "  $1"; }
bad() { echo "ERROR: $1" >&2; errors=$((errors + 1)); }

parkio_configure_deployment_profile "$ENV_FILE" >/dev/null 2>&1 || true

if [ "${PARKIO_DEPLOYMENT_PROFILE:-}" != "invite-production" ]; then
  echo "SKIP: profile '${PARKIO_DEPLOYMENT_PROFILE:-unset}' is not invite-production"
  exit 0
fi

edge_mode="$(parkio_invite_edge_mode_from_env "$ENV_FILE")" || exit 2
acme_authorized="$(parkio_invite_acme_authorized_from_env "$ENV_FILE")" || exit 2
registration_mode="$(parkio_env_value "$ENV_FILE" PARKIO_REGISTRATION_MODE || true)"
invite_creation="$(parkio_env_value "$ENV_FILE" PARKIO_REGISTRATION_INVITE_CREATION_ENABLED || true)"
public_actuator="$(parkio_env_value "$ENV_FILE" PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED || true)"
cors_origin="$(parkio_env_value "$ENV_FILE" PARKIO_CORS_ALLOWED_ORIGINS || true)"
hsts="$(parkio_env_value "$ENV_FILE" PARKIO_HSTS_HEADER_VALUE || true)"
max_upload="$(parkio_env_value "$ENV_FILE" PARKIO_MAX_UPLOAD_SIZE || true)"
max_upload="${max_upload:-25MB}"

echo "=== invite-production public cutover deploy contract ==="

profile="$(parkio_invite_deploy_profile_label "$edge_mode" "$acme_authorized")" || exit 4
if [ "$profile" != "public-cutover" ]; then
  bad "public cutover assertion requires edge=public and acme=true (got profile=$profile)"
fi

parkio_validate_invite_registration_mode "$registration_mode" || exit 4

if [ "$invite_creation" != "false" ]; then
  bad "PARKIO_REGISTRATION_INVITE_CREATION_ENABLED must be false (got '${invite_creation:-<missing>}')"
else
  note "invite creation disabled"
fi

if [ -z "$public_actuator" ] || [ "$public_actuator" != "false" ]; then
  bad "PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED must be false (got '${public_actuator:-<missing>}')"
else
  note "public actuator info disabled"
fi

if [ "$cors_origin" != "https://app.parkio.dev" ]; then
  bad "PARKIO_CORS_ALLOWED_ORIGINS must be https://app.parkio.dev (got '${cors_origin:-<missing>}')"
else
  note "CORS origin is https://app.parkio.dev"
fi

case "$hsts" in
  max-age=86400)
    note "HSTS policy is max-age=86400 without includeSubDomains/preload"
    ;;
  *)
    bad "PARKIO_HSTS_HEADER_VALUE must be max-age=86400 for cutover (got '${hsts:-<missing>}')"
    ;;
esac

if [ "$max_upload" != "25MB" ]; then
  bad "PARKIO_MAX_UPLOAD_SIZE must remain 25MB (got '$max_upload')"
else
  note "upload limit is 25MB"
fi

if printf '%s' "$PARKIO_COMPOSE_FILES" | grep -q invite-public-staged; then
  bad "invite-public-staged overlay must be absent for public cutover"
else
  note "invite-public-staged overlay absent"
fi

if ! printf '%s' "$PARKIO_COMPOSE_FILES" | grep -q invite-public; then
  bad "invite-public overlay must be present for public cutover"
else
  note "invite-public overlay present"
fi

if printf '%s' "$PARKIO_COMPOSE_FILES" | grep -q invite-dark; then
  bad "invite-dark overlay must not be active during public cutover"
fi

if [[ " ${PARKIO_RUNTIME_SERVICES[*]} " != *" caddy "* ]]; then
  bad "caddy must be in the public-cutover runtime service list"
else
  note "caddy present in runtime service list"
fi

if [[ " ${PARKIO_REQUIRED_HEALTHY[*]} " != *" caddy "* ]]; then
  bad "caddy must be in the required-healthy list for public cutover"
else
  note "caddy required healthy"
fi

if printf '%s\n' "${PARKIO_DISABLED_SERVICES[@]}" | grep -qx caddy; then
  bad "caddy must not be listed in PARKIO_DISABLED_SERVICES for public cutover"
else
  note "caddy not disabled"
fi

if [ ! -f "$CADDYFILE" ]; then
  bad "$CADDYFILE is missing"
else
  if grep -Eq '^[[:space:]]*auto_https[[:space:]]+off' "$CADDYFILE"; then
    bad "$CADDYFILE disables automatic HTTPS"
  else
    note "Caddy automatic HTTPS retained"
  fi
  for var in PARKIO_DOMAIN PARKIO_WEB_DOMAIN PARKIO_MEDIA_DOMAIN; do
    if grep -q "{\$$var}" "$CADDYFILE"; then
      note "Caddyfile serves {\$$var}"
    else
      bad "$CADDYFILE no longer serves {\$$var}"
    fi
  done
  if grep -Eq 'reverse_proxy.*:9001|minio:9001' "$CADDYFILE"; then
    bad "Caddyfile must not route to MinIO console :9001"
  fi
  if grep -Eq 'reverse_proxy.*(prometheus|grafana|alertmanager|tempo|loki|kafka|postgres)' "$CADDYFILE"; then
    bad "Caddyfile must not route to internal observability/data services"
  fi
  if grep -Eq '\*\.|{\*}' "$CADDYFILE"; then
    bad "Caddyfile must not define wildcard site blocks"
  fi
fi

if [ ! -f "$HOSTED_BETA_COMPOSE" ]; then
  bad "$HOSTED_BETA_COMPOSE is missing"
elif ! grep -q 'caddy-data:' "$HOSTED_BETA_COMPOSE" || ! grep -q 'caddy-config:' "$HOSTED_BETA_COMPOSE"; then
  bad "caddy-data and caddy-config named volumes must remain defined"
else
  note "caddy-data and caddy-config persistent volumes defined"
fi

model_checked=0
if [ -z "$MODEL" ]; then
  compose_bin=""
  for candidate in docker docker.exe; do
    if command -v "$candidate" >/dev/null 2>&1 && "$candidate" compose version >/dev/null 2>&1; then
      compose_bin="$candidate"
      break
    fi
  done
  if [ -n "$compose_bin" ]; then
    MODEL="$(mktemp)"
    trap 'rm -f -- "$MODEL"' EXIT
    export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-sha-cutover-test}"
    export PARKIO_GIT_SHA="${PARKIO_GIT_SHA:-0000000000000000000000000000000000000000}"
    export PARKIO_IMAGE_CREATED="${PARKIO_IMAGE_CREATED:-1970-01-01T00:00:00Z}"
    export PARKIO_IMAGE_VERSION="${PARKIO_IMAGE_VERSION:-0.0.1-SNAPSHOT}"
    export WSLENV="${WSLENV:+$WSLENV:}PARKIO_IMAGE_TAG:PARKIO_GIT_SHA:PARKIO_IMAGE_CREATED:PARKIO_IMAGE_VERSION"
    render_env="$ENV_FILE"
    [ -f "$render_env" ] || render_env="docker/.env.invite-production.example"
  # shellcheck disable=SC2086
    "$compose_bin" compose --env-file "$render_env" $PARKIO_COMPOSE_FILES \
      config --format json > "$MODEL" 2>/dev/null || MODEL=""
  fi
fi

if [ -n "$MODEL" ] && [ -s "$MODEL" ]; then
  model_checked=1
  if PARKIO_START_SET="${PARKIO_RUNTIME_SERVICES[*]}" \
     python3 "$ROOT/scripts/lib/check_public_cutover_model.py" "$MODEL"; then
    note "merged model: caddy owns public edge; gateway remains internal"
  else
    bad "merged compose model violates the public-cutover host-port contract"
  fi
fi

if [ "$model_checked" -eq 0 ]; then
  if [ "$REQUIRE_MODEL" = "1" ]; then
    bad "merged-model cutover assertions are required (--require-model) but the model could not be rendered"
  else
    echo "  SKIP: merged model unavailable; static assertions only"
  fi
fi

if [ "$SKIP_DNS" -eq 0 ]; then
  if ! "$ROOT/scripts/assert-invite-cutover-dns-authoritative.sh"; then
    bad "authoritative DNS does not yet point all production hostnames at invite-production"
  else
    note "authoritative DNS precondition satisfied"
  fi
else
  note "authoritative DNS guard skipped (--skip-dns)"
fi

if [ "$errors" -ne 0 ]; then
  echo "PUBLIC CUTOVER DEPLOY: FAIL ($errors problem(s))" >&2
  exit 4
fi

echo "invite_edge_mode=$edge_mode"
echo "invite_acme_authorized=$acme_authorized"
echo "registration_mode=$registration_mode"
echo "deploy_profile=$profile"
echo "caddy_runtime_state=enabled"
echo "gateway_host_binding=none"
echo "invite_public_cutover_deploy=PASS"
