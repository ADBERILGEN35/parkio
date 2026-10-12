#!/usr/bin/env bash
# Self-test of nr-attach-diag.sh with stub sudo, docker, systemctl and journalctl, a stub check script that prints its PASS
# lines on stderr like resolve_production_sources.sh --check-live-helper, and helper state files in a temporary directory.
# S1 all three attached -> exit 0 and the stdout-only parser counts 0/0 while the merged one counts 3; S2 the gateway status
# file names the old container, S3 the check fails, S4 two running gateway containers, S5 the gateway cursor names the old
# container -> exit 1. No log content is ever read (the spool holds a marker that must not appear).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/nr-attach-diag.sh"
W="$(mktemp -d)"; trap 'rm -rf "${W:?}"' EXIT
mkdir -p "$W/bin" "$W/state" "$W/logs" "$W/docker"
G=1111111111111111111111111111111111111111111111111111111111111111
GOLD=9999999999999999999999999999999999999999999999999999999999999999
A=2222222222222222222222222222222222222222222222222222222222222222
P=3333333333333333333333333333333333333333333333333333333333333333
cat > "$W/bin/sudo" <<'EOF'
#!/usr/bin/env bash
[ "$1" = -v ] && exit 0
[ "$1" = -n ] && shift
exec "$@"
EOF
cat > "$W/bin/docker" <<EOF
#!/usr/bin/env bash
D="$W/docker"
case "\$*" in
  "ps -q --filter label=com.docker.compose.project=parkio --filter label=com.docker.compose.service="*) s="\${6#label=com.docker.compose.service=}"; cat "\$D/ps-\$s" 2>/dev/null ;;
  "ps -a -q --filter label=com.docker.compose.project=parkio-nr-log-continuous") echo aaaa; echo bbbb ;;
  "inspect -f {{.Id}} "*) echo "\$4" ;;
  "inspect -f {{.State.StartedAt}} "*) cat "\$D/started-\$4" ;;
  inspect*) echo "  container nr-budget-gate aaaa running healthy started 2026-10-10T08:00:00Z restarts 0"; echo "  container fluent-bit-nr-pilot bbbb running healthy started 2026-10-10T08:00:01Z restarts 0" ;;
  *) exit 1 ;;
esac
EOF
printf '#!/bin/sh\necho active\n' > "$W/bin/systemctl"
cat > "$W/bin/journalctl" <<'EOF'
#!/bin/sh
echo "2026-10-12T09:32:20+0000 parkio-civo-prod python3[4242]: source-helper ATTACHED service=gateway-service id=111111111111 driver=json-file since=2026-10-12T09:32:18Z"
echo "2026-10-12T09:32:21+0000 parkio-civo-prod python3[4242]: source-helper READ-ERROR something else"
EOF
cat > "$W/check.sh" <<EOF
#!/usr/bin/env bash
[ -e "$W/check-fail" ] && { echo "source-preflight FAIL: gateway-service live helper identity does not match the current container" >&2; exit 1; }
for s in gateway-service auth-service parking-service; do
  echo "source-preflight PASS: service=\$s live-helper=attached id=\$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["container_id"])' "$W/state/\$s.status.json") spool=readable" >&2
done
EOF
chmod +x "$W/bin/"* "$W/check.sh"
export PATH="$W/bin:$PATH" NRD_STATE_ROOT="$W/state" NRD_SOURCE_ROOT="$W/logs" NRD_CHECK_SCRIPT="$W/check.sh"
now_iso() { date -u -d "-$1 seconds" +%Y-%m-%dT%H:%M:%S.000000000Z; }
setup() {  # gateway status id, gateway cursor id, number of running gateway containers
  rm -f "$W/check-fail"
  printf '%s\n' "$G" > "$W/docker/ps-gateway-service"; [ "$3" -eq 2 ] && printf '%s\n' "$GOLD" >> "$W/docker/ps-gateway-service"
  printf '%s\n' "$A" > "$W/docker/ps-auth-service"; printf '%s\n' "$P" > "$W/docker/ps-parking-service"
  now_iso 300 > "$W/docker/started-$G"; now_iso 90000 > "$W/docker/started-$A"; now_iso 95000 > "$W/docker/started-$P"; now_iso 99000 > "$W/docker/started-$GOLD"
  for pair in "gateway-service:$1:$2" "auth-service:$A:$A" "parking-service:$P:$P"; do
    s="${pair%%:*}"; rest="${pair#*:}"; sid="${rest%%:*}"; cid="${rest#*:}"
    printf '{"service":"%s","status":"attached","container_id":"%s","updated_at":"%s"}\n' "$s" "$sid" "$(now_iso 290)" > "$W/state/$s.status.json"
    printf '{"container_id":"%s","timestamp":"%s"}\n' "$cid" "$(now_iso 5)" > "$W/state/$s.json"
    mkdir -p "$W/logs/$s"; echo "LOG-CONTENT-MARKER should never be printed" > "$W/logs/$s/source-json.log"
  done
}
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
check() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && ok "$1 exit $2" || no "$1 exit $(cat "$W/$1.rc"), want $2"; }
runit() { bash "$TOOL" > "$W/$1" 2>&1 && echo 0 > "$W/$1.rc" || echo $? > "$W/$1.rc"; }
nocontent() { grep -q LOG-CONTENT-MARKER "$W/$1" && no "$1: log content printed" || ok "$1: no log content printed"; }

setup "$G" "$G" 1; runit s1.out; sed -e "s#$W#<W>#g" -e "s#$(hostname)#<host>#g" "$W/s1.out"; rc_is s1.out 0
check s1.out "exit code 0; stdout 0 line(s), 0 with live-helper=attached; stderr 3 line(s), 3 with live-helper=attached"
check s1.out "T1 nr_attached() parses stdout only -> counts 0/0 attached; nr-step.sh (stdout and stderr merged) -> counts 3"
check s1.out "stderr: source-preflight PASS: service=gateway-service live-helper=attached id=111111111111 spool=readable"
check s1.out "gateway-service: container 111111111111 started"
check s1.out "helper status attached, container 111111111111"
check s1.out "-> ATTACHED to the current container"
check s1.out "independently attached: 3 of 3"
check s1.out "source-helper ATTACHED service=gateway-service id=111111111111 since=2026-10-12T09:32:18Z"
check s1.out "unit parkio-nr-log-source.service: active"
check s1.out "container nr-budget-gate aaaa running healthy"
check s1.out "NR ATTACHMENT VERIFIED - 3 of 3 sources attached"
grep -q "READ-ERROR" "$W/s1.out" && no "s1: a non-ATTACHED journal line printed" || ok "s1: only ATTACHED journal lines printed"
nocontent s1.out
setup "$GOLD" "$G" 1; runit s2.out; rc_is s2.out 1; check s2.out "FAIL gateway-service is not attached to its current container (status attached; status file names 999999999999"; check s2.out "NR ATTACHMENT NOT VERIFIED"; nocontent s2.out
setup "$G" "$G" 1; touch "$W/check-fail"; runit s3.out; rc_is s3.out 1; check s3.out "FAIL the check exited 1"; check s3.out "stderr: source-preflight FAIL: gateway-service live helper identity"
setup "$G" "$G" 2; runit s4.out; rc_is s4.out 1; check s4.out "FAIL gateway-service: 2 running container(s), expected exactly 1"
setup "$G" "$GOLD" 1; runit s5.out; rc_is s5.out 1; check s5.out "cursor names 999999999999"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
