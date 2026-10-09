#!/usr/bin/env bash
# Local self-test of p2-release-images.py (P2) on a synthetic host-like stack: a local registry holding the
# "previous" and the "release" images of the five pinned services (release images absent locally, so P2 must
# pull them by digest), six host-built images (one whose latest tag moved after its container started), three
# D2 infrastructure containers, a release parking image whose /etc/localtime links to Etc/UTC, stub web guards
# in the release checkout (one variant prints a URL and blocks), live and release git checkouts, and an env
# file holding secrets that must never be printed.
# Scenarios: S0 (containerd image store) a running image whose only name moved stops the run before any
# change; S1 a conflicting rollback tag stops the run before any change; S2 the normal run passes; S3 a re-run
# is idempotent; S4 a release variant (Istanbul parking image, another caddy image, a blocking API guard)
# fails exactly those gates. Pass = expected lines, no secret or URL printed, reports mode 600, no
# service container recreated, no existing tag moved or removed, probe container and temp files removed.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/p2-release-images.py"
P=p2probe; RPORT=15731; RG="localhost:$RPORT"; W="$(mktemp -d)"; TAG=rollback-pre-22f96990
PINNED=(gateway-service media-service parking-service auth-service web)
BUILT=(user-service gamification-service notification-service moderation-service ai-validation-service analytics-service)
INFRA=(alertmanager prometheus caddy)
JREV=a658edcb0e4152b760ecafa3ef02d7410f9b0ef8; WREV=843ae7cb461bbb66e688964bf2441b4442d23f27
cleanup() {
  docker rm -f $(docker ps -aq --filter "label=com.docker.compose.project=$P") >/dev/null 2>&1 || true
  docker rm -f $(docker ps -aq --filter "label=parkio.release.probe=p2") p2probe-registry >/dev/null 2>&1 || true
  docker images --format '{{.Repository}}:{{.Tag}}' | grep -E "^($RG/|$P-)" | grep -v ':<none>$' \
    | xargs -r docker image rm -f >/dev/null 2>&1 || true
  docker images --format '{{.Repository}} {{.ID}}' | awk -v rg="$RG/" 'index($1, rg) == 1 {print $2}' | sort -u \
    | xargs -r docker image rm -f >/dev/null 2>&1 || true
  rm -rf "$W"
}
trap cleanup EXIT
[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$P")" ] || { echo "project $P in use"; exit 2; }
[ -z "$(docker ps -aq --filter name=^p2probe-registry$)" ] || { echo "p2probe-registry exists"; exit 2; }
docker run -d --name p2probe-registry -p "127.0.0.1:$RPORT:5000" registry:2 >/dev/null
for _ in $(seq 30); do docker exec p2probe-registry wget -q -O- http://localhost:5000/v2/ >/dev/null 2>&1 && break; sleep 1; done
lbl() { echo --label "com.docker.compose.project=$P" --label "com.docker.compose.service=$1"; }
build() {  # build TAG with Dockerfile body on stdin
  docker build -q -t "$1" --provenance=false --sbom=false - >/dev/null
}
digest_of() { docker image inspect "$1" --format '{{index .RepoDigests 0}}' | cut -d@ -f2; }
config_of() { docker buildx imagetools inspect --raw "$1" | python3 -c 'import json,sys; print(json.load(sys.stdin)["config"]["digest"])'; }
declare -A PREV REL CFG
for s in "${PINNED[@]}"; do
  printf 'FROM busybox:latest\nLABEL org.opencontainers.image.revision=prev-%s\nRUN echo prev-%s > /marker\n' "$s" "$s" | build "$RG/parkio/$s:prev"
  docker push -q "$RG/parkio/$s:prev" >/dev/null; PREV[$s]="$RG/parkio/$s@$(digest_of "$RG/parkio/$s:prev")"
  rev=$JREV; [ "$s" = web ] && rev=$WREV
  extra=''; [ "$s" = parking-service ] && extra='RUN ln -sf /usr/share/zoneinfo/Etc/UTC /etc/localtime && rm -f /etc/timezone'
  printf 'FROM busybox:latest\nLABEL org.opencontainers.image.revision=%s\nRUN echo rel-%s > /marker\n%s\n' "$rev" "$s" "$extra" | build "$RG/parkio/$s:rel"
  docker push -q "$RG/parkio/$s:rel" >/dev/null; REL[$s]="$RG/parkio/$s@$(digest_of "$RG/parkio/$s:rel")"
  CFG[$s]="$(config_of "${REL[$s]}")"
