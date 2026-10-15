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
# Canonical Civo overlays via shared N-10 helper (scripts/lib/prod-compose-overlays.sh).
# Policy civo: append only when this invocation uses the hosted-beta production
# file list. Caller-supplied lists, including web-map guard fixtures, must not
# grow Alertmanager or Option C non-root user: values.
#
# Live activation of the non-root overlay requires every overlay-affected volume
# to be COMPLIANT (N-05 / #354). Mutating commands (up|create|run) run a read-only
# plan first; see docs/operations/nonroot-volume-migration.md.
# Break-glass: PARKIO_SKIP_NONROOT_VOLUME_GATE=I_ACCEPT_UNMIGRATED_VOLUMES (default off).
# shellcheck source=lib/prod-compose-overlays.sh
source "$ROOT/scripts/lib/prod-compose-overlays.sh"
mapfile -t _PARKIO_CIVO_OVERLAY_RELS < <(parkio_select_production_overlays "$ROOT" civo "$FILES_LIST") || exit 1
# Process substitution runs the helper in a subshell; derive flags from the returned paths.
PARKIO_CIVO_NONROOT_OVERLAY=0
PARKIO_CIVO_ALERTMANAGER_OVERLAY=0
for _rel in "${_PARKIO_CIVO_OVERLAY_RELS[@]}"; do
  [ -n "$_rel" ] || continue
  ARGS+=(-f "$ROOT/$_rel")
  case "$_rel" in
    "$PARKIO_CIVO_NONROOT_OVERLAY_REL") PARKIO_CIVO_NONROOT_OVERLAY=1 ;;
    "$PARKIO_CIVO_ALERTMANAGER_OVERLAY_REL") PARKIO_CIVO_ALERTMANAGER_OVERLAY=1 ;;
  esac
done
unset _rel _PARKIO_CIVO_OVERLAY_RELS
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
# N-05: volume readiness before any mutating command that carries the non-root overlay.
# shellcheck source=lib/prod-nonroot-volume-gate.sh
source "$ROOT/scripts/lib/prod-nonroot-volume-gate.sh"
if [ "$PARKIO_CIVO_NONROOT_OVERLAY" = "1" ]; then
  parkio_nonroot_volume_gate_maybe "$@" || exit 1
fi
# Web map deploy guard (audit F-05). Any invocation that can create the web
# container renders the merged model once (including operator -f/--profile
# globals), verifies the web image it selects, and then runs Compose with a
# binding override as the LAST -f: services.web is pinned to the verified
# immutable reference with pull_policy: never. A tag move, pull_policy/build in
# any overlay, or a model edit after verification cannot substitute another web
# image. Invocations that cannot touch web (e.g. `up -d --no-deps
# gateway-service`, logs, ps) run unchanged.
# The same rendered model then goes to the conf.d check (B8b): a tmpfs at /etc/nginx/conf.d needs a
# web image that renders it at start (#198 or later), so an older bound image is refused before
# anything starts. Each check has its own break-glass, and neither is set by default:
#   - PARKIO_SKIP_WEB_MAP_GUARD=I_ACCEPT_UNVERIFIED_WEB_IMAGE skips the map guard and the binding. The
#     conf.d check then inspects the image the model names.
#   - PARKIO_SKIP_WEB_CONF_D_CHECK=I_ACCEPT_UNCHECKED_WEB_CONF_D skips only the conf.d check.
# shellcheck source=lib/web-map-guard.sh
source "$ROOT/scripts/lib/web-map-guard.sh"
# shellcheck source=lib/web-conf-d-guard.sh
source "$ROOT/scripts/lib/web-conf-d-guard.sh"
# H2 (owner decision 2026-10-05): the same model then goes to the API endpoint check. The web image
# must call the API the env intends (web's VITE_API_BASE_URL build argument, on PARKIO_DOMAIN). It
# has no break-glass, so it runs even when both checks above are skipped.
# shellcheck source=lib/web-api-endpoint-guard.sh
source "$ROOT/scripts/lib/web-api-endpoint-guard.sh"
# shellcheck source=lib/deploy-image-provenance.sh
source "$ROOT/scripts/lib/deploy-image-provenance.sh"
parkio_web_guard_split_args "$@"
parkio_web_guard_decide

