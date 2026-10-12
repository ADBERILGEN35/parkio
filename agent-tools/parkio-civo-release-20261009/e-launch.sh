#!/usr/bin/env bash
# E (final) launcher (owner request 2026-10-12): runs `t1-step.sh final` once, independent of the SSH session, and records its
# PID, exit code, credential check and the release record. Run as civo on parkio-civo-prod, in the smoke-session shell:
#   bash ~/parkio-release-20261009/tools/e-launch.sh launch    pre-checks, start E, show its log live, then the verdict
#   bash ~/parkio-release-20261009/tools/e-launch.sh status    read-only: RUNNING / COMPLETE / FAILED with the evidence
#   bash ~/parkio-release-20261009/tools/e-launch.sh follow    read-only: the log live until E ends, then the verdict
#
# launch
#  1. Read-only pre-checks; any failure stops before anything starts or is written:
#     - installed T1 v6 and t1-step.sh v6 checksums; the five smoke variables set (names only);
#     - the release checkout clean at the PIN;
#     - T1 state D7 ok and final not ok (a failed E may be run again);
#     - the newest D7 launch COMPLETE (exit 0, T1 D7 ok, password count 0);
#     - the newest coverage check PASS for the six built images;
#     - the release record directory /var/lib/parkio/azure-hosted-beta either absent (created below, owner decision 5) or
#       owned by this user, mode 755 and writable;
#     - no other E launch (lock held, a launch without an exit record, or a running t1-step.sh final / T1 final);
#     - no backup process.
#  2. sudo -v. Then, only when absent: `sudo install -d -m 0755 -o civo -g civo /var/lib/parkio/azure-hosted-beta`
#     (decision 5: civo:civo 0755), verified.
#  3. The runner starts under nohup in its own process group, holds the launch lock, writes its PID and runs
#     `t1-step.sh final` unchanged: E0 every service's container, image and config hash unchanged, E1 smoke, E2 CSRF/refresh/logout, E3 U12 B/C, E4 Flyway, E5 the release record (D7
#     record, coverage and tag checks, then deployed-manifest.json). It then records the exit code and how often the smoke
#     password occurs in the E log, the E records, the smoke log and the release record. The value reaches grep through a
#     file descriptor; it is never in an argument, a file or the output.
#  4. The log is shown live; Ctrl-C ends only the view. Then the verdict.
# Files: ~/parkio-release-20261009/e-launch/<stamp>/{launched,runner.pid,started,e.log,result,exit} (dir 700, files 600).
# Verdict (all modes): 0 E COMPLETE - every condition holds:
#   t1-step exit 0, T1 final ok, state complete, the manifest equal to R/final/current.json with the remaining exposure
#   recorded, password count 0.
# 3 RUNNING. 1 otherwise. After E COMPLETE: unset PARKIO_REAL_USER_PASSWORD.
set -uo pipefail
umask 077
MODE="${1:-}"
R="${EL_OUT:-$HOME/parkio-release-20261009}"                        # overrides: self-test only
REL="${EL_REL:-/opt/parkio-release}"
PIN="${EL_PIN:-f888a81b113c0c486ea0949067779443857e644d}"
FINAL_DIR="${EL_FINAL_DIR:-/var/lib/parkio/azure-hosted-beta}"
T1_SHA="${EL_T1_SHA256:-180385f9cd40d671d1348d6211283fecf88b61225dd9c1f54db7749f315caf79}"
STEP_SHA="${EL_STEP_SHA256:-a3bf4f317a87251321211cfa39b1c9804dde86c78858bec31cf466d67d5a2539}"
BUILT="user-service gamification-service notification-service moderation-service ai-validation-service analytics-service"
TD="$R/tools"
L="$R/e-launch"
SELF="$(readlink -f "$0")"
SMOKE_VARS="PARKIO_REAL_USER_PASSWORD PARKIO_GATEWAY_URL PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED PARKIO_DEPLOYMENT_PROFILE PARKIO_ENV_FILE"
fails=0
pass() { echo "  PASS $*"; }
bad() { echo "  FAIL $*"; fails=$((fails + 1)); }
now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

