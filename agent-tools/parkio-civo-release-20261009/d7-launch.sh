#!/usr/bin/env bash
# D7 launcher (owner request 2026-10-12): starts `t1-step.sh D7` once, independent of the SSH session, and records its PID,
# exit code and credential check. Run as civo on parkio-civo-prod, in the shell that holds the smoke session:
#   bash ~/parkio-release-20261009/tools/d7-launch.sh launch    pre-checks, start D7, show its log live, then the verdict
#   bash ~/parkio-release-20261009/tools/d7-launch.sh status    read-only: RUNNING / COMPLETE / FAILED with the evidence
#   bash ~/parkio-release-20261009/tools/d7-launch.sh follow    read-only: the log live until D7 ends, then the verdict
#
# launch
#  1. Read-only pre-checks; any failure stops before anything starts or is written:
#     installed T1 v4 and t1-step.sh v4 checksums; the newest scan preparation exit 0; the five smoke variables set (names
#     only); the newest P5b receipt PASS with at least 3 minutes left of its 2-hour window; the release checkout clean at
#     the PIN; T1 state nr-resume ok and no D7 or final entry; no other D7 launch (lock held, a launch without an exit
#     record, or a running t1-step.sh D7 / T1 step D7); free space on / >= 12 GiB; no backup process; logind does not kill
#     user processes at logout.
#  2. sudo -v here, so t1-step.sh's own sudo check does not prompt.
#  3. The runner starts under nohup in its own process group (Ctrl-C and a dropped SSH connection do not reach it) and keeps
#     the launch lock for its whole life. It writes its PID, runs `t1-step.sh D7` unchanged (T1 v4 checks every image -
#     revision, pebble absence, both Trivy gates, current databases - before any service starts), then records the exit
#     code and how often the smoke password occurs in the D7 log, the D7 records and the smoke log. The value reaches grep
#     through a file descriptor; it is never in an argument, a file or the output. The credentials stay in the process
#     environment inherited from this shell.
#  4. The log is shown live; Ctrl-C ends only the view. Then the verdict.
# Files: ~/parkio-release-20261009/d7-launch/<stamp>/{launched,runner.pid,started,d7.log,result,exit} (dir 700, files 600).
# Exit (all modes): 0 COMPLETE (t1-step exit 0, T1 D7 ok, password count 0); 3 RUNNING; 1 otherwise (not launched, failed,
# or ended without an exit record). E must not start unless the verdict is D7 COMPLETE.
set -uo pipefail
umask 077
MODE="${1:-}"
R="${D7L_OUT:-$HOME/parkio-release-20261009}"                       # overrides: self-test only
REL="${D7L_REL:-/opt/parkio-release}"
PIN="${D7L_PIN:-f888a81b113c0c486ea0949067779443857e644d}"
T1_SHA="${D7L_T1_SHA256:-01ed008c8ef2268381fcb238025c589b3b0e085f4e8366862a459372c536236e}"
STEP_SHA="${D7L_STEP_SHA256:-0e1395265062c4ef39056d176ac5bb9c803964647a1a32ec97e3764b2071dd7a}"
LOGIND="${D7L_LOGIND:-/etc/systemd/logind.conf}"
MIN_LEFT=3
TD="$R/tools"
L="$R/d7-launch"
SELF="$(readlink -f "$0")"
SMOKE_VARS="PARKIO_REAL_USER_PASSWORD PARKIO_GATEWAY_URL PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED PARKIO_DEPLOYMENT_PROFILE PARKIO_ENV_FILE"
fails=0
pass() { echo "  PASS $*"; }
bad() { echo "  FAIL $*"; fails=$((fails + 1)); }
now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

t1_state() {  # "<nr-resume> <D7> <final> <overall>" from T1's state.json ('-' when absent)
  python3 -I - "$R/state.json" <<'PY'
import json, sys
try:
    s = json.load(open(sys.argv[1], encoding="utf-8"))
except (OSError, ValueError):
    s = {}
st = s.get("steps") or {}
g = lambda k: (st.get(k) or {}).get("status") or "-"
print(g("nr-resume"), g("D7"), g("final"), s.get("status") or "-")
PY
}

t1_d7_failed() {  # T1's failed gate names for D7, one line
  python3 -I - "$R/state.json" <<'PY'
import json, sys
try:
    s = json.load(open(sys.argv[1], encoding="utf-8"))
except (OSError, ValueError):
    s = {}
print("; ".join(((s.get("steps") or {}).get("D7") or {}).get("failed") or [])[:400])
PY
}

