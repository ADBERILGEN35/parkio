#!/usr/bin/env bash
# Resolve/verify approved containers through Docker's supported logs interface.
# No production log content is printed or read into this process.

set -euo pipefail

readonly compose_project="${PARKIO_NR_COMPOSE_PROJECT:-parkio}"
readonly expected_driver="${PARKIO_NR_EXPECTED_DRIVER:-json-file}"
readonly expected_max_size="${PARKIO_NR_EXPECTED_MAX_SIZE:-10m}"
readonly expected_max_file="${PARKIO_NR_EXPECTED_MAX_FILE:-5}"
readonly source_root="${PARKIO_NR_SOURCE_ROOT:-/var/lib/parkio-nr-source/logs}"
readonly source_state_root="${PARKIO_NR_SOURCE_STATE_ROOT:-/var/lib/parkio-nr-source/state}"
readonly services=(gateway-service auth-service parking-service)
readonly allowed_keys_pattern='^PARKIO_NR_(GATEWAY|AUTH|PARKING)_(CONTAINER_ID|DRIVER|MAX_SIZE|MAX_FILE)$|^PARKIO_NR_SOURCE_(ROOT|STATE_ROOT|SET_SHA256)$'

usage() {
  printf 'usage: %s [--check-env SOURCE_ENV_FILE | --check-helper SOURCE_ENV_FILE | --check-live-helper]\n' "$0" >&2
}

fail() {
  printf 'source-preflight FAIL: %s\n' "$*" >&2
  exit 1
}

need() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

service_prefix() {
  case "$1" in
    gateway-service) printf 'GATEWAY' ;;
    auth-service) printf 'AUTH' ;;
    parking-service) printf 'PARKING' ;;
    *) fail "unexpected service: $1" ;;
  esac
}

resolve_sources() {
  local service prefix ids short_id container_id state project_label service_label
  local driver max_size max_file probe_since source_lines source_hash
  source_lines=''
  probe_since="$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"

  for service in "${services[@]}"; do
    ids="$(docker ps \
      --filter "label=com.docker.compose.project=$compose_project" \
      --filter "label=com.docker.compose.service=$service" \
      --filter status=running --format '{{.ID}}')"
    [ "$(printf '%s\n' "$ids" | sed '/^$/d' | wc -l)" -eq 1 ] || \
      fail "$service must resolve to exactly one running $compose_project container"
    short_id="$(printf '%s\n' "$ids" | sed -n '1p')"
    container_id="$(docker inspect --format '{{.Id}}' "$short_id")"
    state="$(docker inspect --format '{{.State.Status}}' "$container_id")"
    project_label="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$container_id")"
    service_label="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$container_id")"
    driver="$(docker inspect --format '{{.HostConfig.LogConfig.Type}}' "$container_id")"
    max_size="$(docker inspect --format '{{index .HostConfig.LogConfig.Config "max-size"}}' "$container_id")"
    max_file="$(docker inspect --format '{{index .HostConfig.LogConfig.Config "max-file"}}' "$container_id")"

    [ "$state" = running ] || fail "$service is not running"
    [ "$project_label" = "$compose_project" ] || fail "$service project label changed"
    [ "$service_label" = "$service" ] || fail "$service service label changed"
    [ "$driver" = "$expected_driver" ] || fail "$service logging driver is $driver, expected $expected_driver"
    [ "$max_size" = "$expected_max_size" ] || fail "$service max-size is $max_size, expected $expected_max_size"
    [ "$max_file" = "$expected_max_file" ] || fail "$service max-file is $max_file, expected $expected_max_file"

    docker logs --timestamps --since "$probe_since" --tail 0 "$container_id" >/dev/null || \
      fail "$service is not readable through docker logs"

    prefix="$(service_prefix "$service")"
    printf -v source_lines '%sPARKIO_NR_%s_CONTAINER_ID=%s\nPARKIO_NR_%s_DRIVER=%s\nPARKIO_NR_%s_MAX_SIZE=%s\nPARKIO_NR_%s_MAX_FILE=%s\n' \
      "$source_lines" "$prefix" "$container_id" "$prefix" "$driver" \
      "$prefix" "$max_size" "$prefix" "$max_file"
    printf 'source-preflight PASS: service=%s id=%s interface=docker-logs driver=%s rotation=%s/%s\n' \
      "$service" "$container_id" "$driver" "$max_size" "$max_file" >&2
  done

  printf -v source_lines '%sPARKIO_NR_SOURCE_ROOT=%s\nPARKIO_NR_SOURCE_STATE_ROOT=%s\n' \
    "$source_lines" "$source_root" "$source_state_root"
  source_hash="$(printf '%s' "$source_lines" | sha256sum | awk '{print $1}')"
  printf '%sPARKIO_NR_SOURCE_SET_SHA256=%s\n' "$source_lines" "$source_hash"
}

