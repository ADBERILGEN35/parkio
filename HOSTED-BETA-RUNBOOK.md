# Parkio Hosted-Beta Runbook

Single-operator runbook for deploying Parkio hosted beta on one VPS after Oracle
capacity is available. This is deployment readiness, not public production. Do
not use this as a high-availability production plan.

## Printable One-Page Cheat Sheet

```bash
# 0. Required operator inputs
# VPS public IP, SSH user, repo URL/branch, PARKIO_WEB_DOMAIN, PARKIO_DOMAIN,
# PARKIO_MEDIA_DOMAIN, ACME email, Resend key, Expo token, alert webhook,
# generated JWT key, gateway secret, DB/Redis/MinIO/Grafana/waitlist secrets.

# 1. DNS and firewall
# A/AAAA: app.example -> VPS, api.example -> VPS, media.example -> VPS
sudo ufw default deny incoming
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable

# 2. Repo and env
git clone <repo-url> /opt/parkio
cd /opt/parkio
cp docker/.env.hosted-beta.example docker/.env
chmod 600 docker/.env
$EDITOR docker/.env

# 3. Validate before any deploy
PARKIO_ENV_FILE=docker/.env ./scripts/preflight-hosted-beta.sh

# 4. Deploy through the canonical script
PARKIO_ENV_FILE=docker/.env \
PARKIO_GATEWAY_URL=https://<PARKIO_DOMAIN> \
PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 \
./scripts/deploy-hosted-beta.sh --operator "<name>"

# 5. Verify
curl -fsS https://<PARKIO_WEB_DOMAIN>/
curl -fsS https://<PARKIO_DOMAIN>/actuator/health
curl -fsS https://<PARKIO_DOMAIN>/api/v1/auth/.well-known/jwks.json
jq -r .gitSha deploy-artifacts/current.json

# 6. Monitoring tunnel
ssh -L 3000:localhost:3000 -L 9090:localhost:9090 -L 9093:localhost:9093 \
    -L 3100:localhost:3100 -L 3200:localhost:3200 -L 9308:localhost:9308 \
    <user>@<vps-ip>

# 7. Backup and rollback
PARKIO_ENV_FILE=docker/.env ./scripts/backup-hosted-beta.sh
PARKIO_ENV_FILE=docker/.env PARKIO_GATEWAY_URL=https://<PARKIO_DOMAIN> \
  ./scripts/rollback-hosted-beta.sh --manifest deploy-artifacts/<previous>.json
```

## Current Deployment Model

| Area | Decision |
|------|----------|
| Host | Single VPS hosted beta; not public production |
| Public ingress | Caddy only, publishing `80` and `443` |
| Public hosts | Web, API, and media each use a DNS hostname |
| TLS | Caddy automatic ACME; `caddy-data` volume must persist |
| Apps | Built from current git commit by `scripts/deploy-hosted-beta.sh` |
| Data | One PostgreSQL container per service, Redis, Kafka, MinIO |
| Observability | Prometheus, Grafana, Loki, Promtail, Tempo, Alertmanager |
| Admin access | SSH tunnel only for observability and loopback-bound exporters |
| Rollback | Manifest-based rollback to existing immutable `sha-<gitsha>` images |
| Backups | Logical Postgres dumps plus MinIO mirror and JSON manifest |

Recommended host from repository sizing docs: 8 vCPU / 24 GB RAM. If the
available Oracle A1 shape is smaller, treat it as a constrained beta host: watch
CPU, disk, ClamAV memory, Kafka heap, and consider moving observability off-box.

## Operator Inputs

Collect these before touching the VPS:

| Input | Example | Notes |
|-------|---------|-------|
| VPS public IP | `203.0.113.10` | Static IP preferred |
| SSH user | `ubuntu` | Needs Docker permission |
| Git ref | `main` or tag | Deploy script refuses dirty tree unless overridden |
| Web domain | `app.beta.parkio.dev` | DNS A/AAAA to VPS |
| API domain | `api.beta.parkio.dev` | DNS A/AAAA to VPS |
| Media domain | `media.beta.parkio.dev` | DNS A/AAAA to VPS |
| ACME email | `ops@example.com` | Let's Encrypt notices |
| Resend API key | `re_...` | Required for auth emails |
| Expo access token | `...` | Required for mobile push provider |
| Alert webhook | Slack or generic HTTPS | Required unless explicitly acknowledged |
| Secrets | Generated values | See environment variable inventory |
| Seed account | beta test user | Required for full authenticated smoke |

