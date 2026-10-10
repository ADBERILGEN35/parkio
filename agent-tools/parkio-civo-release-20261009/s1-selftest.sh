#!/usr/bin/env bash
# Local self-test of s1-image-findings.py. A local registry holds five synthetic release images, referenced by digest as on
# the host: gateway carries a real Go binary (the registry's own) at /usr/bin/pebble, media a synthetic go1.27.2 build-info
# blob, auth go1.26.9, parking and web none. The D7 bases: "jre" with a go1.26.7 pebble and /etc/os-release as a symlink
# (as on Ubuntu), "jdk" without pebble. A project container must stay as it is. S1 -> COMPLETE with these findings; S2 a
# release image missing locally -> INCOMPLETE and nothing pulled; S3 a Dockerfile with another base -> STOP before any
# probe; S4 unit checks of the build-info parser and the version classification.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/s1-image-findings.py"
P=s1probe; RPORT=15751; RG="localhost:$RPORT"; W="$(mktemp -d)"; NAMES=(s1probe-registry s1probe-user)
JREV=a658edcb0e4152b760ecafa3ef02d7410f9b0ef8; WREV=843ae7cb461bbb66e688964bf2441b4442d23f27
for n in "${NAMES[@]}"; do
  ids="$(docker ps -aq --filter "name=^$n$" 2>/dev/null | grep -E '^[0-9a-f]{12,64}$' || true)"
  [ -z "$ids" ] || { echo "container $n exists"; rm -rf "$W"; exit 2; }
done
cleanup() {
  docker rm -f "${NAMES[@]}" >/dev/null 2>&1 || true
  docker ps -aq --filter label=parkio.release.probe=s1 | grep -E '^[0-9a-f]{12,64}$' | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker images --format '{{.Repository}} {{.ID}}' | awk -v rg="$RG/" -v p="$P-" 'index($1, rg) == 1 || index($1, p) == 1 {print $2}' \
    | sort -u | xargs -r docker image rm -f >/dev/null 2>&1 || true
  rm -rf "$W"
}
trap cleanup EXIT
docker run -d --name s1probe-registry -p "127.0.0.1:$RPORT:5000" registry:2 >/dev/null
for _ in $(seq 30); do docker exec s1probe-registry wget -q -O- http://localhost:5000/v2/ >/dev/null 2>&1 && break; sleep 1; done
blob() {  # base64 of a file holding an ELF-like prefix and a Go build-info blob for version $1 (module framing as the linker writes it)
  python3 - "$1" <<'PY'
import base64, sys
def s(b):
    n, out = len(b), bytearray()
    while True:
        x, n = n & 0x7F, n >> 7
        out.append(x | 0x80 if n else x)
        if not n:
            return bytes(out) + b
start, end = bytes.fromhex("3077af0c9274080241e1c107e6d618e6"), bytes.fromhex("f932433186182072008242104116d8f2")
mod = start + b"path\tgithub.com/canonical/pebble/cmd/pebble\nmod\tgithub.com/canonical/pebble\tv1.99.0\th1:x=\n" + end
data = b"\x7fELF" + b"\0" * 28 + b"\xff Go buildinf:" + bytes([8, 2]) + b"\0" * 16 + s(sys.argv[1].encode()) + s(mod) + b"\0" * 64
print(base64.b64encode(data).decode())
PY
}
osrel() { printf "RUN mkdir -p /usr/lib && printf 'ID=ubuntu\\\\nVERSION_ID=\"%s\"\\\\nVERSION_CODENAME=%s\\\\nPRETTY_NAME=\"Ubuntu %s\"\\\\n' > /usr/lib/os-release && ln -sf ../usr/lib/os-release /etc/os-release\n" "$1" "$2" "$1"; }
build() { docker build -q --provenance=false --sbom=false -t "$1" - >/dev/null; }
JRE="$P-temurin:21-jre"; JDK="$P-temurin:21-jdk"
{ echo "FROM busybox:latest"; osrel 26.04 resolute; echo "RUN echo $(blob go1.26.7) | base64 -d > /usr/bin/pebble"; } | build "$JRE"
{ echo "FROM busybox:latest"; osrel 26.04 resolute; } | build "$JDK"
declare -A REF ACC
mk() {  # service revision extra-dockerfile-lines
  { echo "FROM busybox:latest"; echo "LABEL org.opencontainers.image.revision=$2"; osrel 24.04 noble; printf '%b' "$3"; } | build "$RG/parkio/$1:rel"
  docker push -q "$RG/parkio/$1:rel" >/dev/null
  REF[$1]="$RG/parkio/$1@$(docker image inspect "$RG/parkio/$1:rel" --format '{{index .RepoDigests 0}}' | cut -d@ -f2)"
  ACC[$1]="$(docker image inspect "${REF[$1]}" --format '{{.Id}}')"
}
mk gateway-service "$JREV" "COPY --from=registry:2 /bin/registry /usr/bin/pebble\n"
mk media-service "$JREV" "RUN echo $(blob go1.27.2) | base64 -d > /usr/bin/pebble\n"
mk parking-service "$JREV" ""
mk auth-service "$JREV" "RUN echo $(blob go1.26.9) | base64 -d > /usr/bin/pebble\n"
mk web "$WREV" ""
python3 - "$W/expect.json" <<PY
import json, sys
json.dump({"gateway-service": ["${REF[gateway-service]}", "${ACC[gateway-service]}", "$JREV"],
           "media-service": ["${REF[media-service]}", "${ACC[media-service]}", "$JREV"],
           "parking-service": ["${REF[parking-service]}", "${ACC[parking-service]}", "$JREV"],
           "auth-service": ["${REF[auth-service]}", "${ACC[auth-service]}", "$JREV"],
           "web": ["${REF[web]}", "${ACC[web]}", "$WREV"]}, open(sys.argv[1], "w"))
