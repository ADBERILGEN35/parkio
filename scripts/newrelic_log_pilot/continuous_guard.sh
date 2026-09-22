#!/usr/bin/env bash
# Content-free continuous collector guard. On a violated invariant it stops
# only the dedicated helper/collector project and leaves applications untouched.

set -euo pipefail

readonly project="${PARKIO_NR_PILOT_PROJECT:-parkio-nr-log-continuous}"
readonly helper_unit="${PARKIO_NR_SOURCE_HELPER_UNIT:-parkio-nr-log-source.service}"
readonly transport_unit="${PARKIO_NR_TRANSPORT_UNIT:-parkio-nr-log-continuous.service}"
readonly source_root="${PARKIO_NR_SOURCE_ROOT:-/var/lib/parkio-nr-log-continuous/source/logs}"
readonly source_state_root="${PARKIO_NR_SOURCE_STATE_ROOT:-/var/lib/parkio-nr-log-continuous/source/state}"
readonly collector_root="${PARKIO_NR_COLLECTOR_STATE_ROOT:-/var/lib/parkio-nr-log-continuous/collector}"
readonly budget_root="${PARKIO_NR_BUDGET_STATE_ROOT:-/var/lib/parkio-nr-log-continuous/budget}"
readonly source_max="${PARKIO_NR_SOURCE_ALLOCATED_MAX_BYTES:-25165824}"
readonly collector_max="${PARKIO_NR_COLLECTOR_ALLOCATED_MAX_BYTES:-33554432}"
readonly budget_max="${PARKIO_NR_BUDGET_ALLOCATED_MAX_BYTES:-4194304}"
readonly min_free="${PARKIO_NR_HOST_MIN_FREE_BYTES:-5368709120}"
readonly script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

stop_and_fail() {
  local reason="$1"
  printf 'continuous-guard FAIL reason=%s; stopping dedicated log transport\n' "$reason" >&2
  # Keep systemd's state consistent with the stopped containers. --no-block
  # avoids waiting for this guard invocation while systemd stops dependents.
  systemctl --no-block stop "$transport_unit" 2>/dev/null || true
  PARKIO_NR_PILOT_PROJECT="$project" PARKIO_NR_SOURCE_HELPER_UNIT="$helper_unit" \
    "$script_dir/stop_pilot.sh" >&2 || true
  exit 1
}

allocated_bytes() {
  du -sB1 "$1" | awk '{print $1}'
}

for value in "$source_max" "$collector_max" "$budget_max" "$min_free"; do
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || stop_and_fail invalid_numeric_bound
done
for directory in "$source_root" "$source_state_root" "$collector_root" "$budget_root"; do
  [ -d "$directory" ] || stop_and_fail missing_state_directory
done

PARKIO_NR_SOURCE_ROOT="$source_root" PARKIO_NR_SOURCE_STATE_ROOT="$source_state_root" \
  "$script_dir/resolve_production_sources.sh" --check-live-helper >/dev/null 2>&1 || \
  stop_and_fail stale_disconnected_or_unexpected_source

mapfile -t services < <(docker ps --filter "label=com.docker.compose.project=$project" \
  --format '{{.Label "com.docker.compose.service"}}' | sort)
[ "${services[*]-}" = "fluent-bit-nr-pilot nr-budget-gate" ] || stop_and_fail unexpected_or_missing_transport_service

for service in fluent-bit-nr-pilot nr-budget-gate; do
  id="$(docker ps -q --filter "label=com.docker.compose.project=$project" \
    --filter "label=com.docker.compose.service=$service")"
  [ -n "$id" ] || stop_and_fail missing_transport_container
  [ "$(docker inspect --format '{{.State.Running}}/{{.State.OOMKilled}}' "$id")" = true/false ] || \
    stop_and_fail transport_failure_or_oom
done

[ "$(allocated_bytes "$(dirname "$source_root")")" -le "$source_max" ] || stop_and_fail source_storage_bound
[ "$(allocated_bytes "$collector_root")" -le "$collector_max" ] || stop_and_fail collector_storage_bound
[ "$(allocated_bytes "$budget_root")" -le "$budget_max" ] || stop_and_fail budget_storage_bound
[ "$(df -B1 --output=avail "$budget_root" | tail -1 | tr -d ' ')" -ge "$min_free" ] || \
  stop_and_fail host_disk_pressure

printf 'continuous-guard PASS sources=current transport=running storage=within-bounds\n'
