#!/usr/bin/env bash
# Shared helpers for hosted-beta backup / restore.
# shellcheck shell=bash

PARKIO_DB_SERVICES=(
  "auth:parkio-postgres-auth:${POSTGRES_AUTH_USER:-parkio_auth}:${POSTGRES_AUTH_DB:-parkio_auth}"
  "gateway:parkio-postgres-gateway:${POSTGRES_GATEWAY_USER:-parkio_gateway}:${POSTGRES_GATEWAY_DB:-parkio_gateway}"
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
    # Blank .env placeholders (BACKUP_ENCRYPT_PASSPHRASE=) must not wipe a
    # non-empty caller/cron/CI secret already in the process environment.
    local _saved_encrypt="${BACKUP_ENCRYPT_PASSPHRASE-}"
    local _saved_mc="${BACKUP_MC_DEST-}"
    local _saved_prod="${BACKUP_PRODUCTION_MODE-}"
    local _saved_kind="${BACKUP_OFFSITE_KIND-}"
    local _saved_azure_acct="${BACKUP_AZURE_STORAGE_ACCOUNT-}"
    local _saved_azure_ct="${BACKUP_AZURE_CONTAINER-}"
    local _saved_azure_key="${BACKUP_AZURE_STORAGE_KEY-}"
    local _saved_mc_url="${BACKUP_MC_URL-}"
    local _saved_mc_access="${BACKUP_MC_ACCESS_KEY-}"
    local _saved_mc_secret="${BACKUP_MC_SECRET_KEY-}"
    set -a
    # shellcheck disable=SC1090
    . "${env_file}"
    set +a
    if [ -n "${_saved_encrypt}" ]; then
      export BACKUP_ENCRYPT_PASSPHRASE="${_saved_encrypt}"
    fi
    if [ -n "${_saved_mc}" ]; then
      export BACKUP_MC_DEST="${_saved_mc}"
    fi
    if [ -n "${_saved_prod}" ]; then
      export BACKUP_PRODUCTION_MODE="${_saved_prod}"
    fi
    if [ -n "${_saved_kind}" ]; then
      export BACKUP_OFFSITE_KIND="${_saved_kind}"
    fi
    if [ -n "${_saved_azure_acct}" ]; then
      export BACKUP_AZURE_STORAGE_ACCOUNT="${_saved_azure_acct}"
    fi
    if [ -n "${_saved_azure_ct}" ]; then
      export BACKUP_AZURE_CONTAINER="${_saved_azure_ct}"
    fi
    if [ -n "${_saved_azure_key}" ]; then
      export BACKUP_AZURE_STORAGE_KEY="${_saved_azure_key}"
    fi
    if [ -n "${_saved_mc_url}" ]; then
      export BACKUP_MC_URL="${_saved_mc_url}"
    fi
    if [ -n "${_saved_mc_access}" ]; then
      export BACKUP_MC_ACCESS_KEY="${_saved_mc_access}"
    fi
    if [ -n "${_saved_mc_secret}" ]; then
      export BACKUP_MC_SECRET_KEY="${_saved_mc_secret}"
    fi
  elif [ -n "${env_file}" ]; then
    echo "WARN: env file '${env_file}' not found; relying on current environment." >&2
  fi
}

parkio_backup_validate_deployment_profile() {
  local profile="${PARKIO_DEPLOYMENT_PROFILE:-hosted-beta}"
  case "$profile" in
    hosted-beta|azure-hosted-beta) ;;
    *)
      echo "ERROR: unsupported PARKIO_DEPLOYMENT_PROFILE='$profile' for backup/restore." >&2
      return 2
      ;;
  esac
  PARKIO_DEPLOYMENT_PROFILE="$profile"
  export PARKIO_DEPLOYMENT_PROFILE
}

parkio_backup_backend_network() {
  local container="${1:-parkio-minio}"
  docker inspect "${container}" --format '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}}{{"\n"}}{{end}}' 2>/dev/null \
    | grep -E 'backend|parkio' | head -1
}

parkio_backup_production_mode() {
  case "${BACKUP_PRODUCTION_MODE:-0}" in
    1|true|yes|on|TRUE|YES|ON) return 0 ;;
    *) return 1 ;;
  esac
}

parkio_backup_offsite_kind() {
  local kind="${BACKUP_OFFSITE_KIND:-}"
  if [ -n "${kind}" ]; then
    echo "${kind}"
    return 0
  fi
  if [ -n "${BACKUP_AZURE_STORAGE_ACCOUNT:-}" ] && [ -n "${BACKUP_AZURE_CONTAINER:-}" ]; then
    echo azure
    return 0
  fi
  if [ -n "${BACKUP_MC_DEST:-}" ]; then
    echo s3
    return 0
  fi
  echo none
}

