#!/usr/bin/env bash
# Local self-test of p3b-provider-facts.py (P3b) on a synthetic host-like stack: an env file with a duplicated
# push provider line, a placeholder-like Expo token, a set push base URL and a Gemini key; a release Compose model
# wired like docker-compose.apps.yml (compose defaults noop/true/heuristic); running notification-service and
# ai-validation-service containers with their own values; a PostGIS notification database with device tokens and
# push attempts (two marked SENT by the noop sender). Pass = every expected classification and count appears and
# no credential, URL, token or password does. Cleans up after itself.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/p3b-provider-facts.py"
P=p3bprobe; W="$(mktemp -d)"
NAMES=(p3bprobe-notification p3bprobe-aivalidation p3bprobe-postgres-notification)
# Refuse to run (and to clean up anything) when a container of these names already exists.
for n in "${NAMES[@]}"; do
  ids="$(docker ps -aq --filter "name=^$n$" 2>/dev/null | grep -E '^[0-9a-f]{12,64}$' || true)"
  [ -z "$ids" ] || { echo "container $n exists"; rm -rf "$W"; exit 2; }
done
cleanup() { docker rm -f "${NAMES[@]}" >/dev/null 2>&1 || true; rm -rf "$W"; }
trap cleanup EXIT
lbl() { echo --label "com.docker.compose.project=$P" --label "com.docker.compose.service=$1"; }
L="$W/live"; R="$W/release"; mkdir -p "$L/docker" "$R/docker"
ENV="$L/docker/.env.azure-hosted-beta"
cat > "$ENV" <<'E'
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta
PARKIO_PUSH_DELIVERY_PROVIDER=expo
PARKIO_PUSH_DELIVERY_PROVIDER="noop"
PARKIO_EXPO_ACCESS_TOKEN="REPLACE_ME_expo_access_token"
PARKIO_EXPO_PUSH_BASE_URL=https://push.invalid/secret-path-123
PARKIO_AI_VISION_GEMINI_API_KEY=GeminiSecret-Env-555
POSTGRES_NOTIFICATION_PASSWORD=Pw-Synthetic-91
E
printf 'docker/docker-compose.yml\ndocker/docker-compose.azure-hosted-beta.yml\n' > "$R/docker/compose.production.files"
cat > "$R/docker/docker-compose.yml" <<'Y'
name: p3bprobe
services:
  notification-service:
    image: busybox:latest
    environment:
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_NOTIFICATION_PASSWORD}
      PARKIO_PUSH_DELIVERY_ENABLED: ${PARKIO_PUSH_DELIVERY_ENABLED:-true}
      PARKIO_PUSH_DELIVERY_PROVIDER: ${PARKIO_PUSH_DELIVERY_PROVIDER:-noop}
      PARKIO_EXPO_ACCESS_TOKEN: ${PARKIO_EXPO_ACCESS_TOKEN:-}
  ai-validation-service:
    image: busybox:latest
    environment:
      PARKIO_AI_VISION_PROVIDER: ${PARKIO_AI_VISION_PROVIDER:-heuristic}
      PARKIO_AI_VISION_GEMINI_API_KEY: ${PARKIO_AI_VISION_GEMINI_API_KEY:-}
Y
printf 'services: {}\n' > "$R/docker/docker-compose.azure-hosted-beta.yml"
printf 'services: {}\n' > "$R/docker/docker-compose.civo-alertmanager.yml"
git -C "$R" init -q && git -C "$R" add -A && git -C "$R" -c user.name=t -c user.email=t@t commit -qm release
# shellcheck disable=SC2046
docker run -d --name p3bprobe-notification $(lbl notification-service) --network none -e PARKIO_PUSH_DELIVERY_PROVIDER=noop \
  -e PARKIO_PUSH_DELIVERY_ENABLED=true -e PARKIO_EXPO_ACCESS_TOKEN= busybox:latest sleep 3600 >/dev/null
# shellcheck disable=SC2046
docker run -d --name p3bprobe-aivalidation $(lbl ai-validation-service) --network none -e PARKIO_AI_VISION_PROVIDER=weird-one \
  -e PARKIO_AI_VISION_GEMINI_API_KEY=GeminiSecret-Live-777 busybox:latest sleep 3600 >/dev/null
# shellcheck disable=SC2046
docker run -d --name p3bprobe-postgres-notification $(lbl postgres-notification) -e POSTGRES_USER=pk \
  -e POSTGRES_PASSWORD=Pw-Synthetic-91 -e POSTGRES_DB=parkio_notification postgis/postgis:16-3.4 >/dev/null
for _ in $(seq 60); do docker exec p3bprobe-postgres-notification pg_isready -U pk -d parkio_notification >/dev/null 2>&1 && break; sleep 1; done
sleep 3
docker exec -i p3bprobe-postgres-notification psql -q -v ON_ERROR_STOP=1 -U pk -d parkio_notification -f - >/dev/null <<'SQL'
create table device_tokens (id uuid primary key, user_id uuid not null, token varchar(512) not null, platform varchar(16) not null,
  active boolean not null default true);