t1_state() {  # "<D7> <final> <overall>" from T1's state.json ('-' when absent)
  python3 -I - "$R/state.json" <<'PY'
import json, sys
try:
    s = json.load(open(sys.argv[1], encoding="utf-8"))
except (OSError, ValueError):
    s = {}
st = s.get("steps") or {}
g = lambda k: (st.get(k) or {}).get("status") or "-"
print(g("D7"), g("final"), s.get("status") or "-")
PY
}

t1_final_failed() {
  python3 -I - "$R/state.json" <<'PY'
import json, sys
try:
    s = json.load(open(sys.argv[1], encoding="utf-8"))
except (OSError, ValueError):
    s = {}
print("; ".join(((s.get("steps") or {}).get("final") or {}).get("failed") or [])[:400])
PY
}

d7_launch() {  # "<stamp> <exit> <password count> <T1 D7>" of the newest D7 launch
  local d
  d="$(ls -1d "$R"/d7-launch/20*Z 2>/dev/null | tail -1)"
  [ -n "$d" ] || { echo "none - - -"; return; }
  echo "$(basename "$d") $(cat "$d/exit" 2>/dev/null || echo -) $(sed -n 's/^passwordOccurrences=//p' "$d/result" 2>/dev/null || echo -)" \
       "$(sed -n 's/^t1D7=//p' "$d/result" 2>/dev/null || echo -)"
}

coverage_ok() {  # "<ok|no> <stamp> <detail>": newest coverage summary PASS and covering the six images D7 built
  python3 -I - "$R" "$BUILT" <<'PY'
import glob, json, os, sys
r, built = sys.argv[1], sys.argv[2].split()
try:
    s = json.load(open(os.path.join(r, "state.json"), encoding="utf-8"))
    stamp = s["steps"]["D7"]["stamp"]
    images = json.load(open(os.path.join(r, "steps", f"D7-{stamp}.json"), encoding="utf-8")).get("builtImages") or {}
except (OSError, ValueError, KeyError):
    images = {}
dirs = sorted(glob.glob(os.path.join(r, "coverage-*", "summary.json")))
try:
    c = json.load(open(dirs[-1], encoding="utf-8")) if dirs else {}
except (OSError, ValueError):
    c = {}
cov = c.get("services") or {}
missing = [x for x in built if not images.get(x) or (cov.get(x) or {}).get("imageId") != images.get(x)]
ok = c.get("result") == "PASS" and not missing
print("ok" if ok else "no", c.get("stamp", "none"), f"result {c.get('result', '-')}" + (f"; not covered: {','.join(missing)}" if missing else ""))
PY
}

latest() { ls -1d "$L"/20*Z 2>/dev/null | tail -1; }

alive() {
  local pid
  pid="$(cat "$1/runner.pid" 2>/dev/null)" || return 1
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q "e-launch.sh run"
}

e_processes() {  # whole command lines only
  pgrep -f '^(nohup )?bash [^ ]*/t1-step\.sh final$|^python3 -I [^ ]*/t1-release-step\.py final$' 2>/dev/null | wc -l
}

record_ok() {  # the release record exists, equals R/final/current.json and records the remaining exposure
  [ -f "$FINAL_DIR/deployed-manifest.json" ] && cmp -s "$FINAL_DIR/deployed-manifest.json" "$R/final/current.json" \
    && python3 -I -c 'import json,sys; m=json.load(open(sys.argv[1])); sys.exit(0 if m.get("remainingExposure") and m.get("imageScan") else 1)' \
         "$FINAL_DIR/deployed-manifest.json"
}