done
printf 'FROM busybox:latest\nLABEL org.opencontainers.image.revision=%s\nENV TZ=Europe/Istanbul\nRUN echo ist > /marker\n' "$JREV" \
  | build "$RG/parkio/parking-service:ist"
docker push -q "$RG/parkio/parking-service:ist" >/dev/null; IST="$RG/parkio/parking-service@$(digest_of "$RG/parkio/parking-service:ist")"
ISTCFG="$(config_of "$IST")"
for s in "${PINNED[@]}"; do docker image rm "$RG/parkio/$s:rel" "${REL[$s]}" >/dev/null 2>&1 || true; done
docker image rm "$RG/parkio/parking-service:ist" "$IST" >/dev/null 2>&1 || true
for s in "${BUILT[@]}"; do
  printf 'FROM busybox:latest\nLABEL org.opencontainers.image.revision=unknown\nRUN echo %s > /marker\n' "$s" | build "$P-$s:latest"
done
# --- running containers (no network, no ports)
for s in "${PINNED[@]}"; do
  # shellcheck disable=SC2046
  docker run -d --name "$P-$s" $(lbl "$s") --network none "${PREV[$s]}" sleep 3600 >/dev/null
done
for s in "${BUILT[@]}"; do
  # shellcheck disable=SC2046
  docker run -d --name "$P-$s" $(lbl "$s") --network none "$P-$s" sleep 3600 >/dev/null
done
for s in "${INFRA[@]}"; do
  # shellcheck disable=SC2046
  docker run -d --name "$P-$s" $(lbl "$s") --network none busybox:latest sleep 3600 >/dev/null
