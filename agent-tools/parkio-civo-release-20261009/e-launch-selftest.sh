#!/usr/bin/env bash
# Self-test of e-launch.sh. A stub t1-step.sh stands in for T1 final: it prints lines for STUB_SECONDS, writes T1's final
# state, writes R/final/current.json and the release record, exits STUB_RC, prints the password only with STUB_LEAK, and
# writes a different copy with STUB_MISMATCH. The sudo stub runs its command as this user. Launches run in a real pty.
# E1 launch -> E COMPLETE (exit 0); the record directory created 755; records 600; the password in no file and no argv.
# E2 a second launch after E COMPLETE -> refused. E3 a failed E -> FAILED (1), then a re-run is allowed and completes.
# E4 no coverage check / a coverage check of another image -> STOP. E5 the D7 launch not COMPLETE -> STOP. E6 the record
# directory with another mode -> STOP, unchanged. E7 the record copies differ -> FAILED. E8 the password in the log ->
# FAILED. E9 the terminal disappears -> E keeps running, status RUNNING then COMPLETE. E10 status without a launch -> 1.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/e-launch.sh"
W="$(mktemp -d)"; trap 'pkill -f "$W/" 2>/dev/null; rm -rf "${W:?}"' EXIT
mkdir -p "$W/bin" "$W/rel"
printf '#!/bin/sh\n[ "$1" = -v ] && exit 0\nexec "$@"\n' > "$W/bin/sudo"; chmod +x "$W/bin/sudo"
git -C "$W/rel" init -q && git -C "$W/rel" -c user.name=t -c user.email=t@t commit -q --allow-empty -m pin
export EL_PIN="$(git -C "$W/rel" rev-parse HEAD)" EL_REL="$W/rel" PATH="$W/bin:$PATH"
export PARKIO_REAL_USER_PASSWORD="E-Secret-$(openssl rand -hex 8)" PARKIO_GATEWAY_URL=https://api.example \
  PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta PARKIO_ENV_FILE=/x
SIX="user-service gamification-service notification-service moderation-service ai-validation-service analytics-service"
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
has() { if grep -qF -- "$2" "$1"; then ok "$(basename "$1"): $2"; else no "$(basename "$1"): $2"; fi; }

mk() {
  R="$W/$1"; export EL_OUT="$R" EL_FINAL_DIR="$W/$1-var/azure-hosted-beta" STUB_ARGV="$W/$1/argv.txt"
  mkdir -p "$R/tools" "$R/steps" "$R/d7-launch/20261012T123453Z" "$R/coverage-20261012T130000Z"
  echo "# stand-in T1 v5" > "$R/tools/t1-release-step.py"
  cat > "$R/tools/t1-step.sh" <<'EOF'
#!/usr/bin/env bash
echo "T1 step wrapper | $1 | stub"
{ tr '\0' ' ' < "/proc/$PPID/cmdline"; echo; tr '\0' ' ' < "/proc/$$/cmdline"; echo; } > "$STUB_ARGV"
for i in $(seq 1 "${STUB_SECONDS:-2}"); do echo "stub line $i"; sleep 1; done
[ -n "${STUB_LEAK:-}" ] && echo "leak: $PARKIO_REAL_USER_PASSWORD"
st=$([ "${STUB_RC:-0}" = 0 ] && echo ok || echo fail)
python3 -I - "$EL_OUT" "$EL_FINAL_DIR" "$st" "${STUB_MISMATCH:-}" <<'PY'
import json, os, sys
out, final, st, mismatch = sys.argv[1:5]
s = json.load(open(os.path.join(out, "state.json")))
s["steps"]["final"] = {"status": st, "failed": [] if st == "ok" else ["stub gate"]}
if st == "ok":
    s["status"] = "complete"
    m = json.dumps({"gitSha": "x", "remainingExposure": {"buildStageBase": "jdk"}, "imageScan": {"coverage": {}}}) + "\n"
    os.makedirs(os.path.join(out, "final"), exist_ok=True)
    open(os.path.join(out, "final", "current.json"), "w").write(m + ("changed\n" if mismatch else ""))
    open(os.path.join(final, "deployed-manifest.json"), "w").write(m)
json.dump(s, open(os.path.join(out, "state.json"), "w"))
PY
echo "gates passed 30/30" > "$EL_OUT/steps/final-stub.txt"
echo "T1 final: $([ "$st" = ok ] && echo OK || echo FAILED)"
exit "${STUB_RC:-0}"
EOF
  export EL_T1_SHA256="$(sha256sum "$R/tools/t1-release-step.py" | cut -d' ' -f1)"
  export EL_STEP_SHA256="$(sha256sum "$R/tools/t1-step.sh" | cut -d' ' -f1)"
  echo '{"status": "in-progress", "steps": {"D7": {"status": "ok", "stamp": "20261012T123453Z"}}}' > "$R/state.json"
  python3 -I - "$R" "$SIX" <<'PY'
import json, os, sys
r, six = sys.argv[1], sys.argv[2].split()
ids = {s: "sha256:" + str(i) * 64 for i, s in enumerate(six, 1)}
json.dump({"result": "PASS", "builtImages": ids}, open(os.path.join(r, "steps", "D7-20261012T123453Z.json"), "w"))
json.dump({"stamp": "20261012T130000Z", "result": "PASS", "services": {s: {"imageId": i} for s, i in ids.items()}},
          open(os.path.join(r, "coverage-20261012T130000Z", "summary.json"), "w"))
PY
  printf '0\n' > "$R/d7-launch/20261012T123453Z/exit"
  printf 'exit=0\npasswordOccurrences=0\nt1D7=ok\n' > "$R/d7-launch/20261012T123453Z/result"
  unset STUB_SECONDS STUB_RC STUB_LEAK STUB_MISMATCH
}
pty() { (echo "$3; echo LAUNCH_EXIT=\$?"; sleep "$2"; echo exit) | script -qfec "bash --norc --noprofile -i" /dev/null 2>&1 | tr -d '\r' > "$1"; }
waitexit() { local i; for i in $(seq 1 60); do [ -f "$(ls -1d "$R"/e-launch/20*Z | tail -1)/exit" ] && return 0; sleep 0.5; done; return 1; }
secret_free() {
  if grep -r -l -F -- "$PARKIO_REAL_USER_PASSWORD" "$R" "$(dirname "$EL_FINAL_DIR")" >/dev/null 2>&1; then no "$1: the password is in a file"
  else ok "$1: the password is in no file (log, records, release record, argv capture)"; fi
}