verdict() {
  local D id rc n d7 fin overall
  D="$(latest)"
  if [ -z "$D" ]; then
    echo "NO E LAUNCH found in $L"
    return 1
  fi
  id="$(basename "$D")"
  read -r d7 fin overall <<< "$(t1_state)"
  echo "E launch $id | launched $(cat "$D/launched" 2>/dev/null || echo ?) | runner pid $(cat "$D/runner.pid" 2>/dev/null || echo -)" \
       "started $(cat "$D/started" 2>/dev/null || echo -)"
  echo "  log $D/e.log ($(stat -c %a "$D/e.log" 2>/dev/null || echo -))"
  echo "  T1 state: D7 $d7, final $fin, overall $overall"
  if [ -f "$D/exit" ]; then
    rc="$(cat "$D/exit")"
    n="$(sed -n 's/^passwordOccurrences=//p' "$D/result" 2>/dev/null)"
    echo "  finished $(sed -n 's/^finishedAt=//p' "$D/result" 2>/dev/null); t1-step final exit=$rc;" \
         "smoke password found in the E log, records, smoke log and release record: ${n:-?} time(s) (value never shown)"
    grep -E 'gates passed|T1 final:|deployed-manifest.json written' "$D/e.log" 2>/dev/null | tail -3 | sed 's/^/  /'
    if record_ok; then
      echo "  release record $FINAL_DIR/deployed-manifest.json = R/final/current.json (sha256 $(sha256sum "$FINAL_DIR/deployed-manifest.json" | cut -c1-16)), remaining exposure recorded"
    else
      echo "  release record missing, different from R/final/current.json, or without the remaining exposure"
    fi
    if [ "$rc" = 0 ] && [ "$n" = 0 ] && [ "$fin" = ok ] && [ "$overall" = complete ] && record_ok; then
      echo "E COMPLETE - the release record is written; now: unset PARKIO_REAL_USER_PASSWORD"
      return 0
    fi
    [ "$fin" = ok ] || echo "  T1 final failed gates: $(t1_final_failed)"
    echo "E FAILED - send this output; the smoke session stays for a re-run after the cause is fixed"
    return 1
  fi
  if alive "$D"; then
    echo "  last lines of the log:"
    tail -n 12 "$D/e.log" 2>/dev/null | sed 's/^/    /'
    echo "E RUNNING - follow with: bash $SELF follow (Ctrl-C ends only the view)"
    return 3
  fi
  tail -n 12 "$D/e.log" 2>/dev/null | sed 's/^/    /'
  echo "E RUNNER ENDED WITHOUT AN EXIT RECORD - send this output"
  return 1
}

follow() {
  local D pid
  D="$(latest)"
  if [ -n "$D" ] && alive "$D"; then
    pid="$(cat "$D/runner.pid")"
    trap 'echo ""; echo "(view ended by Ctrl-C; E continues in its own process group)"' INT
    tail --pid="$pid" -n 40 -f "$D/e.log"
    trap - INT
  elif [ -n "$D" ]; then
    tail -n 40 "$D/e.log" 2>/dev/null
  fi
  echo ""
  verdict
}

run() {
  local id="$1" D="$L/$1" rc n d7 fin overall
  [ -d "$D" ] && [ ! -e "$D/runner.pid" ] || exit 1
  { true >&8; } 2>/dev/null && flock -n 8 || { echo "runner: launch lock not held" > "$D/runner.err"; exit 1; }
  echo "$$" > "$D/runner.pid.tmp" && mv -f "$D/runner.pid.tmp" "$D/runner.pid"
  now > "$D/started"
  echo "e-launch runner $id | pid $$ | $(now) | t1-step.sh final follows" >> "$D/e.log"
  bash "$TD/t1-step.sh" final 8>&- >> "$D/e.log" 2>&1 < /dev/null
  rc=$?
  if [ -n "${PARKIO_REAL_USER_PASSWORD:-}" ]; then
    n="$(cat "$D/e.log" "$R"/steps/final-* "$R"/smoke-E-* "$R/final/current.json" "$FINAL_DIR/deployed-manifest.json" 2>/dev/null \
         | grep -c -F -f <(printf '%s\n' "$PARKIO_REAL_USER_PASSWORD"))"
  else
    n=unchecked
  fi
  read -r d7 fin overall <<< "$(t1_state)"
  printf 'exit=%s\npasswordOccurrences=%s\nt1Final=%s\noverall=%s\nfinishedAt=%s\n' "$rc" "$n" "$fin" "$overall" "$(now)" \
    > "$D/result.tmp" && mv -f "$D/result.tmp" "$D/result"
  echo "e-launch runner finished | $(now) | t1-step final exit=$rc | T1 final $fin | state $overall | password occurrences $n" >> "$D/e.log"
  echo "$rc" > "$D/exit.tmp" && mv -f "$D/exit.tmp" "$D/exit"
}