done
# analytics: latest moves after its container started, but the running image keeps another name.
docker tag "$P-analytics-service:latest" "$P-analytics-service:keep"
printf 'FROM busybox:latest\nRUN echo moved > /marker\n' | build "$P-analytics-service:latest"
# notification: latest moves and the running image keeps no name (S0).
printf 'FROM busybox:latest\nRUN echo moved-n > /marker\n' | build "$P-notification-service:latest"
STORE=classic; docker info --format '{{json .DriverStatus}}' | grep -q containerd && STORE=containerd
# --- checkouts and env file
L="$W/live"; mkdir -p "$L/docker"; echo live > "$L/README"
git -C "$L" init -q && git -C "$L" add -A && git -C "$L" -c user.name=t -c user.email=t@t commit -qm live
ENV="$L/docker/.env.azure-hosted-beta"
printf 'PGPW=Pw-Synthetic-91\nPARKIO_ALERT_SLACK_WEBHOOK_URL=https://alerts.invalid/hook/Synthetic-Secret-Token-77\nPARKIO_DOMAIN=api.example.invalid\n' > "$ENV"
mkrelease() {  # dir parking-ref caddy-image api-guard-body
  local R="$1"; mkdir -p "$R/docker" "$R/scripts/lib"
  printf 'docker/docker-compose.yml\ndocker/docker-compose.azure-hosted-beta.yml\ndocker/docker-compose.pins.yml\n' > "$R/docker/compose.production.files"
  {
    printf 'name: %s\nservices:\n' "$P"
    for s in "${PINNED[@]}"; do printf '  %s: {image: "busybox:latest", build: {context: .}, environment: {HOOK: "${PARKIO_ALERT_SLACK_WEBHOOK_URL}"}}\n' "$s"; done
    for s in "${BUILT[@]}"; do printf '  %s: {build: {context: .}}\n' "$s"; done
    printf '  alertmanager: {image: "busybox:latest", profiles: ["off"]}\n  prometheus: {image: "busybox:latest"}\n  caddy: {image: "%s"}\n' "$3"
  } > "$R/docker/docker-compose.yml"
  printf 'services:\n  parking-service:\n    environment:\n      JAVA_TOOL_OPTIONS: "${JAVA_TOOL_OPTIONS:--XX:MaxRAMPercentage=65.0 -XX:+UseG1GC}"\n      SPRING_DATASOURCE_PASSWORD: "${PGPW}"\n' \
    > "$R/docker/docker-compose.azure-hosted-beta.yml"
  {
    printf 'services:\n'
    for s in "${PINNED[@]}"; do
      ref="${REL[$s]}"; [ "$s" = parking-service ] && ref="$2"
      printf '  %s:\n    image: %s\n' "$s" "$ref"
    done
  } > "$R/docker/docker-compose.pins.yml"
  printf 'services:\n  alertmanager:\n    profiles: !reset []\n' > "$R/docker/docker-compose.civo-alertmanager.yml"
  cat > "$R/scripts/lib/web-map-guard.sh" <<'G'
parkio_web_guard_bind() {
  local config_json="$1" override_out="$2"; shift 2
  [ -s "$config_json" ] || { echo "web-map-deploy-guard: BLOCKED: no model" >&2; return 1; }
  printf 'services:\n  web:\n    image: stub\n    pull_policy: never\n' > "$override_out"
  echo "web-map-deploy-guard: PASS image=stub mapKey=PRESENT fingerprint=0123456789ab bound=stub"
}
G
  printf 'parkio_web_conf_d_check() { [ -s "$2" ] && echo "web-conf-d-guard: PASS (renders conf.d)"; }\n' > "$R/scripts/lib/web-conf-d-guard.sh"
  printf 'parkio_web_api_endpoint_check() { %s; }\n' "$4" > "$R/scripts/lib/web-api-endpoint-guard.sh"
  git -C "$R" init -q && git -C "$R" add -A && git -C "$R" -c user.name=t -c user.email=t@t commit -qm release
}
mkrelease "$W/release" "${REL[parking-service]}" busybox:latest \
  'echo "web-api-endpoint-guard: PASS web calls https://api.example.invalid/api/v1"'
mkrelease "$W/release2" "$IST" busybox:1.37 \
  'echo "web-api-endpoint-guard: BLOCKED: image bakes https://wrong.example.invalid/api/v1" >&2; return 1'
expect() {  # file parking-ref parking-config
  python3 - "$1" "$2" "$3" <<PY
import json, sys
rel = {$(for s in "${PINNED[@]}"; do r=$JREV; [ "$s" = web ] && r=$WREV; printf '"%s": ["%s", "%s", "%s"], ' "$s" "${REL[$s]}" "${CFG[$s]}" "$r"; done)}
rel["parking-service"] = [sys.argv[2], sys.argv[3], "$JREV"]
prev = {$(for s in "${PINNED[@]}"; do printf '"%s": "%s", ' "$s" "${PREV[$s]}"; done)}
json.dump({"release": rel, "previous": prev}, open(sys.argv[1], "w"))
PY
}
expect "$W/expect.json" "${REL[parking-service]}" "${CFG[parking-service]}"
expect "$W/expect2.json" "$IST" "$ISTCFG"
mkdir -p "$W/tmp"
p2() {  # release-dir expect-file output-file
  P2_LIVE="$L" P2_RELEASE="$1" P2_ENV_FILE="$ENV" P2_PROJECT="$P" P2_OUT="$W/out" P2_PIN_ANY=1 P2_EXPECT="$2" \
    P2_NR_BUDGET="$W/no-such/budget" P2_BASE_IMAGES="busybox:latest,$P-no-such:never" TMPDIR="$W/tmp" \
    python3 -I "$TOOL" > "$3" 2>&1 && echo 0 > "$3.rc" || echo $? > "$3.rc"
}
snap_ctr() { docker ps -a --filter "label=com.docker.compose.project=$P" --format '{{.Label "com.docker.compose.service"}} {{.ID}} {{.State}}' | sort; }
snap_tags() { docker images --format '{{.Repository}}:{{.Tag}} {{.ID}}' | grep -E "^($RG/|$P-|busybox:)" | grep -v ':<none> ' | sort; }
fail=0
ok() { echo "ok   $1"; }
bad() { echo "FAIL $1"; fail=1; }
check() { if grep -qF -- "$2" "$1"; then ok "$(basename "$1"): $2"; else bad "$(basename "$1"): $2"; fi; }
nocheck() { if grep -qF -- "$2" "$1"; then bad "$(basename "$1") must not contain: $2"; else ok "$(basename "$1") lacks: $2"; fi; }
rc_is() { [ "$(cat "$1.rc")" = "$2" ] && ok "$(basename "$1") exit $2" || bad "$(basename "$1") exit $(cat "$1.rc"), want $2"; }
# --- S0: a running image without a name (containerd store) -> STOP before any change
if [ "$STORE" = containerd ]; then
  p2 "$W/release" "$W/expect.json" "$W/s0.out"
  rc_is "$W/s0.out" 1
  check "$W/s0.out" "STOP: running image not taggable (nothing was changed): notification-service runs "
  [ -z "$(docker images --format '{{.Repository}}:{{.Tag}}' | grep ":$TAG$")" ] && ok "S0 wrote no rollback tag" || bad "S0 wrote a rollback tag"
