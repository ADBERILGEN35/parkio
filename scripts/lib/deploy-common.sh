#!/usr/bin/env bash
# Shared helpers for hosted-beta deploy / rollback / smoke.
# shellcheck shell=bash

PARKIO_APP_SERVICES=(
  gateway-service
  auth-service
  user-service
  parking-service
  media-service
  gamification-service
  notification-service
  moderation-service
  ai-validation-service
  analytics-service
  web
)

PARKIO_REQUIRED_HEALTHY=(
  kafka redis minio clamav
  postgres-auth postgres-user postgres-parking postgres-media postgres-gamification
  postgres-notification postgres-moderation postgres-analytics postgres-ai-validation
  gateway-service auth-service user-service parking-service media-service
  gamification-service notification-service moderation-service ai-validation-service analytics-service
  web
)

PARKIO_AZURE_RUNTIME_SERVICES=(
  postgres-auth postgres-gateway postgres-user postgres-parking postgres-media
  postgres-gamification postgres-notification postgres-moderation postgres-analytics
  postgres-ai-validation redis kafka kafka-exporter blackbox-exporter node-exporter
  minio minio-setup clamav prometheus grafana
  auth-service user-service parking-service media-service gamification-service
  notification-service moderation-service ai-validation-service analytics-service
  gateway-service web caddy
)

PARKIO_AZURE_REQUIRED_HEALTHY=(
  kafka redis minio clamav prometheus grafana
  postgres-auth postgres-gateway postgres-user postgres-parking postgres-media
  postgres-gamification postgres-notification postgres-moderation postgres-analytics
  postgres-ai-validation gateway-service auth-service user-service parking-service
  media-service gamification-service notification-service moderation-service
  ai-validation-service analytics-service web caddy
)

# PROD-DEPLOY-01A-R4. Caddy is deliberately ABSENT from the dark runtime.
#
# docker/caddy/Caddyfile enables Caddy's automatic HTTPS against production
# Let's Encrypt for {$PARKIO_DOMAIN}/{$PARKIO_WEB_DOMAIN}/{$PARKIO_MEDIA_DOMAIN},
# which the invite-production env renders to the real api/app/media.parkio.dev.
# Those names still resolve to the hosted-beta VM, so starting Caddy here would
# make the dark runtime issue public ACME orders for production hostnames it
# cannot validate — an externally visible side effect that also burns the
# Let's Encrypt failed-validation budget needed for the PROD-DEPLOY-01B cutover.
#
# Nothing in dark acceptance needs Caddy: no service declares `depends_on:
# caddy`, smoke never contacts it, and the dark endpoint is gateway-service on
# 127.0.0.1:8080. Omitting it makes the no-ACME invariant structural rather than
# configurational — the ACME client is never started, so no config edit or
# overlay-ordering change can re-enable issuance. The production Caddy path is
# untouched and stays available for PROD-DEPLOY-01B.
#
# Enforced by scripts/assert-invite-dark-acme-isolation.sh.
PARKIO_INVITE_RUNTIME_SERVICES=(
  redis kafka kafka-exporter blackbox-exporter node-exporter
  minio minio-setup clamav prometheus grafana alertmanager
  auth-service user-service parking-service media-service gamification-service
  notification-service moderation-service ai-validation-service analytics-service
  gateway-service web
)

PARKIO_INVITE_REQUIRED_HEALTHY=(
  kafka redis minio clamav prometheus grafana alertmanager
  gateway-service auth-service user-service parking-service media-service
  gamification-service notification-service moderation-service ai-validation-service
  analytics-service web
)

PARKIO_RUNTIME_SERVICES=()
PARKIO_DISABLED_SERVICES=()

# Invite-production dark acceptance endpoint (PROD-DEPLOY-01A / D1). Sourced here
# so deploy, rollback and smoke all agree on the single allowed target.
# shellcheck source=dark-gateway-url.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/dark-gateway-url.sh"
# shellcheck source=invite-edge-mode.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/invite-edge-mode.sh"

parkio_env_value() {
  local env_file="$1"
  local key="$2"
  [ -f "$env_file" ] || return 0
  grep "^${key}=" "$env_file" | tail -n 1 | cut -d= -f2- \
    | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}

# Hosted-beta isolation (owner decision 2026-10-05, CL-F12 item 4). The default hosted-beta profile,
# which deploy-hosted-beta.sh, rollback-hosted-beta.sh and validate-hosted-beta-compose.sh use,
# refuses the repository's production configuration. Production is recognised by its public
# hostnames, recorded here as in assert-invite-dark-acme-isolation.sh. PARKIO_ENVIRONMENT cannot tell
# it apart: the production example (docker/.env.azure-hosted-beta.example) sets it to hosted-beta
# too. Production runs through scripts/parkio-prod-compose.sh, which does not use this profile. There
# is no break-glass.
PARKIO_PRODUCTION_HOSTNAMES=(api.parkio.dev app.parkio.dev media.parkio.dev)