load_expected() {
  local file="$1" line key value
  declare -gA expected=()
  [ -r "$file" ] || fail "source env input is not readable: $file"
  while IFS= read -r line || [ -n "$line" ]; do
    [ -n "$line" ] || continue
    case "$line" in \#*) continue ;; esac
    key="${line%%=*}"
    value="${line#*=}"
    [ "$line" != "$key" ] || fail 'malformed line in source env file'
    [[ "$key" =~ $allowed_keys_pattern ]] || fail "unexpected key in source env file: $key"
    [ -z "${expected[$key]+x}" ] || fail "duplicate key in source env file: $key"
    [[ "$value" =~ ^[A-Za-z0-9._/:+-]+$ ]] || fail "unsafe value syntax for $key"
    expected[$key]="$value"
  done <"$file"
}

check_expected() {
  local file="$1" resolved line key value
  local -A current=()
  load_expected "$file"
  resolved="$(resolve_sources)"
  while IFS= read -r line || [ -n "$line" ]; do
    [ -n "$line" ] || continue
    key="${line%%=*}"
    value="${line#*=}"
    current[$key]="$value"
  done <<<"$resolved"
  for key in "${!current[@]}"; do
    [ -n "${expected[$key]+x}" ] || fail "source env file is missing $key"
    [ "${expected[$key]}" = "${current[$key]}" ] || fail "$key is stale"
  done
  [ "${#expected[@]}" -eq "${#current[@]}" ] || fail 'source env file has an incomplete or extra source set'
  printf 'source-preflight PASS: recorded source set is current sha256=%s\n' \
    "${current[PARKIO_NR_SOURCE_SET_SHA256]}" >&2
}

check_helper() {
  local file="$1" service prefix status_file log_file status_id status_value
  check_expected "$file"
  for service in "${services[@]}"; do
    prefix="$(service_prefix "$service")"
    status_file="${expected[PARKIO_NR_SOURCE_STATE_ROOT]}/$service.status.json"
    log_file="${expected[PARKIO_NR_SOURCE_ROOT]}/$service/source-json.log"
    [ -r "$status_file" ] || fail "$service helper status is missing or unreadable"
    [ -r "$log_file" ] || fail "$service helper spool is missing or unreadable"
    status_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["container_id"])' "$status_file")"
    status_value="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["status"])' "$status_file")"
    [ "$status_value" = attached ] || fail "$service helper status is $status_value, expected attached"
    [ "$status_id" = "${expected[PARKIO_NR_${prefix}_CONTAINER_ID]}" ] || fail "$service helper identity is stale"
    printf 'source-preflight PASS: service=%s helper=attached id=%s spool=readable\n' \
      "$service" "$status_id" >&2
  done
  find "${expected[PARKIO_NR_SOURCE_ROOT]}" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | \
    while IFS= read -r directory; do
      case "$directory" in gateway-service|auth-service|parking-service) ;; *) fail "unexpected source directory: $directory" ;; esac
    done
}

check_live_helper() {
  local resolved line key value service prefix status_file log_file status_id status_value
  local -A current=()
  resolved="$(resolve_sources)"
  while IFS= read -r line || [ -n "$line" ]; do
    [ -n "$line" ] || continue
    key="${line%%=*}"
    value="${line#*=}"
    current[$key]="$value"
  done <<<"$resolved"
  for service in "${services[@]}"; do
    prefix="$(service_prefix "$service")"
    status_file="$source_state_root/$service.status.json"
    log_file="$source_root/$service/source-json.log"
    [ -r "$status_file" ] || fail "$service live helper status is missing or unreadable"
    [ -r "$log_file" ] || fail "$service live helper spool is missing or unreadable"
    status_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["container_id"])' "$status_file")"
    status_value="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["status"])' "$status_file")"
    [ "$status_value" = attached ] || fail "$service live helper status is $status_value, expected attached"
    [ "$status_id" = "${current[PARKIO_NR_${prefix}_CONTAINER_ID]}" ] || \
      fail "$service live helper identity does not match the current container"
    printf 'source-preflight PASS: service=%s live-helper=attached id=%s spool=readable\n' \
      "$service" "$status_id" >&2
  done
  find "$source_root" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | \
    while IFS= read -r directory; do
      case "$directory" in gateway-service|auth-service|parking-service) ;; *) fail "unexpected source directory: $directory" ;; esac
    done
}

need docker
need sha256sum
need python3

case "$#" in
  0) resolve_sources ;;
  1)
    case "$1" in
      --check-live-helper) check_live_helper ;;
      *) usage; exit 2 ;;
    esac
    ;;
  2)
    case "$1" in
      --check-env) check_expected "$2" ;;
      --check-helper) check_helper "$2" ;;
      *) usage; exit 2 ;;
    esac
    ;;
  *) usage; exit 2 ;;
esac
