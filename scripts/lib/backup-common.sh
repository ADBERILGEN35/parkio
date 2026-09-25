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
  # The scheduled invite-production runtime root is a versioned operational
  # payload, not a git checkout, so it reports the revision it was installed from
  # (see scripts/azure/install-invite-production-backup-scheduler.sh).
  if [ -n "${PARKIO_BACKUP_GIT_SHA:-}" ]; then
    echo "${PARKIO_BACKUP_GIT_SHA}"
    return 0
  fi
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
    local _saved_azure_sas="${BACKUP_AZURE_SAS_TOKEN-}"
    local _saved_azure_sas_native="${AZURE_STORAGE_SAS_TOKEN-}"
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
    if [ -n "${_saved_azure_sas}" ]; then
      export BACKUP_AZURE_SAS_TOKEN="${_saved_azure_sas}"
    fi
    if [ -n "${_saved_azure_sas_native}" ]; then
      export AZURE_STORAGE_SAS_TOKEN="${_saved_azure_sas_native}"
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
    hosted-beta|azure-hosted-beta|managed-postgres|invite-production) ;;
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

# Dual dump path: Docker socket (hosted-beta) or managed PostgreSQL over TLS.
# PARKIO_PG_MODE=managed requires PARKIO_PG_HOST and per-service POSTGRES_*_PASSWORD
# (or PARKIO_PG_PASSWORD). Never logs credentials. sslmode defaults to verify-full.
parkio_pg_mode() {
  echo "${PARKIO_PG_MODE:-docker}"
}