launch() {
  local d7 fin overall head dirty v missing="" x unfinished=0 dl_stamp dl_exit dl_n dl_d7 cov cov_stamp cov_detail D id runner i
  local create_dir=0 me grp
  me="$(id -un)"; grp="$(id -gn)"
  echo "E launcher | $(now) | release $REL @ ${PIN:0:12} | record $FINAL_DIR"
  echo "== 1. read-only pre-checks (nothing is started or written until all pass)"
  if echo "$T1_SHA  $TD/t1-release-step.py" | sha256sum -c --quiet - >/dev/null 2>&1; then pass "T1 v6 installed (${T1_SHA:0:12})"
  else bad "the installed T1 is not v6 ${T1_SHA:0:12}"; fi
  if echo "$STEP_SHA  $TD/t1-step.sh" | sha256sum -c --quiet - >/dev/null 2>&1; then pass "t1-step.sh v6 installed (${STEP_SHA:0:12})"
  else bad "the installed t1-step.sh is not v6 ${STEP_SHA:0:12}"; fi
  for v in $SMOKE_VARS; do [ -n "${!v:-}" ] || missing="$missing $v"; done
  if [ -z "$missing" ]; then pass "smoke session variables present (5; values not shown)"; else bad "smoke variables missing:$missing"; fi
  head="$(git --no-optional-locks -C "$REL" rev-parse HEAD 2>/dev/null)"
  dirty="$(git --no-optional-locks -C "$REL" status --porcelain 2>/dev/null | wc -l)"
  if [ "$head" = "$PIN" ] && [ "$dirty" = 0 ]; then pass "release checkout clean at the PIN"
  else bad "release checkout not clean at the PIN (HEAD ${head:0:12}, $dirty change(s))"; fi
  read -r d7 fin overall <<< "$(t1_state)"
  if [ "$d7" = ok ] && [ "$fin" != ok ]; then pass "T1 state: D7 ok, final $fin"
  else bad "T1 state: D7 $d7, final $fin - E needs D7 ok and runs once to success"; fi
  read -r dl_stamp dl_exit dl_n dl_d7 <<< "$(d7_launch)"
  if [ "$dl_exit" = 0 ] && [ "$dl_n" = 0 ] && [ "$dl_d7" = ok ]; then pass "D7 launch $dl_stamp COMPLETE (exit 0, T1 D7 ok, password count 0)"
  else bad "the newest D7 launch is not COMPLETE ($dl_stamp: exit $dl_exit, T1 D7 $dl_d7, password count $dl_n)"; fi
  read -r cov cov_stamp cov_detail <<< "$(coverage_ok)"
  if [ "$cov" = ok ]; then pass "coverage check $cov_stamp PASS for the six images D7 built"
  else bad "no passing coverage check for the six images D7 built (newest $cov_stamp: $cov_detail)"; fi
  if [ ! -e "$FINAL_DIR" ]; then
    create_dir=1; pass "release record directory $FINAL_DIR absent: created after sudo as $me:$grp 0755 (decision 5)"
  elif [ -d "$FINAL_DIR" ] && [ "$(stat -c '%U %a' "$FINAL_DIR")" = "$me 755" ] && [ -w "$FINAL_DIR" ]; then
    pass "release record directory $FINAL_DIR: $me, 755, writable"
  else
    bad "release record directory $FINAL_DIR exists as $(stat -c '%U:%G %a' "$FINAL_DIR" 2>/dev/null) - not changed; owner decision"
  fi
  if [ -e "$L/lock" ] && ! flock -n "$L/lock" true; then bad "another E runner holds the launch lock"; else pass "launch lock free"; fi
  for x in "$L"/20*Z; do [ -d "$x" ] && [ ! -f "$x/exit" ] && unfinished=$((unfinished + 1)); done
  if [ "$unfinished" = 0 ]; then pass "no earlier E launch without an exit record"; else bad "$unfinished earlier E launch(es) without an exit record - see: bash $SELF status"; fi
  if [ "$(e_processes)" = 0 ]; then pass "no t1-step.sh final / T1 final process running"; else bad "an E process is already running"; fi
  if [ -z "$(pgrep -f 'pg_dump|run-production-backup|backup-hosted-beta' 2>/dev/null)" ]; then pass "no backup or pg_dump process"
  else bad "a backup or pg_dump process is running"; fi
  if [ "$fails" -gt 0 ]; then
    echo "STOP: $fails pre-check(s) failed - E was not started, nothing was written"
    return 1
  fi
  echo "== 2. sudo$([ "$create_dir" = 1 ] && echo ' and the release record directory')"
  sudo -v || { echo "STOP: sudo is not available - E was not started"; return 1; }
  if [ "$create_dir" = 1 ]; then
    echo "  Run: sudo install -d -m 0755 -o $me -g $grp $FINAL_DIR"
    sudo install -d -m 0755 -o "$me" -g "$grp" "$FINAL_DIR" || { echo "STOP: cannot create $FINAL_DIR - E was not started"; return 1; }
    [ "$(stat -c '%U:%G %a' "$FINAL_DIR")" = "$me:$grp 755" ] && [ -w "$FINAL_DIR" ] \
      || { echo "STOP: $FINAL_DIR is not $me:$grp 755 and writable - E was not started"; return 1; }
    echo "  PASS $FINAL_DIR created: $me:$grp 755, writable"
  fi
  echo "== 3. start E independent of this session"
  mkdir -p "$L" && chmod 700 "$L" || { echo "STOP: cannot create $L - E was not started"; return 1; }
  exec 8>>"$L/lock"
  flock -n 8 || { echo "STOP: another E runner took the launch lock - E was not started"; return 1; }
  id="$(date -u +%Y%m%dT%H%M%SZ)"
  D="$L/$id"
  mkdir "$D" && now > "$D/launched" || { echo "STOP: cannot create $D - E was not started"; return 1; }
  set -m
  nohup bash "$SELF" run "$id" > /dev/null 2>&1 < /dev/null &
  runner=$!
  set +m
  exec 8>&-
  for i in $(seq 1 40); do [ -s "$D/runner.pid" ] && break; sleep 0.25; done
  if [ "$(cat "$D/runner.pid" 2>/dev/null)" != "$runner" ]; then
    echo "STOP: the runner did not start ($(cat "$D/runner.err" 2>/dev/null || echo 'no PID record')) - see: bash $SELF status"
    return 1
  fi
  echo "  runner pid $runner in its own process group under nohup; records in $D (dir 700, files 600)"
  echo "  Ctrl-C or a dropped connection ends only this view. Reconnect: bash $SELF status | follow"
  echo "== 4. the E log, live"
  trap 'echo ""; echo "(view ended by Ctrl-C; E continues in its own process group)"' INT
  tail --pid="$runner" -n +1 -f "$D/e.log"
  trap - INT
  echo ""
  verdict
}

case "$MODE" in
  launch) launch; exit $? ;;
  status) verdict; exit $? ;;
  follow) follow; exit $? ;;
  run) [ -n "${2:-}" ] || exit 2; run "$2"; exit $? ;;
  *) echo "usage: e-launch.sh launch | status | follow"; exit 2 ;;
esac
