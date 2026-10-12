#!/usr/bin/env bash
# NR guard-run diagnostic after nr-resume failed only "guard run ends with continuous-guard PASS" (2026-10-12). Read-only.
#
# Run as civo on parkio-civo-prod; sudo asks for the civo password once:
#   bash ~/parkio-release-20261009/tools/nr-guard-diag.sh
#
# A. T1's own record of the failed gate: its detail, which is the last journal line T1 read (steps/nr-resume-<stamp>.txt).
# B. T1's exact read, as civo without sudo, as T1 runs it:
#      journalctl -u parkio-nr-log-continuous-guard.service -n 3 --no-pager -o cat
#    Exit code, stdout lines (T1 reads stdout only) and stderr lines, sanitized; the last stdout line and whether it holds
#    "continuous-guard PASS"; civo's groups (systemd-journal / adm read the system journal).
# C. The guard runs as the journal recorded them, read with sudo, since shortly before the resume. Each line shows its time,
#    its sender (the guard script, or systemd for the unit messages) and the message, sanitized. Then: the number of runs
#    started, PASS lines and FAIL lines, and, for the run T1 started, whether systemd's unit messages follow its PASS line.
# D. systemd's result of the guard unit's last run (Result, ExecMainStatus, ExecMainCode, start and exit times) and the timer
#    (state, last and next trigger).
# E. Source and transport units, the transport containers (budget gate, collector: id, state, health, start, restarts) and
#    the three live-helper attachments.
# Prints unit names, times, short ids, states and the guard's own status lines - no log content of the services, no key,
# no secret. Changes nothing. Exit 0 when every guard run since the resume succeeded (systemd result success / exit 0, at
# least one PASS line, no FAIL line) and the transport is intact with 3 of 3 attached; 1 otherwise.
set -uo pipefail
R="${NGD_OUT:-$HOME/parkio-release-20261009}"                     # overrides: self-test only
UNIT=parkio-nr-log-continuous-guard.service
TIMER=parkio-nr-log-continuous-guard.timer
NRP=parkio-nr-log-continuous
fails=0
note() { echo "  $*"; }
bad() { echo "  FAIL $*"; fails=$((fails + 1)); }
clean() { sed -E -e 's/[0-9a-f]{64}/<id>/g' -e 's/(id=[0-9a-f]{12})[0-9a-f]+/\1/g' | cut -c1-200; }
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT; chmod 700 "$TMP"

echo "NR guard-run diagnostic | $(hostname) | $(date -u +%Y-%m-%dT%H:%M:%SZ) | read-only"
sudo -v || { echo "STOP: sudo is not available"; exit 1; }

echo "== A. T1's record of the failed gate"
REC="$(ls -t "$R"/steps/nr-resume-*.txt 2>/dev/null | head -1)"
if [ -n "$REC" ]; then
  note "report $(basename "$REC")"
  grep -E 'guard run ends with continuous-guard PASS|guard paused from|PASS three sources|guard timer active' "$REC" | clean | sed 's/^/    /'
else
  note "no nr-resume report found"
fi
since="$(python3 -I -c '
import json, sys
from datetime import datetime, timedelta
try:
    at = (json.load(open(sys.argv[1])).get("steps") or {}).get("nr-resume", {}).get("at")
    t = datetime.strptime(at, "%Y-%m-%dT%H:%M:%SZ") - timedelta(minutes=2)
    print(t.strftime("%Y-%m-%d %H:%M:%S"))
except Exception:
    print("")
' "$R/state.json")"
[ -n "$since" ] || since="$(date -u -d '-40 minutes' '+%Y-%m-%d %H:%M:%S')"

echo "== B. T1's exact read, as civo without sudo (T1 reads stdout only)"
journalctl -u "$UNIT" -n 3 --no-pager -o cat > "$TMP/b.out" 2> "$TMP/b.err"; brc=$?
note "exit code $brc; stdout $(grep -c . "$TMP/b.out") line(s); stderr $(grep -c . "$TMP/b.err") line(s)"
while IFS= read -r l; do note "stdout: $l"; done < <(clean < "$TMP/b.out")
while IFS= read -r l; do note "stderr: $l"; done < <(clean < "$TMP/b.err" | head -4)
blast="$(grep . "$TMP/b.out" | tail -1)"
note "the last stdout line, which T1 compares: '${blast:0:100}' - holds 'continuous-guard PASS': $(case "$blast" in *"continuous-guard PASS"*) echo yes ;; *) echo no ;; esac)"
note "civo's groups: $(id -nG)"