parkio_backup_preflight() {
  if ! parkio_backup_production_mode; then
    return 0
  fi
  if [ -z "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then
    echo "ERROR: BACKUP_PRODUCTION_MODE requires BACKUP_ENCRYPT_PASSPHRASE (fail-closed; no plaintext dumps)." >&2
    return 2
  fi
  local kind
  kind="$(parkio_backup_offsite_kind)"
  if [ "${kind}" = "none" ]; then
    echo "ERROR: BACKUP_PRODUCTION_MODE requires offsite (BACKUP_MC_DEST or BACKUP_AZURE_STORAGE_ACCOUNT+CONTAINER)." >&2
    return 2
  fi
}

parkio_backup_write_metrics() {
  local scope="$1"
  local success="$2"
  local stamp_epoch="$3"
  local db_failed="$4"
  local minio_objects="${5:-0}"
  local offsite_ok="${6:-0}"
  local encrypt_on="${7:-0}"
  local backup_bytes="${8:-0}"
  local textfile_dir="${PARKIO_PROMETHEUS_TEXTFILE_DIR:-docker/prometheus/textfile}"
  local prod_mode=0
  if parkio_backup_production_mode; then
    prod_mode=1
  fi
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
# HELP parkio_backup_offsite_last_success 1 when the last offsite upload succeeded.
# TYPE parkio_backup_offsite_last_success gauge
parkio_backup_offsite_last_success{scope="${scope}"} ${offsite_ok}
# HELP parkio_backup_encryption_enabled 1 when DB dumps were encrypted.
# TYPE parkio_backup_encryption_enabled gauge
parkio_backup_encryption_enabled{scope="${scope}"} ${encrypt_on}
# HELP parkio_backup_last_bytes Approximate local stamp size in bytes.
# TYPE parkio_backup_last_bytes gauge
parkio_backup_last_bytes{scope="${scope}"} ${backup_bytes}
# HELP parkio_backup_production_mode 1 when BACKUP_PRODUCTION_MODE was set for the last run.
# TYPE parkio_backup_production_mode gauge
parkio_backup_production_mode{scope="${scope}"} ${prod_mode}
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
  local offsite_retention="${BACKUP_OFFSITE_RETENTION_DAYS:-14}"
  local encrypt_on=0
  if [ -n "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then encrypt_on=1; fi
  local offsite_kind
  offsite_kind="$(parkio_backup_offsite_kind)"
  local offsite_uploaded="${PARKIO_BACKUP_OFFSITE_UPLOADED:-0}"
  jq -n \
    --arg action "backup" \
    --arg stamp "${stamp}" \
    --arg gitSha "${git_sha}" \
    --arg operator "${operator}" \
    --arg envProfile "${env_file}" \
    --arg deploymentProfile "${PARKIO_DEPLOYMENT_PROFILE:-hosted-beta}" \
    --arg destDir "${dest_dir}" \
    --arg offsiteKind "${offsite_kind}" \
    --argjson databases "${databases_json}" \
    --argjson minio "${minio_json}" \
    --argjson databasesOk "${db_ok}" \
    --argjson databasesFailed "${db_failed}" \
    --argjson minioOk "${minio_ok}" \
    --argjson retentionDays "${retention}" \
    --argjson offsiteRetentionDays "${offsite_retention}" \
    --argjson encryptionEnabled "${encrypt_on}" \
    --argjson offsiteUploaded "${offsite_uploaded}" \
    --arg encryptionAlgorithm "$( [ "${encrypt_on}" -eq 1 ] && echo aes-256-cbc-pbkdf2 || echo none )" \
  '{
    schemaVersion: 2,
    action: $action,
    timestamp: $stamp,
    gitSha: $gitSha,
    operator: $operator,
    envProfile: $envProfile,
    deploymentProfile: $deploymentProfile,
    destination: $destDir,
    databases: $databases,
    minio: $minio,
    databasesOk: $databasesOk,
    databasesFailed: $databasesFailed,
    minioOk: $minioOk,
    retentionDays: $retentionDays,
    offsiteRetentionDays: $offsiteRetentionDays,
    encryption: { enabled: ($encryptionEnabled == 1), algorithm: $encryptionAlgorithm },
    offsite: { kind: $offsiteKind, uploaded: ($offsiteUploaded == 1) },
    checksums: { sha256sums: "SHA256SUMS" }
  }' > "${manifest_path}"
}

# Write a sidecar checksum next to a dump. Fail if no hasher is available.
parkio_backup_write_checksum() {
  local file="$1"
  if [ ! -f "${file}" ]; then
    return 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${file}" > "${file}.sha256"
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "${file}" > "${file}.sha256"
  else
    echo "ERROR: sha256sum/shasum not available; cannot record dump integrity." >&2
    return 1
  fi
}

parkio_backup_write_stamp_integrity() {
  local dest_dir="$1"
  local stamp="${2:-$(basename "${dest_dir}")}"
  if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
    echo "ERROR: cannot write SHA256SUMS (no sha256sum/shasum)." >&2
    return 1
  fi
  (
    cd "${dest_dir}"
    if command -v sha256sum >/dev/null 2>&1; then
      find . -type f ! -name SHA256SUMS ! -name COMPLETE | LC_ALL=C sort | xargs -r sha256sum > SHA256SUMS
    else
      find . -type f ! -name SHA256SUMS ! -name COMPLETE | LC_ALL=C sort | xargs -r shasum -a 256 > SHA256SUMS
    fi
  )
  local sums_hash
  if command -v sha256sum >/dev/null 2>&1; then
    sums_hash="$(sha256sum "${dest_dir}/SHA256SUMS" | awk '{print $1}')"
  else
    sums_hash="$(shasum -a 256 "${dest_dir}/SHA256SUMS" | awk '{print $1}')"
  fi
  printf 'stamp=%s\nsha256sums=%s\n' "${stamp}" "${sums_hash}" > "${dest_dir}/COMPLETE"
}

parkio_backup_verify_stamp() {
  local dest_dir="$1"
  if [ ! -f "${dest_dir}/COMPLETE" ]; then
    echo "ERROR: incomplete stamp (missing COMPLETE): ${dest_dir}" >&2
    return 1
  fi
  if [ ! -f "${dest_dir}/SHA256SUMS" ]; then
    echo "ERROR: missing SHA256SUMS in ${dest_dir}" >&2
    return 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "${dest_dir}" && sha256sum -c SHA256SUMS --strict)
  elif command -v shasum >/dev/null 2>&1; then
    (cd "${dest_dir}" && shasum -a 256 -c SHA256SUMS)
  else
    echo "ERROR: cannot verify checksums (no sha256sum/shasum)." >&2
    return 1
  fi
}

