#!/usr/bin/env bash
#
# PROD-DEPLOY-01A-R4. Fail the dark deployment if it could still make a public
# ACME request for a real Parkio hostname.
#
# The dark runtime runs on vm-parkio-invite-prod while api/app/media.parkio.dev
# still resolve to the hosted-beta VM. docker/caddy/Caddyfile turns on Caddy's
# automatic HTTPS against production Let's Encrypt for exactly those names, so a
# dark stack that starts Caddy issues ACME orders it cannot validate: an
# externally visible side effect of a supposedly dark deploy, and one that
# consumes the failed-validation budget needed for the PROD-DEPLOY-01B cutover.
#
# The remediation is structural — Caddy is not in the dark runtime set — and this
# guard is what makes that binding rather than incidental. It is source/config
# inspection only: it never resolves DNS, never opens a socket, and never
# contacts an ACME directory.
#
# Usage: assert-invite-dark-acme-isolation.sh [--env-file FILE] [--model FILE]
#                                              [--require-model]
#
#   --env-file       env file used to resolve the deployment profile (default:
#                    docker/.env.invite-production)
#   --model          pre-rendered `docker compose config --format json` model.
#                    When omitted the guard renders one if docker compose is
#                    available and otherwise relies on the static assertions.
#   --require-model  fail if the merged model cannot be rendered. A real deploy
#                    passes this: on the production runner an unresolvable model
#                    means the guard cannot see what will start, which must never
#                    be treated as "isolated". A --dry-run deploy does NOT, because
#                    it runs against a synthetic env whose model may not resolve,
#                    and the static assertions already catch a reintroduced edge.
#                    Equivalent: PARKIO_REQUIRE_DARK_ACME_MODEL=1. Deliberately
#                    NOT the test-harness flag PARKIO_REQUIRE_COMPOSE_MODEL —
#                    inheriting that turned an unrenderable sandbox model into a
#                    deploy failure (PROD-DEPLOY-01A-R4 / D3 follow-up).
#
# Exit 0 = isolated. Exit 4 = a public-ACME path survives. Exit 2 = usage/bug.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${PARKIO_ENV_FILE:-docker/.env.invite-production}"
MODEL=""
REQUIRE_MODEL="${PARKIO_REQUIRE_DARK_ACME_MODEL:-0}"
CADDYFILE="docker/caddy/Caddyfile"

# The hostnames that must never appear in a dark ACME order. These are the real
# production names; until PROD-DEPLOY-01B they belong to the hosted-beta VM.
PARKIO_PUBLIC_HOSTNAMES=(api.parkio.dev app.parkio.dev media.parkio.dev)

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --model) MODEL="${2:-}"; shift 2 ;;
    --require-model) REQUIRE_MODEL=1; shift ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"

errors=0
note() { echo "  $1"; }
bad() { echo "ERROR: $1" >&2; errors=$((errors + 1)); }

parkio_configure_deployment_profile "$ENV_FILE" >/dev/null 2>&1 || true

if [ "${PARKIO_DEPLOYMENT_PROFILE:-}" != "invite-production" ]; then
  # Nothing to isolate: the invite-dark overlay is not in play, so the normal
  # production Caddy path (and its automatic HTTPS) is intended and untouched.
  echo "SKIP: profile '${PARKIO_DEPLOYMENT_PROFILE:-unset}' is not invite-production; public ACME is expected there"
  exit 0
fi

echo "=== invite-production ACME isolation (dark or public-staged) ==="

# --------------------------------------------------------------------------- #
# 1. A non-ACME overlay must be active: either invite-dark (Level-A) or       #
#    invite-public-staged (Level-B dark-stage). Both keep Caddy out of the      #
#    start set while production hostnames still resolve to hosted-beta.         #
# --------------------------------------------------------------------------- #
staged_overlay=0
case "$PARKIO_COMPOSE_FILES" in
  *docker/docker-compose.invite-dark.yml*)
    note "invite-dark overlay active" ;;
  *docker/docker-compose.invite-public-staged.yml*)
    staged_overlay=1
    note "invite-public-staged overlay active (public edge staged, ACME disabled)" ;;
  *)
    bad "invite-production must include invite-dark or invite-public-staged overlay; neither found in compose set" ;;
esac