PY
R="$W/release"
for s in user-service gamification-service notification-service moderation-service ai-validation-service analytics-service; do
  mkdir -p "$R/services/$s"; printf 'FROM %s AS build\nRUN true\nFROM %s AS runtime\n' "$JDK" "$JRE" > "$R/services/$s/Dockerfile"
done
git -C "$R" init -q && git -C "$R" add -A && git -C "$R" -c user.name=t -c user.email=t@t commit -qm release
docker run -d --name s1probe-user --label "com.docker.compose.project=$P" --label "com.docker.compose.service=user-service" \
  --network none "$JDK" sleep 3600 >/dev/null
export S1_RELEASE="$R" S1_PROJECT="$P" S1_OUT="$W/out" S1_JRE="$JRE" S1_JDK="$JDK" S1_EXPECT="$W/expect.json" S1_PIN_ANY=1
fail=0
check() { if grep -qF -- "$2" "$W/$1"; then echo "ok   $1: $2"; else echo "FAIL $1: $2"; fail=1; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && echo "ok   $1 exit $2" || { echo "FAIL $1 exit $(cat "$W/$1.rc"), want $2"; fail=1; }; }
runit() { python3 -I "$TOOL" > "$W/$1" 2>&1 && echo 0 > "$W/$1.rc" || echo $? > "$W/$1.rc"; }
before="$(docker inspect --format '{{.Image}} {{.State.StartedAt}}' s1probe-user)"
runit s1.out
sed -e "s#$W#<W>#g" -e "s#$RG#<RG>#g" "$W/s1.out"
rc_is s1.out 0
check s1.out "PASS gateway-service release image identity: image id = accepted config digest; linux/amd64; revision a658edcb0e41"
check s1.out "PASS gateway-service examined: ubuntu 24.04 (noble)"
check s1.out "/usr/bin/pebble PRESENT"
check s1.out "built with go1."
check s1.out "built with go1.20.8, path github.com/docker/distribution/cmd/registry -> affected (older than the fixed lines 1.26.9 / 1.27.2)"
check s1.out "PASS media-service examined: ubuntu 24.04 (noble)"
check s1.out "built with go1.27.2, github.com/canonical/pebble v1.99.0 -> fixed (>= 1.27.2)"
check s1.out "built with go1.26.9, github.com/canonical/pebble v1.99.0 -> fixed (>= 1.26.9)"
check s1.out "PASS parking-service examined: ubuntu 24.04 (noble)"
check s1.out "/usr/bin/pebble absent"
check s1.out "PASS $JRE examined"
check s1.out "ubuntu 26.04 (resolute)"
check s1.out "built with go1.26.7, github.com/canonical/pebble v1.99.0 -> affected (< 1.26.9)"
check s1.out "PASS no image tag added, moved or removed: added none; moved or removed none"
check s1.out "PASS every project container unchanged (image, state, start time): 1 containers"
check s1.out "PASS no probe container left"
check s1.out "pebble built with a Go version Trivy reports as affected: gateway-service, d7-runtime-base"
check s1.out "d7-runtime-base"
check s1.out "D7: the six host-built services would inherit the runtime base's /usr/bin/pebble"
check s1.out "S1 CHECK COMPLETE"
grep -q "affected:.*parking-service\|affected:.*web\|affected:.*media-service\|affected:.*auth-service" "$W/s1.out" \
  && { echo "FAIL s1: a fixed or pebble-free image listed as affected"; fail=1; } || echo "ok   s1: only real findings listed as affected"