parkio_backup_assert_encrypted_dumps() {
  local dest_dir="$1"
  local leftover
  leftover="$(find "${dest_dir}" -maxdepth 1 -type f -name '*.sql.gz' ! -name '*.sql.gz.enc' 2>/dev/null || true)"
  if [ -n "${leftover}" ]; then
    echo "ERROR: plaintext dump(s) present under encrypted policy:" >&2
    echo "${leftover}" >&2
    return 1
  fi
}

# Upload a completed stamp directory AFTER MinIO mirror + COMPLETE marker.
# Empty dest is skip unless BACKUP_PRODUCTION_MODE (preflight already rejected that).
parkio_backup_offsite_upload() {
  local dest_dir="$1"
  local mc_dest="${2:-${BACKUP_MC_DEST:-}}"
  local stamp="${3:-$(basename "${dest_dir}")}"
  local kind
  kind="$(parkio_backup_offsite_kind)"
  if [ "${kind}" = "none" ]; then
    echo "Offsite: unset — local copy only."
    return 0
  fi
  if [ ! -f "${dest_dir}/COMPLETE" ]; then
    echo "ERROR: refusing to upload incomplete stamp (no COMPLETE): ${dest_dir}" >&2
    return 1
  fi
  case "${kind}" in
    azure) parkio_backup_offsite_upload_azure "${dest_dir}" "${stamp}" ;;
    s3) parkio_backup_offsite_upload_s3 "${dest_dir}" "${mc_dest}" "${stamp}" ;;
    *)
      echo "ERROR: unknown BACKUP_OFFSITE_KIND='${kind}'." >&2
      return 1
      ;;
  esac
}