## Host Preparation Checklist

- Ubuntu 22.04 or 24.04 LTS.
- NTP enabled.
- Security updates enabled.
- Docker Engine 24+ installed and running.
- Docker Compose v2.24.4+; the hosted overlay uses `!reset` / `!override`.
- `git`, `curl`, `jq`, and `openssl` installed.
- Ports `80/tcp` and `443/tcp` reachable from the internet.
- Port `22/tcp` limited by SSH policy as appropriate.
- No public exposure of PostgreSQL, Redis, Kafka, MinIO, Grafana, Prometheus,
  Loki, Tempo, Alertmanager, app service ports, or gateway `8080`.

## DNS Checklist

Create A and/or AAAA records before deployment:

| Host | Points to | Used by |
|------|-----------|---------|
| `PARKIO_WEB_DOMAIN` | VPS public IP | Browser SPA |
| `PARKIO_DOMAIN` | VPS public IP | API through Caddy |
| `PARKIO_MEDIA_DOMAIN` | VPS public IP | Presigned media GET URLs |

Wait for DNS propagation before the first Caddy start. If CAA records are used,
allow Let's Encrypt. Do not use `localhost`, `127.0.0.1`, `10.0.2.2`,
`*.local`, or example domains in hosted beta.

## Repository Setup

```bash
git clone <repo-url> /opt/parkio
cd /opt/parkio
git checkout <branch-or-tag>
git status --short
```

The deploy script requires a clean tree by default. Use `--allow-dirty` only for
an intentional emergency deploy and record why.

## Environment File Setup

```bash
cd /opt/parkio
cp docker/.env.hosted-beta.example docker/.env
chmod 600 docker/.env
$EDITOR docker/.env
```

Rules:

- Replace every `CHANGE_ME`.
- Keep values containing spaces or `<...>` quoted, for example
  `PARKIO_EMAIL_FROM="Parkio <verify@example.com>"`.
- Never commit `docker/.env`.
- Keep a copy in a real secrets manager or equivalent private operator store.
- Use distinct database passwords per service.
- Keep `PARKIO_OPENAPI_ENABLED=false`, token logging disabled, test hooks
  disabled, and ClamAV enabled.

## Environment Variable Inventory

Legend: `Req` means required for hosted beta. `Secret` means never commit the
real value.

### Domains, TLS, Proxy

| Variable | Req | Default / example | Secret |
|----------|-----|-------------------|--------|
| `PARKIO_DOMAIN` | Yes | API FQDN, bare hostname | No |
| `PARKIO_WEB_DOMAIN` | Yes | Web FQDN, bare hostname | No |
| `PARKIO_WEB_UPSTREAM` | Yes | `web:80` | No |
| `PARKIO_MEDIA_DOMAIN` | Yes | Media FQDN, bare hostname | No |
| `PARKIO_MAP_CONNECT_SRC` | Optional | `https://api.maptiler.com` | No |
| `PARKIO_ACME_EMAIL` | Yes | Operator email | No |
| `PARKIO_MAX_UPLOAD_SIZE` | Optional | `25MB` | No |
| `PARKIO_TRUSTED_PROXIES` | Yes | Docker/private CIDRs only | No |

### CORS and JWT

