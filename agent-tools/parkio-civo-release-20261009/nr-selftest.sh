#!/usr/bin/env bash
# Self-test of nr-step.sh with stub systemctl, docker and sudo (their state in files) and a stub T1 that stops or starts
# the guard timer like t1-release-step.py nr-pause / nr-resume. P1 pause -> exit 0, report 600 with the T1 and helper
# lines; P2 budget gate unhealthy before, P3 two sources attached before, P4 T1 checksum mismatch -> STOP and T1 never
# called; P5 the budget gate restarted during the step, P6 T1 exit 1 -> FAIL; P7 an old exited container in the project
# is ignored; C1 check -> read-only, T1 never called; R1 resume -> exit 0; R2 resume with T1 failing -> FAIL; usage -> 2.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/nr-step.sh"
W="$(mktemp -d)"; trap 'rm -rf "${W:?}"' EXIT
mkdir -p "$W/bin" "$W/out/tools" "$W/state"
cat > "$W/bin/systemctl" <<EOF
#!/usr/bin/env bash
[ "\$1" = is-active ] || exit 3
v="\$(awk -v u="\$2" '\$1 == u { print \$2 }' "$W/state/units")"; echo "\${v:-inactive}"; [ "\$v" = active ]
EOF
cat > "$W/bin/docker" <<EOF
#!/usr/bin/env bash
case "\$1" in
  ps) cut -d'|' -f1 "$W/state/containers" ;;
  inspect) shift; [ "\$1" = -f ] && shift 2
           for id in "\$@"; do awk -F'|' -v id="\$id" '\$1 == id { printf "%s %s %s %s %s %s\n", \$2, substr(\$1, 1, 12), \$3, \$4, \$5, \$6 }' "$W/state/containers"; done ;;
  *) exit 1 ;;
esac
EOF
cat > "$W/bin/sudo" <<EOF
#!/usr/bin/env bash
case "\$1" in
  -v) exit 0 ;;
  -n) [ "\$2" = env ] && { cat "$W/state/helper"; exit 0; } ;;
esac
exit 1
EOF
cat > "$W/out/tools/t1-release-step.py" <<'PY'
import json, os, sys
S = os.path.join(os.environ["NRST_W"], "state")
mode = sys.argv[1]
with open(os.path.join(S, "t1-called"), "a") as fh:
    fh.write(mode + "\n")
if mode == "status":
    print(json.dumps({"status": "in-progress", "steps": {"D2c": {"status": "ok"}, mode: {"status": "ok"}}}))
    sys.exit(0)
how = open(os.path.join(S, "t1-how")).read().strip() if os.path.exists(os.path.join(S, "t1-how")) else "ok"
units = dict(line.split() for line in open(os.path.join(S, "units")) if line.strip())
units["parkio-nr-log-continuous-guard.timer"] = "inactive" if mode == "nr-pause" else "active"
with open(os.path.join(S, "units"), "w") as fh:
    fh.write("".join(f"{u} {v}\n" for u, v in units.items()))
if how == "restart-gate":
    rows = [r.split("|") for r in open(os.path.join(S, "containers")).read().splitlines() if r]
    for r in rows:
        if r[1] == "nr-budget-gate":
            r[4] = "2026-10-12T09:30:00.000000000Z"
    open(os.path.join(S, "containers"), "w").write("".join("|".join(r) + "\n" for r in rows))
print(f"T1 {mode} | stamp 20261012T093000Z (stub)")
print("  PASS guard timer stopped: inactive" if mode == "nr-pause" else "  PASS guard run ends with continuous-guard PASS")
sys.exit(1 if how == "fail" else 0)
PY
chmod +x "$W/bin/systemctl" "$W/bin/docker" "$W/bin/sudo"
export PATH="$W/bin:$PATH" NRST_W="$W" NR_OUT="$W/out" NR_SETTLE_SECONDS=0
export NR_T1_SHA256="$(sha256sum "$W/out/tools/t1-release-step.py" | cut -d' ' -f1)"
units() { printf 'parkio-nr-log-source.service %s\nparkio-nr-log-continuous.service %s\nparkio-nr-log-continuous-guard.timer %s\nparkio-nr-log-continuous-guard.service %s\n' "$@" > "$W/state/units"; }
GATE='aaaaaaaaaaaa1111|nr-budget-gate|running|healthy|2026-10-10T08:00:00.000000000Z|0'
FLUENT='bbbbbbbbbbbb2222|fluent-bit-nr-pilot|running|healthy|2026-10-10T08:00:01.000000000Z|0'
conts() { printf '%s\n' "$@" > "$W/state/containers"; }
helper() { : > "$W/state/helper"; for s in gateway-service auth-service parking-service; do
  [ "$1" -gt 0 ] && echo "source-preflight PASS: service=$s live-helper=attached id=c0ffee000$1 spool=readable" >> "$W/state/helper"; set -- $(( $1 - 1 )); done; }
