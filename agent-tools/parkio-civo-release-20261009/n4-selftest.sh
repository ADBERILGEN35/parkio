#!/usr/bin/env bash
# Self-test of n4-push-recovery.py against real Docker. A small Compose project wired like docker-compose.apps.yml
# (notification-service takes PARKIO_PUSH_DELIVERY_* and PARKIO_EXPO_ACCESS_TOKEN by interpolation; another service takes
# the HMAC key) runs two busybox containers; the env file carries provider expo, delivery enabled, a token, the HMAC key and
# an empty _PREVIOUS line, beside a P3c-like backup without a token. A fake production wrapper calls docker compose.
# C1 check -> exit 0: classification only, the simulated change touches notification-service only, nothing written;
# D1 disable while the running image is not the release image -> STOP, nothing changed; D2 disable -> only the ENABLED line
# changes, identical backup, notification-service recreated and healthy with delivery disabled, the other container
# untouched; D3 a second disable -> STOP (already false); D4 two ENABLED lines -> STOP before any change; U1 usage -> 2.
# No token, HMAC key or other secret value appears in any output or report.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/n4-push-recovery.py"
P="n4p$$"; W="$(mktemp -d)"; TAG="n4probe-release/notification-service:sha-test$$"
cleanup() {
  (cd "$W/release" 2>/dev/null && docker compose --env-file "$W/live/docker/.env.azure-hosted-beta" -f docker/docker-compose.yml down -t 1 >/dev/null 2>&1) || true
  docker image rm "$TAG" >/dev/null 2>&1 || true
  rm -rf "${W:?}"
}
trap cleanup EXIT
mkdir -p "$W/live/docker" "$W/release/docker" "$W/release/scripts" "$W/out"
ENV="$W/live/docker/.env.azure-hosted-beta"
TOKENV="ExpoTok$(openssl rand -hex 16)"; HMACV="Hmac$(openssl rand -hex 24)"
printf 'PARKIO_DOMAIN=parkio.example\nPARKIO_PUSH_DELIVERY_PROVIDER=expo\nPARKIO_PUSH_DELIVERY_ENABLED=true\nPARKIO_EXPO_ACCESS_TOKEN=%s\nPARKIO_LOGIN_THROTTLE_HMAC_KEY=%s\nPARKIO_LOGIN_THROTTLE_HMAC_KEY_PREVIOUS=\nOTHER_SETTING=kept\n' "$TOKENV" "$HMACV" > "$ENV"
chmod 600 "$ENV"
printf 'PARKIO_DOMAIN=parkio.example\nPARKIO_PUSH_DELIVERY_PROVIDER=noop\nPARKIO_EXPO_ACCESS_TOKEN=\nPARKIO_LOGIN_THROTTLE_HMAC_KEY=%s\nPARKIO_LOGIN_THROTTLE_HMAC_KEY_PREVIOUS=\nOTHER_SETTING=kept\n' "$HMACV" > "$ENV.bak-20261010T120000Z"
chmod 600 "$ENV.bak-20261010T120000Z"
echo "docker/docker-compose.yml" > "$W/release/docker/compose.production.files"
cat > "$W/release/docker/docker-compose.yml" <<EOF
name: $P
services:
  notification-service:
    image: busybox:latest
    command: ["sleep", "3600"]
    environment:
      PARKIO_PUSH_DELIVERY_ENABLED: \${PARKIO_PUSH_DELIVERY_ENABLED:-true}
      PARKIO_PUSH_DELIVERY_PROVIDER: \${PARKIO_PUSH_DELIVERY_PROVIDER:-noop}
      PARKIO_EXPO_ACCESS_TOKEN: \${PARKIO_EXPO_ACCESS_TOKEN:-}
    healthcheck:
      test: ["CMD", "true"]
      interval: 1s
      retries: 3
  other-service:
    image: busybox:latest
    command: ["sleep", "3600"]
    environment:
      THROTTLE_KEY: \${PARKIO_LOGIN_THROTTLE_HMAC_KEY:-}
      SETTING: \${OTHER_SETTING:-}
EOF
cat > "$W/release/scripts/parkio-prod-compose.sh" <<'EOF'
#!/usr/bin/env bash
cd "$(dirname "$0")/.." && exec docker compose --env-file "$PARKIO_ENV_FILE" -f docker/docker-compose.yml "$@"
EOF
chmod +x "$W/release/scripts/parkio-prod-compose.sh"
(cd "$W/release" && docker compose --env-file "$ENV" -f docker/docker-compose.yml up -d >/dev/null 2>&1)
for i in $(seq 1 30); do [ "$(docker inspect -f '{{.State.Health.Status}}' "$P-notification-service-1" 2>/dev/null)" = healthy ] && break; sleep 1; done
docker tag busybox:latest "$TAG"
export N4_LIVE="$W/live" N4_RELEASE="$W/release" N4_ENV_FILE="$ENV" N4_PROJECT="$P" N4_OUT="$W/out" N4_HEALTH_TIMEOUT=60
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
check() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && ok "$1 exit $2" || no "$1 exit $(cat "$W/$1.rc"), want $2"; }
runit() { local name="$1"; shift; env "$@" python3 -I "$TOOL" ${N4ARGS} > "$W/$name" 2>&1 && echo 0 > "$W/$name.rc" || echo $? > "$W/$name.rc"; }
secrets() { if grep -qF -e "$TOKENV" -e "$HMACV" "$W/$1" "$W"/out/N4-*.txt; then no "$1: a secret value in the output or a report"; else ok "$1: no secret value in the output or the reports"; fi; }
cid() { docker ps -q --no-trunc --filter "label=com.docker.compose.project=$P" --filter "label=com.docker.compose.service=$1"; }
sum() { sha256sum "$ENV" | cut -d' ' -f1; }