create table notification_delivery_attempts (id uuid primary key, channel varchar(16) not null, device_token_id uuid,
  status varchar(16) not null, provider_message_id varchar(255), attempted_at timestamptz, created_at timestamptz not null default now());
insert into device_tokens values
  (gen_random_uuid(), gen_random_uuid(), 'ExponentPushToken[SyntheticSecretTokA]', 'ANDROID', true),
  (gen_random_uuid(), gen_random_uuid(), 'ExponentPushToken[SyntheticSecretTokB]', 'ANDROID', true),
  (gen_random_uuid(), gen_random_uuid(), 'ExponentPushToken[SyntheticSecretTokC]', 'IOS', false);
insert into notification_delivery_attempts (id, channel, status, provider_message_id, attempted_at) values
  (gen_random_uuid(), 'PUSH', 'SENT', 'noop-1', '2026-10-01T10:00:00Z'),
  (gen_random_uuid(), 'PUSH', 'SENT', 'noop-2', '2026-10-02T10:00:00Z'),
  (gen_random_uuid(), 'PUSH', 'SENT', 'expo-x', '2026-09-01T10:00:00Z'),
  (gen_random_uuid(), 'PUSH', 'PENDING', null, null),
  (gen_random_uuid(), 'PUSH', 'SKIPPED', null, '2026-09-30T10:00:00Z'),
  (gen_random_uuid(), 'EMAIL', 'SENT', 'noop-3', '2026-10-03T10:00:00Z');
SQL
P3B_LIVE="$L" P3B_RELEASE="$R" P3B_ENV_FILE="$ENV" P3B_PROJECT="$P" P3B_OUT="$W/out" P3B_PG_PREFIX=p3bprobe-postgres- \
  P3B_PIN_ANY=1 python3 -I "$TOOL" > "$W/tool.out" 2>&1 && rc=0 || rc=$?
sed "s#$W#<W>#g" "$W/tool.out"
fail=0
check() { if grep -qF -- "$1" "$W/tool.out"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
[ "$rc" = 0 ] && echo "ok   exit 0" || { echo "FAIL exit $rc"; fail=1; }
check "PARKIO_PUSH_DELIVERY_PROVIDER: noop (2 lines; the last one counts)"
check "PARKIO_PUSH_DELIVERY_ENABLED: absent"
check "PARKIO_EXPO_ACCESS_TOKEN: placeholder-like"
check "PARKIO_EXPO_PUSH_BASE_URL: set"
check "PARKIO_AI_VISION_PROVIDER: absent"
check "PARKIO_AI_VISION_GEMINI_API_KEY: set"
check "PARKIO_DEPLOYMENT_PROFILE: azure-hosted-beta"
check "PARKIO_PREFLIGHT_ALLOW_PROVIDER_OVERRIDE in the file: absent"
check "notification-service: PARKIO_PUSH_DELIVERY_PROVIDER noop; PARKIO_PUSH_DELIVERY_ENABLED true; PARKIO_EXPO_ACCESS_TOKEN placeholder-like; PARKIO_EXPO_PUSH_BASE_URL absent"
check "ai-validation-service: PARKIO_AI_VISION_PROVIDER heuristic; PARKIO_AI_VISION_GEMINI_API_KEY set"
check "notification-service (started "
check "PARKIO_PUSH_DELIVERY_PROVIDER noop; PARKIO_PUSH_DELIVERY_ENABLED true; PARKIO_EXPO_ACCESS_TOKEN empty; PARKIO_EXPO_PUSH_BASE_URL absent"
check "PARKIO_AI_VISION_PROVIDER <other value>; PARKIO_AI_VISION_GEMINI_API_KEY set"
check "device tokens: active 2, inactive 1 (ANDROID active 2, IOS inactive 1)"
check "PUSH delivery attempts by status: PENDING 1, latest "
check "SENT 3 (by the noop sender 2), latest 2026-10-02"
check "SKIPPED 1, latest 2026-09-30"
check "notification-service, provider expo: startup fails without PARKIO_EXPO_ACCESS_TOKEN (ExpoPushConfig)"
check "P3b COMPLETE (read-only)"
for secret in REPLACE_ME push.invalid secret-path-123 GeminiSecret Pw-Synthetic-91 SyntheticSecretTok weird-one; do
  if grep -qF -- "$secret" "$W/tool.out" "$W"/out/*; then echo "FAIL leaked: $secret"; fail=1; fi
done
echo "ok   no credential, URL, token, password or unlisted provider value printed (checked 7 values)"
mode="$(stat -c %a "$W"/out/P3b-provider-facts-*.txt)"; [ "$mode" = 600 ] && echo "ok   report mode 600" || { echo "FAIL report mode $mode"; fail=1; }
[ -z "$(git -C "$R" status --porcelain)" ] && echo "ok   release checkout unchanged" || { echo "FAIL release checkout changed"; fail=1; }
echo "python $(python3 -c 'import sys; print(sys.version.split()[0])'), compose $(docker compose version --short), docker $(docker version --format '{{.Server.Version}}')"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