# parkio_refuse_production_hostnames ENV_FILE PROFILE: renders the profile's model (PARKIO_COMPOSE_FILES)
# with `docker compose config`, which is read-only: nothing is built, pulled or started. It returns 2,
# naming each value, when a hostname the model gives the edge is production's:
#   - caddy's PARKIO_DOMAIN, PARKIO_WEB_DOMAIN and PARKIO_MEDIA_DOMAIN;
#   - the hosts in web's PARKIO_WEB_CSP_CONNECT_SRC.
# The model holds the values exactly as Compose resolves them from the env file and the process env,
# whatever the formatting: comments, `export`, whitespace, quotes (#288 review B1). A port, case and a
# trailing dot are ignored. A model that cannot be rendered, or whose caddy hostnames cannot be read,
# is refused too.
parkio_refuse_production_hostnames() {
  local env_file="$1" profile="$2" dir rc=0
  dir="$(mktemp -d)"
  chmod 700 "$dir"
  # The rendered model holds interpolated env values: a private directory, removed below.
  if ! parkio_compose "$env_file" config --format json >"$dir/model.json" 2>"$dir/render.err"; then
    echo "ERROR: cannot render the $profile model to read the hostnames Compose resolves; the $profile profile refuses it." >&2
    sed -n '1,3p' "$dir/render.err" | sed 's/^/  /' >&2
    rm -rf -- "${dir:?}"
    return 2
  fi
  python3 - "$dir/model.json" "$profile" "${PARKIO_PRODUCTION_HOSTNAMES[@]}" <<'PY' || rc=$?
import json, re, sys

model_path, profile, production = sys.argv[1], sys.argv[2], set(sys.argv[3:])
services = json.load(open(model_path, encoding="utf-8")).get("services") or {}


def env(service):
    value = (services.get(service) or {}).get("environment") or {}
    if isinstance(value, list):
        value = dict(item.split("=", 1) for item in value if "=" in item)
    return {k: ("" if v is None else str(v)) for k, v in value.items()}


def host(value):
    """The host of a hostname, host:port or URL, lowercased, without a trailing dot."""
    text = value.strip().lower()
    text = re.sub(r"^[a-z][a-z0-9+.-]*://", "", text)
    text = re.split(r"[/?#]", text, 1)[0]
    text = text.rsplit("@", 1)[-1]
    text = re.sub(r":\d*$", "", text)
    return text.rstrip(".")


caddy = env("caddy")
found, unreadable = [], []
for key in ("PARKIO_DOMAIN", "PARKIO_WEB_DOMAIN", "PARKIO_MEDIA_DOMAIN"):
    value = caddy.get(key, "")
    if not host(value):
        unreadable.append(f"caddy {key}")
    elif host(value) in production:
        found.append(f"caddy {key}={value}")
for url in env("web").get("PARKIO_WEB_CSP_CONNECT_SRC", "").split():
    if "://" in url and host(url) in production:
        found.append(f"web PARKIO_WEB_CSP_CONNECT_SRC contains {url}")
if unreadable:
    print(f"ERROR: the {profile} model gives no hostname for {', '.join(unreadable)}; "
          f"the {profile} profile refuses a model it cannot check.", file=sys.stderr)
    sys.exit(2)
if found:
    for item in found:
        print(f"ERROR: {item} is a production hostname; the {profile} profile refuses production configuration.",
              file=sys.stderr)
    print("  Production runs through scripts/parkio-prod-compose.sh. A hosted-beta host needs its own hostnames",
          file=sys.stderr)
    print("  (PARKIO_DOMAIN, PARKIO_WEB_DOMAIN, PARKIO_MEDIA_DOMAIN). There is no override.", file=sys.stderr)
    sys.exit(2)
PY
  rm -rf -- "${dir:?}"
  return "$rc"
}

parkio_configure_deployment_profile() {
  local env_file="$1"
  local requested="${PARKIO_DEPLOYMENT_PROFILE:-}"
  if [ -z "$requested" ]; then
    # An env file without the key selects the default. parkio_env_value fails under pipefail when
    # the key is absent, which used to end a `set -e` caller here silently.
    requested="$(parkio_env_value "$env_file" PARKIO_DEPLOYMENT_PROFILE || true)"
  fi
  requested="${requested:-hosted-beta}"

  case "$requested" in
    hosted-beta)
      # CL-F12, owner decision 1b: a supported deployment path while hosted-beta-deploy.yml uses it.
      # Deploy, rollback and DR render docker/compose.production.files exactly, so the model keeps
      # the digest pins and the auth registration settings production carries. Deploy builds only
      # the services the list does not pin, and rollback re-points those to their recorded tags
      # (parkio_hosted_beta_image_plan); a pin is rolled back by reverting its pin file. The Civo
      # wrapper renders the same list plus its Civo-host-specific Alertmanager overlay, so the two
      # rendered models differ by that overlay. The Azure overlay in the list puts these four
      # services in an inactive profile.
      PARKIO_COMPOSE_FILES="$(parkio_canonical_compose_files)" || return 2
      parkio_refuse_production_hostnames "$env_file" hosted-beta || return 2
      PARKIO_RUNTIME_SERVICES=()
      PARKIO_DISABLED_SERVICES=(alertmanager loki promtail tempo)
      ;;
    azure-hosted-beta)
      # The file set comes from docker/compose.production.files, the list
      # scripts/parkio-prod-compose.sh also renders, so deploy and rollback cannot drop a
      # pin or overlay production carries (CL-F12). One delta: docker-compose.images.yml
      # follows docker-compose.apps.yml so deploy builds get immutable sha tags and OCI
      # labels; every digest pin in the list comes later and still wins. The Civo
      # Alertmanager activation overlay is appended only by scripts/parkio-prod-compose.sh.
      PARKIO_COMPOSE_FILES="$(parkio_azure_compose_files)" || return 2
      PARKIO_RUNTIME_SERVICES=("${PARKIO_AZURE_RUNTIME_SERVICES[@]}")
      PARKIO_DISABLED_SERVICES=(alertmanager loki promtail tempo)
      PARKIO_REQUIRED_HEALTHY=("${PARKIO_AZURE_REQUIRED_HEALTHY[@]}")
      ;;
    invite-production)
      local edge_mode acme_authorized
      edge_mode="$(parkio_invite_edge_mode_from_env "$env_file")" || return 2
      acme_authorized="$(parkio_invite_acme_authorized_from_env "$env_file")" || return 2

      # The auth registration overlay passes every PARKIO_REGISTRATION_* setting, each off by default
      # (F-INV-1, owner decision 2026-10-05). It comes before the edge overlay, whose `:?` mapping of
      # PARKIO_REGISTRATION_MODE still refuses a render without the mode.
      local invite_base="-f docker/docker-compose.yml -f docker/docker-compose.apps.yml -f docker/docker-compose.images.yml -f docker/docker-compose.hosted-beta.yml -f docker/docker-compose.managed-db.yml -f docker/docker-compose.auth-registration-env.yml"
      local disabled_common=(
        postgres-auth postgres-gateway postgres-user postgres-parking postgres-media
        postgres-gamification postgres-notification postgres-moderation postgres-analytics
        postgres-ai-validation promtail
      )

      if [ "$edge_mode" = "public" ]; then
        # PUBLIC candidate: Caddy is the sole public edge; gateway stays internal.
        # Caddy remains disabled until PARKIO_INVITE_ACME_AUTHORIZED=true (01B-03 gate).
        PARKIO_COMPOSE_FILES="${invite_base} -f docker/docker-compose.invite-public.yml"
        if [ "$acme_authorized" != "true" ]; then
          # Pre-cutover staging keeps loopback acceptance while Caddy stays off
          # (PROD-DEPLOY-01B-02). Omit this overlay once ACME is authorized.
          PARKIO_COMPOSE_FILES="${PARKIO_COMPOSE_FILES} -f docker/docker-compose.invite-public-staged.yml"
        fi
        PARKIO_RUNTIME_SERVICES=("${PARKIO_INVITE_RUNTIME_SERVICES[@]}")
        if [ "$acme_authorized" = "true" ]; then
          PARKIO_RUNTIME_SERVICES+=(caddy)
        fi
        PARKIO_DISABLED_SERVICES=("${disabled_common[@]}")
        if [ "$acme_authorized" != "true" ]; then
          PARKIO_DISABLED_SERVICES+=(caddy)
        fi
        PARKIO_REQUIRED_HEALTHY=("${PARKIO_INVITE_REQUIRED_HEALTHY[@]}")
        if [ "$acme_authorized" = "true" ]; then
          PARKIO_REQUIRED_HEALTHY+=(caddy)
        fi
      else
        # DARK (default): docker-compose.invite-dark.yml MUST stay last — it re-publishes
        # gateway-service on 127.0.0.1:8080 with `!override`, which only wins if it
        # is merged after the hosted-beta overlay's `ports: !reset []`.
        PARKIO_COMPOSE_FILES="${invite_base} -f docker/docker-compose.invite-dark.yml"
        PARKIO_RUNTIME_SERVICES=("${PARKIO_INVITE_RUNTIME_SERVICES[@]}")
        # `caddy` is listed so the dark omission is explicit in the deploy
        # manifest and asserted, never a silent gap (PROD-DEPLOY-01A-R4).
        # Loki and Tempo are not explicit roots but Grafana starts both through
        # depends_on, so only Promtail is absent from the observability runtime.
        PARKIO_DISABLED_SERVICES=("${disabled_common[@]}" caddy)
        PARKIO_REQUIRED_HEALTHY=("${PARKIO_INVITE_REQUIRED_HEALTHY[@]}")
      fi
      export PARKIO_INVITE_EDGE_MODE="$edge_mode"
      export PARKIO_INVITE_ACME_AUTHORIZED="$acme_authorized"
      ;;
    *)
      echo "ERROR: unsupported PARKIO_DEPLOYMENT_PROFILE='$requested' (expected hosted-beta, azure-hosted-beta, or invite-production)" >&2
      return 2
      ;;
  esac

  PARKIO_DEPLOYMENT_PROFILE="$requested"
  export PARKIO_DEPLOYMENT_PROFILE PARKIO_COMPOSE_FILES
}