| Variable | Req | Default / example | Secret |
|----------|-----|-------------------|--------|
| `PARKIO_CORS_ALLOWED_ORIGINS` | Yes | `https://<PARKIO_WEB_DOMAIN>` | No |
| `PARKIO_CORS_ALLOW_CREDENTIALS` | Yes | `true` | No |
| `PARKIO_JWT_PRIVATE_KEY_PEM` | Yes | PKCS#8 private key with `\n` escapes | Yes |
| `PARKIO_JWT_KEY_ID` | Yes | `parkio-auth-rs256-beta-1` | No |
| `PARKIO_JWT_ISSUER` | Yes | `parkio-auth` | No |
| `PARKIO_JWT_AUDIENCE` | Yes | `parkio-api` | No |
| `PARKIO_JWT_CLOCK_SKEW_SECONDS` | Optional | `30` | No |
| `PARKIO_JWT_ADDITIONAL_PUBLIC_KEYS_JSON` | Optional | blank unless rotating | No |

### Gateway, Email, Push

| Variable | Req | Default / example | Secret |
|----------|-----|-------------------|--------|
| `PARKIO_GATEWAY_INTERNAL_SECRET` | Yes | `openssl rand -base64 48` | Yes |
| `PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS` | Optional | blank unless rotating | Yes |
| `PARKIO_EMAIL_PROVIDER` | Yes | `resend` | No |
| `PARKIO_RESEND_API_KEY` | Yes | `re_...` | Yes |
| `PARKIO_EMAIL_FROM` | Yes | `"Parkio <verify@...>"` | No |
| `PARKIO_EMAIL_REPLY_TO` | Yes | Support email | No |
| `PARKIO_EMAIL_VERIFICATION_LOG_TOKEN` | Yes | `false` | No |
| `PARKIO_PASSWORD_RESET_LOG_TOKEN` | Yes | `false` | No |
| `PARKIO_PUSH_DELIVERY_PROVIDER` | Yes | `expo` | No |
| `PARKIO_EXPO_ACCESS_TOKEN` | Yes | Expo access token | Yes |
| `PARKIO_OPENAPI_ENABLED` | Yes | `false` | No |

### Frontend Build

| Variable | Req | Default / example | Secret |
|----------|-----|-------------------|--------|
| `VITE_API_BASE_URL` | Yes | `https://<PARKIO_DOMAIN>/api/v1` | No |
| `VITE_APP_ENV` | Yes | `hosted-beta` | No |
| `VITE_SMART_RETURN_ENABLED` | Optional | `true` | No |
| `VITE_MAPTILER_KEY` | Optional | blank | Yes |
| `VITE_MAPTILER_STYLE` | Optional | `streets-v2` | No |
| `VITE_FRONTEND_ERROR_REPORTING` | Optional | `disabled` | No |

### Waitlist and Databases

| Variable | Req | Default / example | Secret |
|----------|-----|-------------------|--------|
| `PARKIO_WAITLIST_HASH_SECRET` | Yes | 32+ char random value | Yes |
| `POSTGRES_AUTH_DB` | Optional | `parkio_auth` | No |
| `POSTGRES_AUTH_USER` | Optional | `parkio_auth` | No |
| `POSTGRES_AUTH_PASSWORD` | Yes | random 16+ chars | Yes |
| `POSTGRES_GATEWAY_DB` | Optional | `parkio_gateway` | No |
| `POSTGRES_GATEWAY_USER` | Optional | `parkio_gateway` | No |
| `POSTGRES_GATEWAY_PASSWORD` | Yes | random 16+ chars | Yes |
| `POSTGRES_USER_DB` | Optional | `parkio_user` | No |
| `POSTGRES_USER_USER` | Optional | `parkio_user` | No |
| `POSTGRES_USER_PASSWORD` | Yes | random 16+ chars | Yes |
| `POSTGRES_PARKING_DB` | Optional | `parkio_parking` | No |
| `POSTGRES_PARKING_USER` | Optional | `parkio_parking` | No |
| `POSTGRES_PARKING_PASSWORD` | Yes | random 16+ chars | Yes |
| `POSTGRES_MEDIA_DB` | Optional | `parkio_media` | No |
| `POSTGRES_MEDIA_USER` | Optional | `parkio_media` | No |
| `POSTGRES_MEDIA_PASSWORD` | Yes | random 16+ chars | Yes |
| `POSTGRES_GAMIFICATION_DB` | Optional | `parkio_gamification` | No |
| `POSTGRES_GAMIFICATION_USER` | Optional | `parkio_gamification` | No |
| `POSTGRES_GAMIFICATION_PASSWORD` | Yes | random 16+ chars | Yes |
| `POSTGRES_NOTIFICATION_DB` | Optional | `parkio_notification` | No |
| `POSTGRES_NOTIFICATION_USER` | Optional | `parkio_notification` | No |
| `POSTGRES_NOTIFICATION_PASSWORD` | Yes | random 16+ chars | Yes |
| `POSTGRES_MODERATION_DB` | Optional | `parkio_moderation` | No |
| `POSTGRES_MODERATION_USER` | Optional | `parkio_moderation` | No |
| `POSTGRES_MODERATION_PASSWORD` | Yes | random 16+ chars | Yes |
| `POSTGRES_ANALYTICS_DB` | Optional | `parkio_analytics` | No |
| `POSTGRES_ANALYTICS_USER` | Optional | `parkio_analytics` | No |
| `POSTGRES_ANALYTICS_PASSWORD` | Yes | random 16+ chars | Yes |
| `POSTGRES_AIVALIDATION_DB` | Optional | `parkio_aivalidation` | No |
| `POSTGRES_AIVALIDATION_USER` | Optional | `parkio_aivalidation` | No |
| `POSTGRES_AIVALIDATION_PASSWORD` | Yes | random 16+ chars | Yes |
| `POSTGRES_*_PORT` | Optional | local parity only; not public in hosted overlay | No |