# E1
mk E1; pty "$W/e1.out" 6 "bash $TOOL launch"
sed -e "s#$W#<W>#g" "$W/e1.out" | grep -v -E '^\[\?2004|^bash-|^exit$' | head -45
for s in "PASS T1 v5 installed" "PASS t1-step.sh v5 installed" "PASS smoke session variables present (5; values not shown)" \
         "PASS release checkout clean at the PIN" "PASS T1 state: D7 ok, final -" \
         "PASS D7 launch 20261012T123453Z COMPLETE (exit 0, T1 D7 ok, password count 0)" \
         "PASS coverage check 20261012T130000Z PASS for the six images D7 built" "absent: created after sudo" \
         "PASS launch lock free" "PASS no t1-step.sh final / T1 final process running" "created: " \
         "stub line 2" "e-launch runner finished" "= R/final/current.json" "E COMPLETE - the release record is written" "LAUNCH_EXIT=0"; do
  has "$W/e1.out" "$s"
done
[ "$(stat -c %a "$EL_FINAL_DIR")" = 755 ] && ok "E1 record directory created mode 755" || no "E1 record dir mode"
D="$(ls -1d "$R"/e-launch/20*Z | tail -1)"
[ "$(stat -c %a "$R/e-launch") $(stat -c %a "$D")" = "700 700" ] && [ "$(stat -c %a "$D"/* | sort -u | tr '\n' ' ')" = "600 " ] \
  && ok "E1 launch dir 700, files 600" || no "E1 modes"
grep -q '^exit=0$' "$D/result" && grep -q '^passwordOccurrences=0$' "$D/result" && grep -q '^overall=complete$' "$D/result" \
  && ok "E1 result recorded (exit 0, count 0, complete)" || no "E1 result"
grep -q 'e-launch.sh run' "$R/argv.txt" && ! grep -qF -- "$PARKIO_REAL_USER_PASSWORD" "$R/argv.txt" && ok "E1 argv carry no password" || no "E1 argv"
secret_free E1

# E2
bash "$TOOL" launch > "$W/e2.out" 2>&1; rc=$?; [ "$rc" = 1 ] && ok "E2 second launch after E COMPLETE refused" || no "E2 exit $rc"
has "$W/e2.out" "FAIL T1 state: D7 ok, final ok - E needs D7 ok and runs once to success"