# Owner decision B7 (2026-10-03): the canonical wrapper, scripts/parkio-prod-compose.sh with
# docker/compose.production.files, is the supported production path. The azure-hosted-beta
# deploy/rollback path is left unchanged for existing environments but is deprecated; this only
# warns, so nothing that runs today changes behaviour.
parkio_warn_deprecated_production_path() {
  if [ "${PARKIO_DEPLOYMENT_PROFILE:-}" = "azure-hosted-beta" ]; then
    cat >&2 <<'WARN'
WARNING: the azure-hosted-beta deploy/rollback path is deprecated as a production path (B7).
  Supported: scripts/parkio-prod-compose.sh with docker/compose.production.files.
  This run continues unchanged. Known gaps of this path: it cannot build digest-pinned
  services, it adds docker-compose.images.yml, and it has no Civo Alertmanager overlay.
  See docs/azure/AZURE-DEPLOYMENT-PROFILE.md.
WARN
  fi
}

# Prints the canonical production compose files (docker/compose.production.files, or
# PARKIO_COMPOSE_FILES_LIST like scripts/parkio-prod-compose.sh), one path per line.
parkio_production_compose_files() {
  local list="${PARKIO_COMPOSE_FILES_LIST:-$(parkio_repo_root)/docker/compose.production.files}"
  if [ ! -f "$list" ]; then
    echo "ERROR: missing canonical production compose file list $list" >&2
    return 2
  fi
  local line
  while IFS= read -r line || [ -n "$line" ]; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    printf '%s\n' "$line"
  done < "$list"
}

# The canonical production list as Compose -f arguments, exactly as docker/compose.production.files
# names it. The hosted-beta profile renders it for deploy, rollback and DR (CL-F12 decision 1b).
parkio_canonical_compose_files() {
  local files="" file production_files
  production_files="$(parkio_production_compose_files)" || return 2
  while IFS= read -r file; do
    files="${files} -f ${file}"
  done <<< "$production_files"
  printf '%s\n' "${files# }"
}

# For the hosted-beta profile: how the rendered model gets each app service's image. Prints one
# line per app service, in PARKIO_APP_SERVICES order, tab-separated:
#   built  <service> <image> <platform>  the model builds it with no digest pin. <image> is the
#                                        model's image name, or <project>-<service> as Compose
#                                        names a build-only service.
#   pinned <service> <image> <platform>  the model runs this digest-pinned image.
# <platform> is the model's platform, or "-". Fails when an app service is missing from the model,
# or runs an image it neither builds nor pins by digest.
parkio_hosted_beta_image_plan() {
  local env_file="$1" model rc=0
  model="$(mktemp)"
  chmod 600 "$model"
  # The rendered model holds interpolated env values: private file, removed below.
  if ! parkio_compose "$env_file" config --format json >"$model"; then
    rm -f "$model"
    echo "ERROR: cannot render the compose model to plan the hosted-beta images" >&2
    return 1
  fi
  python3 - "$model" "${PARKIO_APP_SERVICES[@]}" <<'PY' || rc=$?
import json, sys
model = json.load(open(sys.argv[1]))
project = model.get("name")
services = model.get("services") or {}
lines, problems = [], []
for name in sys.argv[2:]:
    service = services.get(name)
    if not isinstance(service, dict):
        problems.append(f"{name} is not in the model")
        continue
    image = service.get("image") or ""
    platform = service.get("platform") or "-"
    if "@sha256:" in image:
        lines.append(f"pinned\t{name}\t{image}\t{platform}")
    elif service.get("build") and (image or project):
        lines.append(f"built\t{name}\t{image or project + '-' + name}\t{platform}")
    else:
        problems.append(f"{name} runs {image or 'no image'} that the model neither builds nor pins by digest")
if problems:
    print("ERROR: hosted-beta image plan: " + "; ".join(problems), file=sys.stderr)
    sys.exit(1)
print("\n".join(lines))
PY
  rm -f "$model"
  return "$rc"
}

# A digest-pinned image must be present before `up --no-build` and the web map guard, which never
# pulls. Pulls it when it is missing, and fails when it is still missing.
parkio_ensure_pinned_image() {
  local svc="$1" image="$2" platform="${3:--}"
  if docker image inspect "$image" >/dev/null 2>&1; then
    return 0
  fi
  echo "Pulling the pinned $svc image $image..."
  local pull_args=(pull)
  [ "$platform" != "-" ] && pull_args+=(--platform "$platform")
  if ! docker "${pull_args[@]}" "$image"; then
    echo "ERROR: cannot pull the pinned $svc image $image. The host needs read access to its registry." >&2
    return 1
  fi
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    echo "ERROR: the pinned $svc image $image is not present after the pull" >&2
    return 1
  fi
}

