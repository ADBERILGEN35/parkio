#!/usr/bin/env bash
# Local self-test of rb1-release-rebaseline.py on a synthetic host: a bare "origin" with an old PIN and a new PIN whose
# difference has exactly the approved shape (13 marketing files, the pebble block in ten Dockerfiles, four image lines in two
# pin files), a release clone at the old PIN, a dirty live checkout, a local registry holding the four replacement images
# (no pebble), pebble-carrying D7 bases, a project container and two rollback tags.
# S1 old -> new: COMPLETE; S2 re-run at the new PIN: COMPLETE, nothing moved; S3 a new PIN with an extra changed path: STOP,
# checkout not moved; S4 a new PIN whose Dockerfile block differs: STOP, not moved; S5 a replacement image that still
# carries pebble: NOT COMPLETE; S6 the published copy with unfilled constants: STOP before any check.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/rb1-release-rebaseline.py"
P=rb1probe; RPORT=15761; RG="localhost:$RPORT"; W="$(mktemp -d)"; NAMES=(rb1probe-registry rb1probe-user)
SRC=5555555555555555555555555555555555555555
for n in "${NAMES[@]}"; do
  ids="$(docker ps -aq --filter "name=^$n$" 2>/dev/null | grep -E '^[0-9a-f]{12,64}$' || true)"
  [ -z "$ids" ] || { echo "container $n exists"; rm -rf "$W"; exit 2; }
done
cleanup() {
  docker rm -f "${NAMES[@]}" >/dev/null 2>&1 || true
  docker ps -aq --filter label=parkio.release.probe=rb1 | grep -E '^[0-9a-f]{12,64}$' | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker images --format '{{.Repository}}:{{.Tag}} {{.ID}}' | awk -v rg="$RG/" -v p="$P-" 'index($1, rg) == 1 || index($1, p) == 1 {print $2}' \
    | sort -u | xargs -r docker image rm -f >/dev/null 2>&1 || true
  rm -rf "$W"
}
trap cleanup EXIT
docker run -d --name rb1probe-registry -p "127.0.0.1:$RPORT:5000" registry:2 >/dev/null
for _ in $(seq 30); do docker exec rb1probe-registry wget -q -O- http://localhost:5000/v2/ >/dev/null 2>&1 && break; sleep 1; done
blob() {
  python3 - "$1" <<'PY'
import base64, sys
def s(b):
    n, out = len(b), bytearray()
    while True:
        x, n = n & 0x7F, n >> 7
        out.append(x | 0x80 if n else x)
        if not n:
            return bytes(out) + b
data = b"\x7fELF" + b"\0" * 28 + b"\xff Go buildinf:" + bytes([8, 2]) + b"\0" * 16 + s(sys.argv[1].encode()) + s(b"") + b"\0" * 32
print(base64.b64encode(data).decode())
PY
}
build() { docker build -q --provenance=false --sbom=false -t "$1" - >/dev/null; }
JRE="$P-temurin:21-jre"; JDK="$P-temurin:21-jdk"
printf 'FROM busybox:latest\nRUN echo ID=ubuntu > /etc/os-release && echo VERSION_ID=26.04 >> /etc/os-release\nRUN echo %s | base64 -d > /usr/bin/pebble\n' "$(blob go1.26.7)" | build "$JRE"
printf 'FROM busybox:latest\nRUN echo ID=ubuntu > /etc/os-release && echo VERSION_ID=26.04 >> /etc/os-release && echo jdk > /marker\nRUN echo %s | base64 -d > /usr/bin/pebble\n' "$(blob go1.26.7)" | build "$JDK"
declare -A REF ACC
mk() {  # service tag extra
  printf 'FROM busybox:latest\nLABEL org.opencontainers.image.revision=%s\nRUN echo ID=ubuntu > /etc/os-release && echo %s-%s > /marker\n%b' "$SRC" "$1" "$2" "$3" | build "$RG/parkio/$1:$2"
  docker push -q "$RG/parkio/$1:$2" >/dev/null
  REF[$1-$2]="$RG/parkio/$1@$(docker image inspect "$RG/parkio/$1:$2" --format '{{index .RepoDigests 0}}' | cut -d@ -f2)"
  ACC[$1-$2]="$(docker image inspect "${REF[$1-$2]}" --format '{{.Id}}')"
}
for s in gateway-service media-service parking-service auth-service; do mk "$s" new ""; done
mk media-service peb "RUN echo $(blob go1.26.7) | base64 -d > /usr/bin/pebble\n"
docker tag "$JDK" "$P-a:rollback-pre-22f96990"; docker tag "$JRE" "$P-b:rollback-pre-22f96990"
docker run -d --name rb1probe-user --label "com.docker.compose.project=$P" --label "com.docker.compose.service=user-service" \
  --network none "$JDK" sleep 3600 >/dev/null
