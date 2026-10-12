#!/usr/bin/env bash
# Self-test of d7-coverage-check.py against real Docker, the real pinned Trivy 0.64.1 and its real databases (needs network).
# Images: "good" = the local candidate notification image with /usr/bin/pebble removed, flattened through
# docker export | import (a derived build's new layer is exported truncated by this workstation's Docker Desktop containerd
# store, "unexpected EOF"; host-built images scan normally, as D7 did); "nojava" = busybox; "pebble" = the candidate as built. The test ignore file is the PIN's plus CVE-2026-106451 (the candidate's
# lz4-java 1.10.1, fixed at the PIN), so the good image passes the policy. Containers labelled like the project run each
# image with `sleep`; a fake T1 state and D7 record name the built images.
# G1 good -> exit 0: identity, current DBs, T1's gate, >= 50 Java packages from app.jar/BOOT-INF/lib, spring-core/boot, OS
#    packages, no pebble, same policy; reduced report without the image config; dir 700, files 600.
# N1 nojava -> T1's gate passes (exit 0 alone proves nothing) but the coverage gates fail -> exit 1.
# N2 pebble -> T1's gate fails and the pebble target is reported -> exit 1. N3 a record naming another image -> exit 1
# before any scan. N4 wrong T1 checksum -> STOP.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/d7-coverage-check.py"
REPO="${D7CT_REPO:-$(git -C "$HERE" rev-parse --show-toplevel)}"
CAND="${D7CT_CANDIDATE:-parkio/notification-service:candidate-843ae7cb461b}"
P="d7cov$$"; W="$(mktemp -d)"
cleanup() {
  docker rm -f "$P-good" "$P-nojava" "$P-pebble" "$P-flat" >/dev/null 2>&1 || true
  docker image rm "$P/notification-service:test" "$P/analytics-service:test" "$P/moderation-service:test" >/dev/null 2>&1 || true
  rm -rf "${W:?}"
}
trap cleanup EXIT
docker image inspect "$CAND" >/dev/null 2>&1 || { echo "SELFTEST SETUP: $CAND not present"; exit 2; }
mkdir -p "$W/out/steps" "$W/rel" "$W/cache"
T1V4="$W/t1v4.py"   # T1 v4 exactly as installed on the host (commit 594af5e1)
git -C "$REPO" show 594af5e1e33309fbfb099a380cb9272973f501b0:agent-tools/parkio-civo-release-20261009/t1-release-step.py > "$T1V4"
[ "$(sha256sum "$T1V4" | cut -d' ' -f1)" = 01ed008c8ef2268381fcb238025c589b3b0e085f4e8366862a459372c536236e ] \
  || { echo "SELFTEST SETUP: T1 v4 copy does not match 01ed008c"; exit 2; }
git -C "$REPO" show f888a81b113c0c486ea0949067779443857e644d:.trivyignore.yaml > "$W/rel/.trivyignore.yaml"
printf '  - id: CVE-2026-106451\n    expired_at: 2099-01-01\n    statement: self-test only - the candidate predates the PIN lz4-java 1.11.4 fix\n' >> "$W/rel/.trivyignore.yaml"
docker run --name "$P-flat" --user root --network none --entrypoint rm "$CAND" -f /usr/bin/pebble \
  && docker export "$P-flat" | docker import - "$P/notification-service:test" >/dev/null \
  || { echo "SELFTEST SETUP: cannot build the good image"; exit 2; }
docker rm -f "$P-flat" >/dev/null
docker tag busybox:latest "$P/analytics-service:test"
docker tag "$CAND" "$P/moderation-service:test"
for x in good:notification-service nojava:analytics-service pebble:moderation-service; do
  docker run -d --name "$P-${x%%:*}" --label "com.docker.compose.project=$P" --label "com.docker.compose.service=${x#*:}" \
    --network none --entrypoint sleep "$P/${x#*:}:test" 1800 >/dev/null
done
iid() { docker image inspect --format '{{.Id}}' "$1"; }
python3 -I - "$W/out" "$(iid "$P/notification-service:test")" "$(iid "$P/analytics-service:test")" "$(iid "$P/moderation-service:test")" <<'PY'
import json, os, sys
out, good, nojava, pebble = sys.argv[1:5]
json.dump({"status": "in-progress", "steps": {"D7": {"status": "ok", "stamp": "20261012T123453Z"}}}, open(os.path.join(out, "state.json"), "w"))
json.dump({"result": "PASS", "builtImages": {"notification-service": good, "analytics-service": nojava, "moderation-service": pebble}},
          open(os.path.join(out, "steps", "D7-20261012T123453Z.json"), "w"))