### Redis, Kafka, MinIO, Scanning

| Variable | Req | Default / example | Secret |
|----------|-----|-------------------|--------|
| `REDIS_PORT` | Optional | `6379` | No |
| `REDIS_PASSWORD` | Yes | random 16+ chars | Yes |
| `KAFKA_CLUSTER_ID` | Yes | `kafka-storage random-uuid` | No |
| `KAFKA_EXTERNAL_PORT` | Optional | `29092`; not public in hosted overlay | No |
| `KAFKA_EXPORTER_PORT` | Optional | `9308` loopback only | No |
| `MINIO_ROOT_USER` | Optional | `parkio` | No |
| `MINIO_ROOT_PASSWORD` | Yes | random 16+ chars | Yes |
| `MINIO_BUCKET` | Optional | `parkio-media` | No |
| `MINIO_API_PORT` | Optional | `9000`; not public in hosted overlay | No |
| `MINIO_CONSOLE_PORT` | Optional | `9001`; not public in hosted overlay | No |
| `PARKIO_MEDIA_STORAGE_ENDPOINT` | Yes | `http://minio:9000` | No |
| `PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT` | Yes | `https://<PARKIO_MEDIA_DOMAIN>` | No |
| `PARKIO_MEDIA_SCANNER_ENABLED` | Yes | `true` | No |
| `PARKIO_MEDIA_SCANNER_HOST` | Optional | `clamav` | No |
| `PARKIO_MEDIA_SCANNER_PORT` | Optional | `3310` | No |
| `PARKIO_MEDIA_SCANNER_CONNECT_TIMEOUT` | Optional | `2s` | No |
| `PARKIO_MEDIA_SCANNER_READ_TIMEOUT` | Optional | `10s` | No |

### Features and Observability