# The azure-hosted-beta (production) deploy/rollback file set as -f arguments: the
# canonical production list with docker-compose.images.yml right after the apps overlay.
parkio_azure_compose_files() {
  local files="" file production_files
  production_files="$(parkio_production_compose_files)" || return 2
  while IFS= read -r file; do
    files="${files} -f ${file}"
    if [ "$file" = "docker/docker-compose.apps.yml" ]; then
      files="${files} -f docker/docker-compose.images.yml"
    fi
  done <<< "$production_files"
  if [[ "$files" != *" -f docker/docker-compose.images.yml"* ]]; then
    echo "ERROR: canonical production list has no docker/docker-compose.apps.yml to build from" >&2
    return 2
  fi
  printf '%s\n' "${files# }"
}

parkio_repo_root() {
  local here
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  echo "$here"
}

parkio_git_sha() {
  git -C "$(parkio_repo_root)" rev-parse HEAD
}

parkio_git_branch() {
  git -C "$(parkio_repo_root)" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "DETACHED"
}

parkio_git_is_dirty() {
  ! git -C "$(parkio_repo_root)" diff --quiet || ! git -C "$(parkio_repo_root)" diff --cached --quiet \
    || [ -n "$(git -C "$(parkio_repo_root)" ls-files --others --exclude-standard)" ]
}

parkio_image_tag_for_sha() {
  echo "sha-${1}"
}

parkio_image_ref() {
  echo "parkio/${1}:${2}"
}

# Runtime Compose. When PARKIO_COMPOSE_BASE_DIR is set (the invite-production
# deploy points it at the stable runtime release) every relative bind mount in
# the model resolves against that stable directory instead of the ephemeral
# Actions checkout — see scripts/lib/runtime-release.sh for why that matters.
# Compose keys the project off `name: parkio` in the model, so changing the base
# directory never forks the project identity.
parkio_compose() {
  local env_file="$1"
  shift
  local base="${PARKIO_COMPOSE_BASE_DIR:-}"
  if [ -n "$base" ]; then
    # shellcheck disable=SC2086
    (cd "$base" && docker compose --env-file "$env_file" $PARKIO_COMPOSE_FILES "$@")
    return
  fi
  # shellcheck disable=SC2086
  docker compose --env-file "$env_file" $PARKIO_COMPOSE_FILES "$@"
}

# Build Compose. Builds need the source tree (`context: ..` reaches the repo
# root), so they always run against the checkout, never against a release — a
# release deliberately contains config only.
parkio_compose_build() {
  local env_file="$1"
  shift
  # shellcheck disable=SC2086
  (cd "$(parkio_repo_root)" && docker compose --env-file "$env_file" $PARKIO_COMPOSE_FILES "$@")
}

# Compose v2.24+ omits inactive-profile services from the resolved model
# (`config --format json` / `config --services`), so disabled-service
# enforcement must not read `.services[<svc>].profiles` from the rendered
# JSON. Instead, validate from the declared profile list and the default
# active service set, and reject disabled services in the explicit runtime
# target.
#   $1: output of `docker compose ... config --profiles`
#   $2: output of `docker compose ... config --services` (no profiles active)
parkio_validate_azure_disabled_services() {
  local profiles_output="$1"
  local active_services_output="$2"
  local svc

  if ! grep -qx 'azure-disabled-observability' <<<"$profiles_output"; then
    echo "ERROR: profile 'azure-disabled-observability' is missing from the compose model" >&2
    return 1
  fi

  for svc in "${PARKIO_DISABLED_SERVICES[@]}"; do
    if grep -qx "$svc" <<<"$active_services_output"; then
      echo "ERROR: disabled service '$svc' is active in the default compose model" >&2
      return 1
    fi
    if [[ " ${PARKIO_RUNTIME_SERVICES[*]} " == *" $svc "* ]]; then
      echo "ERROR: disabled service '$svc' appears in the explicit Azure runtime target" >&2
      return 1
    fi
  done
  return 0
}

# Web map deploy guard (audit F-05). Verify the web image the rendered model
# selects, then start the stack with a binding override as the LAST -f that pins
# services.web to the verified immutable reference with pull_policy: never.
# Covers deploy-hosted-beta, deploy-invite-production and both rollbacks. The same
# rendered model then goes to the conf.d check (B8b, scripts/lib/web_conf_d_guard.py).
# Each check has its own default-off break-glass: PARKIO_SKIP_WEB_MAP_GUARD skips the map guard and
# the binding (the conf.d check then inspects the model's web image); PARKIO_SKIP_WEB_CONF_D_CHECK
# skips only the conf.d check. For the hosted-beta profile, the API endpoint check (H2,
# scripts/lib/web_api_endpoint_guard.py) then requires the web image to call the API its env
# intends. It has no break-glass, so the model is rendered even when both checks above are skipped.
parkio_web_guard_before_up() {
  local env_file="$1" binding_out="$2" map_rc=0 conf_d_rc=0 guard_dir rc=0 api_check=0
  # shellcheck source=web-map-guard.sh
  source "$(parkio_repo_root)/scripts/lib/web-map-guard.sh"
  # shellcheck source=web-conf-d-guard.sh
  source "$(parkio_repo_root)/scripts/lib/web-conf-d-guard.sh"
  # shellcheck source=web-api-endpoint-guard.sh
  source "$(parkio_repo_root)/scripts/lib/web-api-endpoint-guard.sh"
  if [ "${#PARKIO_RUNTIME_SERVICES[@]}" -gt 0 ]; then
    parkio_web_guard_split_args up -d "${PARKIO_RUNTIME_SERVICES[@]}"
  else
    parkio_web_guard_split_args up -d
  fi
  parkio_web_guard_decide
  [ "$PWG_DECISION" = "run" ] || return 0
  parkio_web_guard_skip_requested || map_rc=$?
  parkio_web_conf_d_skip_requested || conf_d_rc=$?
  { [ "$map_rc" -eq 2 ] || [ "$conf_d_rc" -eq 2 ]; } && return 1
  [ "${PARKIO_DEPLOYMENT_PROFILE:-}" = "hosted-beta" ] && api_check=1
  [ "$map_rc" -eq 0 ] && [ "$conf_d_rc" -eq 0 ] && [ "$api_check" -eq 0 ] && return 0
  guard_dir="$(mktemp -d)"
  chmod 700 "$guard_dir"
  if ! parkio_compose "$env_file" config --format json >"$guard_dir/model.json"; then
    rm -rf "$guard_dir"
    echo "ERROR: web map deploy guard: cannot render the compose model" >&2
    return 1
  fi
  if [ "$map_rc" -ne 0 ]; then
    parkio_web_guard_bind "$guard_dir/model.json" "$binding_out" --env-file "$env_file" || rc=$?
    if [ "$rc" -ne 0 ]; then
      rm -rf "$guard_dir"
      echo "ERROR: web map deploy guard failed; nothing was started" >&2
      return 1
    fi
  fi
  # B8b: a tmpfs at /etc/nginx/conf.d needs a web image that renders it at start (#198 or later).
  if [ "$conf_d_rc" -ne 0 ] && ! parkio_web_conf_d_check "$guard_dir/model.json" "$binding_out"; then
    rm -rf "$guard_dir"
    echo "ERROR: web conf.d check failed; nothing was started" >&2
    return 1
  fi
  # H2: a web image built for another API than this env intends is refused (no break-glass).
  if [ "$api_check" -eq 1 ] && ! parkio_web_api_endpoint_check "$guard_dir/model.json" "$binding_out"; then
    rm -rf "$guard_dir"
    echo "ERROR: web API endpoint check failed; nothing was started" >&2
    return 1
  fi
  rm -rf "$guard_dir"
}

