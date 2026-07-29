#!/usr/bin/env bash
# WP-06.2/06.2A — fail-closed staging safety guards.

set -euo pipefail

_STAGING_ALLOWED_ENV_TYPES=(CI_EPHEMERAL STAGING_LOCAL STAGING_SHARED)
_STAGING_SAFE_DB_PATTERN='(_drill_|_restore_|_verify_|_staging_|_test_|_wp062b_|wp062)'

assert_not_production() {
  local env_type="${PARKIO_ENVIRONMENT_TYPE:-}"
  local env_name="${PARKIO_ENVIRONMENT:-${ENVIRONMENT:-}}"
  local env_lower
  env_lower="$(printf '%s' "${env_name}" | tr '[:upper:]' '[:lower:]')"
  if [ "${env_type}" = "PRODUCTION" ] || [ "${env_lower}" = "production" ]; then
    echo "ERROR: PRODUCTION environment rejected." >&2
    exit 1
  fi
  if [ -n "${PARKIO_PRODUCTION_CONFIRM:-}" ] || [ -n "${PARKIO_CONFIRM_PRODUCTION:-}" ]; then
    echo "ERROR: production confirmation variables forbidden in staging verification." >&2
    exit 1
  fi
}

assert_allowed_environment_type() {
  local env_type="${PARKIO_ENVIRONMENT_TYPE:-}"
  if [ -z "${env_type}" ]; then
    echo "ERROR: PARKIO_ENVIRONMENT_TYPE required." >&2
    exit 1
  fi
  local ok=no
  for t in "${_STAGING_ALLOWED_ENV_TYPES[@]}"; do
    if [ "${env_type}" = "${t}" ]; then ok=yes; break; fi
  done
  if [ "${ok}" != yes ]; then
    echo "ERROR: disallowed PARKIO_ENVIRONMENT_TYPE='${env_type}'." >&2
    exit 1
  fi
  if [ "${env_type}" = "STAGING_SHARED" ] && [ "${PARKIO_STAGING_SHARED_OPT_IN:-}" != yes ]; then
    echo "ERROR: STAGING_SHARED requires PARKIO_STAGING_SHARED_OPT_IN=yes." >&2
    exit 1
  fi
}

assert_marker_safe() {
  if [ -z "${PARKIO_STAGING_ISOLATION_MARKER:-}" ]; then
    echo "ERROR: PARKIO_STAGING_ISOLATION_MARKER required." >&2
    exit 1
  fi
  if [ "${#PARKIO_STAGING_ISOLATION_MARKER}" -lt 8 ]; then
    echo "ERROR: isolation marker too short." >&2
    exit 1
  fi
  case "${PARKIO_STAGING_ISOLATION_MARKER}" in
    *[\;\|\&\$\(\)\`\\]*) echo "ERROR: marker contains shell metacharacters." >&2; exit 1 ;;
  esac
}

assert_isolation_marker() { assert_marker_safe; }

assert_safe_database_name() {
  local db="${1:-}"
  if [ -z "${db}" ]; then echo "ERROR: empty database name." >&2; exit 1; fi
  if [[ ! "${db}" =~ ${_STAGING_SAFE_DB_PATTERN} ]]; then
    echo "ERROR: unsafe database '${db}'." >&2
    exit 1
  fi
}

assert_restore_target_not_source() {
  local source="${1:-}" target="${2:-}"
  if [ -n "${source}" ] && [ "${source}" = "${target}" ]; then
    echo "ERROR: restore target equals source database." >&2
    exit 1
  fi
}

assert_destructive_opt_in() {
  if [ "${PARKIO_STAGING_ALLOW_DESTRUCTIVE:-}" != yes ]; then
    echo "ERROR: PARKIO_STAGING_ALLOW_DESTRUCTIVE=yes required." >&2
    exit 1
  fi
}

assert_compose_project_isolated() {
  local project="${COMPOSE_PROJECT_NAME:-}"
  if [ -z "${project}" ]; then echo "ERROR: COMPOSE_PROJECT_NAME required." >&2; exit 1; fi
  if [[ ! "${project}" =~ ^parkio-wp062- ]]; then
    echo "ERROR: COMPOSE_PROJECT_NAME must start with parkio-wp062-." >&2
    exit 1
  fi
  if [ "${project}" = "parkio" ]; then
    echo "ERROR: developer compose project 'parkio' is forbidden." >&2
    exit 1
  fi
}

# Fail closed if a required host port is already listening. Does not kill the occupant.
assert_host_ports_free() {
  local port
  for port in "$@"; do
    if command -v ss >/dev/null 2>&1; then
      if ss -ltn 2>/dev/null | awk '{print $4}' | grep -E "[:.]${port}$" >/dev/null 2>&1; then
        echo "ERROR: host port ${port} is occupied — refusing to start isolated stack." >&2
        exit 1
      fi
    elif command -v netstat >/dev/null 2>&1; then
      if netstat -an 2>/dev/null | grep -E "[:.]${port}[[:space:]].*LISTEN" >/dev/null 2>&1; then
        echo "ERROR: host port ${port} is occupied — refusing to start isolated stack." >&2
        exit 1
      fi
    else
      # Best-effort TCP probe without killing anything.
      if (echo >/dev/tcp/127.0.0.1/"${port}") >/dev/null 2>&1; then
        echo "ERROR: host port ${port} appears open — refusing to start isolated stack." >&2
        exit 1
      fi
    fi
  done
}

assert_safe_evidence_path() {
  local path="${1:-}"
  if [[ "${path}" =~ \.\. ]]; then echo "ERROR: evidence path traversal rejected." >&2; exit 1; fi
  if [[ ! "${path}" =~ ^(build/operational-evidence|artifacts/wp-06-2)/ ]]; then
    echo "ERROR: evidence path outside approved roots." >&2
    exit 1
  fi
}

assert_no_global_docker_cleanup() {
  if [ -n "${PARKIO_DOCKER_PRUNE:-}" ] || [ -n "${PARKIO_SYSTEM_PRUNE:-}" ]; then
    echo "ERROR: global docker prune forbidden." >&2
    exit 1
  fi
}

assert_staging_safety() {
  assert_not_production
  assert_allowed_environment_type
  assert_isolation_marker
  assert_no_global_docker_cleanup
}

assert_restore_target_safety() {
  local db="${1:-}" source="${2:-${PARKIO_RESTORE_SOURCE_DB:-}}"
  assert_staging_safety
  assert_destructive_opt_in
  assert_safe_database_name "${db}"
  assert_restore_target_not_source "${source}" "${db}"
}