| Variable | Req | Default / example | Secret |
|----------|-----|-------------------|--------|
| `PARKIO_SMART_RETURN_ENABLED` | Optional | `true` for controlled beta | No |
| `PARKIO_SMART_RETURN_SCHEDULER_ENABLED` | Optional | `true` for controlled beta | No |
| `PARKIO_SMART_RETURN_TEST_HOOKS_ENABLED` | Yes | `false` | No |
| `NODE_EXPORTER_PORT` | Optional | `9100` loopback only | No |
| `BLACKBOX_EXPORTER_PORT` | Optional | `9115` loopback only | No |
| `PROMETHEUS_PORT` | Optional | `9090` loopback only | No |
| `ALERTMANAGER_PORT` | Optional | `9093` loopback only | No |
| `LOKI_PORT` | Optional | `3100` loopback only | No |
| `PROMTAIL_PORT` | Optional | `9080` loopback only | No |
| `TEMPO_PORT` | Optional | `3200` loopback only | No |
| `GRAFANA_PORT` | Optional | `3000` loopback only | No |
| `GRAFANA_ADMIN_USER` | Optional | `admin` | No |
| `GRAFANA_ADMIN_PASSWORD` | Yes | random 12+ chars | Yes |
| `PARKIO_ENVIRONMENT` | Yes | `hosted-beta` | No |
| `PARKIO_LOKI_RETENTION_PERIOD` | Optional | `168h` | No |
| `PARKIO_TRACING_ENABLED` | Optional | `true` | No |
| `PARKIO_TRACING_SAMPLING_PROBABILITY` | Optional | `1.0` | No |
| `PARKIO_TRACING_OTLP_ENDPOINT` | Optional | `http://tempo:4318/v1/traces` | No |
| `PARKIO_TEMPO_RETENTION` | Optional | `48h` | No |
| `PARKIO_ALERT_SLACK_WEBHOOK_URL` | Yes* | Slack URL, or use generic webhook | Yes |
| `PARKIO_ALERT_SLACK_CHANNEL` | Optional | `#parkio-alerts` | No |
| `PARKIO_ALERT_WEBHOOK_URL` | Yes* | Generic HTTPS webhook alternative | Secret if private |
| `PARKIO_ALERT_WEBHOOK_SECRET` | Optional | Bearer token for generic webhook | Yes |
| `PARKIO_ALERT_REPEAT_CRITICAL` | Optional | `1h` | No |
| `PARKIO_ALERT_REPEAT_WARNING` | Optional | `4h` | No |

`*` At least one alert webhook should be set. To intentionally run without
outbound alerts, set `PARKIO_PREFLIGHT_ALLOW_NO_ALERT_WEBHOOK=1` only for the
preflight command and record the decision.

### Backup and Operator Script Variables

| Variable | Req | Default / example | Secret |
|----------|-----|-------------------|--------|
| `BACKUP_DIR` | Yes | `/var/backups/parkio` | No |
| `BACKUP_ENCRYPT_PASSPHRASE` | Optional | blank; recommended if uploading | Yes |
| `BACKUP_MC_DEST` | Optional | `s3/parkio-backups` | No |
| `BACKUP_RETENTION_DAYS` | Optional | `14` | No |
| `PARKIO_ENV_FILE` | Yes for scripts | `docker/.env` | No |
| `PARKIO_GATEWAY_URL` | Yes for smoke | `https://<PARKIO_DOMAIN>` | No |
| `PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED` | Yes for hosted smoke | `1` | No |
| `PARKIO_DEPLOY_OPERATOR` / `--operator` | Optional | shell user | No |
| `PARKIO_DEPLOY_ARTIFACT_DIR` | Optional | `deploy-artifacts` | No |
| `PARKIO_DEPLOY_HEALTH_TIMEOUT` | Optional | `900` seconds | No |
| `PARKIO_BACKUP_OPERATOR` | Optional | shell user | No |
| `PARKIO_BACKUP_ARTIFACT_DIR` | Optional | `backup-artifacts` | No |
| `PARKIO_PREFLIGHT_ALLOW_PROVIDER_OVERRIDE` | Optional | unset | No |
| `PARKIO_PREFLIGHT_ALLOW_NO_ALERT_WEBHOOK` | Optional | unset | No |

Smoke/seed credentials are optional but recommended for full authenticated smoke:
`PARKIO_REAL_USER_EMAIL`, `PARKIO_REAL_USER_PASSWORD`,
`PARKIO_REAL_MODERATOR_EMAIL`, `PARKIO_REAL_MODERATOR_PASSWORD`,
`PARKIO_REAL_ADMIN_EMAIL`, `PARKIO_REAL_ADMIN_PASSWORD`.

### Image and Runtime Override Variables

These are optional unless an operator deliberately pins images or tunes runtime
limits. Defaults come from compose files.

