#!/usr/bin/env bash
# Self-test of d7-launch.sh. A stub t1-step.sh stands in for T1 (prints lines for STUB_SECONDS, writes T1's D7 state and a
# D7 record, exits STUB_RC, prints the password only when STUB_LEAK is set); sudo is a stub; launches run in a real pty
# (`script`) from an interactive bash, as on the host.
# L1 launch -> D7 COMPLETE, exit 0; PID/exit/result recorded; dir 700, files 600; the password in no file and no argv.
# L2 a second launch after D7 ran -> refused, nothing started. L3 Ctrl-C in the live view -> D7 keeps running, verdict
# RUNNING (3), later COMPLETE. L4 the terminal disappears -> D7 keeps running; a launch meanwhile is refused; status
# RUNNING then COMPLETE. L5 t1-step exit 1 -> FAILED (1). L6 the password in the log -> FAILED. L7 each pre-check failure
# -> STOP, nothing written. L8 status without a launch -> 1. L9 a runner gone without an exit record -> status 1, launch
# refused.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/d7-launch.sh"
W="$(mktemp -d)"; trap 'pkill -f "$W/" 2>/dev/null; rm -rf "${W:?}"' EXIT
mkdir -p "$W/bin" "$W/rel"
printf '#!/bin/sh\n[ "$1" = -v ] && exit 0\nexit 1\n' > "$W/bin/sudo"; chmod +x "$W/bin/sudo"
git -C "$W/rel" init -q && git -C "$W/rel" -c user.name=t -c user.email=t@t commit -q --allow-empty -m pin
export D7L_PIN="$(git -C "$W/rel" rev-parse HEAD)" D7L_REL="$W/rel" PATH="$W/bin:$PATH"
export PARKIO_REAL_USER_PASSWORD="Launch-Secret-$(openssl rand -hex 8)" PARKIO_GATEWAY_URL=https://api.example \
  PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta PARKIO_ENV_FILE=/x
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
has() { if grep -qF -- "$2" "$1"; then ok "$(basename "$1"): $2"; else no "$(basename "$1"): $2"; fi; }

mk() {  # a fresh host directory for one case
  R="$W/$1"; export D7L_OUT="$R" STUB_ARGV="$R/argv.txt"
  mkdir -p "$R/tools" "$R/backup" "$R/steps"
  echo "# stand-in T1" > "$R/tools/t1-release-step.py"
  cat > "$R/tools/t1-step.sh" <<'EOF'
#!/usr/bin/env bash
echo "T1 step wrapper | $1 | stub"
{ tr '\0' ' ' < "/proc/$PPID/cmdline"; echo; tr '\0' ' ' < "/proc/$$/cmdline"; echo; } > "$STUB_ARGV"
for i in $(seq 1 "${STUB_SECONDS:-2}"); do echo "stub line $i"; sleep 1; done
[ -n "${STUB_LEAK:-}" ] && echo "leak: $PARKIO_REAL_USER_PASSWORD"
st=$([ "${STUB_RC:-0}" = 0 ] && echo ok || echo fail)
python3 -I - "$D7L_OUT/state.json" "$st" <<'PY'
import json, sys
s = json.load(open(sys.argv[1]))
s["steps"]["D7"] = {"status": sys.argv[2], "failed": [] if sys.argv[2] == "ok" else ["stub gate"]}
json.dump(s, open(sys.argv[1], "w"))
PY
echo "gates passed 9/9" > "$D7L_OUT/steps/D7-stub.txt"
echo "T1 D7: $([ "$st" = ok ] && echo OK || echo FAILED)"
exit "${STUB_RC:-0}"
EOF
  export D7L_T1_SHA256="$(sha256sum "$R/tools/t1-release-step.py" | cut -d' ' -f1)"
  export D7L_STEP_SHA256="$(sha256sum "$R/tools/t1-step.sh" | cut -d' ' -f1)"
  echo '{"stamp": "20261012T120335Z", "exit": 0}' > "$R/scanprep-20261012T120335Z.json"
  local st; st="$(date -u -d '-40 minutes' +%Y-%m-%dT%H-%M-%SZ)"
  echo "{\"stamp\": \"$st\", \"result\": \"PASS\"}" > "$R/backup/P5b-receipt-$st.json"
  echo '{"status": "partial", "steps": {"nr-resume": {"status": "ok"}}}' > "$R/state.json"
  printf '[Login]\n#KillUserProcesses=no\n' > "$R/logind.conf"; export D7L_LOGIND="$R/logind.conf"
  unset STUB_SECONDS STUB_RC STUB_LEAK
}
pty() {  # pty <out> <seconds to keep stdin open> <command line>: the command typed into an interactive bash in a real pty
  (echo "$3; echo LAUNCH_EXIT=\$?"; sleep "$2"; echo exit) | script -qfec "bash --norc --noprofile -i" /dev/null 2>&1 \
    | tr -d '\r' > "$1"
}
waitexit() { local i; for i in $(seq 1 60); do [ -f "$(ls -1d "$R"/d7-launch/20*Z | tail -1)/exit" ] && return 0; sleep 0.5; done; return 1; }
secret_free() {
  if grep -r -l -F -- "$PARKIO_REAL_USER_PASSWORD" "$R" >/dev/null 2>&1; then no "$1: the password is in a file under the host dir"
  else ok "$1: the password is in no file (log, records, PID/exit/result, argv capture)"; fi
}

