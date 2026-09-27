#!/usr/bin/env bash
# Durable production compose wrapper for parkio-civo-prod.
# Always includes GMP release pins so normal ops retain digest pins.
#
# Usage (from repo root or any cwd):
#   PARKIO_ENV_FILE=docker/.env.azure-hosted-beta \
#     ./scripts/parkio-prod-compose.sh up -d --no-build --no-deps gateway-service
#
# Optional mechanical recovery overlay (host-local prior tags; not a security fix):
#   PARKIO_GMP_RECOVERY=1 ./scripts/parkio-prod-compose.sh up -d --no-build --no-deps <service>
#
# Do not commit production .env files. Cron quoting on
# PARKIO_MUNICIPAL_OCCUPANCY_RETENTION_CRON must remain double-quoted in the env file.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${PARKIO_ENV_FILE:-$ROOT/docker/.env.azure-hosted-beta}"
FILES_LIST="${PARKIO_COMPOSE_FILES_LIST:-$ROOT/docker/compose.production.files}"
ARGS=()
while IFS= read -r line || [ -n "$line" ]; do
  [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
  ARGS+=(-f "$ROOT/$line")
done < "$FILES_LIST"
# Optional recovery: PARKIO_GMP_RECOVERY=1 appends host-local prior-image overlay when present.
if [ "${PARKIO_GMP_RECOVERY:-0}" = "1" ]; then
  recovery="$ROOT/docker/docker-compose.gmp-recovery-prior.yml"
  if [ ! -f "$recovery" ]; then
    echo "ERROR: PARKIO_GMP_RECOVERY=1 but missing $recovery" >&2
    exit 2
  fi
  ARGS+=(-f "$recovery")
fi
cd "$ROOT"
# Web map deploy guard (audit F-05). Any invocation that can create the web
# container renders the merged model once (including operator -f/--profile
# globals), verifies the web image it selects, and then runs Compose with a
# binding override as the LAST -f: services.web is pinned to the verified
# immutable reference with pull_policy: never. A tag move, pull_policy/build in
# any overlay, or a model edit after verification cannot substitute another web
# image. Invocations that cannot touch web (e.g. `up -d --no-deps
# gateway-service`, logs, ps) run unchanged.
# shellcheck source=lib/web-map-guard.sh
source "$ROOT/scripts/lib/web-map-guard.sh"
parkio_web_guard_split_args "$@"
parkio_web_guard_decide
if [ "$PWG_DECISION" = "refuse" ]; then
  echo "ERROR: web map deploy guard: $PWG_REASON" >&2
  exit 1
fi
if [ "$PWG_DECISION" = "run" ]; then
  skip_rc=0
  parkio_web_guard_skip_requested || skip_rc=$?
  if [ "$skip_rc" -eq 2 ]; then
    exit 1
  elif [ "$skip_rc" -ne 0 ]; then
    guard_dir="$(mktemp -d)"
    chmod 700 "$guard_dir"
    trap 'rm -rf "$guard_dir"' EXIT
    # The rendered model contains interpolated env values: private dir, removed on exit.
    docker compose --env-file "$ENV_FILE" "${ARGS[@]}" "${PWG_GLOBAL[@]}" config --format json >"$guard_dir/model.json" \
      || { echo "ERROR: web map deploy guard: cannot render the compose model" >&2; exit 1; }
    parkio_web_guard_bind "$guard_dir/model.json" "$guard_dir/web-binding.yml" --env-file "$ENV_FILE" \
      || { echo "ERROR: web map deploy guard failed; nothing was started" >&2; exit 1; }
    rm -f "$guard_dir/model.json"
    if [ -f "$guard_dir/web-binding.yml" ]; then
      rc=0
      docker compose --env-file "$ENV_FILE" "${ARGS[@]}" "${PWG_GLOBAL[@]}" -f "$guard_dir/web-binding.yml" \
        "$PWG_SUBCMD" "${PWG_SUBARGS[@]}" || rc=$?
      exit "$rc"
    fi
  fi
fi
exec docker compose --env-file "$ENV_FILE" "${ARGS[@]}" "$@"