| Variable | Req | Default / example | Secret |
|----------|-----|-------------------|--------|
| `PARKIO_IMAGE_TAG` | Script-set | `sha-<gitsha>` | No |
| `PARKIO_GIT_SHA` | Script-set | current git SHA | No |
| `PARKIO_IMAGE_CREATED` | Script-set | UTC timestamp | No |
| `PARKIO_IMAGE_VERSION` | Optional | git tag or `0.0.1-SNAPSHOT` | No |
| `POSTGRES_IMAGE` | Optional | `postgres:16-alpine` | No |
| `POSTGIS_IMAGE` | Optional | `postgis/postgis:16-3.4` | No |
| `REDIS_IMAGE` | Optional | `redis:7-alpine` | No |
| `KAFKA_IMAGE` | Optional | `confluentinc/cp-kafka:7.7.1` | No |
| `KAFKA_EXPORTER_IMAGE` | Optional | `danielqsj/kafka-exporter:v1.8.0` | No |
| `BLACKBOX_EXPORTER_IMAGE` | Optional | `prom/blackbox-exporter:v0.25.0` | No |
| `NODE_EXPORTER_IMAGE` | Optional | `prom/node-exporter:v1.8.2` | No |
| `MINIO_IMAGE` | Optional | `minio/minio:RELEASE.2024-09-13T20-26-02Z` | No |
| `MINIO_MC_IMAGE` | Optional | `minio/mc:RELEASE.2024-09-16T17-43-14Z` | No |
| `CLAMAV_IMAGE` | Optional | compose default | No |
| `CADDY_IMAGE` | Optional | `caddy:2.8-alpine` | No |
| `PROMETHEUS_IMAGE` | Optional | compose default | No |
| `ALERTMANAGER_IMAGE` | Optional | `prom/alertmanager:v0.27.0` | No |
| `GRAFANA_IMAGE` | Optional | compose default | No |
| `LOKI_IMAGE` | Optional | compose default | No |
| `PROMTAIL_IMAGE` | Optional | compose default | No |
| `TEMPO_IMAGE` | Optional | compose default | No |
| `CLAMAV_PORT` | Optional | `3310`; not public in hosted overlay | No |
| `JAVA_TOOL_OPTIONS` | Optional | JVM cgroup-safe defaults | No |
| `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE` | Optional | `30s` | No |
| `KAFKA_HEAP_OPTS` | Optional | `-Xms768m -Xmx768m` | No |
| `KAFKA_LOG_RETENTION_HOURS` | Optional | `168` | No |
| `KAFKA_LOG_RETENTION_BYTES` | Optional | `2147483648` | No |
| `KAFKA_LOG_SEGMENT_BYTES` | Optional | `268435456` | No |
| `PARKIO_ALERTMANAGER_VALIDATE_ONLY` | Test only | unset | No |

## Preflight Checklist

Run:

```bash
PARKIO_ENV_FILE=docker/.env ./scripts/preflight-hosted-beta.sh
```

Must pass:

- No `CHANGE_ME`, placeholder, local-dev, `admin`, `password`, `secret`, or
  committed sample secret values.
- JWT private key present and parseable as PEM text.
- Gateway secret, DB passwords, Redis, MinIO, Grafana, waitlist, Resend, Expo,
  and alert secrets set.
- Domains are bare public FQDNs.
- URLs are HTTPS where public.
- CORS is explicit and not `*`.
- `VITE_API_BASE_URL` points to the public API domain.
- Media public endpoint equals `https://<PARKIO_MEDIA_DOMAIN>`.
- Email provider is `resend`, push provider is `expo`.
- OpenAPI disabled, token logging disabled, Smart Return test hooks disabled.
- Hosted compose renders successfully.

## Deployment Checklist

1. Confirm clean tree:

   ```bash
   git status --short
   git rev-parse HEAD
   ```

2. Optional: copy previous manifest before deploying:

   ```bash
   cp deploy-artifacts/current.json deploy-artifacts/previous.json 2>/dev/null || true
   ```

3. Deploy:

   ```bash
   PARKIO_ENV_FILE=docker/.env \
   PARKIO_GATEWAY_URL=https://<PARKIO_DOMAIN> \
   PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 \
   ./scripts/deploy-hosted-beta.sh --operator "<name>"
   ```

