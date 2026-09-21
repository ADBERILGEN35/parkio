#!/usr/bin/env bash
# Stop only the dedicated log-pilot helper and collector containers.
set -euo pipefail

readonly project="${PARKIO_NR_PILOT_PROJECT:-parkio-nr-log-pilot}"
readonly helper_unit="${PARKIO_NR_SOURCE_HELPER_UNIT:-parkio-nr-log-source.service}"
readonly allowed='^(fluent-bit-nr-pilot|nr-budget-gate)$'

if command -v systemctl >/dev/null 2>&1; then
  systemctl stop "$helper_unit" 2>/dev/null || true
fi

mapfile -t ids < <(docker ps -q --filter "label=com.docker.compose.project=$project")
for id in "${ids[@]-}"; do
  [ -n "$id" ] || continue
  service="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$id")"
  [[ "$service" =~ $allowed ]] || {
    printf 'refusing to stop unexpected service %s in project %s\n' "$service" "$project" >&2
    exit 1
  }
done
if [ "${#ids[@]}" -gt 0 ]; then
  docker stop --time 30 "${ids[@]}" >/dev/null
fi
printf 'parkio New Relic log pilot stopped; application containers were not targeted\n'
