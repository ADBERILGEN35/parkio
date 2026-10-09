#!/usr/bin/env bash
# Local self-test of release-scope.py (D0.2.2) against Compose itself, on a synthetic host-like stack:
# four recorded file lists (Civo list, deploy-wrapper list, an older list, Civo list + a temporary web
# binding that no longer exists), digest-pinned and host-built services, a database that gains read_only,
# a moved relative textfile bind, an image tag that moves, a service dropped from and one added to the
# model, production hostnames, and a second Compose project.
# Pass = the tool's lists equal what Compose does: D2-D7 (--no-deps) recreate exactly the explicit and
# built services, and a whole-project up afterwards recreates exactly the reported drift and creates
# exactly the reported absent services. Local images only (busybox, alpine); cleans up after itself.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/release-scope.py"
P=scopeprobe3; Q=scopeprobe3-nr; W="$(mktemp -d)"
cleanup() { for p in "$P" "$Q"; do docker compose -p "$p" down -v --remove-orphans >/dev/null 2>&1 || true; done
            docker image rm "$P-user-service:latest" "$P-analytics-service:latest" "$P-redis:1" >/dev/null 2>&1 || true
            rm -rf "$W"; }
trap cleanup EXIT
for p in "$P" "$Q"; do [ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$p")" ] || { echo "project $p in use"; exit 2; }; done
BB="$(docker image inspect busybox:latest --format '{{index .RepoDigests 0}}')"
AL="$(docker image inspect alpine:3 --format '{{index .RepoDigests 0}}')"
L="$W/live/docker"; mkdir -p "$L/caddy" "$L/prometheus/textfile"
echo x > "$L/caddy/Caddyfile"; echo y > "$L/prometheus/prometheus.yml"; : > "$L/prometheus/textfile/.gitkeep"
cat > "$L/docker-compose.yml" <<'Y'
name: scopeprobe3
x-sleep: &sleep
  command: ["sh", "-c", "sleep 3600"]
services:
  caddy: {<<: *sleep, image: "busybox:latest", volumes: ["./caddy/Caddyfile:/etc/caddy/Caddyfile:ro"], environment: {PARKIO_DOMAIN: "${PARKIO_DOMAIN}", PARKIO_WEB_DOMAIN: "${PARKIO_WEB_DOMAIN}", PARKIO_MEDIA_DOMAIN: "${PARKIO_MEDIA_DOMAIN}"}}
  prometheus: {<<: *sleep, image: "busybox:latest", volumes: ["./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro"]}
  alertmanager: {<<: *sleep, image: "busybox:latest", profiles: ["azure-disabled-observability"]}
  node-exporter: {<<: *sleep, image: "busybox:latest", volumes: ["./prometheus/textfile:/textfile-collector"]}
  postgres-auth: {<<: *sleep, image: "alpine:3", volumes: ["pg-auth:/var/lib/postgresql/data"]}
  redis: {<<: *sleep, image: "scopeprobe3-redis:1"}
  gateway-service: {<<: *sleep, image: "busybox:latest", build: {context: .}}
  auth-service: {<<: *sleep, image: "busybox:latest", build: {context: .}, environment: {HMAC: "${HMAC:-}"}}
  media-service: {<<: *sleep, image: "busybox:latest", build: {context: .}}
  parking-service: {<<: *sleep, image: "busybox:latest", build: {context: .}}
  web: {<<: *sleep, image: "busybox:latest", build: {context: .}}
  user-service: {<<: *sleep, build: {context: .}}
  analytics-service: {<<: *sleep, build: {context: .}}
  legacy: {<<: *sleep, image: "busybox:latest"}
