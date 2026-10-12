#!/usr/bin/env bash
# NR - the N1 pause and resume of the New Relic continuous-log guard around D3-D6b, with read-only before/after evidence
# (plan v2.1 section 8, option N1; owner decisions 2026-10-10).
#
# Run as civo on parkio-civo-prod, in the release shell; sudo asks for the civo password once:
#   bash ~/parkio-release-20261009/tools/nr-step.sh pause     before D3
#   bash ~/parkio-release-20261009/tools/nr-step.sh resume    after D6b
#   bash ~/parkio-release-20261009/tools/nr-step.sh check     read-only snapshot only
#
# 1. The installed T1 copy must match its pinned SHA-256 (T1 v6, 2026-10-12: E identity and record fixes; v4: D7 scanner fixes; v3: counts the helper check's stderr; verifies
#    the guard run from its own journal lines with sudo, starting nothing when the timer is already active); sudo
#    is validated once, so T1's own sudo calls do not prompt.
# 2. Snapshot before (read-only): the states of the source unit, the transport unit (it runs the budget gate and the
#    collector), the guard timer and the guard service; for every container of the transport project
#    parkio-nr-log-continuous (nr-budget-gate, fluent-bit-nr-pilot): id, state, Docker health, start time and restart
#    count; and the plan's common check `resolve_production_sources.sh --check-live-helper` (three live-helper=attached
#    lines). pause needs before: source and transport active, both transport containers running and healthy, three
#    sources attached - otherwise STOP with nothing changed (the owner decides between N3 and N2).
# 3. Runs only `t1-release-step.py nr-pause` (sudo systemctl stop parkio-nr-log-continuous-guard.timer) or
#    `t1-release-step.py nr-resume` (T1 checks the three sources first, then starts the timer and one guard run).
# 4. Snapshot after. pause needs: the timer and the guard service inactive; source and transport still active; the same
#    two transport containers (same ids and start times, so not restarted), running and healthy; three sources attached.
#    resume needs: the timer active; source and transport active; the same transport containers, running and healthy;
#    three sources attached.
# 5. Prints and writes ~/parkio-release-20261009/steps/NR-<mode>-<UTC stamp>.txt (mode 600); prints T1 status.
# Names, ids, states and times only - no secret is read or printed. Exit 0 only when T1 and every check pass.
set -uo pipefail
MODE="${1:-}"
R="${NR_OUT:-$HOME/parkio-release-20261009}"
T1="${NR_T1:-$R/tools/t1-release-step.py}"
T1_SHA="${NR_T1_SHA256:-180385f9cd40d671d1348d6211283fecf88b61225dd9c1f54db7749f315caf79}"   # override: self-test only
NRP=parkio-nr-log-continuous
UNITS="parkio-nr-log-source.service parkio-nr-log-continuous.service parkio-nr-log-continuous-guard.timer parkio-nr-log-continuous-guard.service"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT=""
fails=0
say() { printf '%s\n' "$*"; REPORT+="$*"$'\n'; }
check() { if [ "$2" = yes ]; then say "  PASS $1"; else say "  FAIL $1${3:+: $3}"; fails=$((fails + 1)); fi; }
save() {
  mkdir -p "$R/steps" && chmod 700 "$R/steps" && ( umask 077; printf '%s' "$REPORT" > "$R/steps/NR-$MODE-$STAMP.txt" ) \
    && echo "saved: $R/steps/NR-$MODE-$STAMP.txt"
}
finish() { local rc="$1"; shift; say "$*"; save; exit "$rc"; }
yn() { if [ "$1" = "$2" ]; then echo yes; else echo no; fi; }

snapshot() {  # $1 = label; sets U_<unit>, CONT (lines "service id state health started restarts") and ATTACHED
  local u v
  say "-- NR $1"
  for u in $UNITS; do
    v="$(systemctl is-active "$u" 2>/dev/null)"; v="${v:-unknown}"
    say "  unit $u: $v"
    printf -v "U_${u//[.-]/_}" '%s' "$v"
  done
  CONT="$(docker ps -a -q --filter "label=com.docker.compose.project=$NRP" 2>/dev/null | xargs -r docker inspect -f \
    '{{index .Config.Labels "com.docker.compose.service"}} {{printf "%.12s" .Id}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} {{.State.StartedAt}} {{.RestartCount}}' \
    2>/dev/null | LC_ALL=C sort)"
  if [ -n "$CONT" ]; then
    while read -r s id st h started rc; do
      say "  container $s: id $id state $st health $h started $started restarts $rc"
    done <<< "$CONT"
  else
    say "  no container of the project $NRP"
  fi
  local out
  out="$(sudo -n env PARKIO_NR_COMPOSE_PROJECT=parkio PARKIO_NR_SOURCE_ROOT=/var/lib/parkio-nr-log-continuous/source/logs \
    PARKIO_NR_SOURCE_STATE_ROOT=/var/lib/parkio-nr-log-continuous/source/state \
    /opt/parkio-nr-log-continuous/current/scripts/newrelic_log_pilot/resolve_production_sources.sh --check-live-helper 2>&1)"
  ATTACHED="$(printf '%s\n' "$out" | grep -c 'live-helper=attached')"
  while IFS= read -r line; do say "$line"; done \
    < <(printf '%s\n' "$out" | grep -E 'live-helper=|FAIL' | sed -e 's/^source-preflight //' -e 's/^/  /' | head -6)
  say "  sources attached: $ATTACHED of 3"
}
transport_ok() {  # exactly the two transport services running, each healthy (an old exited container does not count)
  local s running
  running="$(printf '%s\n' "$CONT" | awk '$3 == "running"')"
  for s in fluent-bit-nr-pilot nr-budget-gate; do
    printf '%s\n' "$running" | grep -q "^$s [0-9a-f]* running healthy " || return 1
  done
  [ "$(printf '%s\n' "$running" | sed '/^$/d' | wc -l)" -eq 2 ]
}
identity() { printf '%s\n' "$CONT" | awk '$3 == "running" { print $1, $2, $5 }'; }   # running: service, id, start time