[ "$before" = "$(docker inspect --format '{{.Image}} {{.State.StartedAt}}' s1probe-user)" ] && echo "ok   project container untouched" \
  || { echo "FAIL project container changed"; fail=1; }
python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); assert r["result"] == "COMPLETE"; assert r["images"]["d7-runtime-base"]["pebble"]["goBuildInfo"]["go"] == "go1.26.7"; assert r["images"]["d7-runtime-base"]["osRelease"]["VERSION_CODENAME"] == "resolute"' \
  "$(ls "$W"/out/S1-image-findings-*.json | head -1)" && echo "ok   JSON record" || { echo "FAIL JSON record"; fail=1; }
modes="$(stat -c %a "$W"/out/* | sort -u | tr '\n' ' ')"; [ "$modes" = "600 " ] && echo "ok   reports mode 600" || { echo "FAIL modes $modes"; fail=1; }
# S2: a release image that is not present locally -> INCOMPLETE, and it is not pulled
FAKE="$RG/parkio/auth-service@sha256:0000000000000000000000000000000000000000000000000000000000000001"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); d["auth-service"][0]=sys.argv[2]; json.dump(d, open(sys.argv[3], "w"))' \
  "$W/expect.json" "$FAKE" "$W/expect2.json"
S1_EXPECT="$W/expect2.json" runit s2.out; rc_is s2.out 1
check s2.out "FAIL auth-service release image present: absent locally (not pulled by this check)"
check s2.out "S1 CHECK INCOMPLETE"
docker image inspect "$FAKE" >/dev/null 2>&1 && { echo "FAIL s2 pulled"; fail=1; } || echo "ok   s2: nothing pulled"
# S3: a Dockerfile with another base stops before any probe
printf 'FROM other:1 AS build\nFROM %s\n' "$JRE" > "$R/services/user-service/Dockerfile"; git -C "$R" -c user.name=t -c user.email=t@t commit -qam other
runit s3.out; rc_is s3.out 1
check s3.out "STOP: user-service/Dockerfile FROM lines are not [$JDK, $JRE]"
grep -q "examined" "$W/s3.out" && { echo "FAIL s3 probed"; fail=1; } || echo "ok   s3: no image probed"
[ -z "$(docker ps -aq --filter label=parkio.release.probe=s1)" ] && echo "ok   no probe container after S1-S3" || { echo "FAIL probe left"; fail=1; }
# S4: parser and classification
python3 -I - "$TOOL" "$(blob go1.26.7)" <<'PY' && echo "ok   s4 parser and classification" || { echo "FAIL s4"; fail=1; }
import base64, importlib.util, sys
spec = importlib.util.spec_from_file_location("s1", sys.argv[1]); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
info = m.go_build_info(base64.b64decode(sys.argv[2]))
assert info == {"go": "go1.26.7", "path": "github.com/canonical/pebble/cmd/pebble", "module": "github.com/canonical/pebble",
                "moduleVersion": "v1.99.0"}, info
assert m.go_build_info(b"\x7fELF no build info here") is None
assert m.go_build_info(b"\xff Go buildinf:" + bytes([8, 0]) + b"\0" * 64) is None  # pointer format (Go < 1.18) not parsed
cases = {"go1.26.7": "affected (< 1.26.9)", "go1.26.8": "affected (< 1.26.9)", "go1.26.9": "fixed (>= 1.26.9)",
         "go1.27.1": "affected (< 1.27.2)", "go1.27.2": "fixed (>= 1.27.2)", "go1.27": "affected (< 1.27.2)",
         "go1.25.12": "affected (older than the fixed lines 1.26.9 / 1.27.2)", "go1.28.0": "not affected (newer than the fixed lines)",
         "devel": "unknown Go version"}
for v, want in cases.items():
    assert m.classify(v) == want, (v, m.classify(v))
PY
echo "docker $(docker version --format '{{.Server.Version}}')"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