else
  echo "skip S0 (classic image store keeps the moved image as <none>)"
fi
docker rm -f "$P-notification-service" >/dev/null
# shellcheck disable=SC2046
docker run -d --name "$P-notification-service" $(lbl notification-service) --network none "$P-notification-service" sleep 3600 >/dev/null
snap_ctr > "$W/ctr0"; snap_tags > "$W/tags0"
# --- S1: a rollback tag name already points at another image -> STOP before any change
docker tag busybox:latest "$P-user-service:$TAG"
p2 "$W/release" "$W/expect.json" "$W/s1.out"
sed "s#$W#<W>#g" "$W/s1.out"
rc_is "$W/s1.out" 1
check "$W/s1.out" "STOP: rollback tag name already used by another image (nothing was changed): $P-user-service:$TAG"
[ -z "$(docker images --format '{{.Repository}}:{{.Tag}}' | grep ":$TAG$" | grep -v "^$P-user-service:")" ] \
  && ok "S1 wrote no rollback tag" || bad "S1 wrote a rollback tag"
docker image inspect "${REL[gateway-service]}" >/dev/null 2>&1 && bad "S1 pulled an image" || ok "S1 pulled nothing"
docker image rm "$P-user-service:$TAG" >/dev/null
# --- S2: normal run
p2 "$W/release" "$W/expect.json" "$W/s2.out"
sed "s#$W#<W>#g" "$W/s2.out"
rc_is "$W/s2.out" 0
for s in "${PINNED[@]}" "${BUILT[@]}"; do
  repo="$RG/parkio/$s"; case " ${BUILT[*]} " in *" $s "*) repo="$P-$s";; esac
  check "$W/s2.out" "rollback tag $repo:$TAG: to create"
  run_id="$(docker inspect "$P-$s" --format '{{.Image}}')"; tag_id="$(docker image inspect "$repo:$TAG" --format '{{.Id}}' 2>/dev/null || true)"
  [ "$run_id" = "$tag_id" ] && ok "$repo:$TAG = running image" || bad "$repo:$TAG != running image"
