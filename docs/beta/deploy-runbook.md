# Hosted-beta deploy runbook (R5.1)

Safe, repeatable deployment of Parkio application images for **hosted-beta**.
Images are always built from the **current git commit** and tagged so rollback
can restore a previous SHA without rebuilding.

## Image tagging

| Tag | Meaning |
|-----|---------|
| `parkio/<service>:sha-<fullGitSha>` | Immutable tag for this commit |
| `parkio/<service>:beta-latest` | Mutable pointer to the last successful deploy |

Optional: when `HEAD` is an exact semver tag (`v1.2.3`), the OCI `version` label
uses that tag (`PARKIO_IMAGE_VERSION`).

OCI labels (set at build time via Dockerfile `ARG`s):

- `org.opencontainers.image.revision` = git SHA
- `org.opencontainers.image.created` = UTC build timestamp
- `org.opencontainers.image.version` = version / snapshot
- `org.opencontainers.image.source` = GitHub repo URL

## Compose files

Always use:

```text
docker/docker-compose.yml
docker/docker-compose.apps.yml
docker/docker-compose.images.yml
docker/docker-compose.hosted-beta.yml   # on the VPS (port lockdown + TLS)
```

`docker-compose.images.yml` **requires** `PARKIO_IMAGE_TAG` (e.g. `sha-<gitsha>`).
Never run `up -d` without it when this overlay is included — Compose will refuse
to start rather than silently using an untagged/stale image name.

## Prerequisites

- Docker Compose v2
- `git`, `jq`, `curl`
- Env file with real secrets (`docker/.env` or hosted-beta env)
- Seeded smoke user (optional but recommended): `scripts/seed-real-e2e.sh`

## Secret & configuration preflight (R-005)

`scripts/deploy-hosted-beta.sh` runs `scripts/preflight-hosted-beta.sh` **before
any image is built**. If any check fails the deploy aborts with exit code 3 and a
grouped, human-readable list of every problem. You can also run it standalone:

```bash
PARKIO_ENV_FILE=docker/.env ./scripts/preflight-hosted-beta.sh
```

### Required secrets

| Variable | Rule | Generate / obtain |
|----------|------|-------------------|
| `PARKIO_JWT_PRIVATE_KEY_PEM` | non-empty PKCS#8 PEM (`BEGIN PRIVATE KEY`) | `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048` (single line, `\n` escapes) |
| `PARKIO_JWT_KEY_ID` / `ISSUER` / `AUDIENCE` | non-empty | per environment |
| `PARKIO_GATEWAY_INTERNAL_SECRET` | ≥ 32 chars, no placeholder/local-dev value | `openssl rand -base64 48` |
| `POSTGRES_<SVC>_PASSWORD` (×9) | ≥ 16 chars each, distinct, no `*_local_dev_pw` | `openssl rand -base64 24` |
| `REDIS_PASSWORD` | ≥ 16 chars (empty disables Redis auth) | `openssl rand -base64 24` |
| `MINIO_ROOT_PASSWORD` | ≥ 16 chars | `openssl rand -base64 24` |
| `GRAFANA_ADMIN_PASSWORD` | ≥ 12 chars, not `admin` | `openssl rand -base64 24` |
| `KAFKA_CLUSTER_ID` | exactly 22 chars, not the committed dev id | `docker run --rm confluentinc/cp-kafka:7.7.1 kafka-storage random-uuid` |
| `PARKIO_RESEND_API_KEY` | `re_` prefix | Resend dashboard |
| `PARKIO_EXPO_ACCESS_TOKEN` | non-empty, no placeholder | expo.dev → Access tokens |
| `PARKIO_ALERT_SLACK_WEBHOOK_URL` | `https://hooks.slack.com/…`, or generic `PARKIO_ALERT_WEBHOOK_URL` | Slack app → Incoming webhooks |

### Domain / URL rules

- `PARKIO_DOMAIN`, `PARKIO_WEB_DOMAIN`, `PARKIO_MEDIA_DOMAIN`: bare public FQDNs —
  no scheme, no `localhost`/`127.0.0.1`/`10.0.2.2`/`*.local`/`example.com`.
- `VITE_API_BASE_URL`: HTTPS and its host must equal `PARKIO_DOMAIN`.
- `PARKIO_CORS_ALLOWED_ORIGINS`: HTTPS origins only, never `*`;
  `PARKIO_CORS_ALLOW_CREDENTIALS=true` (SPA refresh cookie).
- `PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT`: must equal `https://$PARKIO_MEDIA_DOMAIN`
  exactly (SigV4 signs the Host header; the compose default `http://localhost:9000`
  breaks all media if left unset).
- `PARKIO_TRUSTED_PROXIES`: private (RFC1918/loopback) ranges only.

### Provider / safety rules