reset() { rm -f "$W/state/t1-called" "$W/state/t1-how"; units active active active inactive; conts "$GATE" "$FLUENT"; helper 3; }
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
check() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && ok "$1 exit $2" || no "$1 exit $(cat "$W/$1.rc"), want $2"; }
runit() { local name="$1"; shift; bash "$TOOL" "$@" > "$W/$name" 2>&1 && echo 0 > "$W/$name.rc" || echo $? > "$W/$name.rc"; }
t1_not_called() { [ ! -e "$W/state/t1-called" ] && ok "$1: T1 not called" || no "$1: T1 was called"; }

reset; runit p1.out pause; sed -e "s#$W#<W>#g" -e "s#$(hostname)#<host>#g" "$W/p1.out"; rc_is p1.out 0
check p1.out "unit parkio-nr-log-continuous-guard.timer: active"
check p1.out "container nr-budget-gate: id aaaaaaaaaaaa state running health healthy"
check p1.out "service=gateway-service live-helper=attached"
check p1.out "sources attached: 3 of 3"
check p1.out "T1 nr-pause | stamp 20261012T093000Z (stub)"
check p1.out "t1 nr-pause exit=0"
check p1.out "PASS guard timer stopped"
check p1.out "PASS guard service not running"
check p1.out "PASS budget gate and collector running and healthy"
check p1.out "PASS the same transport containers, not restarted (ids and start times unchanged)"
check p1.out "state in-progress; D2c=ok, status=ok"
check p1.out "NR PAUSE COMPLETE - only the guard timer is stopped; source, transport, budget gate and collector unchanged"
f="$(ls "$W"/out/steps/NR-pause-*.txt | head -1)"
[ "$(stat -c %a "$f")" = 600 ] && ok "report 600" || no "report mode"
grep -qF "T1 nr-pause | stamp" "$f" && grep -qF "live-helper=attached" "$f" && grep -qF "NR PAUSE COMPLETE" "$f" \
  && ok "report holds the T1 output, the helper lines and the verdict" || no "report incomplete"
grep -q '^parkio-nr-log-continuous-guard.timer inactive$' "$W/state/units" && ok "timer stopped by T1 only" || no "timer state"

reset; conts "${GATE/healthy/unhealthy}" "$FLUENT"; runit p2.out pause; rc_is p2.out 1
check p2.out "FAIL budget gate and collector running and healthy"; check p2.out "STOP: the NR pipeline is not intact before the pause - nothing was changed"; t1_not_called p2
reset; helper 2; runit p3.out pause; rc_is p3.out 1; check p3.out "FAIL three sources attached: 2 of 3"; t1_not_called p3
reset; NR_T1_SHA256=0000000000000000000000000000000000000000000000000000000000000000 runit p4.out pause; rc_is p4.out 1
check p4.out "STOP: the installed T1 copy does not match 000000000000 - nothing was changed"; t1_not_called p4
reset; echo restart-gate > "$W/state/t1-how"; runit p5.out pause; rc_is p5.out 1
check p5.out "FAIL the same transport containers, not restarted (ids and start times unchanged)"; check p5.out "STOP: NR pause not verified (1 failed check(s))"
reset; echo fail > "$W/state/t1-how"; runit p6.out pause; rc_is p6.out 1; check p6.out "FAIL T1 nr-pause exit 0"
reset; conts "$GATE" "$FLUENT" 'cccccccccccc3333|fluent-bit-nr-pilot|exited|none|2026-10-01T08:00:00.000000000Z|0'; runit p7.out pause; rc_is p7.out 0
check p7.out "container fluent-bit-nr-pilot: id cccccccccccc state exited"; check p7.out "NR PAUSE COMPLETE"
reset; runit c1.out check; rc_is c1.out 0; check c1.out "NR CHECK DONE (read-only)"; t1_not_called c1
reset; units active active inactive inactive; runit r1.out resume; rc_is r1.out 0
check r1.out "PASS guard timer active"; check r1.out "NR RESUME COMPLETE - guard timer running again; transport unchanged"
reset; units active active inactive inactive; echo fail > "$W/state/t1-how"; runit r2.out resume; rc_is r2.out 1; check r2.out "FAIL T1 nr-resume exit 0"
runit u1.out bogus; rc_is u1.out 2; check u1.out "usage: nr-step.sh pause|resume|check"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