if [ "$staged_overlay" -eq 1 ]; then
  case "$PARKIO_COMPOSE_FILES" in
    *docker/docker-compose.invite-public.yml*)
      note "invite-public overlay present for staged public edge" ;;
    *)
      bad "invite-public-staged requires invite-public overlay in the compose set" ;;
  esac
  if [ "${PARKIO_INVITE_EDGE_MODE:-}" != "public" ]; then
    bad "invite-public-staged requires PARKIO_INVITE_EDGE_MODE=public"
  else
    note "edge mode is public (staged)"
  fi
  if [ "${PARKIO_INVITE_ACME_AUTHORIZED:-}" = "true" ]; then
    bad "invite-public-staged requires PARKIO_INVITE_ACME_AUTHORIZED=false"
  else
    note "ACME is not authorized for this deploy"
  fi
fi

# --------------------------------------------------------------------------- #
# 2. Caddy must not be started, and the omission must be declared.             #
# --------------------------------------------------------------------------- #
if [ "${#PARKIO_RUNTIME_SERVICES[@]}" -eq 0 ]; then
  bad "invite-production must use an explicit runtime service list; an empty list starts every service including caddy"
elif [[ " ${PARKIO_RUNTIME_SERVICES[*]} " == *" caddy "* ]]; then
  bad "caddy is in the invite-production runtime service list; the stack would run Caddy's ACME client against ${PARKIO_PUBLIC_HOSTNAMES[*]}"
else
  note "caddy absent from the runtime service list (will not start)"
fi

if [[ " ${PARKIO_REQUIRED_HEALTHY[*]} " == *" caddy "* ]]; then
  bad "caddy is in the invite-production required-healthy list but is never started; the deploy would wait forever"
else
  note "caddy absent from the required-healthy list"
fi

if [[ " ${PARKIO_DISABLED_SERVICES[*]} " == *" caddy "* ]]; then
  note "caddy declared in the disabled-service list (omission is explicit)"
else
  bad "caddy must be listed in PARKIO_DISABLED_SERVICES so the omission is explicit and lands in the deploy manifest"
fi

# --------------------------------------------------------------------------- #
# 3. The production Caddy path must still be intact for PROD-DEPLOY-01B.       #
#                                                                             #
# Dark isolation is achieved by not starting Caddy — NOT by disabling automatic #
# HTTPS globally. If someone "fixes" this by gutting the Caddyfile, the cutover #
# silently loses TLS, so assert the production config still asks for it.        #
# --------------------------------------------------------------------------- #
if [ ! -f "$CADDYFILE" ]; then
  bad "$CADDYFILE is missing; the PROD-DEPLOY-01B public TLS path is gone"
else
  if grep -Eq '^[[:space:]]*auto_https[[:space:]]+off' "$CADDYFILE"; then
    bad "$CADDYFILE disables automatic HTTPS globally; dark isolation must not weaken the production TLS path"
  else
    note "production Caddyfile retains automatic HTTPS for PROD-DEPLOY-01B"
  fi
  for var in PARKIO_DOMAIN PARKIO_WEB_DOMAIN PARKIO_MEDIA_DOMAIN; do
    if grep -q "{\$$var}" "$CADDYFILE"; then
      note "production Caddyfile still serves {\$$var}"
    else
      bad "$CADDYFILE no longer serves {\$$var}; the PROD-DEPLOY-01B cutover would not terminate TLS for it"
    fi
  done
fi

# --------------------------------------------------------------------------- #
# 4. Merged-model proof: nothing in the dark start set is an ACME client, and  #
#    the dark start set publishes nothing beyond loopback.                     #
# --------------------------------------------------------------------------- #
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
    export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-sha-test}"
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
  # The model resolved, so the assertions ran either way; `model_checked` records
  # that fact so a genuine FAIL is never reported as "compose unavailable".
  model_checked=1
  if PARKIO_START_SET="${PARKIO_RUNTIME_SERVICES[*]}" \
     PARKIO_PUBLIC_HOSTS="${PARKIO_PUBLIC_HOSTNAMES[*]}" \
     python3 "$ROOT/scripts/lib/check_dark_acme_model.py" "$MODEL"; then
    note "merged model: runtime start set contains no ACME client and no non-loopback publish"
  else
    bad "merged compose model still permits a public ACME path in the effective start set"
  fi
fi

if [ "$model_checked" -eq 0 ]; then
  if [ "$REQUIRE_MODEL" = "1" ]; then
    bad "merged-model ACME assertions are required (--require-model) but the model could not be rendered"
  else
    echo "  SKIP: merged model unavailable; static assertions only"
  fi
fi

if [ "$errors" -ne 0 ]; then
  echo "DARK ACME ISOLATION: FAIL ($errors problem(s))" >&2
  exit 4
fi
echo "DARK ACME ISOLATION: PASS"