PY
export D7C_T1="$T1V4" T1_OUT="$W/out" T1_RELEASE="$W/rel" T1_PROJECT="$P" \
  D7C_REF="$P/{svc}:test" D7C_CACHE="$W/cache" D7C_KEEP_CACHE=1
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
has() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
runit() { local name="$1"; shift; env "$@" python3 -I "$TOOL" > "$W/$name" 2>&1; echo $? > "$W/$name.rc"; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && ok "$1 exit $2" || no "$1 exit $(cat "$W/$1.rc"), want $2"; }

runit g1.out D7C_SERVICES=notification-service; sed -e "s#$W#<W>#g" -e "s#$P#<p>#g" "$W/g1.out"; rc_is g1.out 0
has g1.out "PASS T1 state D7 ok; its record PASS with the built image ids"
has g1.out "PASS notification-service: $P/notification-service:test = the image D7 built = the running container's image"
has g1.out "PASS D7's freshness gate: vulnerability DB present and updated within 48 h"
has g1.out "PASS notification-service: T1's D7 gate again (CRITICAL fixed; HIGH/CRITICAL fixed libraries): targets Java (0)"
has g1.out "PASS notification-service: full inventory report written"
has g1.out "JAR files inside app.jar/BOOT-INF/lib"; has g1.out "PASS notification-service: spring-core and spring-boot among them: spring-core 6.2.19"
has g1.out "PASS notification-service: OS packages scanned ($P/notification-service:test (ubuntu 26.04))"
has g1.out "PASS notification-service: no usr/bin/pebble target"
has g1.out "PASS notification-service: the policy recomputed from the inventory gives T1's result"
has g1.out "suppressed by the release's ignore file: CVE-2026-106451"
has g1.out "D. Meaning of \"targets Java (0)\""; has g1.out "D7 COVERAGE CHECK PASS"
grep -q -E "PASS notification-service: Java target scanned: [0-9]+ packages, [0-9]+ of them" "$W/g1.out" && ok "g1: Java package count reported" || no "g1 count"
D="$(ls -1d "$W"/out/coverage-* | tail -1)"
[ "$(stat -c %a "$D")" = 700 ] && [ "$(stat -c %a "$D"/* | sort -u | tr '\n' ' ')" = "600 " ] && ok "g1: coverage dir 700, files 600" || no "g1 modes"
python3 -I - "$D" <<'PY' && ok "g1: reduced report (no image config) and summary agree: >= 50 BOOT-INF/lib packages, result PASS" || no "g1 report"
import json, os, sys
d = sys.argv[1]
rep = json.load(open(os.path.join(d, "notification-service.json")))
s = json.load(open(os.path.join(d, "summary.json")))
assert "ImageConfig" not in rep["Metadata"] and "ImageConfig" not in json.dumps(rep), "image config kept"
java = [t for t in rep["Results"] if t["Type"] == "jar"][0]
n = sum("/BOOT-INF/lib/" in (p["FilePath"] or "") for p in java["Packages"])
assert n >= 50 and s["services"]["notification-service"]["javaFromBootInfLib"] == n and s["result"] == "PASS", (n, s["result"])
PY
[ -s "$W/cache/db/trivy.db" ] && ok "g1: the test's shared cache was kept (D7C_KEEP_CACHE)" || no "g1 cache"

runit n1.out D7C_SERVICES=analytics-service; rc_is n1.out 1
has n1.out "PASS analytics-service: T1's D7 gate again (CRITICAL fixed; HIGH/CRITICAL fixed libraries)"
has n1.out "FAIL analytics-service: Java target scanned: 0 packages, 0 of them from 0 JAR files"
has n1.out "FAIL analytics-service: spring-core and spring-boot among them"
has n1.out "FAIL analytics-service: OS packages scanned"
has n1.out "D7 COVERAGE CHECK NOT COMPLETE - do not run E"

runit n2.out D7C_SERVICES=moderation-service; rc_is n2.out 1
has n2.out "FAIL moderation-service: T1's D7 gate again"; has n2.out "findings: CVE-2026-78667 stdlib v1.26.7"
has n2.out "FAIL moderation-service: no usr/bin/pebble target"

python3 -I - "$W/out" <<'PY'
import json, os, sys
p = os.path.join(sys.argv[1], "steps", "D7-20261012T123453Z.json")
r = json.load(open(p)); r["builtImages"]["notification-service"] = "sha256:" + "0" * 64; json.dump(r, open(p, "w"))
PY
runit n3.out D7C_SERVICES=notification-service; rc_is n3.out 1
has n3.out "FAIL notification-service: $P/notification-service:test = the image D7 built = the running container's image"
grep -q "C. notification-service" "$W/n3.out" && no "n3 scanned after an identity failure" || ok "n3: no scan after an identity failure"

runit n4.out D7C_T1_SHA256="$(printf '0%.0s' $(seq 64))"; rc_is n4.out 1; has n4.out "STOP: the installed T1"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
