#!/usr/bin/env bash
# Local self-test of s2-candidate-pebble-check.py on real docker-save archives shaped like candidate artifacts: two images
# FROM the official eclipse-temurin:21-jre digest CI builds from, one removing /usr/bin/pebble in an upper layer (as PR #332
# does) and one not. S1 removed -> base layer adds pebble (go1.26.7), an upper layer deletes it, final absent, PASS;
# S2 not removed -> final present, FAIL; S3 a tampered artifact file -> SHA256SUMS mismatch, FAIL; S4 a failed library gate
# in trivy-summary.json -> FAIL even though the binary is absent.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/s2-candidate-pebble-check.py"
BASE="eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5"
P=s2probe; W="$(mktemp -d)"
cleanup() {
  docker images --format '{{.Repository}}:{{.Tag}}' | awk -v p="$P/" 'index($1, p) == 1' | xargs -r docker image rm -f >/dev/null 2>&1 || true
  rm -rf "$W"
}
trap cleanup EXIT
docker image inspect "$BASE" >/dev/null 2>&1 || { echo "base $BASE is not present locally (this test does not pull)"; exit 2; }
artifact() {  # name image summary-library
  local d="$W/$1"; mkdir -p "$d"
  docker image inspect "$2" > "$d/inspect.json"
  docker save "$2" | gzip -1 > "$d/image.tar.gz"
  printf '{"scanner": "aquasec/trivy:0.64.1", "fixed_critical": 0, "fixed_high": 0, "critical_gate": "pass", "library_gate": "%s", "gate": "%s"}\n' "$3" "$3" > "$d/trivy-summary.json"
  printf '%s\n' '{"Metadata": {"ImageConfig": {"history": [{"created_by": "RUN /bin/sh -c rm -f /usr/bin/pebble # buildkit"}]}}, "Results": [{"Target": "img (ubuntu 26.04)", "Class": "os-pkgs"}, {"Target": "Java", "Class": "lang-pkgs"}]}' > "$d/trivy.json"
  : > "$d/trivy-library.txt"; : > "$d/trivy-critical.txt"; : > "$d/trivy.txt"
  (cd "$d" && sha256sum inspect.json trivy.json trivy.txt trivy-critical.txt trivy-library.txt trivy-summary.json image.tar.gz > SHA256SUMS)
}
printf 'FROM %s\nRUN rm -f /usr/bin/pebble\nRUN echo app > /app.txt\n' "$BASE" | docker build -q --provenance=false --sbom=false -t "$P/removed:1" - >/dev/null
printf 'FROM %s\nRUN echo app > /app.txt\n' "$BASE" | docker build -q --provenance=false --sbom=false -t "$P/kept:1" - >/dev/null
artifact removed "$P/removed:1" pass
artifact kept "$P/kept:1" pass
cp -r "$W/removed" "$W/tampered"; echo '{"Results": [], "x": 1}' > "$W/tampered/trivy.json"
cp -r "$W/removed" "$W/libfail"; printf '{"critical_gate": "pass", "library_gate": "fail", "gate": "fail"}\n' > "$W/libfail/trivy-summary.json"
(cd "$W/libfail" && sha256sum inspect.json trivy.json trivy.txt trivy-critical.txt trivy-library.txt trivy-summary.json image.tar.gz > SHA256SUMS)
cp -r "$W/removed" "$W/gotarget"; printf '%s\n' '{"Results": [{"Target": "usr/bin/pebble", "Class": "lang-pkgs", "Type": "gobinary"}]}' > "$W/gotarget/trivy.json"
(cd "$W/gotarget" && sha256sum inspect.json trivy.json trivy.txt trivy-critical.txt trivy-library.txt trivy-summary.json image.tar.gz > SHA256SUMS)
fail=0
check() { if grep -qF -- "$2" "$W/$1"; then echo "ok   $1: $2"; else echo "FAIL $1: $2"; fail=1; fi; }
run() { S2_OUT="$W/$1.json" python3 -I "$TOOL" "${@:2}" > "$W/$1" 2>&1 && echo 0 > "$W/$1.rc" || echo $? > "$W/$1.rc"; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && echo "ok   $1 exit $2" || { echo "FAIL $1 exit $(cat "$W/$1.rc"), want $2"; fail=1; }; }
run s1.out "$W/removed"; sed "s#$W#<W>#g" "$W/s1.out"; rc_is s1.out 0
check s1.out "SHA256SUMS OK"
check s1.out "usr/bin/pebble added - 9949346 bytes, sha256 5e33a1304f20, go1.26.7, github.com/canonical/pebble v1.32.3-0.20260908071357-f1688182910f -> affected (< 1.26.9)"
check s1.out "usr/bin/pebble deleted (whiteout)"
check s1.out "final runtime filesystem: usr/bin/pebble ABSENT"
check s1.out "candidate scan: critical gate pass, library gate pass (fixed CRITICAL 0, HIGH 0); targets img (ubuntu 26.04), Java; usr/bin/pebble as a scan target: no"
check s1.out "S2 PASS: 1/1 images"
run s2.out "$W/kept"; rc_is s2.out 1
check s2.out "final runtime filesystem: usr/bin/pebble PRESENT"
check s2.out "S2 FAIL: 0/1 images"
grep -q "deleted" "$W/s2.out" && { echo "FAIL s2: a deletion reported"; fail=1; } || echo "ok   s2: no deletion reported"
run s3.out "$W/tampered"; rc_is s3.out 1; check s3.out "SHA256SUMS MISMATCH trivy.json"
run s4.out "$W/libfail"; rc_is s4.out 1; check s4.out "candidate scan: critical gate pass, library gate fail"
run s6.out "$W/gotarget"; rc_is s6.out 1; check s6.out "usr/bin/pebble as a scan target: yes"
run s5.out "$W/removed" "$W/kept"; rc_is s5.out 1; check s5.out "S2 FAIL: 1/2 images"
python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); assert r[0]["finalPebble"] == "absent" and r[0]["ok"] is True and [e["event"] for e in r[0]["pebbleEvents"]] == ["added", "deleted (whiteout)"], r' \
  "$W/s1.out.json" && echo "ok   JSON record" || { echo "FAIL JSON record"; fail=1; }
echo "docker $(docker version --format '{{.Server.Version}}')"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