4. Wait for the script to finish. It performs:

   - preflight,
   - dirty-tree check,
   - compose render,
   - app image build,
   - `sha-<gitsha>` image tagging,
   - `beta-latest` tagging,
   - `docker compose up -d`,
   - readiness wait,
   - smoke checks,
   - deploy manifest write.

5. Record:

   ```bash
   jq -r .gitSha deploy-artifacts/current.json
   jq -r .rollbackCommand deploy-artifacts/current.json
   docker compose --env-file docker/.env \
     -f docker/docker-compose.yml \
     -f docker/docker-compose.apps.yml \
     -f docker/docker-compose.images.yml \
     -f docker/docker-compose.hosted-beta.yml ps
   ```

Do not use raw `docker compose up -d --build` as the canonical hosted-beta
deploy path. It bypasses deploy manifests, image tagging, and rollback metadata.

## Post-Deployment Checklist

- Web loads: `curl -fsS https://<PARKIO_WEB_DOMAIN>/`.
- API health responds: `curl -fsS https://<PARKIO_DOMAIN>/actuator/health`.
- JWKS responds:
  `curl -fsS https://<PARKIO_DOMAIN>/api/v1/auth/.well-known/jwks.json`.
- Caddy has issued certificates; inspect Caddy logs if first TLS request fails.
- Public direct service ports are not reachable.
- Grafana reachable only through SSH tunnel.
- Prometheus targets are up.
- Alertmanager has a Slack or generic webhook receiver unless intentionally
  running alert-silent.
- Backup dry run and first real backup are complete.
- `deploy-artifacts/current.json` copied to private operator records.

## Backup Checklist

Dry run:

```bash
PARKIO_ENV_FILE=docker/.env ./scripts/backup-hosted-beta.sh --dry-run
```

Real backup:

```bash
PARKIO_ENV_FILE=docker/.env ./scripts/backup-hosted-beta.sh
```

Nightly cron:

```cron
30 3 * * * root cd /opt/parkio && PARKIO_ENV_FILE=docker/.env ./scripts/backup-hosted-beta.sh >> /var/log/parkio-backup.log 2>&1
```

Verify:

- `backup-artifacts/backup-current.json` exists.
- `docker/prometheus/textfile/parkio_backup.prom` updated.
- Prometheus alert `BackupFailedOrStale` is not firing.
- Offsite upload works if `BACKUP_MC_DEST` is configured.

## Restore Checklist

Restore is destructive unless run with `--dry-run`.

Dry run:

```bash
PARKIO_ENV_FILE=docker/.env \
./scripts/restore-hosted-beta.sh --manifest backup-artifacts/backup-current.json --dry-run
```

Real restore requires explicit confirmation:

```bash
PARKIO_ENV_FILE=docker/.env \
./scripts/restore-hosted-beta.sh --manifest backup-artifacts/backup-current.json
```

Scope options:

```bash
--only databases
--only minio
--only all
```

## Rollback Checklist

Rollback does not rebuild. It requires the previous `sha-<gitsha>` images to
exist locally or be pulled and tagged correctly.

1. Choose previous manifest:

   ```bash
   ls -1 deploy-artifacts/deploy-*.json
   ```

2. Dry run:

   ```bash
   PARKIO_ENV_FILE=docker/.env \
   ./scripts/rollback-hosted-beta.sh --manifest deploy-artifacts/<previous>.json --dry-run
   ```

3. Execute:

   ```bash
   PARKIO_ENV_FILE=docker/.env \
   PARKIO_GATEWAY_URL=https://<PARKIO_DOMAIN> \
   PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 \
   ./scripts/rollback-hosted-beta.sh --manifest deploy-artifacts/<previous>.json
   ```

4. Verify:

   ```bash
   jq -r .gitSha deploy-artifacts/current.json
   PARKIO_GATEWAY_URL=https://<PARKIO_DOMAIN> \
   PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 \
   ./scripts/smoke-hosted-beta.sh
   ```

Estimated rollback time: 3-8 minutes if previous images exist locally.

## Incident Checklist