parkio_pg_dump_stream() {
  local user="$1"
  local db="$2"
  local container="${3:-}"
  local mode
  mode="$(parkio_pg_mode)"
  if [ "${mode}" = "managed" ]; then
    : "${PARKIO_PG_HOST:?PARKIO_PG_HOST required when PARKIO_PG_MODE=managed}"
    local port="${PARKIO_PG_PORT:-5432}"
    local sslmode="${PARKIO_PG_SSLMODE:-verify-full}"
    if [ "${sslmode}" = "disable" ]; then
      echo "ERROR: PARKIO_PG_SSLMODE=disable is not allowed." >&2
      return 2
    fi
    local pw="${PARKIO_PG_PASSWORD:-}"
    case "${db}" in
      "${POSTGRES_AUTH_DB:-parkio_auth}") pw="${POSTGRES_AUTH_PASSWORD:-$pw}" ;;
      "${POSTGRES_GATEWAY_DB:-parkio_gateway}") pw="${POSTGRES_GATEWAY_PASSWORD:-$pw}" ;;
      "${POSTGRES_USER_DB:-parkio_user}") pw="${POSTGRES_USER_PASSWORD:-$pw}" ;;
      "${POSTGRES_PARKING_DB:-parkio_parking}") pw="${POSTGRES_PARKING_PASSWORD:-$pw}" ;;
      "${POSTGRES_MEDIA_DB:-parkio_media}") pw="${POSTGRES_MEDIA_PASSWORD:-$pw}" ;;
      "${POSTGRES_GAMIFICATION_DB:-parkio_gamification}") pw="${POSTGRES_GAMIFICATION_PASSWORD:-$pw}" ;;
      "${POSTGRES_NOTIFICATION_DB:-parkio_notification}") pw="${POSTGRES_NOTIFICATION_PASSWORD:-$pw}" ;;
      "${POSTGRES_MODERATION_DB:-parkio_moderation}") pw="${POSTGRES_MODERATION_PASSWORD:-$pw}" ;;
      "${POSTGRES_ANALYTICS_DB:-parkio_analytics}") pw="${POSTGRES_ANALYTICS_PASSWORD:-$pw}" ;;
      "${POSTGRES_AIVALIDATION_DB:-parkio_aivalidation}") pw="${POSTGRES_AIVALIDATION_PASSWORD:-$pw}" ;;
    esac
    if [ -z "${pw}" ]; then
      echo "ERROR: managed dump missing password for database ${db}" >&2
      return 2
    fi
    if command -v pg_dump >/dev/null 2>&1; then
      PGPASSWORD="${pw}" PGSSLMODE="${sslmode}" PGSSLROOTCERT="${PARKIO_PG_SSLROOTCERT:-}" pg_dump \
        -h "${PARKIO_PG_HOST}" -p "${port}" -U "${user}" -d "${db}" \
        --no-owner --clean --if-exists
    else
      local -a docker_args
      docker_args=(
        run --rm
        -e "PGPASSWORD=${pw}"
        -e "PGSSLMODE=${sslmode}"
      )
      if [ -n "${PARKIO_PG_SSLROOTCERT:-}" ]; then
        docker_args+=(
          -e "PGSSLROOTCERT=${PARKIO_PG_SSLROOTCERT}"
          -v "${PARKIO_PG_SSLROOTCERT}:${PARKIO_PG_SSLROOTCERT}:ro"
        )
      fi
      docker_args+=(
        postgres:16-alpine
        pg_dump -h "${PARKIO_PG_HOST}" -p "${port}" -U "${user}" -d "${db}"
        --no-owner --clean --if-exists
      )
      docker "${docker_args[@]}"
    fi
    return $?
  fi
  docker exec "${container}" pg_dump -U "${user}" -d "${db}" --no-owner --clean --if-exists
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
  if [ "$scope" = invite-production ]; then
    textfile_dir="${PARKIO_PROMETHEUS_TEXTFILE_DIR:-/var/lib/parkio/observability/textfile}"
  fi
  local prod_mode=0
  if parkio_backup_production_mode; then
    prod_mode=1
  fi
  local root
  root="$(parkio_backup_repo_root)"
  case "$textfile_dir" in /*) ;; *) textfile_dir="$root/$textfile_dir" ;; esac
  python3 "$root/scripts/lib/backup-metrics.py" "$textfile_dir" "$scope" \
    "$success" "$stamp_epoch" "$db_failed" "$minio_objects" "$offsite_ok" \
    "$encrypt_on" "$backup_bytes" "$prod_mode" "${MINIO_BUCKET:-parkio-media}"
  # Optional async Slack-biz enqueue (disabled unless PARKIO_SLACK_BIZ_ENABLED=true).
  # Never blocks backup success on Slack; failures are logged and ignored.
  parkio_backup_enqueue_slack_biz "$scope" "$success" "$offsite_ok" || true
}

# Enqueue distinct local/offsite backup status events for the Y03 Slack biz relay.
# Requires PARKIO_SLACK_BIZ_ENABLED=true and a configured PARKIO_SLACK_BIZ_WEBHOOK_URL*.
# Does not imply watchdog coverage. Local success does not imply offsite success.
parkio_backup_enqueue_slack_biz() {
  case "${PARKIO_SLACK_BIZ_ENABLED:-}" in
    1|true|TRUE|yes|YES|on|ON) ;;
    *) return 0 ;;
  esac
  local scope="$1"
  local success="$2"
  local offsite_ok="$3"
  local root
  root="$(parkio_backup_repo_root)"
  local local_outcome=failed
  if [ "$success" = "1" ]; then
    local_outcome=success
  fi
  local offsite_outcome=not_observed
  local offsite_reason=not_observed
  if [ "$offsite_ok" = "1" ]; then
    offsite_outcome=success
    offsite_reason=n/a
  elif [ "$offsite_ok" = "0" ] && [ "$success" = "1" ]; then
    # Local completed but offsite metric is 0 → failed upload (not merely unknown)
    offsite_outcome=failed
    offsite_reason=upload
  fi
  local date_utc
  date_utc="$(date -u +%Y-%m-%d)"
  local payload
  payload="$(printf '{"scope":"%s","date":"%s","local_outcome":"%s","offsite_outcome":"%s","offsite_reason":"%s","occurred_at":"%s"}' \
    "$scope" "$date_utc" "$local_outcome" "$offsite_outcome" "$offsite_reason" "$(date -u +%Y-%m-%dT%H:%M:%SZ)")"
  if ! printf '%s\n' "$payload" | python3 "$root/scripts/slack_biz/enqueue.py" --kind backup >/dev/null 2>&1; then
    echo "WARN: slack-biz backup enqueue failed (backup itself unaffected)" >&2
    return 0
  fi
  return 0
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

  local minio_enc=0
  local minio_artifact="none"
  local minio_algo="none"
  if [ -f "${dest_dir}/minio.tar.gz.enc" ]; then
    minio_enc=1
    minio_artifact="minio.tar.gz.enc"
    minio_algo="aes-256-cbc-pbkdf2"
  elif [ -d "${dest_dir}/minio" ]; then
    minio_artifact="minio/"
    minio_algo="none"
  fi
  minio_json="$(jq -n \
    --arg bucket "${MINIO_BUCKET:-parkio-media}" \
    --argjson objectCount "${minio_objects}" \
    --arg path "${dest_dir}/minio" \
    --argjson clientSideEncryption "${minio_enc}" \
    --arg artifact "${minio_artifact}" \
    --arg algorithm "${minio_algo}" \
    '{bucket:$bucket,objectCount:$objectCount,path:$path,clientSideEncryption:($clientSideEncryption==1),artifact:$artifact,algorithm:$algorithm}')"

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
    schemaVersion: 3,
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

# COMPLETE and offsite upload require every dump to succeed and a valid ledger.
# Full production scope also requires a successful MinIO capture/seal.
# Existing stamps are never rewritten by this check.
# Usage: parkio_backup_allow_complete <dest_dir> <db_failed> [minio_ok]
# Two-argument form is the documented DB-only scope (ledger + dumps).
parkio_backup_allow_complete() {
  local dest_dir="$1"
  local db_failed="${2:-0}"
  local minio_ok="${3-}"
  if [ "${db_failed}" -ne 0 ]; then
    echo "ERROR: refusing COMPLETE: database dump failures=${db_failed}" >&2
    return 1
  fi
  local ledger="${dest_dir}/erasure-tombstones.json"
  if [ ! -f "${ledger}" ]; then
    echo "ERROR: refusing COMPLETE: erasure ledger missing" >&2
    return 1
  fi
  python3 -c '
import json, sys
path = sys.argv[1]
try:
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
except Exception as exc:
    print("ERROR: erasure ledger is not JSON:", type(exc).__name__, file=sys.stderr)
    sys.exit(1)
if not isinstance(data, list):
    print("ERROR: erasure ledger must be a JSON array", file=sys.stderr)
    sys.exit(1)
' "${ledger}" || return 1
  if [ -n "${minio_ok}" ]; then
    if [ "${minio_ok}" != "1" ]; then
      echo "ERROR: refusing COMPLETE: MinIO capture/seal failed (minioOk=${minio_ok})" >&2
      return 1
    fi
    if [ -n "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then
      if [ ! -f "${dest_dir}/minio.tar.gz.enc" ]; then
        echo "ERROR: refusing COMPLETE: sealed MinIO artifact missing" >&2
        return 1
      fi
      if [ -d "${dest_dir}/minio" ]; then
        echo "ERROR: refusing COMPLETE: plaintext MinIO tree still present" >&2
        return 1
      fi
    elif [ ! -d "${dest_dir}/minio" ]; then
      echo "ERROR: refusing COMPLETE: MinIO tree missing" >&2
      return 1
    fi
  fi
}

# Remove only this run's unfinished MinIO artifacts. Never touch other stamps.
parkio_backup_discard_partial_minio() {
  local dest_dir="$1"
  [ -n "${dest_dir}" ] && [ -d "${dest_dir}" ] || return 0
  chmod -R u+w "${dest_dir}/minio" 2>/dev/null || true
  rm -rf "${dest_dir}/minio"
  find "${dest_dir}" -maxdepth 1 -name '.minio-seal.*' -exec rm -rf {} + 2>/dev/null || true
  rm -f "${dest_dir}/minio.tar.gz.enc" "${dest_dir}/minio.tar.gz.enc.sha256" \
    "${dest_dir}/minio-encryption.json"
}

# If the current stamp was interrupted before finalize, drop COMPLETE only.
# Leaves dumps, ledger, SHA256SUMS, and other diagnostics in place.
parkio_backup_clear_unfinalized_complete() {
  if [ "${PARKIO_BACKUP_FINALIZED:-0}" != "1" ] && [ -n "${DEST_DIR:-}" ]; then
    rm -f "${DEST_DIR}/COMPLETE"
    find "${DEST_DIR}" -maxdepth 1 -name '.COMPLETE.*' -type f -exec rm -f {} + 2>/dev/null || true
  fi
}

parkio_backup_prune_expired_stamps() {
  local backup_dir="$1"
  local retention="${2:-${BACKUP_RETENTION_DAYS:-14}}"
  if [ ! -d "${backup_dir}" ]; then
    return 0
  fi
  find "${backup_dir}" -mindepth 1 -maxdepth 1 -type d -mtime "+${retention}" -exec rm -rf {} + 2>/dev/null || true
}

parkio_backup_write_stamp_integrity() {
  local dest_dir="$1"
  local stamp="${2:-$(basename "${dest_dir}")}"
  if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
    echo "ERROR: cannot write SHA256SUMS (no sha256sum/shasum)." >&2
    return 1
  fi
  if ! (
    cd "${dest_dir}"
    if command -v sha256sum >/dev/null 2>&1; then
      find . -type f ! -name SHA256SUMS ! -name COMPLETE ! -name '.COMPLETE.*' | LC_ALL=C sort | xargs -r sha256sum > SHA256SUMS
    else
      find . -type f ! -name SHA256SUMS ! -name COMPLETE ! -name '.COMPLETE.*' | LC_ALL=C sort | xargs -r shasum -a 256 > SHA256SUMS
    fi
  ); then
    echo "ERROR: failed to write SHA256SUMS" >&2
    rm -f "${dest_dir}/SHA256SUMS" "${dest_dir}/COMPLETE"
    return 1
  fi
  local sums_hash
  if command -v sha256sum >/dev/null 2>&1; then
    sums_hash="$(sha256sum "${dest_dir}/SHA256SUMS" | awk '{print $1}')" || return 1
  else
    sums_hash="$(shasum -a 256 "${dest_dir}/SHA256SUMS" | awk '{print $1}')" || return 1
  fi
  if [ -z "${sums_hash}" ]; then
    echo "ERROR: empty SHA256SUMS digest; refusing COMPLETE" >&2
    rm -f "${dest_dir}/COMPLETE"
    return 1
  fi
  local complete_tmp
  complete_tmp="$(mktemp "${dest_dir}/.COMPLETE.XXXXXX")"
  printf 'stamp=%s\nsha256sums=%s\n' "${stamp}" "${sums_hash}" > "${complete_tmp}"
  mv "${complete_tmp}" "${dest_dir}/COMPLETE"
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

# Strip a single leading '?' from a SAS token. Never log the token value.
parkio_backup_normalize_sas_token() {
  local raw="${1-}"
  if [ -z "${raw}" ]; then
    echo ""
    return 0
  fi
  case "${raw}" in
    \?\?*)
      echo "ERROR: malformed SAS token (double '?')." >&2
      return 2
      ;;
    \?*)
      printf '%s' "${raw#?}"
      ;;
    *)
      printf '%s' "${raw}"
      ;;
  esac
}

# Resolve Azure auth without printing secrets.
# Precedence: BACKUP_AZURE_SAS_TOKEN > AZURE_STORAGE_SAS_TOKEN > BACKUP_AZURE_STORAGE_KEY >
# AZURE_STORAGE_KEY > --auth-mode login.
# For SAS, exports AZURE_STORAGE_SAS_TOKEN (CLI preferred env; avoids --sas-token argv).
# Sets PARKIO_AZURE_AUTH_MODE to one of: SAS | ACCOUNT_KEY | LOGIN
# (Do not capture stdout via $() — export side effects must remain in the caller shell.)
parkio_backup_azure_resolve_auth() {
  PARKIO_AZURE_AUTH_MODE=""
  local sas_raw=""
  if [ -n "${BACKUP_AZURE_SAS_TOKEN:-}" ]; then
    sas_raw="${BACKUP_AZURE_SAS_TOKEN}"
  elif [ -n "${AZURE_STORAGE_SAS_TOKEN:-}" ]; then
    sas_raw="${AZURE_STORAGE_SAS_TOKEN}"
  fi
  if [ -n "${sas_raw}" ]; then
    local sas
    sas="$(parkio_backup_normalize_sas_token "${sas_raw}")" || return 2
    if [ -z "${sas}" ]; then
      echo "ERROR: Azure SAS token is empty after normalization." >&2
      return 2
    fi
    if ! printf '%s' "${sas}" | grep -Eq '(^|[?&])sv=' \
      || ! printf '%s' "${sas}" | grep -Eq '(^|[?&])sig='; then
      echo "ERROR: Azure SAS token failed shape check (expected sv= and sig= params)." >&2
      return 2
    fi
    export AZURE_STORAGE_SAS_TOKEN="${sas}"
    # Prefer SAS over any ambient account key for this process.
    unset AZURE_STORAGE_KEY 2>/dev/null || true
    PARKIO_AZURE_AUTH_MODE="SAS"
    export PARKIO_AZURE_AUTH_MODE
    return 0
  fi
  if [ -n "${BACKUP_AZURE_STORAGE_KEY:-}" ] || [ -n "${AZURE_STORAGE_KEY:-}" ]; then
    PARKIO_AZURE_AUTH_MODE="ACCOUNT_KEY"
    export PARKIO_AZURE_AUTH_MODE
    return 0
  fi
  PARKIO_AZURE_AUTH_MODE="LOGIN"
  export PARKIO_AZURE_AUTH_MODE
  return 0
}

# Seal mirrored MinIO tree with the same AES-256-CBC PBKDF2 passphrase as DB dumps.
# Leaves minio.tar.gz.enc (+ .sha256) and minio-encryption.json; removes plaintext minio/.
parkio_backup_seal_minio() {
  local dest_dir="$1"
  local plain="${dest_dir}/minio"
  local enc="${dest_dir}/minio.tar.gz.enc"
  local meta="${dest_dir}/minio-encryption.json"

  if [ -z "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then
    return 0
  fi
  if [ -f "${enc}" ]; then
    echo "MinIO already sealed."
    return 0
  fi
  if [ ! -d "${plain}" ]; then
    return 0
  fi

  local work
  work="$(mktemp -d "${dest_dir}/.minio-seal.XXXXXX")"
  chmod 700 "${work}"

  if ! tar -C "${dest_dir}" -czf "${work}/minio.tar.gz" minio; then
    echo "ERROR: failed to archive MinIO backup tree." >&2
    rm -rf "${work}"
    return 1
  fi
  chmod 600 "${work}/minio.tar.gz"
  if ! openssl enc -aes-256-cbc -pbkdf2 -salt \
      -pass env:BACKUP_ENCRYPT_PASSPHRASE \
      -in "${work}/minio.tar.gz" -out "${enc}"; then
    echo "ERROR: MinIO client-side encryption failed." >&2
    rm -f "${enc}"
    rm -rf "${work}"
    return 1
  fi
  chmod 600 "${enc}"
  if ! parkio_backup_write_checksum "${enc}"; then
    rm -f "${enc}" "${enc}.sha256"
    rm -rf "${work}"
    return 1
  fi
  printf '%s\n' '{"schemaVersion":1,"clientSideEncryption":true,"algorithm":"aes-256-cbc-pbkdf2","artifact":"minio.tar.gz.enc","format":"tar.gz.enc"}' \
    > "${meta}"
  chmod 600 "${meta}"

  chmod -R u+w "${plain}" 2>/dev/null || true
  rm -rf "${plain}"
  rm -rf "${work}"
  echo "MinIO sealed -> minio.tar.gz.enc (aes-256-cbc-pbkdf2)"
}

# Decrypt/extract MinIO backup into out_parent (creates out_parent/minio/...).
# Supports historical plaintext dest_dir/minio/ trees.
parkio_backup_unseal_minio() {
  local dest_dir="$1"
  local out_parent="$2"
  mkdir -p "${out_parent}"

  if [ -f "${dest_dir}/minio.tar.gz.enc" ]; then
    if [ -z "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then
      echo "ERROR: BACKUP_ENCRYPT_PASSPHRASE required to decrypt MinIO backup." >&2
      return 1
    fi
    local work
    work="$(mktemp -d "${out_parent}/.minio-unseal.XXXXXX")"
    chmod 700 "${work}"
    if ! openssl enc -d -aes-256-cbc -pbkdf2 \
        -pass env:BACKUP_ENCRYPT_PASSPHRASE \
        -in "${dest_dir}/minio.tar.gz.enc" -out "${work}/minio.tar.gz"; then
      echo "ERROR: MinIO decrypt failed." >&2
      rm -rf "${work}"
      return 1
    fi
    if ! tar -xzf "${work}/minio.tar.gz" -C "${out_parent}"; then
      echo "ERROR: MinIO archive extract failed." >&2
      rm -rf "${work}"
      return 1
    fi
    rm -rf "${work}"
    if [ ! -d "${out_parent}/minio" ]; then
      echo "ERROR: decrypted MinIO archive missing minio/ root." >&2
      return 1
    fi
    return 0
  fi

  if [ -d "${dest_dir}/minio" ]; then
    # Historical plaintext stamp (pre client-side MinIO encryption).
    cp -a "${dest_dir}/minio" "${out_parent}/"
    return 0
  fi

  echo "ERROR: no MinIO backup artifact in ${dest_dir}" >&2
  return 1
}

# Offsite must never receive a plaintext MinIO tree.
parkio_backup_assert_minio_offsite_safe() {
  local dest_dir="$1"
  if [ -d "${dest_dir}/minio" ]; then
    echo "ERROR: refusing offsite upload of plaintext MinIO tree at ${dest_dir}/minio" >&2
    echo "ERROR: set BACKUP_ENCRYPT_PASSPHRASE so MinIO is sealed to minio.tar.gz.enc first." >&2
    return 1
  fi
  return 0
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
  parkio_backup_assert_minio_offsite_safe "${dest_dir}" || return 1
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
  local mc_image="${MINIO_MC_IMAGE:-quay.io/minio/mc@sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727}"
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
  local auth_mode
  parkio_backup_azure_resolve_auth || return 2
  auth_mode="${PARKIO_AZURE_AUTH_MODE}"
  echo "Uploading ${dest_dir} -> azure://${container}/${stamp} (account configured, TLS on, auth=${auth_mode})"
  local complete_tmp
  complete_tmp="$(mktemp "${TMPDIR:-/tmp}/parkio-complete.XXXXXX")"
  mv "${dest_dir}/COMPLETE" "${complete_tmp}"
  local extra=()
  case "${auth_mode}" in
    SAS)
      # AZURE_STORAGE_SAS_TOKEN already exported; avoid --sas-token argv exposure.
      ;;
    ACCOUNT_KEY)
      extra+=(--account-key "${AZURE_STORAGE_KEY:-${BACKUP_AZURE_STORAGE_KEY}}")
      ;;
    LOGIN)
      extra+=(--auth-mode login)
      ;;
    *)
      echo "ERROR: unknown Azure auth mode." >&2
      mv "${complete_tmp}" "${dest_dir}/COMPLETE"
      return 1
      ;;
  esac
  local rc=0
  # Intentionally no set -x around az (secrets may be in env/argv).
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
      local auth_mode
      parkio_backup_azure_resolve_auth || return 2
      auth_mode="${PARKIO_AZURE_AUTH_MODE}"
      echo "Azure offsite pull auth=${auth_mode}"
      local extra=()
      case "${auth_mode}" in
        SAS) ;;
        ACCOUNT_KEY)
          extra+=(--account-key "${AZURE_STORAGE_KEY:-${BACKUP_AZURE_STORAGE_KEY}}")
          ;;
        LOGIN)
          extra+=(--auth-mode login)
          ;;
      esac
      # Intentionally no set -x around az (secrets may be in env/argv).
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
