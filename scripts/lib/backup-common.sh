#!/usr/bin/env bash
# Shared helpers for hosted-beta backup / restore.
# shellcheck shell=bash

PARKIO_DB_SERVICES=(
  "auth:parkio-postgres-auth:${POSTGRES_AUTH_USER:-parkio_auth}:${POSTGRES_AUTH_DB:-parkio_auth}"
  "user:parkio-postgres-user:${POSTGRES_USER_USER:-parkio_user}:${POSTGRES_USER_DB:-parkio_user}"
  "parking:parkio-postgres-parking:${POSTGRES_PARKING_USER:-parkio_parking}:${POSTGRES_PARKING_DB:-parkio_parking}"
  "media:parkio-postgres-media:${POSTGRES_MEDIA_USER:-parkio_media}:${POSTGRES_MEDIA_DB:-parkio_media}"
  "gamification:parkio-postgres-gamification:${POSTGRES_GAMIFICATION_USER:-parkio_gamification}:${POSTGRES_GAMIFICATION_DB:-parkio_gamification}"
  "notification:parkio-postgres-notification:${POSTGRES_NOTIFICATION_USER:-parkio_notification}:${POSTGRES_NOTIFICATION_DB:-parkio_notification}"
  "moderation:parkio-postgres-moderation:${POSTGRES_MODERATION_USER:-parkio_moderation}:${POSTGRES_MODERATION_DB:-parkio_moderation}"
  "analytics:parkio-postgres-analytics:${POSTGRES_ANALYTICS_USER:-parkio_analytics}:${POSTGRES_ANALYTICS_DB:-parkio_analytics}"
  "ai-validation:parkio-postgres-ai-validation:${POSTGRES_AIVALIDATION_USER:-parkio_aivalidation}:${POSTGRES_AIVALIDATION_DB:-parkio_aivalidation}"
)

parkio_backup_repo_root() {
  local here
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  echo "$here"
}

parkio_backup_git_sha() {
  git -C "$(parkio_backup_repo_root)" rev-parse HEAD 2>/dev/null || echo "unknown"
}

parkio_backup_stamp() {
  date -u +%Y-%m-%dT%H-%M-%SZ
}

parkio_backup_load_env() {
  local env_file="${1:-}"
  if [ -n "${env_file}" ] && [ -f "${env_file}" ]; then
    set -a
    # shellcheck disable=SC1090
    . "${env_file}"
    set +a
  elif [ -n "${env_file}" ]; then
    echo "WARN: env file '${env_file}' not found; relying on current environment." >&2
  fi
}

parkio_backup_backend_network() {
  local container="${1:-parkio-minio}"
  docker inspect "${container}" --format '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}}{{"\n"}}{{end}}' 2>/dev/null \
    | grep -E 'backend|parkio' | head -1
}

parkio_backup_write_metrics() {
  local scope="$1"
  local success="$2"
  local stamp_epoch="$3"
  local db_failed="$4"
  local minio_objects="${5:-0}"
  local textfile_dir="${PARKIO_PROMETHEUS_TEXTFILE_DIR:-docker/prometheus/textfile}"
  local root
  root="$(parkio_backup_repo_root)"
  mkdir -p "${root}/${textfile_dir}"
  cat > "${root}/${textfile_dir}/parkio_backup.prom" <<EOF
# HELP parkio_backup_last_success 1 when the last hosted-beta backup succeeded.
# TYPE parkio_backup_last_success gauge
parkio_backup_last_success{scope="${scope}"} ${success}
# HELP parkio_backup_last_timestamp_seconds Unix epoch of the last backup attempt.
# TYPE parkio_backup_last_timestamp_seconds gauge
parkio_backup_last_timestamp_seconds{scope="${scope}"} ${stamp_epoch}
# HELP parkio_backup_databases_failed Number of database dumps that failed in the last run.
# TYPE parkio_backup_databases_failed gauge
parkio_backup_databases_failed{scope="${scope}"} ${db_failed}
# HELP parkio_backup_minio_objects Object count in the mirrored MinIO bucket when known.
# TYPE parkio_backup_minio_objects gauge
parkio_backup_minio_objects{scope="${scope}",bucket="${MINIO_BUCKET:-parkio-media}"} ${minio_objects}
EOF
}

parkio_backup_write_manifest() {
  local manifest_path="$1"
  local stamp="$2"
  local git_sha="$3"
  local operator="$4"
  local env_file="$5"
  local dest_dir="$6"
  local db_ok="$7"
  local db_failed="$8"
  local minio_ok="$9"
  local minio_objects="${10:-0}"
  local databases_json minio_json

  databases_json="["
  local first=1 entry name
  for entry in "${PARKIO_DB_SERVICES[@]}"; do
    IFS=":" read -r name _ _ _ <<< "${entry}"
    if [ "$first" -eq 1 ]; then first=0; else databases_json+=","; fi
    databases_json+="\"${name}\""
  done
  databases_json+="]"

  minio_json="{\"bucket\":\"${MINIO_BUCKET:-parkio-media}\",\"objectCount\":${minio_objects},\"path\":\"${dest_dir}/minio\"}"

  mkdir -p "$(dirname "${manifest_path}")"
  local retention="${BACKUP_RETENTION_DAYS:-14}"
  jq -n \
    --arg action "backup" \
    --arg stamp "${stamp}" \
    --arg gitSha "${git_sha}" \
    --arg operator "${operator}" \
    --arg envProfile "${env_file}" \
    --arg destDir "${dest_dir}" \
    --argjson databases "${databases_json}" \
    --argjson minio "${minio_json}" \
    --argjson databasesOk "${db_ok}" \
    --argjson databasesFailed "${db_failed}" \
    --argjson minioOk "${minio_ok}" \
    --argjson retentionDays "${retention}" \
  '{
    schemaVersion: 1,
    action: $action,
    timestamp: $stamp,
    gitSha: $gitSha,
    operator: $operator,
    envProfile: $envProfile,
    destination: $destDir,
    databases: $databases,
    minio: $minio,
    databasesOk: $databasesOk,
    databasesFailed: $databasesFailed,
    minioOk: $minioOk,
    retentionDays: $retentionDays
  }' > "${manifest_path}"
}
