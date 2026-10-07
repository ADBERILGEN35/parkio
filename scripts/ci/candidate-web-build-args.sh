#!/usr/bin/env bash
# Prints the web image build arguments release.yml uses (job images, service web), derived from
# docker/web-hosted-beta.release-bake.env with release.yml's fallbacks and the same hosted-beta
# contract check, one ARG=VALUE per line. The MapTiler key and IMAGE_* values are the caller's:
#   scripts/ci/candidate-web-build-args.sh > args.txt
# Exit 2 when the bake file breaks the live contract (release.yml refuses it too).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BAKE="${PARKIO_WEB_BAKE:-docker/web-hosted-beta.release-bake.env}"
WEB_APP_ENV="${WEB_APP_ENV:-hosted-beta}"
WEB_API_BASE_URL="${WEB_API_BASE_URL:-https://api.parkio.dev/api/v1}"
declare -A bake=()
while IFS='=' read -r key value; do bake["$key"]="$value"; done < <(
  env -i PATH="$PATH" bash -c 'set -a; . "$1"; set +a; for k in $(compgen -v VITE_); do printf "%s=%s\n" "$k" "${!k}"; done' bake "$ROOT/$BAKE")
municipal="${bake[VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED]:-}"
case "$municipal" in true|false) ;; *) echo "FAIL: $BAKE VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED must be true or false" >&2; exit 2 ;; esac
if [ "$WEB_APP_ENV" = "hosted-beta" ]; then
  if [ "${bake[VITE_APP_ENV]:-}" != "hosted-beta" ] || [ "${bake[VITE_API_BASE_URL]:-}" != "$WEB_API_BASE_URL" ] \
     || [ "${bake[VITE_PUBLIC_EXPLORE_ENABLED]:-}" != "true" ] || [ "$municipal" != "true" ]; then
    echo "FAIL: $BAKE must match the hosted-beta live contract (app env, API base, Explore ON, municipal ON)" >&2
    exit 2
  fi
fi
printf '%s\n' \
  "VITE_API_BASE_URL=${bake[VITE_API_BASE_URL]:-$WEB_API_BASE_URL}" \
  "VITE_APP_ENV=${bake[VITE_APP_ENV]:-$WEB_APP_ENV}" \
  "VITE_PUBLIC_EXPLORE_ENABLED=${bake[VITE_PUBLIC_EXPLORE_ENABLED]:-false}" \
  "VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=$municipal" \
  "VITE_SMART_PARKING_ASSISTANT_ENABLED=${bake[VITE_SMART_PARKING_ASSISTANT_ENABLED]:-false}" \
  "VITE_SMART_RETURN_ENABLED=${bake[VITE_SMART_RETURN_ENABLED]:-true}" \
  "VITE_WAITLIST_INTAKE_MODE=${bake[VITE_WAITLIST_INTAKE_MODE]:-api}" \
  "VITE_MAPTILER_STYLE=${bake[VITE_MAPTILER_STYLE]:-streets-v2}" \
  "VITE_FRONTEND_ERROR_REPORTING=${bake[VITE_FRONTEND_ERROR_REPORTING]:-disabled}" \
  "VERIFY_REQUIRE_PUBLIC_EXPLORE=true" \
  "VERIFY_REQUIRE_MUNICIPAL=$municipal"
