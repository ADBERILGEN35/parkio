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

PARKIO_INVITE_RUNTIME_SERVICES=(
  redis kafka kafka-exporter blackbox-exporter node-exporter
  minio minio-setup clamav prometheus grafana alertmanager
  auth-service user-service parking-service media-service gamification-service
  notification-service moderation-service ai-validation-service analytics-service
  gateway-service web caddy
)

PARKIO_INVITE_REQUIRED_HEALTHY=(
  kafka redis minio clamav prometheus grafana alertmanager
  gateway-service auth-service user-service parking-service media-service
  gamification-service notification-service moderation-service ai-validation-service
  analytics-service web caddy
)

PARKIO_RUNTIME_SERVICES=()
PARKIO_DISABLED_SERVICES=()

# Invite-production dark acceptance endpoint (PROD-DEPLOY-01A / D1). Sourced here
# so deploy, rollback and smoke all agree on the single allowed target.
# shellcheck source=dark-gateway-url.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/dark-gateway-url.sh"

parkio_env_value() {
  local env_file="$1"
  local key="$2"
  [ -f "$env_file" ] || return 0
  grep "^${key}=" "$env_file" | tail -n 1 | cut -d= -f2- \
    | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}

parkio_configure_deployment_profile() {
  local env_file="$1"
  local requested="${PARKIO_DEPLOYMENT_PROFILE:-}"
  if [ -z "$requested" ]; then
    requested="$(parkio_env_value "$env_file" PARKIO_DEPLOYMENT_PROFILE)"
  fi
  requested="${requested:-hosted-beta}"

  case "$requested" in
    hosted-beta)
      PARKIO_COMPOSE_FILES="-f docker/docker-compose.yml -f docker/docker-compose.apps.yml -f docker/docker-compose.images.yml -f docker/docker-compose.hosted-beta.yml"
      PARKIO_RUNTIME_SERVICES=()
      PARKIO_DISABLED_SERVICES=()
      ;;
    azure-hosted-beta)
      PARKIO_COMPOSE_FILES="-f docker/docker-compose.yml -f docker/docker-compose.apps.yml -f docker/docker-compose.images.yml -f docker/docker-compose.hosted-beta.yml -f docker/docker-compose.azure-hosted-beta.yml"
      PARKIO_RUNTIME_SERVICES=("${PARKIO_AZURE_RUNTIME_SERVICES[@]}")
      PARKIO_DISABLED_SERVICES=(alertmanager loki promtail tempo)
      PARKIO_REQUIRED_HEALTHY=("${PARKIO_AZURE_REQUIRED_HEALTHY[@]}")
      ;;
    invite-production)
      # docker-compose.invite-dark.yml MUST stay last: it re-publishes
      # gateway-service on 127.0.0.1:8080 with `!override`, which only wins if it
      # is merged after the hosted-beta overlay's `ports: !reset []`.
      PARKIO_COMPOSE_FILES="-f docker/docker-compose.yml -f docker/docker-compose.apps.yml -f docker/docker-compose.images.yml -f docker/docker-compose.hosted-beta.yml -f docker/docker-compose.managed-db.yml -f docker/docker-compose.invite-dark.yml"
      PARKIO_RUNTIME_SERVICES=("${PARKIO_INVITE_RUNTIME_SERVICES[@]}")
      PARKIO_DISABLED_SERVICES=(postgres-auth postgres-gateway postgres-user postgres-parking postgres-media postgres-gamification postgres-notification postgres-moderation postgres-analytics postgres-ai-validation loki promtail tempo)
      PARKIO_REQUIRED_HEALTHY=("${PARKIO_INVITE_REQUIRED_HEALTHY[@]}")
      ;;
    *)
      echo "ERROR: unsupported PARKIO_DEPLOYMENT_PROFILE='$requested' (expected hosted-beta, azure-hosted-beta, or invite-production)" >&2
      return 2
      ;;
  esac

  PARKIO_DEPLOYMENT_PROFILE="$requested"
  export PARKIO_DEPLOYMENT_PROFILE PARKIO_COMPOSE_FILES
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

parkio_compose() {
  local env_file="$1"
  shift
  # shellcheck disable=SC2086
  docker compose --env-file "$env_file" $PARKIO_COMPOSE_FILES "$@"
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

parkio_compose_up() {
  local env_file="$1"
  if [ "${#PARKIO_RUNTIME_SERVICES[@]}" -gt 0 ]; then
    parkio_compose "$env_file" up -d "${PARKIO_RUNTIME_SERVICES[@]}"
  else
    parkio_compose "$env_file" up -d
  fi
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
  for svc in "${PARKIO_APP_SERVICES[@]}"; do
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
      PARKIO_SPA_RANKING_STRATEGY: $strategy,
      PARKIO_SPA_RANKING_SHADOW_ENABLED: $shadow,
      PARKIO_SPA_RANKING_EVALUATION_ENABLED: $evaluation,
      PARKIO_SPA_RANKING_EVALUATION_ROLLUP_ENABLED: $rollup
    }'
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
  local compose_files_json compose_structure_json images_json image_digests_json migrations_json runtime_services_json disabled_services_json feature_flags_json svc first rollback_target

  compose_files_json="$(parkio_compose_files_json)"
  migrations_json="$(parkio_migration_versions_json)"
  runtime_services_json="$(parkio_runtime_services_json)"
  disabled_services_json="$(parkio_disabled_services_json)"
  image_digests_json="$(parkio_image_digests_json "$image_tag")"
  feature_flags_json="$(parkio_feature_flags_json "$env_file")"
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
  for svc in "${PARKIO_APP_SERVICES[@]}"; do
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
    --argjson composeFiles "$compose_files_json" \
    --argjson composeStructure "$compose_structure_json" \
    --argjson images "$images_json" \
    --argjson imageDigests "$image_digests_json" \
    --argjson migrationVersions "$migrations_json" \
    --argjson featureFlags "$feature_flags_json" \
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
      runtimeServices: $runtimeServices,
      disabledServices: $disabledServices,
      migrationNote: "Flyway runs automatically on service startup (readiness requires successful migrate). migrationVersions lists scripts present in source at deploy time.",
      rollbackCommand: ("PARKIO_DEPLOYMENT_PROFILE=" + $deploymentProfile + " PARKIO_ENV_FILE=" + $envProfile + " " + $rollbackScript + " --manifest " + $rollbackTarget)
    }' > "$manifest_path"
}