1. Classify:

   - API down: check Caddy, gateway, auth, user.
   - Login broken: check auth, Redis, CORS, cookies, JWKS.
   - Upload broken: check media, MinIO, ClamAV, media public endpoint.
   - Map/search broken: check parking, PostGIS DB, gateway.
   - Notifications broken: check notification-service, Expo token, Kafka.
   - Data risk: stop deploys, run backup, decide restore vs rollback.

2. Open observability tunnel:

   ```bash
   ssh -L 3000:localhost:3000 -L 9090:localhost:9090 -L 9093:localhost:9093 \
       -L 3100:localhost:3100 -L 3200:localhost:3200 -L 9308:localhost:9308 \
       <user>@<vps-ip>
   ```

3. Inspect:

   ```bash
   docker compose --env-file docker/.env \
     -f docker/docker-compose.yml \
     -f docker/docker-compose.apps.yml \
     -f docker/docker-compose.images.yml \
     -f docker/docker-compose.hosted-beta.yml ps
   docker logs parkio-caddy --tail 100
   docker logs parkio-gateway-service-1 --tail 100
   ```

4. Decide:

   - Config mistake: fix `.env`, rerun preflight, redeploy.
   - Bad release: rollback by manifest.
   - Data corruption/loss: restore from backup after explicit approval.
   - Resource saturation: reduce traffic, restart affected service, move
     observability off-box, or scale host.

5. Record incident time, cause, commands run, deploy/rollback/backup manifests,
   and remaining follow-up work.

## Canonical Commands

| Task | Command |
|------|---------|
| Validate env and compose | `PARKIO_ENV_FILE=docker/.env ./scripts/preflight-hosted-beta.sh` |
| Deploy | `PARKIO_ENV_FILE=docker/.env PARKIO_GATEWAY_URL=https://<api-host> PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 ./scripts/deploy-hosted-beta.sh` |
| Smoke only | `PARKIO_GATEWAY_URL=https://<api-host> PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 ./scripts/smoke-hosted-beta.sh` |
| Backup | `PARKIO_ENV_FILE=docker/.env ./scripts/backup-hosted-beta.sh` |
| Restore dry run | `PARKIO_ENV_FILE=docker/.env ./scripts/restore-hosted-beta.sh --manifest backup-artifacts/backup-current.json --dry-run` |
| Rollback dry run | `PARKIO_ENV_FILE=docker/.env ./scripts/rollback-hosted-beta.sh --manifest deploy-artifacts/<previous>.json --dry-run` |
| Rollback | `PARKIO_ENV_FILE=docker/.env PARKIO_GATEWAY_URL=https://<api-host> PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 ./scripts/rollback-hosted-beta.sh --manifest deploy-artifacts/<previous>.json` |

Obsolete for hosted beta:

- `PARKIO_GATEWAY_URL=http://127.0.0.1:8080` with the hosted-beta overlay; the
  gateway port is private, so use the public API domain through Caddy.
- Raw `docker compose up -d --build` as the deployment path; use the deploy
  script so manifests and rollback metadata are written.

## Verification Commands for This Repository

Run before cutting a deployment-ready commit:

```bash
./scripts/test-preflight-hosted-beta.sh
./scripts/validate-hosted-beta-compose.sh
sh -n docker/alertmanager/render-config.sh
bash -n scripts/deploy-hosted-beta.sh scripts/rollback-hosted-beta.sh \
  scripts/backup-hosted-beta.sh scripts/restore-hosted-beta.sh \
  scripts/validate-hosted-beta-compose.sh scripts/smoke-hosted-beta.sh
PARKIO_ENV_FILE=docker/.env ./scripts/preflight-hosted-beta.sh
PARKIO_ENV_FILE=docker/.env ./scripts/deploy-hosted-beta.sh --dry-run --allow-dirty
PARKIO_ENV_FILE=docker/.env ./scripts/backup-hosted-beta.sh --dry-run
```

Do not run `chaos-compose-validation.sh`, `restore-drill.sh`, or live smoke
against a production-like host unless a stack is intentionally running and the
operator understands the impact.