parkio_compose_up() {
  local env_file="$1" binding_dir rc=0
  binding_dir="$(mktemp -d)"
  chmod 700 "$binding_dir"
  if ! parkio_web_guard_before_up "$env_file" "$binding_dir/web-binding.yml"; then
    rm -rf "$binding_dir"
    return 1
  fi
  # Dynamic scope: parkio_compose below sees the binding override appended last.
  local PARKIO_COMPOSE_FILES="$PARKIO_COMPOSE_FILES"
  if [ -f "$binding_dir/web-binding.yml" ]; then
    PARKIO_COMPOSE_FILES="$PARKIO_COMPOSE_FILES -f $binding_dir/web-binding.yml"
  fi
  # PARKIO_COMPOSE_UP_NO_BUILD=1 (hosted-beta deploy and rollback) forbids Compose from building anything.
  local up_args=(up -d)
  [ "${PARKIO_COMPOSE_UP_NO_BUILD:-0}" = "1" ] && up_args+=(--no-build)
  if [ "${#PARKIO_RUNTIME_SERVICES[@]}" -gt 0 ]; then
    parkio_compose "$env_file" "${up_args[@]}" "${PARKIO_RUNTIME_SERVICES[@]}" || rc=$?
  else
    parkio_compose "$env_file" "${up_args[@]}" || rc=$?
  fi
  rm -rf "$binding_dir"
  return "$rc"
}

parkio_default_gateway_url() {
  case "${PARKIO_DEPLOYMENT_PROFILE:-hosted-beta}" in
    azure-hosted-beta)
      echo "https://api.parkio.dev"
      ;;
    invite-production)
      # NEVER the public route. api.parkio.dev resolves to the hosted-beta VM
      # until the PROD-DEPLOY-01B cutover, so defaulting there would point dark
      # acceptance at live hosted-beta. The dark topology publishes exactly one
      # endpoint (docker/docker-compose.invite-dark.yml) and this is it.
      echo "$PARKIO_DARK_GATEWAY_ALLOWED_URL"
      ;;
    *)
      echo "http://127.0.0.1:8080"
      ;;
  esac
}

parkio_runtime_services_json() {
  local out="[" first=1 svc
  for svc in "${PARKIO_RUNTIME_SERVICES[@]}"; do
    if [ "$first" -eq 1 ]; then first=0; else out+=","; fi
    out+="\"${svc}\""
  done
  out+="]"
  echo "$out"
}

parkio_disabled_services_json() {
  local out="[" first=1 svc
  for svc in "${PARKIO_DISABLED_SERVICES[@]}"; do
    if [ "$first" -eq 1 ]; then first=0; else out+=","; fi
    out+="\"${svc}\""
  done
  out+="]"
  echo "$out"
}

parkio_image_digests_json() {
  local image_tag="$1" out="{" first=1 svc ref digest
  shift
  # Optional service names: the hosted-beta profile records only the services it builds.
  local services=("${PARKIO_APP_SERVICES[@]}")
  [ "$#" -gt 0 ] && services=("$@")
  for svc in "${services[@]}"; do
    ref="$(parkio_image_ref "$svc" "$image_tag")"
    digest="$(docker image inspect --format '{{.Id}}' "$ref" 2>/dev/null || true)"
    if [ "$first" -eq 1 ]; then first=0; else out+=","; fi
    if [ -n "$digest" ]; then
      out+="\"${svc}\":\"${digest}\""
    else
      out+="\"${svc}\":null"
    fi
  done
  out+="}"
  echo "$out"
}

parkio_feature_flags_json() {
  local env_file="$1"
  jq -n \
    --arg accountErasure "$(parkio_env_value "$env_file" PARKIO_ACCOUNT_ERASURE_ENABLED)" \
    --arg municipal "$(parkio_env_value "$env_file" PARKIO_MUNICIPAL_ENABLED)" \
    --arg izum "$(parkio_env_value "$env_file" PARKIO_MUNICIPAL_IZUM_ENABLED)" \
    --arg ispark "$(parkio_env_value "$env_file" PARKIO_MUNICIPAL_ISPARK_ENABLED)" \
    --arg anpark "$(parkio_env_value "$env_file" PARKIO_MUNICIPAL_ANPARK_ENABLED)" \
    --arg konya "$(parkio_env_value "$env_file" PARKIO_MUNICIPAL_KONYA_ENABLED)" \
    --arg kayseri "$(parkio_env_value "$env_file" PARKIO_MUNICIPAL_KAYSERI_ENABLED)" \
    --arg osm "$(parkio_env_value "$env_file" PARKIO_MUNICIPAL_OSM_IMPORT_ENABLED)" \
    --arg recommendations "$(parkio_env_value "$env_file" PARKIO_SPA_RECOMMENDATIONS_ENABLED)" \
    --arg ranking "$(parkio_env_value "$env_file" PARKIO_SPA_RANKING_ENABLED)" \
    --arg strategy "$(parkio_env_value "$env_file" PARKIO_SPA_RANKING_STRATEGY)" \
    --arg shadow "$(parkio_env_value "$env_file" PARKIO_SPA_RANKING_SHADOW_ENABLED)" \
    --arg evaluation "$(parkio_env_value "$env_file" PARKIO_SPA_RANKING_EVALUATION_ENABLED)" \
    --arg rollup "$(parkio_env_value "$env_file" PARKIO_SPA_RANKING_EVALUATION_ROLLUP_ENABLED)" \
    '{
      PARKIO_ACCOUNT_ERASURE_ENABLED: $accountErasure,
      PARKIO_MUNICIPAL_ENABLED: $municipal,
      PARKIO_MUNICIPAL_IZUM_ENABLED: $izum,
      PARKIO_MUNICIPAL_ISPARK_ENABLED: $ispark,
      PARKIO_MUNICIPAL_ANPARK_ENABLED: $anpark,
      PARKIO_MUNICIPAL_KONYA_ENABLED: $konya,
      PARKIO_MUNICIPAL_KAYSERI_ENABLED: $kayseri,
      PARKIO_MUNICIPAL_OSM_IMPORT_ENABLED: $osm,
      PARKIO_SPA_RECOMMENDATIONS_ENABLED: $recommendations,
      PARKIO_SPA_RANKING_ENABLED: $ranking,
      PARKIO_SPA_RANKING_STRATEGY: $strategy,
      PARKIO_SPA_RANKING_SHADOW_ENABLED: $shadow,
      PARKIO_SPA_RANKING_EVALUATION_ENABLED: $evaluation,
      PARKIO_SPA_RANKING_EVALUATION_ROLLUP_ENABLED: $rollup
    }'
}

