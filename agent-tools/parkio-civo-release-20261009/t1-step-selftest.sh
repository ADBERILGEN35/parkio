#!/usr/bin/env bash
# Self-test of t1-step.sh with a stub sudo and a stub T1. S1 D3 runs with every pre-flight passing; S2 checksum mismatch,
# S3 a missing smoke variable, S4 a receipt older than 2 hours, S5 a FAIL receipt, S9 sudo failing -> STOP with T1 never
# called; S6 D4 needs no smoke variables; S7 final skips the freshness check; S8 T1 failing -> its exit code is returned
# and the records/status are still shown; S10 NR resumed; S11 usage. The smoke password's value never appears.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/t1-step.sh"
W="$(mktemp -d)"; trap 'rm -rf "${W:?}"' EXIT
mkdir -p "$W/bin" "$W/out/tools" "$W/out/backup" "$W/out/steps"
printf '#!/bin/sh\n[ "$1" = -v ] && exit "$(cat "%s/sudo-rc" 2>/dev/null || echo 0)"\nexit 1\n' "$W" > "$W/bin/sudo"; chmod +x "$W/bin/sudo"
cat > "$W/out/tools/t1-release-step.py" <<'PY'
import json, os, sys
W = os.environ["T1ST_W"]
with open(os.path.join(W, "t1-called"), "a") as fh:
    fh.write(" ".join(sys.argv[1:]) + "\n")
if sys.argv[1:] == ["status"]:
    print(json.dumps({"pin": "f888a81b", "status": "in-progress", "steps": {"nr-pause": {"status": "ok"}}}))
    sys.exit(0)
print("T1 " + " ".join(sys.argv[1:]) + " | stub run; smoke session prepared (variables present, values not shown)")
rc = int(open(os.path.join(W, "t1-rc")).read()) if os.path.exists(os.path.join(W, "t1-rc")) else 0
open(os.path.join(W, "out", "steps", sys.argv[-1] + "-20261012T093000Z.json"), "w").write("{}")
sys.exit(rc)
PY
export PATH="$W/bin:$PATH" T1ST_W="$W" T1S_OUT="$W/out"
export T1S_T1_SHA256="$(sha256sum "$W/out/tools/t1-release-step.py" | cut -d' ' -f1)"
export PARKIO_REAL_USER_PASSWORD=SECRETVALUE-not-to-print PARKIO_GATEWAY_URL=https://api.parkio.dev PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 \
  PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta PARKIO_ENV_FILE=/opt/parkio/docker/.env.azure-hosted-beta
receipt() {  # minutes_ago result
  rm -f "$W"/out/backup/P5b-receipt-*.json
  local s; s="$(date -u -d "-$1 minutes" +%Y-%m-%dT%H-%M-%SZ)"
  printf '{"stamp": "%s", "result": "%s"}\n' "$s" "$2" > "$W/out/backup/P5b-receipt-$s.json"
}
state() {  # paused_minutes_ago resumed(0|1)
  local p; p="$(date -u -d "-$1 minutes" +%Y-%m-%dT%H:%M:%SZ)"
  if [ "$2" = 1 ]; then
    printf '{"nrPausedAt": "%s", "steps": {"nr-pause": {"status": "ok"}, "nr-resume": {"status": "ok", "at": "2026-10-12T11:00:00Z"}}}\n' "$p"
  else
    printf '{"nrPausedAt": "%s", "steps": {"nr-pause": {"status": "ok"}}}\n' "$p"
  fi > "$W/out/state.json"
}
reset() { rm -f "$W/t1-called" "$W/t1-rc" "$W/sudo-rc"; receipt 30 PASS; state 10 0; }
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
check() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && ok "$1 exit $2" || no "$1 exit $(cat "$W/$1.rc"), want $2"; }
runit() { local name="$1"; shift; env "$@" bash "$TOOL" "${STEPARG:-D3}" > "$W/$name" 2>&1 && echo 0 > "$W/$name.rc" || echo $? > "$W/$name.rc"; }
called() { if grep -qx -- "$2" "$W/t1-called" 2>/dev/null; then ok "$1: T1 called with '$2'"; else no "$1: T1 not called with '$2'"; fi; }
not_called() { [ ! -e "$W/t1-called" ] && ok "$1: T1 not called" || no "$1: T1 was called"; }
secret() { grep -qF SECRETVALUE "$W/$1" && no "$1: the password value appears" || ok "$1: no password value in the output"; }

reset; runit s1.out; sed -e "s#$W#<W>#g" -e "s#$(id -un)#<user>#g" "$W/s1.out"; rc_is s1.out 0
check s1.out "T1 copy matches"; check s1.out "sudo validated"; check s1.out "smoke session variables present (5; values not shown)"
check s1.out "PASS): 89 min left in the 2-hour window"; check s1.out "NR guard: timer paused since"; check s1.out "(10 min) - nr-resume is due after D6b, before D7"
check s1.out "T1 step D3 | stub run"; check s1.out "t1 step D3 exit=0"; check s1.out "D3-20261012T093000Z.json"; called s1 "step D3"; called s1 "status"; secret s1.out
reset; T1S_T1_SHA256=0000000000000000000000000000000000000000000000000000000000000000 runit s2.out; rc_is s2.out 1; check s2.out "STOP: the installed T1 copy does not match 000000000000 - T1 was not run, nothing was changed"; not_called s2
reset; runit s3.out -u PARKIO_GATEWAY_URL; rc_is s3.out 1; check s3.out "STOP: PARKIO_GATEWAY_URL is not set in this shell"; not_called s3
reset; receipt 150 PASS; runit s4.out; rc_is s4.out 1; check s4.out "STOP: no verified backup within 2 hours"; check s4.out "PASS, -3"; not_called s4
reset; receipt 10 FAIL; runit s5.out; rc_is s5.out 1; check s5.out "STOP: no verified backup within 2 hours"; not_called s5
reset; STEPARG=D4 runit s6.out -u PARKIO_REAL_USER_PASSWORD -u PARKIO_GATEWAY_URL; rc_is s6.out 0; called s6 "step D4"
reset; receipt 400 PASS; STEPARG=final runit s7.out; rc_is s7.out 0; called s7 "final"
reset; echo 1 > "$W/t1-rc"; runit s8.out; rc_is s8.out 1; check s8.out "t1 step D3 exit=1"; check s8.out "== 3. records and status"; called s8 "status"
reset; echo 1 > "$W/sudo-rc"; runit s9.out; rc_is s9.out 1; check s9.out "STOP: sudo is not available"; not_called s9
reset; state 120 1; runit s10.out; rc_is s10.out 0; check s10.out "NR guard: resumed (nr-resume ok at 2026-10-12T11:00:00Z)"
reset; STEPARG=D9 runit s11.out; rc_is s11.out 2; check s11.out "usage: t1-step.sh D3|D4|D5|D6a|D6b|D7|final"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