echo "== C. the guard runs in the journal since $since UTC (sudo, read-only)"
sudo -n journalctl -u "$UNIT" --since "$since UTC" --no-pager -o short-iso > "$TMP/c.out" 2> "$TMP/c.err"; crc=$?
note "exit code $crc; $(grep -c . "$TMP/c.out") line(s)"
sed -E 's/^([^ ]+) [^ ]+ ([^:]+): /\1 \2: /' "$TMP/c.out" | clean | tail -24 | sed 's/^/    /'
runs="$(grep -c -E 'Start(ing|ed) ' "$TMP/c.out")"; npass="$(grep -c 'continuous-guard PASS' "$TMP/c.out")"; nfail="$(grep -c 'continuous-guard FAIL' "$TMP/c.out")"
note "runs started: $runs; PASS lines: $npass; FAIL lines: $nfail"
first_pass_line="$(grep -n 'continuous-guard PASS' "$TMP/c.out" | head -1 | cut -d: -f1)"
if [ -n "$first_pass_line" ]; then
  after="$(tail -n +"$((first_pass_line + 1))" "$TMP/c.out" | head -3 | sed -E 's/^([^ ]+) [^ ]+ ([^:]+): /\2: /' | clean | tr '\n' '|')"
  note "lines right after the first PASS line: ${after:-none}"
fi
[ "$crc" -eq 0 ] || bad "sudo journalctl exited $crc"
[ "$npass" -ge 1 ] || bad "no 'continuous-guard PASS' line since the resume"
[ "$nfail" -eq 0 ] || bad "$nfail 'continuous-guard FAIL' line(s) since the resume"

echo "== D. systemd's result of the guard unit's last run, and the timer"
systemctl show "$UNIT" -p ActiveState -p Result -p ExecMainStatus -p ExecMainCode -p ExecMainStartTimestamp -p ExecMainExitTimestamp \
  > "$TMP/d.out" 2>/dev/null
while IFS= read -r l; do note "$l"; done < "$TMP/d.out"
res="$(sed -n 's/^Result=//p' "$TMP/d.out")"; st="$(sed -n 's/^ExecMainStatus=//p' "$TMP/d.out")"
[ "$res" = success ] && [ "$st" = 0 ] || bad "the last guard run did not succeed (Result=${res:-?}, ExecMainStatus=${st:-?})"
systemctl show "$TIMER" -p ActiveState -p LastTriggerUSec -p NextElapseUSecRealtime 2>/dev/null | while IFS= read -r l; do note "timer $l"; done

echo "== E. source, transport and attachments"
for u in parkio-nr-log-source.service parkio-nr-log-continuous.service "$TIMER" "$UNIT"; do note "unit $u: $(systemctl is-active "$u" 2>/dev/null || true)"; done
[ "$(systemctl is-active parkio-nr-log-source.service 2>/dev/null) $(systemctl is-active parkio-nr-log-continuous.service 2>/dev/null)" = "active active" ] \
  || bad "source or transport is not active"
docker ps -a -q --filter "label=com.docker.compose.project=$NRP" | xargs -r docker inspect -f \
  '{{index .Config.Labels "com.docker.compose.service"}} {{printf "%.12s" .Id}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} {{.State.StartedAt}} {{.RestartCount}}' \
  > "$TMP/e.out" 2>/dev/null
while read -r s id stt h started rc; do note "container $s: $id $stt $h started $started restarts $rc"; done < "$TMP/e.out"
[ "$(awk '$3 == "running" && $4 == "healthy"' "$TMP/e.out" | wc -l)" -eq 2 ] || bad "the two transport containers are not both running and healthy"
att="$(sudo -n env PARKIO_NR_COMPOSE_PROJECT=parkio PARKIO_NR_SOURCE_ROOT=/var/lib/parkio-nr-log-continuous/source/logs \
  PARKIO_NR_SOURCE_STATE_ROOT=/var/lib/parkio-nr-log-continuous/source/state \
  /opt/parkio-nr-log-continuous/current/scripts/newrelic_log_pilot/resolve_production_sources.sh --check-live-helper 2>&1 | grep -c 'live-helper=attached')"
note "sources attached: $att of 3"
[ "$att" -eq 3 ] || bad "$att of 3 sources attached"

echo ""
if grep -q -i -E 'insufficient permissions|not seeing messages|No journal files' "$TMP/b.out" "$TMP/b.err" || [ "$(grep -c . "$TMP/b.out")" -eq 0 ] \
   || grep -q -- '-- No entries --' "$TMP/b.out"; then
  cause="T1's journalctl, run as civo without sudo, did not see the guard unit's journal (permission or no entries)"
elif case "$blast" in *"continuous-guard PASS"*) true ;; *) false ;; esac; then
  cause="T1's read ends with the PASS line now; at the moment of the check the line was not the last (timing)"
else
  cause="T1's read ends with '${blast:0:80}': a line after the guard's PASS line is the last one"
fi
if [ "$fails" -eq 0 ]; then
  echo "GUARD RUNS VERIFIED - every run since the resume succeeded and printed continuous-guard PASS; transport intact; 3 of 3 attached"
  echo "T1's single-line check: $cause"
  exit 0
fi
echo "GUARD RUNS NOT VERIFIED ($fails finding(s)) - do not change the timer or the NR stack; send this output"
echo "T1's single-line check: $cause"
exit 1
