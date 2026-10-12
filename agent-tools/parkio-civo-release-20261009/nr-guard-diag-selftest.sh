#!/usr/bin/env bash
# Self-test of nr-guard-diag.sh with stub journalctl (T1's civo read and the sudo read told apart by their arguments),
# systemctl, docker and sudo. G1 systemd's unit messages follow the PASS line -> exit 0, cause "a line after the guard's PASS
# line"; G2 civo sees no entries -> exit 0, cause "permission or no entries"; G3 a FAIL line, G4 a failed unit result,
# G5 an unhealthy transport container, G6 two of three attached -> exit 1.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/nr-guard-diag.sh"
W="$(mktemp -d)"; trap 'rm -rf "${W:?}"' EXIT
mkdir -p "$W/bin" "$W/out/steps"
cat > "$W/bin/journalctl" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"-o cat"*) cat "$W/civo.out"; cat "$W/civo.err" >&2; exit 0 ;;
  *"-o short-iso"*) cat "$W/root.out"; exit 0 ;;
esac
exit 1
EOF
cat > "$W/bin/systemctl" <<EOF
#!/usr/bin/env bash
case "\$1 \$2" in
  "show parkio-nr-log-continuous-guard.service") cat "$W/show-unit" ;;
  "show parkio-nr-log-continuous-guard.timer") printf 'ActiveState=active\nLastTriggerUSec=Mon 2026-10-12 10:14:00 UTC\nNextElapseUSecRealtime=Mon 2026-10-12 10:15:00 UTC\n' ;;
  "is-active "*) echo active ;;
esac
EOF
cat > "$W/bin/docker" <<EOF
#!/usr/bin/env bash
case "\$1" in
  ps) echo aaaa; echo bbbb ;;
  inspect) cat "$W/transport" ;;
esac
EOF
cat > "$W/bin/sudo" <<EOF
#!/usr/bin/env bash
[ "\$1" = -v ] && exit 0
[ "\$1" = -n ] && shift
case "\$*" in *resolve_production_sources.sh*) cat "$W/helper" >&2; exit 0 ;; esac
exec "\$@"
EOF
chmod +x "$W/bin/"*
export PATH="$W/bin:$PATH" NGD_OUT="$W/out"
printf '{"steps": {"nr-resume": {"status": "fail", "at": "2026-10-12T10:09:53Z"}}}\n' > "$W/out/state.json"
printf 'T1 nr-resume | stamp 20261012T100953Z\n  PASS three sources attached: 3/3\n  PASS guard timer active\n  FAIL guard run ends with continuous-guard PASS: Finished Parkio continuous New Relic log transport invariant guard.\n  guard paused from 2026-10-12T09:21:56Z to 2026-10-12T10:09:55Z\n' > "$W/out/steps/nr-resume-20261012T100953Z.txt"
PASSLINE='continuous-guard PASS sources=current transport=running storage=within-bounds'
setup() {
  printf '2026-10-12T10:09:54+0000 civo-host systemd[1]: Starting parkio-nr-log-continuous-guard.service - Parkio continuous New Relic log transport invariant guard...\n2026-10-12T10:09:55+0000 civo-host bash[4242]: %s\n2026-10-12T10:09:55+0000 civo-host systemd[1]: parkio-nr-log-continuous-guard.service: Deactivated successfully.\n2026-10-12T10:09:55+0000 civo-host systemd[1]: Finished parkio-nr-log-continuous-guard.service - Parkio continuous New Relic log transport invariant guard.\n' "$PASSLINE" > "$W/root.out"
  printf '%s\nparkio-nr-log-continuous-guard.service: Deactivated successfully.\nFinished parkio-nr-log-continuous-guard.service - Parkio continuous New Relic log transport invariant guard.\n' "$PASSLINE" > "$W/civo.out"
  : > "$W/civo.err"
  printf 'ActiveState=inactive\nResult=success\nExecMainStatus=0\nExecMainCode=1\nExecMainStartTimestamp=Mon 2026-10-12 10:14:00 UTC\nExecMainExitTimestamp=Mon 2026-10-12 10:14:01 UTC\n' > "$W/show-unit"
  printf 'nr-budget-gate aaaaaaaaaaaa running healthy 2026-10-10T08:00:00Z 0\nfluent-bit-nr-pilot bbbbbbbbbbbb running healthy 2026-10-10T08:00:01Z 0\n' > "$W/transport"
  : > "$W/helper"; for s in gateway-service auth-service parking-service; do echo "source-preflight PASS: service=$s live-helper=attached id=0123456789abcdef spool=readable" >> "$W/helper"; done
}
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
check() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && ok "$1 exit $2" || no "$1 exit $(cat "$W/$1.rc"), want $2"; }
runit() { bash "$TOOL" > "$W/$1" 2>&1 && echo 0 > "$W/$1.rc" || echo $? > "$W/$1.rc"; }

setup; runit g1.out; sed -e "s#$W#<W>#g" -e "s#$(hostname)#<host>#g" -e "s#$(id -un)#<user>#g" "$W/g1.out"; rc_is g1.out 0
check g1.out "FAIL guard run ends with continuous-guard PASS: Finished Parkio continuous New Relic log transport invariant guard."
check g1.out "exit code 0; stdout 3 line(s); stderr 0 line(s)"
check g1.out "the last stdout line, which T1 compares: 'Finished parkio-nr-log-continuous-guard.service"
check g1.out "holds 'continuous-guard PASS': no"
check g1.out "runs started: 1; PASS lines: 1; FAIL lines: 0"
check g1.out "lines right after the first PASS line: systemd[1]: parkio-nr-log-continuous-guard.service: Deactivated successfully.|"
check g1.out "Result=success"
check g1.out "sources attached: 3 of 3"
check g1.out "GUARD RUNS VERIFIED"
check g1.out "T1's single-line check: T1's read ends with 'Finished parkio-nr-log-continuous-guard.service"
setup; printf -- '-- No entries --\n' > "$W/civo.out"; echo "Hint: You are currently not seeing messages from other users and the system." > "$W/civo.err"
runit g2.out; rc_is g2.out 0; check g2.out "permission or no entries"; check g2.out "stderr: Hint: You are currently not seeing messages"
setup; printf '2026-10-12T10:12:00+0000 civo-host bash[5]: continuous-guard FAIL reason=source_not_attached; stopping dedicated log transport\n' >> "$W/root.out"
runit g3.out; rc_is g3.out 1; check g3.out "FAIL 1 'continuous-guard FAIL' line(s) since the resume"; check g3.out "GUARD RUNS NOT VERIFIED"
setup; sed -i 's/^Result=success/Result=exit-code/; s/^ExecMainStatus=0/ExecMainStatus=1/' "$W/show-unit"
runit g4.out; rc_is g4.out 1; check g4.out "FAIL the last guard run did not succeed (Result=exit-code, ExecMainStatus=1)"
setup; sed -i '1s/healthy/unhealthy/' "$W/transport"; runit g5.out; rc_is g5.out 1; check g5.out "FAIL the two transport containers are not both running and healthy"
setup; sed -i '3d' "$W/helper"; runit g6.out; rc_is g6.out 1; check g6.out "FAIL 2 of 3 sources attached"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