# Resolve the real merged Compose model, validate the controlled-invite matrix,
# and retain only an explicit allowlist of non-secret feature values. The raw
# resolved model may contain secrets and is therefore streamed, never persisted.
parkio_effective_feature_configuration_json() {
  local env_file="$1"
  parkio_compose "$env_file" config --format json \
    | python3 "$(parkio_repo_root)/scripts/lib/assert-invite-production-feature-config.py" --evidence \
        --public-explore-mode "${PARKIO_DISPATCH_PUBLIC_EXPLORE_MODE:-off}" \
        --public-explore-authorization "${PARKIO_DISPATCH_PUBLIC_EXPLORE_AUTHORIZATION:-}"
}

# F-INV-3 (owner decision 2026-10-05): the deployed release's recorded manifest. A deploy or a
# rollback records the manifest of the release it is about to start, before any of its containers
# start: Flyway may apply that release's migrations as soon as a service starts, even if the start
# then fails. A later rollback reads this record, never a checkout's deploy-artifacts/current.json,
# to know the live schema (parkio_assert_rollback_schema_compatible). A rollback is often a new
# workflow run on a clean checkout, so the record lives outside any checkout, in one place per host
# that every deployer shares (#290 review B2: a per-user record lets another user's older record pass):
#   - invite-production: its runtime root, beside the `current` release link (/opt/parkio/invite-production);
#   - hosted-beta and azure-hosted-beta: /var/lib/parkio/<profile>. Every user who deploys or rolls back
#     on the host must be able to write it, for example:
#       sudo install -d -m 2775 -g <deployers group> /var/lib/parkio/hosted-beta
#   - local-dev (--no-hosted-beta-overlay, a developer machine): ${XDG_STATE_HOME:-$HOME/.local/state}/parkio/local-dev.
# PARKIO_DEPLOY_STATE_DIR overrides all of them; the override is logged.
parkio_deploy_state_dir() {
  if [ -n "${PARKIO_DEPLOY_STATE_DIR:-}" ]; then
    echo "NOTE: deploy state directory overridden by PARKIO_DEPLOY_STATE_DIR: $PARKIO_DEPLOY_STATE_DIR" >&2
    printf '%s\n' "$PARKIO_DEPLOY_STATE_DIR"
    return 0
  fi
  case "${PARKIO_DEPLOYMENT_PROFILE:-hosted-beta}" in
    invite-production) printf '%s\n' "${PARKIO_RUNTIME_ROOT:-/opt/parkio/invite-production}" ;;
    local-dev) printf '%s/parkio/local-dev\n' "${XDG_STATE_HOME:-$HOME/.local/state}" ;;
    *) printf '/var/lib/parkio/%s\n' "${PARKIO_DEPLOYMENT_PROFILE:-hosted-beta}" ;;
  esac
}

# parkio_assert_deploy_state_dir_writable: exit-3-style refusal (returns 3) when the deploy state
# directory cannot be created or written, so a live deploy stops before it builds or starts anything.
parkio_assert_deploy_state_dir_writable() {
  local dir
  dir="$(parkio_deploy_state_dir)"
  if ! mkdir -p "$dir" 2>/dev/null || [ ! -w "$dir" ]; then
    echo "ERROR: the deploy state directory $dir is not writable by $(id -un)." >&2
    echo "       A deploy records the deployed release's manifest there, and a rollback refuses without it" >&2
    echo "       (F-INV-3). Create it for every deployer, e.g.: sudo install -d -m 2775 -g <group> $dir" >&2
    return 3
  fi
}

parkio_deployed_manifest_path() {
  printf '%s/deployed-manifest.json\n' "$(parkio_deploy_state_dir)"
}

# parkio_record_deployed_manifest MANIFEST: atomically records MANIFEST as the deployed release's.
# Returns non-zero, leaving the previous record in place, when it cannot; callers test the status,
# so each step checks its own.
parkio_record_deployed_manifest() {
  local manifest="$1" dest tmp
  dest="$(parkio_deployed_manifest_path)" || return 1
  mkdir -p "$(dirname "$dest")" || return 1
  tmp="$dest.tmp.$$"
  if ! cp -- "$manifest" "$tmp" || ! mv -f -T -- "$tmp" "$dest"; then
    rm -f -- "$tmp"
    echo "ERROR: cannot record the deployed release's manifest at $dest" >&2
    return 1
  fi
  echo "Deployed release manifest recorded: $dest"
}

parkio_wait_healthy() {
  local env_file="$1"
  local timeout_s="${2:-900}"
  local deadline status cid svc failed
  deadline=$((SECONDS + timeout_s))
  while true; do
    failed=0
    for svc in "${PARKIO_REQUIRED_HEALTHY[@]}"; do
      cid="$(parkio_compose "$env_file" ps -q "$svc" 2>/dev/null || true)"
      if [ -z "$cid" ]; then
        echo "  waiting: $svc (no container)"
        failed=1
        continue
      fi
      status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid")"
      if [ "$status" != "healthy" ]; then
        echo "  waiting: $svc ($status)"
        failed=1
      fi
    done
    if [ "$failed" -eq 0 ]; then
      echo "All required services are healthy."
      return 0
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "ERROR: timed out waiting for healthy services after ${timeout_s}s" >&2
      parkio_compose "$env_file" ps >&2 || true
      return 1
    fi
    sleep 10
  done
}

