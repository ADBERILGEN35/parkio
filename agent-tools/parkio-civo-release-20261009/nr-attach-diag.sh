#!/usr/bin/env bash
# NR attachment diagnostic after D3 reported "NR three sources attached: 0/0 attached (exit 0)" (2026-10-12). Read-only.
#
# Run as civo on parkio-civo-prod; sudo asks for the civo password once:
#   bash ~/parkio-release-20261009/tools/nr-attach-diag.sh
#
# A. T1's exact check, argv unchanged (t1-release-step.py NR_CHECK):
#      sudo env PARKIO_NR_COMPOSE_PROJECT=parkio PARKIO_NR_SOURCE_ROOT=<source root> PARKIO_NR_SOURCE_STATE_ROOT=<state root>
#        /opt/parkio-nr-log-continuous/current/scripts/newrelic_log_pilot/resolve_production_sources.sh --check-live-helper
#    with stdout and stderr captured separately: exit code, line counts and `live-helper=attached` lines per stream, and what
#    each parser counts - T1's nr_attached() reads stdout only, nr-step.sh merges stderr into stdout. The script exits 1 on
#    any source that is not attached to its current container.
# B. Independent comparison for gateway-service, auth-service and parking-service (compose project parkio):
#    - the running container: exactly one, with its id and start time;
#    - the helper's status file <state root>/<service>.status.json: status, container_id, updated_at;
#    - the helper's cursor file <state root>/<service>.json: container_id and the timestamp of the last log line it read;
#    - the spool <source root>/<service>/source-json.log: readable, size, time of the last write (content never read).
#    A service counts as attached when the status is attached and both files name the current container.
# C. The helper journal since the oldest of the three containers started: the `source-helper ATTACHED` lines only.
# D. The NR units and the two transport containers (budget gate, collector): state, health, start time.
# Prints service names, 12-character container ids, states, times, ages, sizes and counts - no log content, key or secret.
# Changes nothing. Exit 0 when A exits 0 with three attached lines and B finds all three attached to their current
# containers; 1 otherwise.
set -uo pipefail
STATE_ROOT="${NRD_STATE_ROOT:-/var/lib/parkio-nr-log-continuous/source/state}"     # overrides: self-test only
SOURCE_ROOT="${NRD_SOURCE_ROOT:-/var/lib/parkio-nr-log-continuous/source/logs}"
CHECK="${NRD_CHECK_SCRIPT:-/opt/parkio-nr-log-continuous/current/scripts/newrelic_log_pilot/resolve_production_sources.sh}"
NRP=parkio-nr-log-continuous
SERVICES="gateway-service auth-service parking-service"
fails=0
note() { echo "  $*"; }
bad() { echo "  FAIL $*"; fails=$((fails + 1)); }
short_ids() { sed -E 's/(id=[0-9a-f]{12})[0-9a-f]*/\1/g'; }
age() { local now; now="$(date -u +%s)"; echo $(( now - $1 )); }
epoch() { date -u -d "$1" +%s 2>/dev/null || echo 0; }
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT; chmod 700 "$TMP"

echo "NR attachment diagnostic | $(hostname) | $(date -u +%Y-%m-%dT%H:%M:%SZ) | read-only"
sudo -v || { echo "STOP: sudo is not available"; exit 1; }

echo "== A. T1's exact check, stdout and stderr captured separately"
sudo -n env PARKIO_NR_COMPOSE_PROJECT=parkio PARKIO_NR_SOURCE_ROOT="$SOURCE_ROOT" PARKIO_NR_SOURCE_STATE_ROOT="$STATE_ROOT" \
  "$CHECK" --check-live-helper > "$TMP/out" 2> "$TMP/err"
rc=$?
o_lines="$(grep -c . "$TMP/out")"; e_lines="$(grep -c . "$TMP/err")"
o_att="$(grep -c 'live-helper=attached' "$TMP/out")"; e_att="$(grep -c 'live-helper=attached' "$TMP/err")"
o_lh="$(grep -c 'live-helper=' "$TMP/out")"
note "exit code $rc; stdout $o_lines line(s), $o_att with live-helper=attached; stderr $e_lines line(s), $e_att with live-helper=attached"
note "T1 nr_attached() parses stdout only -> counts $o_att/$o_lh attached; nr-step.sh (stdout and stderr merged) -> counts $((o_att + e_att))"
while IFS= read -r line; do note "stderr: $line"; done < <(grep -E 'source-preflight' "$TMP/err" | short_ids | head -6)
[ "$rc" -eq 0 ] || bad "the check exited $rc (a source is not attached to its current container, or the check could not run)"
[ $((o_att + e_att)) -eq 3 ] || bad "the check printed $((o_att + e_att)) attached line(s), not 3"