volumes: {pg-auth: {}}
Y
printf 'name: scopeprobe3\nservices: {}\n' > "$L/docker-compose.azure-hosted-beta.yml"
pins() { printf 'services:\n'; for s in gateway-service auth-service media-service parking-service web; do printf '  %s: {image: "%s"}\n' "$s" "$1"; done; }
pins "$BB" > "$L/docker-compose.pins.yml"
printf 'services:\n  alertmanager:\n    profiles: !reset []\n' > "$L/docker-compose.civo-alertmanager.yml"
printf 'docker/docker-compose.yml\ndocker/docker-compose.azure-hosted-beta.yml\ndocker/docker-compose.pins.yml\n' > "$L/compose.production.files"
HOSTS='PARKIO_DOMAIN=api.parkio.dev\nPARKIO_WEB_DOMAIN=app.parkio.dev\nPARKIO_MEDIA_DOMAIN=media.parkio.dev\n'
printf "$HOSTS" > "$L/.env"
docker tag busybox:latest "$P-user-service:latest"; docker tag busybox:latest "$P-analytics-service:latest"; docker tag busybox:latest "$P-redis:1"
A=(-f "$L/docker-compose.yml" -f "$L/docker-compose.azure-hosted-beta.yml" -f "$L/docker-compose.pins.yml")
q() { docker compose "$@" >/dev/null 2>&1; }
q --env-file "$L/.env" -f "$L/docker-compose.yml" up -d --no-build postgres-auth
q --env-file "$L/.env" "${A[@]}" up -d --no-build --no-deps user-service analytics-service
q --env-file "$L/.env" "${A[@]}" -f "$L/docker-compose.civo-alertmanager.yml" up -d --no-build --no-deps caddy prometheus alertmanager node-exporter redis gateway-service auth-service media-service parking-service legacy
mkdir -p "$W/tmpbind"; printf '{"services": {"web": {"image": "%s", "pull_policy": "never"}}}' "$BB" > "$W/tmpbind/web-binding.yml"
q --env-file "$L/.env" "${A[@]}" -f "$L/docker-compose.civo-alertmanager.yml" -f "$W/tmpbind/web-binding.yml" up -d --no-build --no-deps web
rm -rf "$W/tmpbind"
printf 'services:\n  transport: {image: "busybox:latest", command: ["sh", "-c", "sleep 3600"]}\n' > "$W/nr.yml"
q -p "$Q" -f "$W/nr.yml" up -d
# Release: new pins, database read_only, legacy dropped, kafka added, HMAC added, redis tag moves.
cp -r "$W/live" "$W/release"; R="$W/release/docker"
pins "$AL" > "$R/docker-compose.pins.yml"
sed -i 's#postgres-auth: {<<: \*sleep, image: "alpine:3", volumes#postgres-auth: {<<: *sleep, image: "alpine:3", read_only: true, volumes#' "$R/docker-compose.yml"
sed -i 's#^  legacy: {<<: \*sleep, image: "busybox:latest"}#  kafka: {<<: *sleep, image: "busybox:latest"}#' "$R/docker-compose.yml"
printf "${HOSTS}HMAC=newkey\n" > "$R/.env"
docker tag alpine:3 "$P-redis:1"
python3 -I "$TOOL" --release "$W/release" --env-file "$R/.env" --project "$P" --out "$W/out" > "$W/tool.out"
sed "s#$W#<W>#g" "$W/tool.out"
fail=0
check() { if grep -qF -- "$1" "$W/tool.out"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
check "self-check total: 14/14"
check "D2-D6 recreate 8: ['alertmanager', 'auth-service', 'caddy', 'gateway-service', 'media-service', 'parking-service', 'prometheus', 'web']"
check "D7 recreates 2: ['analytics-service', 'user-service']"
check "drift kept by D2-D7 (3): ['node-exporter', 'postgres-auth', 'redis']"
check "redis [CACHE] L1: DRIFT, kept as is by D2-D7 -- same image reference now resolves to another local image"
check "a whole-project up would also recreate those 3 and create 1 service(s)"
check "hosted-beta profile: REFUSES this env by design"
check "project scopeprobe3-nr: 1 container(s), untouched"
snap() { docker ps -a --filter "label=com.docker.compose.project=$P" --format '{{.Label "com.docker.compose.service"}} {{.ID}}' | sort; }
effect() { join -a1 -a2 -e NONE -o 0,1.2,2.2 "$1" "$2" | awk '$2 != $3 {print $1, ($2 == "NONE" ? "created" : "recreated")}' | sort | tr '\n' ' '; }
C=(-f "$R/docker-compose.yml" -f "$R/docker-compose.azure-hosted-beta.yml" -f "$R/docker-compose.pins.yml" -f "$R/docker-compose.civo-alertmanager.yml")
printf '{"services": {"web": {"image": "%s", "pull_policy": "never"}}}' "$AL" > "$W/bind.yml"
snap > "$W/s0"
for svc in "caddy prometheus alertmanager" gateway-service auth-service media-service parking-service; do
  # shellcheck disable=SC2086
  q --env-file "$R/.env" "${C[@]}" up -d --no-build --no-deps --force-recreate $svc
done
q --env-file "$R/.env" "${C[@]}" -f "$W/bind.yml" up -d --no-build --no-deps --force-recreate web
docker tag alpine:3 "$P-user-service:latest"; docker tag alpine:3 "$P-analytics-service:latest"
q --env-file "$R/.env" "${C[@]}" up -d --no-build --no-deps user-service analytics-service
snap > "$W/s7"
want7="alertmanager recreated analytics-service recreated auth-service recreated caddy recreated gateway-service recreated media-service recreated parking-service recreated prometheus recreated user-service recreated web recreated "
got7="$(effect "$W/s0" "$W/s7")"
if [ "$got7" = "$want7" ]; then echo "ok   D2-D7 recreate exactly the explicit and built services"; else echo "FAIL D2-D7 effect: $got7"; fail=1; fi
q --env-file "$R/.env" "${C[@]}" -f "$W/bind.yml" up -d --no-build
snap > "$W/sw"
wantw="kafka created node-exporter recreated postgres-auth recreated redis recreated "
gotw="$(effect "$W/s7" "$W/sw")"
if [ "$gotw" = "$wantw" ]; then echo "ok   whole-project up afterwards = reported drift + absent"; else echo "FAIL whole-project effect: $gotw"; fail=1; fi
echo "compose $(docker compose version --short), docker $(docker version --format '{{.Server.Version}}'), storage $(docker info --format '{{.Driver}}')"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