# L1
mk L1; pty "$W/l1.out" 6 "bash $TOOL launch"
sed -e "s#$W#<W>#g" "$W/l1.out" | grep -v -E '^\[\?2004|^bash-|^exit$' | head -60
has "$W/l1.out" "PASS T1 v4 installed"; has "$W/l1.out" "PASS t1-step.sh v4 installed"; has "$W/l1.out" "PASS scan preparation 20261012T120335Z exit 0"
has "$W/l1.out" "PASS smoke session variables present (5; values not shown)"; has "$W/l1.out" "min left"
has "$W/l1.out" "PASS release checkout clean at the PIN"; has "$W/l1.out" "PASS T1 state: nr-resume ok, no D7 or final entry"
has "$W/l1.out" "PASS launch lock free"; has "$W/l1.out" "PASS logind keeps user processes after logout"
has "$W/l1.out" "stub line 2"; has "$W/l1.out" "d7-launch runner finished"; has "$W/l1.out" "D7 COMPLETE - E may follow"
has "$W/l1.out" "LAUNCH_EXIT=0"
D="$(ls -1d "$R"/d7-launch/20*Z | tail -1)"
[ "$(stat -c %a "$R/d7-launch")" = 700 ] && [ "$(stat -c %a "$D")" = 700 ] && ok "L1 launch dirs mode 700" || no "L1 dir modes"
modes="$(stat -c %a "$D"/* | sort -u | tr '\n' ' ')"; [ "$modes" = "600 " ] && ok "L1 launch files mode 600" || no "L1 file modes: $modes"
[ "$(cat "$D/exit")" = 0 ] && grep -q '^passwordOccurrences=0$' "$D/result" && grep -q '^t1D7=ok$' "$D/result" \
  && ok "L1 exit 0, password count 0, T1 D7 ok recorded" || no "L1 records"
grep -q 'd7-launch.sh run' "$R/argv.txt" && ! grep -qF -- "$PARKIO_REAL_USER_PASSWORD" "$R/argv.txt" \
  && ok "L1 the runner and t1-step.sh argv carry no password" || no "L1 argv"
secret_free L1
bash "$TOOL" status > "$W/l1s.out" 2>&1; rc=$?; [ "$rc" = 0 ] && ok "L1 status after completion exit 0" || no "L1 status exit $rc"
has "$W/l1s.out" "D7 COMPLETE - E may follow"

# L2: a second launch after D7 ran
n0="$(ls -1d "$R"/d7-launch/20*Z | wc -l)"
bash "$TOOL" launch > "$W/l2.out" 2>&1; rc=$?
[ "$rc" = 1 ] && [ "$(ls -1d "$R"/d7-launch/20*Z | wc -l)" = "$n0" ] && ok "L2 second launch refused (exit 1), nothing started" || no "L2 exit $rc"
has "$W/l2.out" "FAIL T1 state: nr-resume ok, D7 ok, final - - a D7 that ran or is running is not launched again"
has "$W/l2.out" "STOP: 1 pre-check(s) failed - D7 was not started, nothing was written"

# L3: Ctrl-C in the live view
mk L3; export STUB_SECONDS=8
(echo "bash $TOOL launch; echo LAUNCH_EXIT=\$?"; sleep 3; printf '\003'; sleep 3; echo exit) \
  | script -qfec "bash --norc --noprofile -i" /dev/null 2>&1 | tr -d '\r' > "$W/l3.out"
has "$W/l3.out" "(view ended by Ctrl-C; D7 continues in its own process group)"
has "$W/l3.out" "D7 RUNNING - follow with"; has "$W/l3.out" "LAUNCH_EXIT=3"
waitexit && ok "L3 D7 ran to the end after Ctrl-C" || no "L3 no exit record"
bash "$TOOL" status > "$W/l3s.out" 2>&1; rc=$?; [ "$rc" = 0 ] && ok "L3 status COMPLETE (exit 0)" || no "L3 status exit $rc"
grep -q '^stub line 8$' "$(ls -1d "$R"/d7-launch/20*Z | tail -1)/d7.log" && ok "L3 the log has every line" || no "L3 log incomplete"
secret_free L3