# --- repositories
O="$W/origin.git"; A="$W/author"; git init -q --bare "$O"; git init -q "$A"
g() { git -C "$A" -c user.name=t -c user.email=t@t "$@"; }
SERVICES="gateway-service auth-service media-service parking-service user-service gamification-service notification-service moderation-service ai-validation-service analytics-service"
MKT="docs/releases/MARKETING-SOURCE-DEPLOYMENT.md scripts/validate-marketing-site.mjs web/marketing/.htaccess web/marketing/404.html web/marketing/i18n.js web/marketing/index.html web/marketing/privacy/index.html web/marketing/styles.css web/marketing/terms/index.html web/marketing/waitlist.adapter.test.mjs web/marketing/waitlist.pages.i18n.test.mjs web/marketing/waitlist/confirm/index.html web/marketing/waitlist/unsubscribe/index.html"
for f in $MKT; do mkdir -p "$A/$(dirname "$f")"; echo old > "$A/$f"; done
for s in $SERVICES; do
  mkdir -p "$A/services/$s"
  printf 'FROM %s AS build\nRUN true\n\nFROM %s AS runtime\nWORKDIR /app\nRUN apt-get update \\\n && apt-get install -y --no-install-recommends curl \\\n && rm -rf /var/lib/apt/lists/*\n\nUSER parkio\nENTRYPOINT ["java", "-jar", "/app/app.jar"]\n' "$JDK" "$JRE" > "$A/services/$s/Dockerfile"
done
mkdir -p "$A/docker"
pinfile() {  # file svc...
  local f="$1"; shift
  { echo "# header old"; echo "name: parkio"; echo; echo "services:"
    for s in "$@"; do printf '  %s:\n    image: %s/parkio/%s@sha256:%064d\n\n' "$s" "$RG" "$s" 0; done; } > "$A/docker/$f"
}
pinfile docker-compose.gmp-release-pins.yml gateway-service media-service parking-service
pinfile docker-compose.auth-release-pin.yml auth-service
echo "web pin" > "$A/docker/docker-compose.web-release-pin.yml"; mkdir -p "$A/services/gateway-service/src"; echo "class X {}" > "$A/services/gateway-service/src/X.java"
g add -A; g commit -qm old; OLD=$(git -C "$A" rev-parse HEAD)
for f in $MKT; do echo new >> "$A/$f"; done
python3 - "$A" $SERVICES <<'PY'
import sys
root, services = sys.argv[1], sys.argv[2:]
block = ("# The Ubuntu 26.04 base layer (built with Rockcraft) ships /usr/bin/pebble, a Go service manager\n"
         "# that nothing here runs: the base image clears its entrypoint, the temurin entrypoint never calls\n"
         "# it, and this image starts java directly. Removing it keeps that binary's Go stdlib findings\n"
         "# (Security CI library gate) out of the image filesystem.\nRUN rm -f /usr/bin/pebble\n\n")
anchor = " && rm -rf /var/lib/apt/lists/*\n\n"
for s in services:
    p = f"{root}/services/{s}/Dockerfile"; t = open(p).read(); assert t.count(anchor) == 1
    open(p, "w").write(t.replace(anchor, anchor + block))