parkio_backup_offsite_upload_s3() {
  local dest_dir="$1"
  local mc_dest="${2:-${BACKUP_MC_DEST:-}}"
  local stamp="$3"
  if [ -z "${mc_dest}" ]; then
    echo "ERROR: BACKUP_MC_DEST is required for s3 offsite." >&2
    return 1
  fi
  local complete_tmp
  complete_tmp="$(mktemp "${TMPDIR:-/tmp}/parkio-complete.XXXXXX")"
  mv "${dest_dir}/COMPLETE" "${complete_tmp}"
  local rc=0
  if [ -n "${BACKUP_MC_URL:-}" ]; then
    parkio_backup_mc_docker cp_recursive "${dest_dir}" "${mc_dest}/${stamp}" || rc=$?
    if [ "${rc}" -eq 0 ]; then
      parkio_backup_mc_docker cp_file "${complete_tmp}" "${mc_dest}/${stamp}/COMPLETE" || rc=$?
    fi
  elif command -v mc >/dev/null 2>&1; then
    echo "Uploading ${dest_dir} -> ${mc_dest}/${stamp}"
    mc mirror --overwrite "${dest_dir}" "${mc_dest}/${stamp}" || rc=$?
    if [ "${rc}" -eq 0 ]; then
      mc cp "${complete_tmp}" "${mc_dest}/${stamp}/COMPLETE" || rc=$?
    fi
  else
    echo "ERROR: BACKUP_MC_DEST set but 'mc' is not installed and BACKUP_MC_URL is unset." >&2
    rc=1
  fi
  mv "${complete_tmp}" "${dest_dir}/COMPLETE"
  return "${rc}"
}

parkio_backup_mc_docker() {
  local action="$1"
  local src="$2"
  local dest="$3"
  local mc_image="${MINIO_MC_IMAGE:-minio/mc:RELEASE.2024-09-16T17-43-14Z}"
  local network="${BACKUP_MC_DOCKER_NETWORK:-}"
  if [ -z "${network}" ]; then
    network="$(parkio_backup_backend_network "${BACKUP_OFFSITE_MINIO_CONTAINER:-parkio-offsite-minio}")"
  fi
  if [ -z "${network}" ]; then
    echo "ERROR: could not resolve Docker network for offsite mc." >&2
    return 1
  fi
  local dest_dir_host src_file
  case "${action}" in
    cp_recursive)
      docker run --rm --network "${network}" --entrypoint /bin/sh \
        -v "${src}:/upload:ro" \
        -e MC_URL="${BACKUP_MC_URL}" \
        -e MC_ACCESS="${BACKUP_MC_ACCESS_KEY:?set BACKUP_MC_ACCESS_KEY}" \
        -e MC_SECRET="${BACKUP_MC_SECRET_KEY:?set BACKUP_MC_SECRET_KEY}" \
        -e MC_DEST="${dest}" \
        "${mc_image}" -c '
          set -eu
          mc alias set offsite "$MC_URL" "$MC_ACCESS" "$MC_SECRET" >/dev/null
          mc mb -p "offsite/$(echo "$MC_DEST" | cut -d/ -f2)" >/dev/null 2>&1 || true
          mc mirror --overwrite /upload "$MC_DEST"
        '
      ;;
    cp_file)
      src_file="$(basename "${src}")"
      docker run --rm --network "${network}" --entrypoint /bin/sh \
        -v "$(dirname "${src}"):/upload:ro" \
        -e MC_URL="${BACKUP_MC_URL}" \
        -e MC_ACCESS="${BACKUP_MC_ACCESS_KEY:?set BACKUP_MC_ACCESS_KEY}" \
        -e MC_SECRET="${BACKUP_MC_SECRET_KEY:?set BACKUP_MC_SECRET_KEY}" \
        -e MC_DEST="${dest}" \
        -e SRC_FILE="${src_file}" \
        "${mc_image}" -c '
          set -eu
          mc alias set offsite "$MC_URL" "$MC_ACCESS" "$MC_SECRET" >/dev/null
          mc cp "/upload/${SRC_FILE}" "$MC_DEST"
        '
      ;;
    pull)
      dest_dir_host="${src}"
      docker run --rm --network "${network}" --entrypoint /bin/sh \
        -v "${dest_dir_host}:/download" \
        -e MC_URL="${BACKUP_MC_URL}" \
        -e MC_ACCESS="${BACKUP_MC_ACCESS_KEY:?set BACKUP_MC_ACCESS_KEY}" \
        -e MC_SECRET="${BACKUP_MC_SECRET_KEY:?set BACKUP_MC_SECRET_KEY}" \
        -e MC_DEST="${dest}" \
        "${mc_image}" -c '
          set -eu
          mc alias set offsite "$MC_URL" "$MC_ACCESS" "$MC_SECRET" >/dev/null
          mc mirror --overwrite "$MC_DEST" /download
          chmod -R a+rwX /download
        '
      ;;
    *)
      echo "ERROR: unknown mc docker action '${action}'." >&2
      return 1
      ;;
  esac
}