parkio_compose_files_json() {
  # PARKIO_COMPOSE_FILES is a shell word list: -f path -f path ...
  local -a words
  local out="["
  local first=1
  local i=0
  # shellcheck disable=SC2206
  words=($PARKIO_COMPOSE_FILES)
  while [ "$i" -lt "${#words[@]}" ]; do
    if [ "${words[$i]}" = "-f" ]; then
      i=$((i + 1))
      if [ "$first" -eq 1 ]; then first=0; else out+=","; fi
      out+="\"${words[$i]}\""
    fi
    i=$((i + 1))
  done
  out+="]"
  echo "$out"
}

# F-INV-2 (owner decision 2026-10-05): an invite-production rollback renders this checkout's compose
# file list against the target's staged release, so the list must be exactly the one the target
# deploy recorded in its manifest's composeFiles, in the same order. parkio_assert_rollback_compose_files
# TARGET_MANIFEST returns 0 when it is, and 3, before anything is written or started, when composeFiles
# is absent, malformed or different. A difference is reported as "compose file list changed (...)"
# with every added and removed file. Nothing is left out silently, and there is no override.
parkio_assert_rollback_compose_files() {
  local manifest="$1"
  python3 - "$manifest" "$(parkio_compose_files_json)" <<'PY'
import json
import re
import sys

manifest_path, current = sys.argv[1], json.loads(sys.argv[2])
COMPOSE_FILE = re.compile(r"docker/[A-Za-z0-9][A-Za-z0-9._-]*\.ya?ml")


def refuse(message, *advice):
    print(f"ERROR: {message}", file=sys.stderr)
    for line in advice:
        print(f"       {line}", file=sys.stderr)
    sys.exit(3)


NO_EVIDENCE = ("Without the target deploy's compose file list, the rollback cannot show that it renders the",
               "files that deploy ran, so it is refused (F-INV-2). There is no override.")
try:
    with open(manifest_path, encoding="utf-8") as fh:
        manifest = json.load(fh)
except (OSError, ValueError) as error:
    refuse(f"cannot read the target manifest's compose file list: {error}", *NO_EVIDENCE)
if not isinstance(manifest, dict) or "composeFiles" not in manifest:
    refuse("the target manifest records no composeFiles.", *NO_EVIDENCE)
target = manifest["composeFiles"]
if (not isinstance(target, list) or not target
        or not all(isinstance(f, str) and COMPOSE_FILE.fullmatch(f) for f in target)
        or len(set(target)) != len(target)):
    refuse(f"the target manifest's composeFiles is malformed: {json.dumps(target)[:400]}", *NO_EVIDENCE)
if target == current:
    print(f"rollback compose files: exactly the target deploy's list ({len(target)} files)")
    sys.exit(0)
added = [f for f in current if f not in target]
removed = [f for f in target if f not in current]
changes = [f"added {f}" for f in added] + [f"removed {f}" for f in removed] or ["same files, another order"]
refuse(f"compose file list changed ({'; '.join(changes)}).",
       f"The target deploy rendered: {json.dumps(target)}",
       f"This rollback would render: {json.dumps(current)}",
       "A rollback across a compose file-list change is refused (F-INV-2): no file is left out, and there",
       "is no override. Run it from a checkout whose list matches the target deploy's, or deploy a",
       "compatible release.")
PY
}

parkio_migration_versions_json() {
  # Source-tree Flyway scripts present at deploy time (not live DB state).
  local root svc dir f out first mig_first
  root="$(parkio_repo_root)"
  out="{"
  first=1
  for svc in "${PARKIO_APP_SERVICES[@]}"; do
    dir="$root/services/$svc/src/main/resources/db/migration"
    if [ "$first" -eq 1 ]; then first=0; else out+=","; fi
    out+="\"${svc}\":["
    mig_first=1
    if [ -d "$dir" ]; then
      for f in "$dir"/V*.sql; do
        [ -f "$f" ] || continue
        if [ "$mig_first" -eq 1 ]; then mig_first=0; else out+=","; fi
        out+="\"$(basename "$f")\""
      done
    fi
    out+="]"
  done
  out+="}"
  echo "$out"
}