PY
setpins() {  # gateway media parking auth refs
  python3 - "$A/docker" "$@" <<'PY'
import re, sys
d, gw, me, pa, au = sys.argv[1:6]
for f, pairs in (("docker-compose.gmp-release-pins.yml", (("gateway-service", gw), ("media-service", me), ("parking-service", pa))),
                 ("docker-compose.auth-release-pin.yml", (("auth-service", au),))):
    t = open(f"{d}/{f}").read().replace("# header old", "# header new: source 5555, run 1")
    for svc, ref in pairs:
        t = re.sub(rf"(?m)^(  {svc}:\n    image: )\S+$", lambda m: m.group(1) + ref, t)
    open(f"{d}/{f}", "w").write(t)
PY
}
setpins "${REF[gateway-service-new]}" "${REF[media-service-new]}" "${REF[parking-service-new]}" "${REF[auth-service-new]}"
g add -A; g commit -qm new; NEW=$(git -C "$A" rev-parse HEAD)
echo "class Y {}" > "$A/services/gateway-service/src/X.java"; g commit -qam extra; NEW2=$(git -C "$A" rev-parse HEAD)
g checkout -q "$NEW"; sed -i 's/out of the image filesystem\./out of the image./' "$A/services/user-service/Dockerfile"; g commit -qam block; NEW3=$(git -C "$A" rev-parse HEAD)
g checkout -q "$NEW"; setpins "${REF[gateway-service-new]}" "${REF[media-service-peb]}" "${REF[parking-service-new]}" "${REF[auth-service-new]}"
g commit -qam peb; NEW4=$(git -C "$A" rev-parse HEAD)
g branch -q b2 "$NEW2"; g branch -q b3 "$NEW3"; g branch -q b4 "$NEW4"; g branch -q main2 "$NEW"
git -C "$O" config uploadpack.allowAnySHA1InWant true; git -C "$O" symbolic-ref HEAD refs/heads/old
git -C "$A" push -q "$O" "$OLD:refs/heads/old"
R="$W/release"; git clone -q "$O" "$R"; git -C "$R" checkout -q --detach "$OLD"
git -C "$A" push -q "$O" "$NEW:refs/heads/api" b2 b3 b4   # after the clone: RB1 has to fetch the new PIN
L="$W/live"; git init -q "$L"; echo a > "$L/f"; git -C "$L" add -A; git -C "$L" -c user.name=t -c user.email=t@t commit -qm live; echo dirty >> "$L/f"
expect() {  # file new-pin media-variant
  python3 - "$1" "$OLD" "$2" "$SRC" "${REF[gateway-service-new]}" "${ACC[gateway-service-new]}" "${REF[media-service-$3]}" "${ACC[media-service-$3]}" \
    "${REF[parking-service-new]}" "${ACC[parking-service-new]}" "${REF[auth-service-new]}" "${ACC[auth-service-new]}" <<'PY'
import json, sys
f, old, new, src, gr, ga, mr, ma, pr, pa, ar, aa = sys.argv[1:13]
json.dump({"old_pin": old, "new_pin": new, "source": src, "release": {"gateway-service": [gr, ga, src], "media-service": [mr, ma, src],
           "parking-service": [pr, pa, src], "auth-service": [ar, aa, src]}}, open(f, "w"))
PY
}
expect "$W/e1.json" "$NEW" new; expect "$W/e2.json" "$NEW2" new; expect "$W/e3.json" "$NEW3" new; expect "$W/e4.json" "$NEW4" peb
export RB1_RELEASE="$R" RB1_LIVE="$L" RB1_PROJECT="$P" RB1_OUT="$W/out" RB1_JRE="$JRE" RB1_JDK="$JDK"
fail=0
check() { if grep -qF -- "$2" "$W/$1"; then echo "ok   $1: $2"; else echo "FAIL $1: $2"; fail=1; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && echo "ok   $1 exit $2" || { echo "FAIL $1 exit $(cat "$W/$1.rc"), want $2"; fail=1; }; }
runit() { RB1_EXPECT="$2" python3 -I "$TOOL" > "$W/$1" 2>&1 && echo 0 > "$W/$1.rc" || echo $? > "$W/$1.rc"; }
head_is() { [ "$(git -C "$R" rev-parse HEAD)" = "$2" ] && echo "ok   $1: release checkout at ${2:0:7}" || { echo "FAIL $1: release checkout moved"; fail=1; }; }
# S3/S4 first, while the checkout is at the old PIN
runit s3.out "$W/e2.json"; rc_is s3.out 1
check s3.out "fetched ${NEW2:0:12} from origin"
check s3.out "unexpected: services/gateway-service/src/X.java"
check s3.out "STOP: the new PIN changes paths outside the approved scope; the checkout is not moved"; head_is s3 "$OLD"
runit s4.out "$W/e3.json"; rc_is s4.out 1
check s4.out "FAIL every Dockerfile adds exactly the pebble-removal block and removes nothing: differs: user-service"
check s4.out "STOP: the new PIN does not match the approved remediation; the checkout is not moved"; head_is s4 "$OLD"
before="$(docker inspect --format '{{.Image}} {{.State.StartedAt}}' rb1probe-user)"
runit s1.out "$W/e1.json"
sed -e "s#$W#<W>#g" -e "s#$RG#<RG>#g" "$W/s1.out"
rc_is s1.out 0
check s1.out "PASS the new PIN descends from the old PIN"
check s1.out "PASS changed paths are exactly marketing (13), the ten Dockerfiles and the two pin files: 25 paths"
check s1.out "PASS every Dockerfile adds exactly the pebble-removal block and removes nothing: 10/10"
check s1.out "PASS the pin files change only comments and image lines"
check s1.out "PASS the pin files name exactly the four replacement digests"
check s1.out "moved ${OLD:0:12} -> ${NEW:0:12}"
check s1.out "PASS release checkout at the new PIN and clean"
check s1.out "PASS every runtime stage at the new PIN removes /usr/bin/pebble (D7 builds FROM the same two bases): 10/10"
check s1.out "PASS gateway-service replacement image identity: present;"
check s1.out "PASS auth-service final filesystem without /usr/bin/pebble: ubuntu ?; /usr/bin/pebble absent"
check s1.out "PASS $JRE examined"
check s1.out "/usr/bin/pebble present (go1.26.7, sha256"
check s1.out "base not clean - expected; D7's runtime stage removes it"
check s1.out "PASS no image tag added, moved or removed (rollback tags included): rollback tags 2/2 unchanged"
check s1.out "PASS every project container unchanged (image, state, start time): 1 containers"
check s1.out "PASS live checkout /opt/parkio unchanged (HEAD and status)"
check s1.out "PASS no probe container left"
check s1.out "RB1 REBASELINE COMPLETE"
head_is s1 "$NEW"
[ "$(git -C "$L" status --porcelain | wc -l)" = 1 ] && echo "ok   live checkout still dirty" || { echo "FAIL live checkout"; fail=1; }
[ "$before" = "$(docker inspect --format '{{.Image}} {{.State.StartedAt}}' rb1probe-user)" ] && echo "ok   project container untouched" || { echo "FAIL container"; fail=1; }
runit s2.out "$W/e1.json"; rc_is s2.out 0; check s2.out "already at the new PIN"; check s2.out "RB1 REBASELINE COMPLETE"
git -C "$R" checkout -q --detach "$NEW4" 2>/dev/null || { git -C "$R" fetch -q origin b4 && git -C "$R" checkout -q --detach "$NEW4"; }
runit s5.out "$W/e4.json"; rc_is s5.out 1
check s5.out "FAIL media-service final filesystem without /usr/bin/pebble"
check s5.out "RB1 REBASELINE NOT COMPLETE"
sed -E 's/^NEW_PIN = "[0-9a-f]{40}"$/NEW_PIN = "__NEW_PIN__"/' "$TOOL" > "$W/rb1-unfilled.py"   # the copy as it was before the PIN existed
RB1_OUT="$W/out6" python3 -I "$W/rb1-unfilled.py" > "$W/s6.out" 2>&1 && echo 0 > "$W/s6.out.rc" || echo $? > "$W/s6.out.rc"
rc_is s6.out 1; check s6.out "STOP: this copy has unfilled constants"
[ -z "$(docker ps -aq --filter label=parkio.release.probe=rb1)" ] && echo "ok   no probe container after S1-S6" || { echo "FAIL probe left"; fail=1; }
modes="$(stat -c %a "$W"/out/* | sort -u | tr '\n' ' ')"; [ "$modes" = "600 " ] && echo "ok   reports mode 600" || { echo "FAIL modes $modes"; fail=1; }
echo "docker $(docker version --format '{{.Server.Version}}')"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