parkio_backup_offsite_upload_azure() {
  local dest_dir="$1"
  local stamp="$2"
  local account="${BACKUP_AZURE_STORAGE_ACCOUNT:-}"
  local container="${BACKUP_AZURE_CONTAINER:-}"
  if [ -z "${account}" ] || [ -z "${container}" ]; then
    echo "ERROR: Azure offsite requires BACKUP_AZURE_STORAGE_ACCOUNT and BACKUP_AZURE_CONTAINER." >&2
    return 1
  fi
  if ! command -v az >/dev/null 2>&1; then
    echo "ERROR: Azure offsite requires the Azure CLI (az)." >&2
    return 1
  fi
  echo "Uploading ${dest_dir} -> azure://${container}/${stamp} (account configured, TLS on)"
  local complete_tmp
  complete_tmp="$(mktemp "${TMPDIR:-/tmp}/parkio-complete.XXXXXX")"
  mv "${dest_dir}/COMPLETE" "${complete_tmp}"
  local extra=()
  if [ -n "${AZURE_STORAGE_KEY:-}" ] || [ -n "${BACKUP_AZURE_STORAGE_KEY:-}" ]; then
    extra+=(--account-key "${AZURE_STORAGE_KEY:-${BACKUP_AZURE_STORAGE_KEY}}")
  else
    extra+=(--auth-mode login)
  fi
  local rc=0
  az storage blob upload-batch \
    --account-name "${account}" \
    --destination "${container}/${stamp}" \
    --source "${dest_dir}" \
    --overwrite \
    "${extra[@]}" >/dev/null || rc=$?
  if [ "${rc}" -eq 0 ]; then
    az storage blob upload \
      --account-name "${account}" \
      --container-name "${container}" \
      --name "${stamp}/COMPLETE" \
      --file "${complete_tmp}" \
      --overwrite \
      "${extra[@]}" >/dev/null || rc=$?
  fi
  mv "${complete_tmp}" "${dest_dir}/COMPLETE"
  return "${rc}"
}

parkio_backup_offsite_pull() {
  local dest_dir="$1"
  local stamp="$2"
  local kind
  kind="$(parkio_backup_offsite_kind)"
  mkdir -p "${dest_dir}"
  case "${kind}" in
    azure)
      local account="${BACKUP_AZURE_STORAGE_ACCOUNT:-}"
      local container="${BACKUP_AZURE_CONTAINER:-}"
      local extra=()
      if [ -n "${AZURE_STORAGE_KEY:-}" ] || [ -n "${BACKUP_AZURE_STORAGE_KEY:-}" ]; then
        extra+=(--account-key "${AZURE_STORAGE_KEY:-${BACKUP_AZURE_STORAGE_KEY}}")
      else
        extra+=(--auth-mode login)
      fi
      az storage blob download-batch \
        --account-name "${account}" \
        --source "${container}" \
        --pattern "${stamp}/*" \
        --destination "${dest_dir}" \
        --overwrite \
        "${extra[@]}" >/dev/null
      if [ -d "${dest_dir}/${stamp}" ]; then
        # download-batch may nest the stamp directory
        shopt -s dotglob nullglob
        mv "${dest_dir}/${stamp}"/* "${dest_dir}/" 2>/dev/null || true
        rmdir "${dest_dir}/${stamp}" 2>/dev/null || true
        shopt -u dotglob nullglob
      fi
      ;;
    s3)
      local mc_dest="${BACKUP_MC_DEST:?BACKUP_MC_DEST required}"
      if [ -n "${BACKUP_MC_URL:-}" ]; then
        parkio_backup_mc_docker pull "${dest_dir}" "${mc_dest}/${stamp}"
        if [ -d "${dest_dir}/${stamp}" ]; then
          shopt -s dotglob nullglob
          mv "${dest_dir}/${stamp}"/* "${dest_dir}/" 2>/dev/null || true
          rmdir "${dest_dir}/${stamp}" 2>/dev/null || true
          shopt -u dotglob nullglob
        fi
      else
        mc mirror --overwrite "${mc_dest}/${stamp}" "${dest_dir}/"
        if [ -d "${dest_dir}/${stamp}" ]; then
          shopt -s dotglob nullglob
          mv "${dest_dir}/${stamp}"/* "${dest_dir}/" 2>/dev/null || true
          rmdir "${dest_dir}/${stamp}" 2>/dev/null || true
          shopt -u dotglob nullglob
        fi
      fi
      ;;
    *)
      echo "ERROR: no offsite configured for pull." >&2
      return 1
      ;;
  esac
  parkio_backup_verify_stamp "${dest_dir}"
}