case "$MODE" in pause|resume|check) ;; *) echo "usage: nr-step.sh pause|resume|check"; exit 2 ;; esac
say "NR $MODE | $(hostname) | $(date -u +%Y-%m-%dT%H:%M:%SZ) | plan section 8, option N1"
say "== 1. T1 copy and sudo"
if [ "$MODE" != check ]; then
  echo "$T1_SHA  $T1" | sha256sum -c --quiet - >/dev/null 2>&1 || finish 1 "STOP: the installed T1 copy does not match ${T1_SHA:0:12} - nothing was changed"
  say "  T1 copy matches ${T1_SHA:0:12}"
fi
sudo -v || finish 1 "STOP: sudo is not available - nothing was changed"
say "  sudo validated"

say "== 2. before"
snapshot before
B_SRC="$U_parkio_nr_log_source_service"; B_TRN="$U_parkio_nr_log_continuous_service"
B_ID="$(identity)"
if [ "$MODE" = check ]; then
  finish 0 "NR CHECK DONE (read-only)"
fi
if [ "$MODE" = pause ]; then
  check "source and transport active" "$(yn "$B_SRC $B_TRN" "active active")" "$B_SRC / $B_TRN"
  check "budget gate and collector running and healthy" "$(transport_ok && echo yes || echo no)"
  check "three sources attached" "$(yn "$ATTACHED" 3)" "$ATTACHED of 3"
  [ "$fails" -eq 0 ] || finish 1 "STOP: the NR pipeline is not intact before the pause - nothing was changed; owner decision (N3/N2)"
fi

say "== 3. t1-release-step.py nr-$MODE (running...)"
t1out="$(python3 -I "$T1" "nr-$MODE" </dev/null 2>&1)"; t1rc=$?
while IFS= read -r line; do say "$line"; done <<< "$t1out"
say "  t1 nr-$MODE exit=$t1rc"

say "== 4. after"
sleep "${NR_SETTLE_SECONDS:-5}"
snapshot after
check "T1 nr-$MODE exit 0" "$(yn "$t1rc" 0)"
check "source and transport active" "$(yn "$U_parkio_nr_log_source_service $U_parkio_nr_log_continuous_service" "active active")"
check "budget gate and collector running and healthy" "$(transport_ok && echo yes || echo no)"
check "the same transport containers, not restarted (ids and start times unchanged)" "$(yn "$(identity)" "$B_ID")"
check "three sources attached" "$(yn "$ATTACHED" 3)" "$ATTACHED of 3"
if [ "$MODE" = pause ]; then
  check "guard timer stopped" "$(yn "$U_parkio_nr_log_continuous_guard_timer" inactive)" "$U_parkio_nr_log_continuous_guard_timer"
  check "guard service not running" "$([ "$U_parkio_nr_log_continuous_guard_service" != active ] && echo yes || echo no)" \
    "$U_parkio_nr_log_continuous_guard_service"
else
  check "guard timer active" "$(yn "$U_parkio_nr_log_continuous_guard_timer" active)" "$U_parkio_nr_log_continuous_guard_timer"
fi
say "-- T1 status"
while IFS= read -r line; do say "$line"; done < <(python3 -I "$T1" status 2>&1 | python3 -I -c 'import json,sys; d=json.load(sys.stdin); print("  state " + str(d.get("status")) + "; " + ", ".join(k + "=" + str((v or {}).get("status")) for k, v in sorted((d.get("steps") or {}).items())))' 2>/dev/null)
if [ "$fails" -eq 0 ]; then
  finish 0 "NR $(echo "$MODE" | tr a-z A-Z) COMPLETE - $( [ "$MODE" = pause ] && echo 'only the guard timer is stopped; source, transport, budget gate and collector unchanged' || echo 'guard timer running again; transport unchanged')"
fi
finish 1 "STOP: NR $MODE not verified ($fails failed check(s)) - do not run the next step; send this output"