parkio_write_manifest() {
  local manifest_path="$1"
  local action="$2"
  local operator="$3"
  local env_file="$4"
  local image_tag="$5"
  local git_sha="$6"
  local branch="$7"
  local created="$8"
  local version="$9"
  local previous_manifest="${10:-}"
  local compose_structure_path="${11:-}"
  local compose_files_json compose_structure_json images_json image_digests_json pinned_images_json migrations_json runtime_services_json disabled_services_json feature_flags_json effective_feature_configuration_json svc first rollback_target
  local plan kind image platform
  local image_services=("${PARKIO_APP_SERVICES[@]}")
  local requested_dark_gateway_input raw_dark_gateway_input_blank effective_dark_gateway_url dark_gateway_input_source

  requested_dark_gateway_input="${PARKIO_REQUESTED_DARK_GATEWAY_URL_INPUT_EVIDENCE:-}"
  raw_dark_gateway_input_blank="${PARKIO_RAW_DARK_GATEWAY_INPUT_BLANK:-}"
  effective_dark_gateway_url="${PARKIO_EFFECTIVE_DARK_GATEWAY_URL:-}"
  dark_gateway_input_source="${PARKIO_DARK_GATEWAY_INPUT_SOURCE:-}"

  if [ -n "$requested_dark_gateway_input$raw_dark_gateway_input_blank$effective_dark_gateway_url$dark_gateway_input_source" ]; then
    if [ "$requested_dark_gateway_input" != "<blank>" ] \
      || [ "$raw_dark_gateway_input_blank" != "true" ] \
      || [ "$effective_dark_gateway_url" != "$PARKIO_DARK_GATEWAY_ALLOWED_URL" ] \
      || [ "$dark_gateway_input_source" != "workflow_dispatch" ]; then
      echo "ERROR: incomplete or invalid dark gateway dispatch attestation." >&2
      return 2
    fi
  fi

  compose_files_json="$(parkio_compose_files_json)"
  migrations_json="$(parkio_migration_versions_json)"
  runtime_services_json="$(parkio_runtime_services_json)"
  disabled_services_json="$(parkio_disabled_services_json)"
  # hosted-beta (CL-F12): `images` lists only the services the model builds, under the tags this
  # deploy gives them and a rollback re-points to; `pinnedImages` lists the digest pins it runs.
  # azure-hosted-beta keeps every app service in `images` (docker-compose.images.yml tags them all),
  # and records the pins that win over those tags too: a rollback is refused when its pins differ
  # from the deployed release's (#290 review B1).
  pinned_images_json="null"
  if [ "$PARKIO_DEPLOYMENT_PROFILE" = "hosted-beta" ] || [ "$PARKIO_DEPLOYMENT_PROFILE" = "azure-hosted-beta" ]; then
    plan="${PARKIO_HOSTED_BETA_IMAGE_PLAN:-}"
    if [ -z "$plan" ]; then
      plan="$(parkio_hosted_beta_image_plan "$env_file")" || return 2
    fi
    if [ "$PARKIO_DEPLOYMENT_PROFILE" = "hosted-beta" ]; then
      image_services=()
    fi
    pinned_images_json="{"
    first=1
    while IFS=$'\t' read -r kind svc image platform; do
      case "$kind" in
        built)
          if [ "$PARKIO_DEPLOYMENT_PROFILE" = "hosted-beta" ]; then
            image_services+=("$svc")
          fi
          ;;
        pinned)
          if [ "$first" -eq 1 ]; then first=0; else pinned_images_json+=","; fi
          pinned_images_json+="\"${svc}\":\"${image}\""
          ;;
      esac
    done <<< "$plan"
    pinned_images_json+="}"
  fi
  image_digests_json="{}"
  if [ "${#image_services[@]}" -gt 0 ]; then
    image_digests_json="$(parkio_image_digests_json "$image_tag" "${image_services[@]}")"
  fi
  feature_flags_json="$(parkio_feature_flags_json "$env_file")"
  effective_feature_configuration_json="null"
  if [ "$PARKIO_DEPLOYMENT_PROFILE" = "invite-production" ]; then
    effective_feature_configuration_json="$(parkio_effective_feature_configuration_json "$env_file")" || {
      echo "ERROR: resolved invite-production feature configuration is invalid." >&2
      return 2
    }
  fi
  compose_structure_json="null"
  if [ -n "$compose_structure_path" ]; then
    if [ ! -f "$compose_structure_path" ]; then
      echo "ERROR: sanitized Compose structure not found: $compose_structure_path" >&2
      return 2
    fi
    compose_structure_json="$(jq -c . "$compose_structure_path")" || {
      echo "ERROR: sanitized Compose structure is not valid JSON" >&2
      return 2
    }
  fi
  images_json="{"
  first=1
  for svc in "${image_services[@]}"; do
    if [ "$first" -eq 1 ]; then first=0; else images_json+=","; fi
    images_json+="\"${svc}\":\"$(parkio_image_ref "$svc" "$image_tag")\""
  done
  images_json+="}"

  mkdir -p "$(dirname "$manifest_path")"
  rollback_target="$previous_manifest"
  if [ -z "$rollback_target" ]; then
    rollback_target="<previous-manifest.json>"
  fi

  local rollback_script="./scripts/rollback-hosted-beta.sh"
  if [ "$PARKIO_DEPLOYMENT_PROFILE" = "invite-production" ]; then
    rollback_script="./scripts/rollback-invite-production.sh"
  fi

  jq -n \
    --arg action "$action" \
    --arg gitSha "$git_sha" \
    --arg branch "$branch" \
    --arg buildTime "$created" \
    --arg imageTag "$image_tag" \
    --arg imageVersion "$version" \
    --arg envProfile "$env_file" \
    --arg deploymentProfile "$PARKIO_DEPLOYMENT_PROFILE" \
    --arg databaseServer "$(parkio_env_value "$env_file" PARKIO_PG_HOST)" \
    --arg operator "$operator" \
    --arg previousManifest "$previous_manifest" \
    --arg rollbackTarget "$rollback_target" \
    --arg rollbackScript "$rollback_script" \
    --arg requestedDarkGatewayUrlInput "$requested_dark_gateway_input" \
    --arg rawDarkGatewayInputBlank "$raw_dark_gateway_input_blank" \
    --arg effectiveDarkGatewayUrl "$effective_dark_gateway_url" \
    --arg darkGatewayInputSource "$dark_gateway_input_source" \
    --argjson composeFiles "$compose_files_json" \
    --argjson composeStructure "$compose_structure_json" \
    --argjson images "$images_json" \
    --argjson imageDigests "$image_digests_json" \
    --argjson pinnedImages "$pinned_images_json" \
    --argjson migrationVersions "$migrations_json" \
    --argjson featureFlags "$feature_flags_json" \
    --argjson effectiveFeatureConfiguration "$effective_feature_configuration_json" \
    --argjson runtimeServices "$runtime_services_json" \
    --argjson disabledServices "$disabled_services_json" \
    '{
      schemaVersion: 1,
      action: $action,
      gitSha: $gitSha,
      branch: $branch,
      buildTime: $buildTime,
      imageTag: $imageTag,
      imageVersion: $imageVersion,
      composeFiles: $composeFiles,
      composeStructure: $composeStructure,
      envProfile: $envProfile,
      deploymentProfile: $deploymentProfile,
      databaseServer: (if $databaseServer == "" then null else $databaseServer end),
      operator: $operator,
      previousManifest: (if $previousManifest == "" then null else $previousManifest end),
      images: $images,
      imageDigests: $imageDigests,
      migrationVersions: $migrationVersions,
      featureFlags: $featureFlags,
      effectiveFeatureConfiguration: $effectiveFeatureConfiguration,
      runtimeServices: $runtimeServices,
      disabledServices: $disabledServices,
      migrationNote: "Flyway runs automatically on service startup (readiness requires successful migrate). migrationVersions lists scripts present in source at deploy time.",
      rollbackCommand: ("PARKIO_DEPLOYMENT_PROFILE=" + $deploymentProfile + " PARKIO_ENV_FILE=" + $envProfile + " " + $rollbackScript + " --manifest " + $rollbackTarget)
    } + (if $pinnedImages == null then {} else { pinnedImages: $pinnedImages } end)
      + (if $requestedDarkGatewayUrlInput == "" then {} else {
      requestedDarkGatewayUrlInput: $requestedDarkGatewayUrlInput,
      rawDarkGatewayInputBlank: ($rawDarkGatewayInputBlank == "true"),
      effectiveDarkGatewayUrl: $effectiveDarkGatewayUrl,
      darkGatewayInputSource: $darkGatewayInputSource
    } end)' > "$manifest_path"
}
