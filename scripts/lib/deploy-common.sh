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
)

PARKIO_REQUIRED_HEALTHY=(
  kafka redis minio clamav
  postgres-auth postgres-user postgres-parking postgres-media postgres-gamification
  postgres-notification postgres-moderation postgres-analytics postgres-ai-validation
  gateway-service auth-service user-service parking-service media-service
  gamification-service notification-service moderation-service ai-validation-service analytics-service
)

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
  local compose_files_json images_json migrations_json svc first rollback_target

  compose_files_json="$(parkio_compose_files_json)"
  migrations_json="$(parkio_migration_versions_json)"
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

  jq -n \
    --arg action "$action" \
    --arg gitSha "$git_sha" \
    --arg branch "$branch" \
    --arg buildTime "$created" \
    --arg imageTag "$image_tag" \
    --arg imageVersion "$version" \
    --arg envProfile "$env_file" \
    --arg operator "$operator" \
    --arg previousManifest "$previous_manifest" \
    --arg rollbackTarget "$rollback_target" \
    --argjson composeFiles "$compose_files_json" \
    --argjson images "$images_json" \
    --argjson migrationVersions "$migrations_json" \
    '{
      schemaVersion: 1,
      action: $action,
      gitSha: $gitSha,
      branch: $branch,
      buildTime: $buildTime,
      imageTag: $imageTag,
      imageVersion: $imageVersion,
      composeFiles: $composeFiles,
      envProfile: $envProfile,
      operator: $operator,
      previousManifest: (if $previousManifest == "" then null else $previousManifest end),
      images: $images,
      migrationVersions: $migrationVersions,
      migrationNote: "Flyway runs automatically on service startup (readiness requires successful migrate). migrationVersions lists scripts present in source at deploy time.",
      rollbackCommand: ("PARKIO_ENV_FILE=" + $envProfile + " ./scripts/rollback-hosted-beta.sh --manifest " + $rollbackTarget)
    }' > "$manifest_path"
}