- `PARKIO_EMAIL_PROVIDER=resend` and `PARKIO_PUSH_DELIVERY_PROVIDER=expo`. Another
  *real* provider can be allowed intentionally with
  `PARKIO_PREFLIGHT_ALLOW_PROVIDER_OVERRIDE=1` (logged as WARN; document why in the
  deploy notes). `logging`/`noop` are never accepted.
- `PARKIO_OPENAPI_ENABLED=false` must be **explicit** — the compose default is
  `true`, so omitting it would expose Swagger publicly.
- `PARKIO_EMAIL_VERIFICATION_LOG_TOKEN` / `PARKIO_PASSWORD_RESET_LOG_TOKEN` must
  not be `true` (token logging), `PARKIO_SMART_RETURN_TEST_HOOKS_ENABLED` must not
  be `true`, `PARKIO_MEDIA_SCANNER_ENABLED` must not be `false`.
- `PARKIO_ENVIRONMENT=hosted-beta`; no `SPRING_PROFILES_ACTIVE=dev|local|test`;
  no `jdwp`/`Xdebug` in `JAVA_TOOL_OPTIONS`.
- No alert webhook at all requires `PARKIO_PREFLIGHT_ALLOW_NO_ALERT_WEBHOOK=1`
  (alerts then only visible via SSH tunnel to Alertmanager).

### Reading failures

Every failure prints `FAIL <variable>: <what is wrong>` plus a `fix:` line with
the exact command or value to use. Fix all `FAIL` lines, re-run the preflight,
then deploy. Standalone the preflight also renders the compose config via
`scripts/validate-hosted-beta-compose.sh` (the deploy script skips that step
because it renders the config itself right after).

Regression tests: `./scripts/test-preflight-hosted-beta.sh` (fixtures in
`scripts/preflight-fixtures/`).

## Deploy

From the **repository root**:

```bash
# Clean tree required unless you intentionally override
PARKIO_ENV_FILE=docker/.env \
PARKIO_GATEWAY_URL=https://<PARKIO_DOMAIN> \
PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 \
./scripts/deploy-hosted-beta.sh
```

Flags:

| Flag | Purpose |
|------|---------|
| `--dry-run` | Render compose + write manifest only (no build/up) |
| `--allow-dirty` | Allow uncommitted changes (not recommended) |
| `--no-hosted-beta-overlay` | Local apps ports (no TLS lockdown) |
| `--skip-smoke` | Skip post-deploy smoke |

What the script does:

0. Runs the R-005 secret/configuration preflight — aborts (exit 3) before any
   build if the env file has placeholder secrets, local-dev values, non-HTTPS
   domains or unsafe toggles (skipped only with `--no-hosted-beta-overlay`)
1. Refuses a dirty working tree (unless `--allow-dirty`)
2. Sets `PARKIO_IMAGE_TAG=sha-$(git rev-parse HEAD)`
3. Builds **all** app images from current source (`docker compose build`)
4. Tags each image `beta-latest`
5. `docker compose up -d` (Flyway migrates on startup)
6. Waits for readiness healthchecks
7. Runs `scripts/smoke-hosted-beta.sh`
8. Writes `deploy-artifacts/deploy-<sha>-<time>.json` and `deploy-artifacts/current.json`
   (includes `images`, `composeFiles`, `migrationVersions`, `rollbackCommand`)
9. Prints the rollback command

### Verify the running commit

```bash
# From manifest
jq -r .gitSha deploy-artifacts/current.json

# From a running container label
docker inspect parkio-gateway-service-1 \
  --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}'
```

These must match `git rev-parse HEAD` after a successful deploy.

## Avoiding stale images

| Do | Do not |
|----|--------|
| `./scripts/deploy-hosted-beta.sh` | `docker compose up -d` without `--build` after `git pull` |
| `docker compose ... build` then `up -d` with `images.yml` | Rely on an old `parkio-*-service:latest` from weeks ago |
| Check OCI `revision` label | Assume container name implies current code |

CI workflows that start the stack must use `--build` or the deploy script.

## CI

Workflow: `.github/workflows/hosted-beta-deploy.yml`

- **build** (default): dry-run manifest + build images on `ubuntu-latest`, upload
  `deploy-artifacts/` as an artifact. Optional GHCR push when `vars.PUBLISH_IMAGES=true`.
- **deploy**: `workflow_dispatch` only, runs on a self-hosted runner labeled
  `parkio-beta` with a real env file.
- **rollback**: `workflow_dispatch` only, same runner, requires a prior manifest.

Local operators do not need GitHub secrets; use the scripts directly on the VPS.

## Related

- [Rollback runbook](./rollback-runbook.md)
- [Beta runbook (local)](../../docker/BETA_RUNBOOK.md)
- [Hosted Beta Runbook](../../HOSTED-BETA-RUNBOOK.md)
- [Supply-chain security](../operations/supply-chain-security.md)
