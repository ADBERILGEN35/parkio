#!/usr/bin/env bash
# Resolve or verify the three production Docker json-file sources without
# reading log contents. Status is written to stderr; stdout is an env file.

set -euo pipefail

readonly compose_project="${PARKIO_NR_COMPOSE_PROJECT:-parkio}"
readonly expected_max_size="${PARKIO_NR_EXPECTED_MAX_SIZE:-10m}"
readonly expected_max_file="${PARKIO_NR_EXPECTED_MAX_FILE:-5}"
readonly services=(gateway-service auth-service parking-service)
readonly allowed_keys_pattern='^PARKIO_NR_(GATEWAY|AUTH|PARKING)_(CONTAINER_ID|LOG_PATH|LOG_DIR)$|^PARKIO_NR_SOURCE_SET_SHA256$'

usage() {
  printf 'usage: %s [--check-env SOURCE_ENV_FILE]\n' "$0" >&2
}

fail() {
  printf 'source-preflight FAIL: %s\n' "$*" >&2
  exit 1
}

need() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

root_test() {
  if [ "$(id -u)" -eq 0 ]; then
    test "$@"
  else
    sudo -n test "$@"
  fi
}

root_stat() {
  if [ "$(id -u)" -eq 0 ]; then
    stat "$@"
  else
    sudo -n stat "$@"
  fi
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
  local docker_root service prefix ids short_id container_id state project_label
  local service_label driver max_size max_file log_path log_dir expected_path
  local mode owner source_lines source_hash
  local -a source_dirs=()

  docker_root="$(docker info --format '{{.DockerRootDir}}')"
  [ -n "$docker_root" ] || fail 'DockerRootDir is empty'

  source_lines=''
  for service in "${services[@]}"; do
    ids="$(docker ps \
      --filter "label=com.docker.compose.project=$compose_project" \
      --filter "label=com.docker.compose.service=$service" \
      --filter status=running \
      --format '{{.ID}}')"
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
    log_path="$(docker inspect --format '{{.LogPath}}' "$container_id")"

    [ "$state" = running ] || fail "$service is not running"
    [ "$project_label" = "$compose_project" ] || fail "$service project label changed"
    [ "$service_label" = "$service" ] || fail "$service service label changed"
    [ "$driver" = json-file ] || fail "$service logging driver is $driver, expected json-file"
    [ "$max_size" = "$expected_max_size" ] || fail "$service max-size is $max_size, expected $expected_max_size"
    [ "$max_file" = "$expected_max_file" ] || fail "$service max-file is $max_file, expected $expected_max_file"
    [ -n "$log_path" ] || fail "$service LogPath is empty"

    expected_path="$docker_root/containers/$container_id/$container_id-json.log"
    [ "$log_path" = "$expected_path" ] || fail "$service LogPath does not match its current container identity"
    log_dir="${log_path%/*}"
    root_test -f "$log_path" || fail "$service LogPath is not a regular file"
    root_test -r "$log_path" || fail "$service LogPath is not root-readable"
    root_test -x "$log_dir" || fail "$service LogPath parent is not root-searchable"
    mode="$(root_stat -c '%a' "$log_path")"
    owner="$(root_stat -c '%U:%G' "$log_path")"

    for existing_dir in "${source_dirs[@]-}"; do
      [ "$existing_dir" != "$log_dir" ] || fail "$service shares a log directory with another source"
    done
    source_dirs+=("$log_dir")

    prefix="$(service_prefix "$service")"
    printf -v source_lines '%sPARKIO_NR_%s_CONTAINER_ID=%s\nPARKIO_NR_%s_LOG_PATH=%s\nPARKIO_NR_%s_LOG_DIR=%s\n' \
      "$source_lines" "$prefix" "$container_id" "$prefix" "$log_path" "$prefix" "$log_dir"
    printf 'source-preflight PASS: service=%s id=%s driver=%s rotation=%s/%s mode=%s owner=%s\n' \
      "$service" "$container_id" "$driver" "$max_size" "$max_file" "$mode" "$owner" >&2
  done

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
    [ "$line" != "$key" ] || fail "malformed line in source env file"
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

need docker
need sha256sum
need stat
if [ "$(id -u)" -ne 0 ]; then
  need sudo
fi

case "$#" in
  0) resolve_sources ;;
  2)
    [ "$1" = --check-env ] || { usage; exit 2; }
    check_expected "$2"
    ;;
  *) usage; exit 2 ;;
esac