S0="$(sum)"; N0="$(cid notification-service)"; O0="$(cid other-service)"
N4ARGS="check" runit c1.out N4_RELEASE_TAG="$TAG"; sed -e "s#$W#<W>#g" -e "s#$P#<p>#g" -e "s#owner $(id -un)#owner <user>#g" "$W/c1.out"; rc_is c1.out 0
check c1.out "PARKIO_PUSH_DELIVERY_PROVIDER: expo (1 line)"
check c1.out "PARKIO_PUSH_DELIVERY_ENABLED: true (1 line)"
check c1.out "PARKIO_EXPO_ACCESS_TOKEN: set (1 line)"
check c1.out "PARKIO_LOGIN_THROTTLE_HMAC_KEY: set (left untouched)"
check c1.out "PARKIO_LOGIN_THROTTLE_HMAC_KEY_PREVIOUS: empty (left untouched)"
check c1.out "PASS the HMAC key is set and its _PREVIOUS line is present"
check c1.out ".bak-20261010T120000Z: mode 600; PARKIO_PUSH_DELIVERY_PROVIDER noop; PARKIO_PUSH_DELIVERY_ENABLED absent; PARKIO_EXPO_ACCESS_TOKEN empty"
check c1.out "its push settings: PARKIO_PUSH_DELIVERY_PROVIDER expo; PARKIO_PUSH_DELIVERY_ENABLED true; PARKIO_EXPO_ACCESS_TOKEN set"
check c1.out "running container = release model config: yes"
check c1.out "PASS notification-service would get PARKIO_PUSH_DELIVERY_ENABLED=false, provider and token unchanged"
check c1.out "PASS only notification-service's config hash would change: changed: notification-service"
check c1.out "N4 CHECK COMPLETE"
secrets c1.out
[ "$(sum)" = "$S0" ] && [ "$(cid notification-service)" = "$N0" ] && ok "c1: env file and containers unchanged" || no "c1 changed something"
[ "$(ls -A "$W/live/docker" | grep -c 'bak-n4')" = 0 ] && ok "c1: no backup written" || no "c1 wrote a backup"

N4ARGS="disable --push-incident" runit d1.out N4_RELEASE_TAG="n4probe-missing/notification-service:none"; rc_is d1.out 1
check d1.out "is not the release image"; check d1.out "nothing was changed"
[ "$(sum)" = "$S0" ] && [ "$(cid notification-service)" = "$N0" ] && ok "d1: env file and container unchanged" || no "d1 changed something"

N4ARGS="disable --push-incident" runit d2.out N4_RELEASE_TAG="$TAG"; sed -e "s#$W#<W>#g" -e "s#$P#<p>#g" -e "s#owner $(id -un)#owner <user>#g" "$W/d2.out"; rc_is d2.out 0
check d2.out "PASS env file: only PARKIO_PUSH_DELIVERY_ENABLED changed (now false)"
check d2.out "PASS only notification-service's release config changes: changed: notification-service"
check d2.out "PASS wrapper exit 0"
check d2.out "PASS notification-service recreated and healthy within 60 s"
check d2.out "PASS notification-service runs the same release image"
check d2.out "PASS its environment: delivery disabled, provider and token unchanged"
check d2.out "PASS no other container of the project changed"
check d2.out "N4 DISABLE COMPLETE"
secrets d2.out
B="$(ls -d "$W"/live/docker/.env.azure-hosted-beta.bak-n4-* | head -1)"
python3 - "$ENV" "$B" <<'PY' && ok "d2: the env file differs from the backup in exactly the ENABLED line; the backup equals the original" || no "d2 diff"
import sys
new, old = open(sys.argv[1], "rb").read().splitlines(), open(sys.argv[2], "rb").read().splitlines()
diff = [(a, b) for a, b in zip(old, new) if a != b]
assert len(old) == len(new) and diff == [(b"PARKIO_PUSH_DELIVERY_ENABLED=true", b"PARKIO_PUSH_DELIVERY_ENABLED=false")], diff
PY
[ "$(sha256sum "$B" | cut -d' ' -f1)" = "$S0" ] && [ "$(stat -c %a "$B")" = 600 ] && ok "d2: backup identical to the original, mode 600" || no "d2 backup"
[ "$(cid notification-service)" != "$N0" ] && [ "$(cid other-service)" = "$O0" ] && ok "d2: only notification-service was recreated" || no "d2 containers"
[ "$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$(cid notification-service)" | grep -c '^PARKIO_PUSH_DELIVERY_ENABLED=false$')" = 1 ] \
  && ok "d2: the new container has delivery disabled" || no "d2 container env"

S1="$(sum)"; N4ARGS="disable --push-incident" runit d3.out N4_RELEASE_TAG="$TAG"; rc_is d3.out 1
check d3.out "is already false; nothing to change"; [ "$(sum)" = "$S1" ] && ok "d3: nothing changed" || no "d3 changed the file"
printf 'PARKIO_PUSH_DELIVERY_ENABLED=true\n' >> "$ENV"; S2="$(sum)"
N4ARGS="disable --push-incident" runit d4.out N4_RELEASE_TAG="$TAG"; rc_is d4.out 1
check d4.out "must be on at most one standard line (found 2"; [ "$(sum)" = "$S2" ] && ok "d4: nothing changed" || no "d4 changed the file"
N4ARGS="bogus" runit u1.out; rc_is u1.out 2; check u1.out "usage: n4-push-recovery.py check | disable --push-incident"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