backup_window() {  # "<ok|stale> <stamp> <result> <deadline> <minutes left>"
  python3 -I - "$R/backup" "$MIN_LEFT" <<'PY'
import glob, json, os, sys
from datetime import datetime, timedelta, timezone
files = sorted(glob.glob(os.path.join(sys.argv[1], "P5b-receipt-*.json")))
try:
    r = json.load(open(files[-1], encoding="utf-8")) if files else {}
except (OSError, ValueError):
    r = {}
try:
    t = datetime.strptime(r.get("stamp", ""), "%Y-%m-%dT%H-%M-%SZ").replace(tzinfo=timezone.utc)
except ValueError:
    print("stale none - - -1")
    sys.exit()
end = t + timedelta(hours=2)
left = int((end - datetime.now(timezone.utc)).total_seconds() // 60)
ok = r.get("result") == "PASS" and left >= int(sys.argv[2])
print("ok" if ok else "stale", r.get("stamp"), r.get("result", "-"), end.strftime("%Y-%m-%dT%H:%M:%SZ"), left)
PY
}

scanprep() {  # "<stamp> <exit>" of the newest scan preparation record
  python3 -I - "$R" <<'PY'
import glob, json, os, sys
files = sorted(glob.glob(os.path.join(sys.argv[1], "scanprep-*.json")))
try:
    r = json.load(open(files[-1], encoding="utf-8")) if files else {}
except (OSError, ValueError):
    r = {}
print(r.get("stamp", "none"), r.get("exit", "-"))
PY
}

latest() { ls -1d "$L"/20*Z 2>/dev/null | tail -1; }

alive() {  # the launch directory's runner is still running
  local pid
  pid="$(cat "$1/runner.pid" 2>/dev/null)" || return 1
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q "d7-launch.sh run"
}

d7_processes() {  # whole command lines only, so a shell whose text merely mentions D7 is not counted
  pgrep -f '^(nohup )?bash [^ ]*/t1-step\.sh D7$|^python3 -I [^ ]*/t1-release-step\.py step D7$' 2>/dev/null | wc -l
}

verdict() {  # prints the status of the newest launch; returns 0 COMPLETE, 3 RUNNING, 1 otherwise
  local D id rc n d7 nr fin overall
  D="$(latest)"
  if [ -z "$D" ]; then
    echo "NO D7 LAUNCH found in $L"
    return 1
  fi
  id="$(basename "$D")"
  read -r nr d7 fin overall <<< "$(t1_state)"
  echo "D7 launch $id | launched $(cat "$D/launched" 2>/dev/null || echo ?) | runner pid $(cat "$D/runner.pid" 2>/dev/null || echo -)" \
       "started $(cat "$D/started" 2>/dev/null || echo -)"
  echo "  log $D/d7.log ($(stat -c %a "$D/d7.log" 2>/dev/null || echo -))"
  echo "  T1 state: D7 $d7, final $fin, overall $overall"
  if [ -f "$D/exit" ]; then
    rc="$(cat "$D/exit")"
    n="$(sed -n 's/^passwordOccurrences=//p' "$D/result" 2>/dev/null)"
    echo "  finished $(sed -n 's/^finishedAt=//p' "$D/result" 2>/dev/null); t1-step D7 exit=$rc;" \
         "smoke password found in the D7 log, records and smoke log: ${n:-?} time(s) (value never shown)"
    grep -E 'gates passed|T1 D7:|D7 stops before any service starts' "$D/d7.log" 2>/dev/null | tail -3 | sed 's/^/  /'
    if [ "$rc" = 0 ] && [ "$n" = 0 ] && [ "$d7" = ok ]; then
      echo "D7 COMPLETE - E may follow"
      return 0
    fi
    [ "$d7" = ok ] || echo "  T1 D7 failed gates: $(t1_d7_failed)"
    echo "D7 FAILED - do not run E; send this output"
    return 1
  fi
  if alive "$D"; then
    echo "  last lines of the log:"
    tail -n 12 "$D/d7.log" 2>/dev/null | sed 's/^/    /'
    echo "D7 RUNNING - follow with: bash $SELF follow (Ctrl-C ends only the view); do not run E"
    return 3
  fi
  tail -n 12 "$D/d7.log" 2>/dev/null | sed 's/^/    /'
  echo "D7 RUNNER ENDED WITHOUT AN EXIT RECORD - do not run E; send this output"
  return 1
}

follow() {
  local D pid
  D="$(latest)"
  if [ -n "$D" ] && alive "$D"; then
    pid="$(cat "$D/runner.pid")"
    trap 'echo ""; echo "(view ended by Ctrl-C; D7 continues in its own process group)"' INT
    tail --pid="$pid" -n "${1:-40}" -f "$D/d7.log"
    trap - INT
  elif [ -n "$D" ]; then
    tail -n 40 "$D/d7.log" 2>/dev/null
  fi
  echo ""
  verdict
}

run() {  # the detached runner: started by launch only, with the launch lock on fd 8
  local id="$1" D="$L/$1" rc n nr d7 fin overall
  [ -d "$D" ] && [ ! -e "$D/runner.pid" ] || exit 1
  { true >&8; } 2>/dev/null && flock -n 8 || { echo "runner: launch lock not held" > "$D/runner.err"; exit 1; }
  echo "$$" > "$D/runner.pid.tmp" && mv -f "$D/runner.pid.tmp" "$D/runner.pid"
  now > "$D/started"
  echo "d7-launch runner $id | pid $$ | $(now) | t1-step.sh D7 follows" >> "$D/d7.log"
  bash "$TD/t1-step.sh" D7 8>&- >> "$D/d7.log" 2>&1 < /dev/null
  rc=$?
  if [ -n "${PARKIO_REAL_USER_PASSWORD:-}" ]; then
    n="$(cat "$D/d7.log" "$R"/steps/D7-* "$R"/smoke-D7-* 2>/dev/null | grep -c -F -f <(printf '%s\n' "$PARKIO_REAL_USER_PASSWORD"))"
  else
    n=unchecked
  fi
  read -r nr d7 fin overall <<< "$(t1_state)"
  printf 'exit=%s\npasswordOccurrences=%s\nt1D7=%s\nfinishedAt=%s\n' "$rc" "$n" "$d7" "$(now)" > "$D/result.tmp" \
    && mv -f "$D/result.tmp" "$D/result"
  echo "d7-launch runner finished | $(now) | t1-step D7 exit=$rc | T1 D7 $d7 | password occurrences $n" >> "$D/d7.log"
  echo "$rc" > "$D/exit.tmp" && mv -f "$D/exit.tmp" "$D/exit"
}

launch() {
  local st stamp result deadline left sp_stamp sp_exit nr d7 fin overall head dirty free kill_conf D id runner
  echo "D7 launcher | $(now) | release $REL @ ${PIN:0:12}"
  echo "== 1. read-only pre-checks (nothing is started or written until all pass)"
  if echo "$T1_SHA  $TD/t1-release-step.py" | sha256sum -c --quiet - >/dev/null 2>&1; then pass "T1 v4 installed (${T1_SHA:0:12})"
  else bad "the installed T1 is not v4 ${T1_SHA:0:12}"; fi
  if echo "$STEP_SHA  $TD/t1-step.sh" | sha256sum -c --quiet - >/dev/null 2>&1; then pass "t1-step.sh v4 installed (${STEP_SHA:0:12})"
  else bad "the installed t1-step.sh is not v4 ${STEP_SHA:0:12}"; fi
  read -r sp_stamp sp_exit <<< "$(scanprep)"
  if [ "$sp_exit" = 0 ]; then pass "scan preparation $sp_stamp exit 0"; else bad "no passing scan preparation (newest $sp_stamp exit $sp_exit)"; fi
  local missing="" v
  for v in $SMOKE_VARS; do [ -n "${!v:-}" ] || missing="$missing $v"; done
  if [ -z "$missing" ]; then pass "smoke session variables present (5; values not shown)"; else bad "smoke variables missing:$missing"; fi
  read -r st stamp result deadline left <<< "$(backup_window)"
  if [ "$st" = ok ]; then pass "verified backup $stamp ($result): D7 must start before $deadline - $left min left"
  else bad "no verified backup with >= $MIN_LEFT min left (newest $stamp $result, deadline $deadline, $left min left) - take a fresh P5a/P5b"; fi
  head="$(git --no-optional-locks -C "$REL" rev-parse HEAD 2>/dev/null)"
  dirty="$(git --no-optional-locks -C "$REL" status --porcelain 2>/dev/null | wc -l)"
  if [ "$head" = "$PIN" ] && [ "$dirty" = 0 ]; then pass "release checkout clean at the PIN"
  else bad "release checkout not clean at the PIN (HEAD ${head:0:12}, $dirty change(s))"; fi
  read -r nr d7 fin overall <<< "$(t1_state)"
  if [ "$nr" = ok ] && [ "$d7" = - ] && [ "$fin" = - ]; then pass "T1 state: nr-resume ok, no D7 or final entry"
  else bad "T1 state: nr-resume $nr, D7 $d7, final $fin - a D7 that ran or is running is not launched again"; fi
  if [ -e "$L/lock" ] && ! flock -n "$L/lock" true; then bad "another D7 runner holds the launch lock"; else pass "launch lock free"; fi
  local unfinished=0 x
  for x in "$L"/20*Z; do [ -d "$x" ] && [ ! -f "$x/exit" ] && unfinished=$((unfinished + 1)); done
  if [ "$unfinished" = 0 ]; then pass "no earlier launch without an exit record"; else bad "$unfinished earlier launch(es) without an exit record - see: bash $SELF status"; fi
  if [ "$(d7_processes)" = 0 ]; then pass "no t1-step.sh D7 / T1 step D7 process running"; else bad "a D7 process is already running"; fi
  free="$(df -Pk / | awk 'NR == 2 { print $4 }')"
  if [ "${free:-0}" -ge $((12 * 1024 * 1024)) ]; then pass "free space on / $((free / 1024 / 1024)) GiB (>= 12)"; else bad "free space on / below 12 GiB"; fi
  if [ -z "$(pgrep -f 'pg_dump|run-production-backup|backup-hosted-beta' 2>/dev/null)" ]; then pass "no backup or pg_dump process"
  else bad "a backup or pg_dump process is running"; fi
  kill_conf="$(grep -h -E '^[[:space:]]*KillUserProcesses[[:space:]]*=' "$LOGIND" "${LOGIND}.d"/*.conf 2>/dev/null | tail -1 | tr -d ' ')"
  case "${kill_conf#*=}" in
    yes|true|1|on) bad "logind kills user processes at logout ($kill_conf): D7 would not survive a dropped session" ;;
    *) pass "logind keeps user processes after logout (${kill_conf:-KillUserProcesses not set, default no})" ;;
  esac
  if [ "$fails" -gt 0 ]; then
    echo "STOP: $fails pre-check(s) failed - D7 was not started, nothing was written"
    return 1
  fi
  echo "== 2. sudo (t1-step.sh validates it again without a prompt)"
  sudo -v || { echo "STOP: sudo is not available - D7 was not started"; return 1; }
  read -r st stamp result deadline left <<< "$(backup_window)"
  [ "$st" = ok ] || { echo "STOP: the backup window closed while waiting for sudo ($deadline) - take a fresh P5a/P5b"; return 1; }
  echo "== 3. start D7 independent of this session"
  mkdir -p "$L" && chmod 700 "$L" || { echo "STOP: cannot create $L - D7 was not started"; return 1; }
  exec 8>>"$L/lock"
  flock -n 8 || { echo "STOP: another D7 runner took the launch lock - D7 was not started"; return 1; }
  id="$(date -u +%Y%m%dT%H%M%SZ)"
  D="$L/$id"
  mkdir "$D" && now > "$D/launched" || { echo "STOP: cannot create $D - D7 was not started"; return 1; }
  set -m
  nohup bash "$SELF" run "$id" > /dev/null 2>&1 < /dev/null &
  runner=$!
  set +m
  exec 8>&-
  local i
  for i in $(seq 1 40); do [ -s "$D/runner.pid" ] && break; sleep 0.25; done
  if [ "$(cat "$D/runner.pid" 2>/dev/null)" != "$runner" ]; then
    echo "STOP: the runner did not start ($(cat "$D/runner.err" 2>/dev/null || echo 'no PID record')) - see: bash $SELF status"
    return 1
  fi
  echo "  runner pid $runner in its own process group under nohup; records in $D (dir 700, files 600)"
  echo "  Ctrl-C or a dropped connection ends only this view. Reconnect: bash $SELF status | follow"
  echo "== 4. the D7 log, live"
  trap 'echo ""; echo "(view ended by Ctrl-C; D7 continues in its own process group)"' INT
  tail --pid="$runner" -n +1 -f "$D/d7.log"
  trap - INT
  echo ""
  verdict
}

case "$MODE" in
  launch) launch; exit $? ;;
  status) verdict; exit $? ;;
  follow) follow 40; exit $? ;;
  run) [ -n "${2:-}" ] || exit 2; run "$2"; exit $? ;;
  *) echo "usage: d7-launch.sh launch | status | follow"; exit 2 ;;
esac
