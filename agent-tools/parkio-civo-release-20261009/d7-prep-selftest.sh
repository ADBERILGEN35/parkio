#!/usr/bin/env bash
# Local self-test of d7-prep-base-image.py: a local registry stands in for Docker Hub; the "jre" base image is absent and
# gets pulled, the "jdk" one is present; six Dockerfiles name both; a project container and a rollback tag must stay as
# they are. S3 a Dockerfile with another base stops before any pull; S1 pulls (PASS); S2 re-runs (present, PASS).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/d7-prep-base-image.py"
P=d7pprobe; RPORT=15741; RG="localhost:$RPORT"; W="$(mktemp -d)"; NAMES=(d7pprobe-registry d7pprobe-user)
for n in "${NAMES[@]}"; do
  ids="$(docker ps -aq --filter "name=^$n$" 2>/dev/null | grep -E '^[0-9a-f]{12,64}$' || true)"
  [ -z "$ids" ] || { echo "container $n exists"; rm -rf "$W"; exit 2; }
done
cleanup() {
  docker rm -f "${NAMES[@]}" >/dev/null 2>&1 || true
  docker images --format '{{.Repository}} {{.ID}}' | awk -v rg="$RG/" -v p="$P-" 'index($1, rg) == 1 || index($1, p) == 1 {print $2}' \
    | sort -u | xargs -r docker image rm -f >/dev/null 2>&1 || true
  rm -rf "$W"
}
trap cleanup EXIT
docker run -d --name d7pprobe-registry -p "127.0.0.1:$RPORT:5000" registry:2 >/dev/null
for _ in $(seq 30); do docker exec d7pprobe-registry wget -q -O- http://localhost:5000/v2/ >/dev/null 2>&1 && break; sleep 1; done
JRE="$RG/selftest/temurin:21-jre"; JDK="$RG/selftest/temurin:21-jdk"
printf 'FROM busybox:latest\nRUN echo jre > /marker\n' | docker build -q -t "$JRE" --provenance=false --sbom=false - >/dev/null
printf 'FROM busybox:latest\nRUN echo jdk > /marker\n' | docker build -q -t "$JDK" --provenance=false --sbom=false - >/dev/null
docker push -q "$JRE" >/dev/null; docker image rm "$JRE" >/dev/null
printf 'FROM busybox:latest\nRUN echo old-user > /marker\n' | docker build -q -t "$P-user-service:latest" - >/dev/null
docker tag "$P-user-service:latest" "$P-user-service:rollback-pre-22f96990"
docker run -d --name d7pprobe-user --label "com.docker.compose.project=$P" --label "com.docker.compose.service=user-service" \
  --network none "$P-user-service" sleep 3600 >/dev/null
R="$W/release"
for s in user-service gamification-service notification-service moderation-service ai-validation-service analytics-service; do
  mkdir -p "$R/services/$s"; printf 'FROM %s AS build\nRUN true\nFROM %s AS runtime\n' "$JDK" "$JRE" > "$R/services/$s/Dockerfile"
done
git -C "$R" init -q && git -C "$R" add -A && git -C "$R" -c user.name=t -c user.email=t@t commit -qm release
export D7P_RELEASE="$R" D7P_PROJECT="$P" D7P_OUT="$W/out" D7P_PULL="$JRE" D7P_OTHER="$JDK" D7P_PIN_ANY=1
fail=0
check() { if grep -qF -- "$2" "$W/$1"; then echo "ok   $1: $2"; else echo "FAIL $1: $2"; fail=1; fi; }
runit() { python3 -I "$TOOL" > "$W/$1" 2>&1 && echo 0 > "$W/$1.rc" || echo $? > "$W/$1.rc"; }
# S3: a Dockerfile with another base stops before any pull
printf 'FROM other:1 AS build\nFROM %s\n' "$JRE" > "$R/services/user-service/Dockerfile"; git -C "$R" -c user.name=t -c user.email=t@t commit -qam other
runit s3.out; git -C "$R" -c user.name=t -c user.email=t@t revert --no-edit HEAD >/dev/null
[ "$(cat "$W/s3.out.rc")" = 1 ] && echo "ok   s3 exit 1" || { echo "FAIL s3 exit"; fail=1; }
check s3.out "STOP: user-service/Dockerfile FROM lines are not"
docker image inspect "$JRE" >/dev/null 2>&1 && { echo "FAIL s3 pulled"; fail=1; } || echo "ok   s3: nothing pulled"
before="$(docker inspect --format '{{.Image}}' d7pprobe-user) $(docker image inspect --format '{{.Id}}' "$P-user-service:rollback-pre-22f96990")"
runit s1.out
sed "s#$W#<W>#g" "$W/s1.out"
[ "$(cat "$W/s1.out.rc")" = 0 ] && echo "ok   s1 exit 0" || { echo "FAIL s1 exit"; fail=1; }
check s1.out "the six Dockerfiles build FROM $JDK and run FROM $JRE"
check s1.out "pulling $JRE ..."
check s1.out "PASS $JRE present, linux/amd64"
check s1.out "PASS no existing tag moved or removed (service and rollback tags included)"
check s1.out "PASS only $JRE added"
check s1.out "rollback tags still on their images: 1/1"
check s1.out "PASS every project container unchanged"
check s1.out "D7 PREP COMPLETE"
runit s2.out
[ "$(cat "$W/s2.out.rc")" = 0 ] && echo "ok   s2 exit 0" || { echo "FAIL s2 exit"; fail=1; }
check s2.out "already present"
check s2.out "tags added: none; moved or removed: none"
after="$(docker inspect --format '{{.Image}}' d7pprobe-user) $(docker image inspect --format '{{.Id}}' "$P-user-service:rollback-pre-22f96990")"
[ "$before" = "$after" ] && echo "ok   container image and rollback tag unchanged" || { echo "FAIL container or rollback tag changed"; fail=1; }
python3 -c 'import json,sys; [json.load(open(p)) for p in sys.argv[1:]]' "$W"/out/*.json && echo "ok   JSON records parse" || { echo "FAIL JSON"; fail=1; }
modes="$(stat -c %a "$W"/out/* | sort -u | tr '\n' ' ')"; [ "$modes" = "600 " ] && echo "ok   reports mode 600" || { echo "FAIL modes $modes"; fail=1; }
echo "docker $(docker version --format '{{.Server.Version}}')"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