# L4: the terminal disappears; a launch meanwhile is refused
mk L4; export STUB_SECONDS=10
(echo "bash $TOOL launch"; sleep 20) | script -qfec "bash --norc --noprofile -i" /dev/null > /dev/null 2>&1 & sp=$!
sleep 3; kill -9 "$sp"; sleep 1
D="$(ls -1d "$R"/d7-launch/20*Z | tail -1)"; pid="$(cat "$D/runner.pid" 2>/dev/null)"
kill -0 "$pid" 2>/dev/null && ok "L4 runner $pid alive after the terminal disappeared" || no "L4 runner gone"
bash "$TOOL" status > "$W/l4r.out" 2>&1; rc=$?; [ "$rc" = 3 ] && ok "L4 status RUNNING (exit 3)" || no "L4 status exit $rc"
bash "$TOOL" launch > "$W/l4d.out" 2>&1; rc=$?
[ "$rc" = 1 ] && ok "L4 launch while running refused" || no "L4 duplicate exit $rc"
has "$W/l4d.out" "FAIL another D7 runner holds the launch lock"; has "$W/l4d.out" "earlier launch(es) without an exit record"
has "$W/l4d.out" "FAIL a D7 process is already running"
waitexit && ok "L4 D7 ran to the end" || no "L4 no exit record"
bash "$TOOL" follow > "$W/l4f.out" 2>&1; rc=$?; [ "$rc" = 0 ] && ok "L4 follow after reconnect: COMPLETE (exit 0)" || no "L4 follow exit $rc"
has "$W/l4f.out" "stub line 10"; secret_free L4

# L5: t1-step fails
mk L5; export STUB_RC=1; pty "$W/l5.out" 6 "bash $TOOL launch"
has "$W/l5.out" "t1-step D7 exit=1"; has "$W/l5.out" "T1 D7 failed gates: stub gate"
has "$W/l5.out" "D7 FAILED - do not run E; send this output"; has "$W/l5.out" "LAUNCH_EXIT=1"

# L6: the password reaches the log -> FAILED even with exit 0
mk L6; export STUB_LEAK=1; pty "$W/l6.out" 6 "bash $TOOL launch"
has "$W/l6.out" "smoke password found in the D7 log, records and smoke log: 1 time(s) (value never shown)"
has "$W/l6.out" "D7 FAILED - do not run E"
[ "$(grep -cF -- "$PARKIO_REAL_USER_PASSWORD" "$W/l6.out")" = 1 ] && ok "L6 only the stub's own leak line shows it (the launcher never prints it)" || no "L6 launcher printed it"

# L7: pre-check failures stop before anything is written
for c in stale scanprep smoke t1sha logind dirty nrresume; do
  mk "L7-$c"
  case "$c" in
    stale) rm -f "$R"/backup/*; st="$(date -u -d '-118 minutes' +%Y-%m-%dT%H-%M-%SZ)"; echo "{\"stamp\": \"$st\", \"result\": \"PASS\"}" > "$R/backup/P5b-receipt-$st.json"; want="no verified backup with >= 3 min left" ;;
    scanprep) echo '{"stamp": "20261012T130000Z", "exit": 1}' > "$R/scanprep-20261012T130000Z.json"; want="no passing scan preparation (newest 20261012T130000Z exit 1)" ;;
    smoke) want="smoke variables missing: PARKIO_GATEWAY_URL" ;;
    t1sha) echo "# changed" >> "$R/tools/t1-release-step.py"; want="the installed T1 is not v4" ;;
    logind) printf '[Login]\nKillUserProcesses=yes\n' > "$R/logind.conf"; want="logind kills user processes at logout" ;;
    dirty) touch "$W/rel/untracked-$c"; want="release checkout not clean at the PIN" ;;
    nrresume) echo '{"steps": {"nr-resume": {"status": "fail"}}}' > "$R/state.json"; want="T1 state: nr-resume fail" ;;
  esac
  if [ "$c" = smoke ]; then env -u PARKIO_GATEWAY_URL bash "$TOOL" launch > "$W/l7.out" 2>&1; else bash "$TOOL" launch > "$W/l7.out" 2>&1; fi; rc=$?
  [ "$rc" = 1 ] && grep -qF -- "FAIL $want" "$W/l7.out" && grep -q 'STOP: 1 pre-check(s) failed' "$W/l7.out" && [ ! -e "$R/d7-launch" ] \
    && ok "L7 $c -> STOP before anything, nothing written" || { no "L7 $c (exit $rc)"; grep -E 'FAIL|STOP' "$W/l7.out" | head -3; }
  rm -f "$W/rel/untracked-$c"
done

# L8: status without a launch; L9: a runner gone without an exit record
mk L8; bash "$TOOL" status > "$W/l8.out" 2>&1; rc=$?; [ "$rc" = 1 ] && ok "L8 status without a launch exit 1" || no "L8 exit $rc"
has "$W/l8.out" "NO D7 LAUNCH found"
mk L9; mkdir -p "$R/d7-launch/20261012T000000Z"; echo 999999 > "$R/d7-launch/20261012T000000Z/runner.pid"
bash "$TOOL" status > "$W/l9.out" 2>&1; rc=$?; [ "$rc" = 1 ] && ok "L9 status exit 1" || no "L9 exit $rc"
has "$W/l9.out" "D7 RUNNER ENDED WITHOUT AN EXIT RECORD"
bash "$TOOL" launch > "$W/l9l.out" 2>&1; rc=$?; [ "$rc" = 1 ] && ok "L9 launch refused" || no "L9 launch exit $rc"
has "$W/l9l.out" "1 earlier launch(es) without an exit record"
bash "$TOOL" bogus > "$W/u.out" 2>&1; [ "$?" = 2 ] && ok "usage exit 2" || no "usage"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