echo "== B. independent comparison with the current containers"
attached=0; oldest=""
for s in $SERVICES; do
  ids="$(docker ps -q --filter label=com.docker.compose.project=parkio --filter "label=com.docker.compose.service=$s" --filter status=running)"
  n="$(printf '%s\n' "$ids" | sed '/^$/d' | wc -l)"
  if [ "$n" -ne 1 ]; then bad "$s: $n running container(s), expected exactly 1"; continue; fi
  full="$(docker inspect -f '{{.Id}}' "$ids")"; started="$(docker inspect -f '{{.State.StartedAt}}' "$ids")"
  [ -z "$oldest" ] || [ "$(epoch "$started")" -lt "$(epoch "$oldest")" ] && oldest="$started"
  status_json="$(sudo -n cat "$STATE_ROOT/$s.status.json" 2>/dev/null)"
  cursor_json="$(sudo -n cat "$STATE_ROOT/$s.json" 2>/dev/null)"
  read -r st_status st_id st_updated cu_id cu_ts <<< "$(python3 -I -c '
import json, sys
def load(text):
    try:
        return json.loads(text)
    except ValueError:
        return {}
s, c = load(sys.argv[1]), load(sys.argv[2])
print(s.get("status") or "-", s.get("container_id") or "-", s.get("updated_at") or "-", c.get("container_id") or "-", c.get("timestamp") or "-")
' "$status_json" "$cursor_json")"
  spool="$(sudo -n stat -c '%s %Y' "$SOURCE_ROOT/$s/source-json.log" 2>/dev/null)"
  readable="$(sudo -n test -r "$SOURCE_ROOT/$s/source-json.log" && echo yes || echo no)"
  note "$s: container ${full:0:12} started $started"
  note "  helper status $st_status, container ${st_id:0:12}, updated $st_updated"
  if [ "$cu_ts" != "-" ]; then
    note "  cursor container ${cu_id:0:12}, last log line read $cu_ts ($(age "$(epoch "$cu_ts")") s ago)"
  else
    note "  cursor: none yet"
  fi
  if [ -n "$spool" ]; then
    note "  spool readable $readable, ${spool%% *} bytes, last written $(age "${spool##* }") s ago"
  else
    note "  spool: missing or unreadable"
  fi
  if [ "$st_status" = attached ] && [ "$st_id" = "$full" ] && [ "$cu_id" = "$full" ] && [ "$readable" = yes ]; then
    note "  -> ATTACHED to the current container"
    attached=$((attached + 1))
  else
    bad "$s is not attached to its current container (status $st_status; status file names ${st_id:0:12}; cursor names ${cu_id:0:12}; current ${full:0:12}; spool readable $readable)"
  fi
done
note "independently attached: $attached of 3"

echo "== C. helper journal since $oldest (ATTACHED lines only)"
if [ -n "$oldest" ]; then
  sudo -n journalctl -u parkio-nr-log-source.service --since "$(date -u -d "$oldest" '+%Y-%m-%d %H:%M:%S') UTC" --no-pager -o short-iso 2>/dev/null \
    | grep 'source-helper ATTACHED' | sed -E 's/ driver=[^ ]*//; s/^([^ ]+) [^ ]+ [^ ]+: /\1 /' | short_ids | tail -6 \
    | while IFS= read -r line; do note "$line"; done
fi

echo "== D. NR units and transport containers"
for u in parkio-nr-log-source.service parkio-nr-log-continuous.service parkio-nr-log-continuous-guard.timer parkio-nr-log-continuous-guard.service; do
  note "unit $u: $(systemctl is-active "$u" 2>/dev/null || true)"
done
docker ps -a -q --filter "label=com.docker.compose.project=$NRP" | xargs -r docker inspect -f \
  '  container {{index .Config.Labels "com.docker.compose.service"}} {{printf "%.12s" .Id}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} started {{.State.StartedAt}} restarts {{.RestartCount}}'

echo ""
if [ "$fails" -eq 0 ]; then
  echo "NR ATTACHMENT VERIFIED - 3 of 3 sources attached to the current gateway, auth and parking containers;"
  echo "the check exits 0 and prints its PASS lines on stderr, so a parser that reads stdout only counts 0/0"
  exit 0
fi
echo "NR ATTACHMENT NOT VERIFIED ($fails finding(s)) - do not re-run D3 or resume the guard; send this output"
exit 1