done
check "$W/s2.out" "PASS rollback tags: 11 of 11 point at the running images"
for s in "${PINNED[@]}"; do check "$W/s2.out" "PASS $s release image: pulled;"; done
check "$W/s2.out" "manifest digest = pin"
check "$W/s2.out" "linux/amd64; revision a658edcb0e41"
check "$W/s2.out" "linux/amd64; revision 843ae7cb461b"
check "$W/s2.out" "gateway-service: running"
check "$W/s2.out" "= previous pin named in the release file"
check "$W/s2.out" "$P-analytics-service:latest MOVED since the container started"
check "$W/s2.out" "$P-user-service:latest = running"
check "$W/s2.out" "caddy: running"
check "$W/s2.out" "PASS D2 recreates keep their images"
check "$W/s2.out" "PASS web map guard: web-map-deploy-guard: PASS image=stub"
check "$W/s2.out" "PASS web conf.d check: web-conf-d-guard: PASS"
check "$W/s2.out" "PASS web API endpoint check: web-api-endpoint-guard: PASS web calls <url>"
check "$W/s2.out" "image config: TZ absent; JAVA_TOOL_OPTIONS absent; JDK_JAVA_OPTIONS absent; entrypoint/cmd without user.timezone"
check "$W/s2.out" "release model (parking-service): TZ absent; JAVA_TOOL_OPTIONS without user.timezone; JDK_JAVA_OPTIONS absent; no entrypoint/command override"
check "$W/s2.out" "image files: /etc/timezone absent; /etc/localtime -> /usr/share/zoneinfo/Etc/UTC; probe container removed: yes"
check "$W/s2.out" "JVM resolution order user.timezone, TZ, /etc/timezone, /etc/localtime: Etc/UTC from /etc/localtime link"
check "$W/s2.out" "PASS parking session time zone is UTC: V41's UPDATE then changes no row by construction"
check "$W/s2.out" "busybox:latest: present"
check "$W/s2.out" "$P-no-such:never: absent locally (the D7 build would pull it)"
check "$W/s2.out" "PASS release checkout still clean"
check "$W/s2.out" "P2 COMPLETE"
# --- S3: re-run is idempotent
p2 "$W/release" "$W/expect.json" "$W/s3.out"
rc_is "$W/s3.out" 0
check "$W/s3.out" "rollback tag $P-user-service:$TAG: kept (same image)"
check "$W/s3.out" "$RG/parkio/web:$TAG -> "
check "$W/s3.out" "(kept, verified)"
check "$W/s3.out" "PASS gateway-service release image: present;"
nocheck "$W/s3.out" "(created, verified)"
check "$W/s3.out" "P2 COMPLETE"
# --- S4: release variant with three failing gates
p2 "$W/release2" "$W/expect2.json" "$W/s4.out"
sed "s#$W#<W>#g" "$W/s4.out" | sed -n '/^D\. /,$p'
rc_is "$W/s4.out" 1
check "$W/s4.out" "caddy: running"
check "$W/s4.out" "DIFFERENT image: D2 would change it"
check "$W/s4.out" "FAIL D2 recreates keep their images"
check "$W/s4.out" "FAIL web API endpoint check: web-api-endpoint-guard: BLOCKED: image bakes <url>"
check "$W/s4.out" "image config: TZ Europe/Istanbul;"
check "$W/s4.out" "Europe/Istanbul from TZ"
check "$W/s4.out" "FAIL parking session time zone is UTC: STOP before D6b: V41 decision needed"
check "$W/s4.out" "P2 INCOMPLETE (a gate failed; no service container was touched)"
# --- global properties
for f in "$W"/s*.out "$W"/out/*; do
  for secret in Pw-Synthetic-91 Synthetic-Secret-Token alerts.invalid api.example.invalid wrong.example.invalid; do
    grep -qF -- "$secret" "$f" && bad "$(basename "$f") leaks $secret"
  done
done
ok "no secret, URL or synthetic host in any output or report (checked 5 values)"
modes="$(stat -c %a "$W"/out/* | sort -u | tr '\n' ' ')"; [ "$modes" = "600 " ] && ok "reports mode 600" || bad "report modes $modes"
python3 -c 'import json,sys; [json.load(open(p)) for p in sys.argv[1:]]' "$W"/out/*.json && ok "JSON records parse" || bad "JSON records"
grep -l '"result": "PASS"' "$W"/out/*.json | wc -l | grep -qx 2 && ok "two PASS records (S2, S3)" || bad "PASS record count"
grep -l '"result": "FAIL"' "$W"/out/*.json | wc -l | grep -qx 1 && ok "one FAIL record (S4)" || bad "FAIL record count"
want_stopped=1; [ "$STORE" = containerd ] && want_stopped=2
grep -l '"result": "STOPPED"' "$W"/out/*.json | wc -l | grep -qx "$want_stopped" && ok "$want_stopped STOPPED record(s) (S0, S1)" || bad "STOPPED record count"
snap_ctr | cmp -s - "$W/ctr0" && ok "no service container recreated, started or stopped" || bad "service containers changed"
python3 - "$W/tags0" "$TAG" <<'PY' && ok "existing tags unchanged; only the 11 rollback tags added" || bad "tag snapshot"
import subprocess, sys
before = {l.split(" ")[0]: l.split(" ")[1] for l in open(sys.argv[1]).read().split("\n") if l}
out = subprocess.run(["docker", "images", "--format", "{{.Repository}}:{{.Tag}} {{.ID}}"], capture_output=True, text=True).stdout
after = {l.split(" ")[0]: l.split(" ")[1] for l in out.split("\n") if l and ":<none> " not in l}
moved = [k for k, v in before.items() if after.get(k) != v]
added = [k for k in after if k not in before and (k.startswith("localhost:15731/") or k.startswith("p2probe-"))]
sys.exit(0 if not moved and len(added) == 11 and all(k.endswith(":" + sys.argv[2]) for k in added) else 1)
PY
[ -z "$(docker ps -aq --filter label=parkio.release.probe=p2)" ] && ok "probe containers removed" || bad "probe container left"
[ -z "$(ls -A "$W/tmp")" ] && ok "private temporary directories removed" || bad "temporary files left: $(ls -A "$W/tmp")"
[ -z "$(git -C "$W/release" status --porcelain)" ] && ok "release checkout clean" || bad "release checkout changed"
# --- classic image store branch of the identity check (no daemon involved)
python3 -I - "$TOOL" <<'PY' && ok "classic store identity branch" || bad "classic store identity branch"
import importlib.util, sys
spec = importlib.util.spec_from_file_location("p2", sys.argv[1]); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
ref = "ghcr.io/x/y@sha256:" + "b" * 64; acc = "sha256:" + "a" * 64; rev = "r" * 40
base = {"Id": acc, "RepoDigests": [ref], "Os": "linux", "Architecture": "amd64", "Config": {"Labels": {"org.opencontainers.image.revision": rev}}}
assert m.identity(base, ref, acc, rev) == (True, "image id = accepted config digest; linux/amd64; revision " + rev[:12])
assert not m.identity(dict(base, Id="sha256:" + "c" * 64), ref, acc, rev)[0]
assert not m.identity(dict(base, RepoDigests=[]), ref, acc, rev)[0]
assert not m.identity(dict(base, Architecture="arm64"), ref, acc, rev)[0]
assert not m.identity(dict(base, Config={"Labels": {}}), ref, acc, rev)[0]
cd = dict(base, Id="sha256:" + "b" * 64, Descriptor={"digest": "sha256:" + "b" * 64, "annotations": {"config.digest": "sha256:" + "d" * 64}})
assert not m.identity(cd, ref, acc, rev)[0]
assert m.repository("parkio-user-service") == "parkio-user-service"
assert m.repository("localhost:5000/a/b:tag") == "localhost:5000/a/b"
assert m.repository("ghcr.io/a/b@sha256:" + "e" * 64) == "ghcr.io/a/b"
assert m.repository("sha256:" + "e" * 64) is None
assert m.zone_of(":posix/Europe/Istanbul") == "Europe/Istanbul" and m.zone_of("../usr/share/zoneinfo/Etc/UTC") == "Etc/UTC"
PY
echo "python $(python3 -c 'import sys; print(sys.version.split()[0])'), compose $(docker compose version --short), docker $(docker version --format '{{.Server.Version}}'), store $(docker info --format '{{json .DriverStatus}}' | grep -q containerd && echo containerd || echo classic)"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