# N-17: mutating invocations render the model and fail closed on unpinned /
# missing / wrong-identity / invalid-attestation images before any up/create/run.
_parkio_mutating=0
case "${PWG_SUBCMD:-}" in
  up|create|run) _parkio_mutating=1 ;;
esac
if [ -n "${PWG_AMBIGUOUS:-}" ]; then
  for _a in "$@"; do
    case "$_a" in up|create|run) _parkio_mutating=1 ;; esac
  done
fi

if [ "$_parkio_mutating" = "1" ]; then
  if [ "$PWG_DECISION" = "refuse" ]; then
    echo "ERROR: web map deploy guard: $PWG_REASON" >&2
    exit 1
  fi
  map_rc=0
  conf_d_rc=0
  if [ "$PWG_DECISION" = "run" ]; then
    parkio_web_guard_skip_requested || map_rc=$?
    parkio_web_conf_d_skip_requested || conf_d_rc=$?
    if [ "$map_rc" -eq 2 ] || [ "$conf_d_rc" -eq 2 ]; then
      exit 1
    fi
  fi
  guard_dir="$(mktemp -d)"
  chmod 700 "$guard_dir"
  trap 'rm -rf "$guard_dir"' EXIT
  docker compose --env-file "$ENV_FILE" "${ARGS[@]}" "${PWG_GLOBAL[@]}" config --format json >"$guard_dir/model.json" \
    || { echo "ERROR: deploy image provenance: cannot render the compose model" >&2; exit 1; }
  if [ "$PWG_DECISION" = "run" ] && [ "$map_rc" -ne 0 ]; then
    parkio_web_guard_bind "$guard_dir/model.json" "$guard_dir/web-binding.yml" --env-file "$ENV_FILE" \
      || { echo "ERROR: web map deploy guard failed; nothing was started" >&2; exit 1; }
  fi
  if [ "$PWG_DECISION" = "run" ] && [ "$conf_d_rc" -ne 0 ]; then
    parkio_web_conf_d_check "$guard_dir/model.json" "$guard_dir/web-binding.yml" \
      || { echo "ERROR: web conf.d check failed; nothing was started" >&2; exit 1; }
  fi
  if [ "$PWG_DECISION" = "run" ]; then
    parkio_web_api_endpoint_check "$guard_dir/model.json" "$guard_dir/web-binding.yml" \
      || { echo "ERROR: web API endpoint check failed; nothing was started" >&2; exit 1; }
  fi
  parkio_deploy_image_provenance_check "$guard_dir/model.json" \
    || { echo "ERROR: image provenance check failed; nothing was started" >&2; exit 1; }
  rm -f "$guard_dir/model.json"
  if [ -f "$guard_dir/web-binding.yml" ]; then
    rc=0
    docker compose --env-file "$ENV_FILE" "${ARGS[@]}" "${PWG_GLOBAL[@]}" -f "$guard_dir/web-binding.yml" \
      "$PWG_SUBCMD" "${PWG_SUBARGS[@]}" || rc=$?
    exit "$rc"
  fi
  exec docker compose --env-file "$ENV_FILE" "${ARGS[@]}" "${PWG_GLOBAL[@]}" \
    "$PWG_SUBCMD" "${PWG_SUBARGS[@]}"
fi

if [ "$PWG_DECISION" = "refuse" ]; then
  echo "ERROR: web map deploy guard: $PWG_REASON" >&2
  exit 1
fi
exec docker compose --env-file "$ENV_FILE" "${ARGS[@]}" "$@"