# E3
mk E3; export STUB_RC=1; pty "$W/e3.out" 6 "bash $TOOL launch"
has "$W/e3.out" "T1 final failed gates: stub gate"; has "$W/e3.out" "E FAILED - send this output"; has "$W/e3.out" "LAUNCH_EXIT=1"
export STUB_RC=0; pty "$W/e3b.out" 6 "bash $TOOL launch"
has "$W/e3b.out" "PASS T1 state: D7 ok, final fail"; has "$W/e3b.out" "PASS release record directory"
has "$W/e3b.out" "E COMPLETE - the release record is written"; has "$W/e3b.out" "LAUNCH_EXIT=0"

# E4 / E5 / E6: pre-check failures stop before anything is written
for c in nocov othercov d7leak d7fail dirmode; do
  mk "E4-$c"
  case "$c" in
    nocov) rm -rf "$R"/coverage-*; want="no passing coverage check for the six images D7 built (newest none" ;;
    othercov) python3 -I -c 'import json,sys; p=sys.argv[1]; s=json.load(open(p)); s["services"]["notification-service"]["imageId"]="sha256:x"; json.dump(s,open(p,"w"))' "$R/coverage-20261012T130000Z/summary.json"; want="no passing coverage check for the six images D7 built (newest 20261012T130000Z: result PASS; not covered: notification-service)" ;;
    d7leak) printf 'exit=0\npasswordOccurrences=1\nt1D7=ok\n' > "$R/d7-launch/20261012T123453Z/result"; want="the newest D7 launch is not COMPLETE" ;;
    d7fail) printf '1\n' > "$R/d7-launch/20261012T123453Z/exit"; want="the newest D7 launch is not COMPLETE (20261012T123453Z: exit 1" ;;
    dirmode) mkdir -p "$EL_FINAL_DIR" && chmod 700 "$EL_FINAL_DIR"; want="release record directory $EL_FINAL_DIR exists as" ;;
  esac
  bash "$TOOL" launch > "$W/e4.out" 2>&1; rc=$?
  [ "$rc" = 1 ] && grep -qF -- "FAIL $want" "$W/e4.out" && grep -q 'STOP: 1 pre-check(s) failed' "$W/e4.out" && [ ! -e "$R/e-launch" ] \
    && { [ "$c" != dirmode ] || [ "$(stat -c %a "$EL_FINAL_DIR")" = 700 ]; } \
    && ok "E4 $c -> STOP before anything, nothing written" || { no "E4 $c (exit $rc)"; grep -E 'FAIL|STOP' "$W/e4.out" | head -3; }
done

# E7 / E8
mk E7; export STUB_MISMATCH=1; pty "$W/e7.out" 6 "bash $TOOL launch"
has "$W/e7.out" "release record missing, different from R/final/current.json"; has "$W/e7.out" "E FAILED"
mk E8; export STUB_LEAK=1; pty "$W/e8.out" 6 "bash $TOOL launch"
has "$W/e8.out" "smoke password found in the E log, records, smoke log and release record: 1 time(s) (value never shown)"
has "$W/e8.out" "E FAILED"

# E9: the terminal disappears
mk E9; export STUB_SECONDS=8
(echo "bash $TOOL launch"; sleep 20) | script -qfec "bash --norc --noprofile -i" /dev/null > /dev/null 2>&1 & sp=$!
sleep 3; kill -9 "$sp"; sleep 1
bash "$TOOL" status > "$W/e9r.out" 2>&1; rc=$?; [ "$rc" = 3 ] && ok "E9 status RUNNING after the terminal disappeared" || no "E9 status exit $rc"
bash "$TOOL" launch > "$W/e9d.out" 2>&1; rc=$?; [ "$rc" = 1 ] && ok "E9 launch while running refused" || no "E9 duplicate exit $rc"
has "$W/e9d.out" "FAIL another E runner holds the launch lock"; has "$W/e9d.out" "FAIL an E process is already running"
waitexit && ok "E9 E ran to the end" || no "E9 no exit record"
bash "$TOOL" status > "$W/e9s.out" 2>&1; rc=$?; [ "$rc" = 0 ] && ok "E9 status COMPLETE after reconnect" || no "E9 status exit $rc"
secret_free E9

# E10
mk E10; bash "$TOOL" status > "$W/e10.out" 2>&1; rc=$?; [ "$rc" = 1 ] && ok "E10 status without a launch exit 1" || no "E10 exit $rc"
has "$W/e10.out" "NO E LAUNCH found"
bash "$TOOL" bogus > /dev/null 2>&1; [ "$?" = 2 ] && ok "usage exit 2" || no "usage"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
