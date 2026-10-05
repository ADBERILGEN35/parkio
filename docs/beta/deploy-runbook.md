# Hosted-beta deploy runbook (R5.1)

> **Production:** with `PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta`, this script is a
> deprecated production path (owner decision B7, 2026-10-03). It still runs unchanged, and
> prints a warning. The supported production path is `scripts/parkio-prod-compose.sh` with
> `docker/compose.production.files`. See `docs/azure/AZURE-DEPLOYMENT-PROFILE.md`.

> **Default profile (CL-F12, owner decision 1b, 2026-10-05).**
> - **Status.** `hosted-beta` is the profile an env file selects when it names none. It is a supported deployment path while `.github/workflows/hosted-beta-deploy.yml` uses it.
> - **File set.** Its deploy, rollback and DR render `docker/compose.production.files` exactly, so they run the production model with its digest pins and auth registration settings.
> - **Difference from Civo.** The model differs from the Civo wrapper's only by the Civo-host-specific Alertmanager overlay, so the two are not identical.
> - **Before the next deploy,** follow the [release step](#release-step-before-the-next-hosted-beta-deploy-cl-f12).

Safe, repeatable deployment of Parkio application images for **hosted-beta**.
Images are always built from the **current git commit** and tagged so rollback
can restore a previous SHA without rebuilding.

## Image tagging

With the default hosted-beta profile:

| Image | Meaning |
|-------|---------|
| `parkio-<service>` | The name the model gives a service it builds (user, gamification, notification, moderation, ai-validation, analytics). Deploy builds it and `up` runs it. |
| `parkio/<service>:sha-<fullGitSha>` | Immutable tag of that build, recorded in the manifest's `images` for rollback |
| `parkio/<service>:beta-latest` | Mutable pointer to the last successful deploy |
| `ghcr.io/…/<service>@sha256:…` | Digest pins from the pin files for gateway, auth, parking, media and web, recorded in `pinnedImages`. Deploy pulls them; it never builds them. |

The other profiles build every app service as `parkio/<service>:sha-<fullGitSha>`.

Optional: when `HEAD` is an exact semver tag (`v1.2.3`), the OCI `version` label
uses that tag (`PARKIO_IMAGE_VERSION`).

OCI labels (set at build time via Dockerfile `ARG`s):

- `org.opencontainers.image.revision` = git SHA
- `org.opencontainers.image.created` = UTC build timestamp
- `org.opencontainers.image.version` = version / snapshot
- `org.opencontainers.image.source` = GitHub repo URL

## Compose files

The default hosted-beta profile renders `docker/compose.production.files`, in its order, and
nothing else (CL-F12):

```text
docker/docker-compose.yml
docker/docker-compose.apps.yml
docker/docker-compose.hosted-beta.yml       # port lockdown + TLS
docker/docker-compose.azure-hosted-beta.yml
docker/docker-compose.gmp-release-pins.yml
docker/docker-compose.auth-registration-env.yml
docker/docker-compose.auth-release-pin.yml
docker/docker-compose.web-release-pin.yml
```

- **The four observability services are off.** The Azure overlay puts `alertmanager`, `loki`, `promtail` and `tempo` in an inactive profile.
- **The Civo wrapper adds one more file.** `scripts/parkio-prod-compose.sh` renders the same list plus the Civo-host-specific `docker-compose.civo-alertmanager.yml`.
- **Local runs (`--no-hosted-beta-overlay`)** use `docker-compose.yml`, `docker-compose.apps.yml` and `docker-compose.images.yml`.
  - `docker-compose.images.yml` **requires** `PARKIO_IMAGE_TAG` (e.g. `sha-<gitsha>`).
  - Never run `up -d` without it when this overlay is included. Compose will refuse to start rather than silently use an untagged or stale image name.

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
3. With the default hosted-beta profile:
   - **Pins first.** It checks the digest-pinned images and pulls any that are missing. A pull failure stops the deploy before any build.
   - **Builds.** It builds only the services the list does not pin, with the OCI build-args, under their model names.
   - **Tags.** It tags each build `sha-<gitsha>` and `beta-latest`.

   Other profiles build **all** app images (`docker compose build`) and tag each `beta-latest`.
4. Writes the plan into the manifest:
   - `images`: the built services and their `sha-` tags;
   - `pinnedImages`: the digest pins. azure-hosted-beta manifests record them too, beside every app service in `images`.
5. Records the manifest as the deployed release's, before anything starts (F-INV-3).
   - **Where:** `deployed-manifest.json`, outside the checkout, in one directory per host: `/var/lib/parkio/<profile>` for hosted-beta and azure-hosted-beta, the runtime root for invite-production, and `${XDG_STATE_HOME:-~/.local/state}/parkio/local-dev` for local-dev. `PARKIO_DEPLOY_STATE_DIR` overrides it; the scripts say so.
   - **Why:** the rollback's schema gate reads it to know the live schema and the digest pins that run. It refuses without it.
   - **Who writes it:** every deploy and rollback on the host, whichever user runs it.
   - **Prerequisite:** create the directory once, writable by every user who deploys or rolls back, for example `sudo install -d -m 2775 -g <deployers group> /var/lib/parkio/hosted-beta`. A live deploy refuses (exit 3) before it builds anything when it cannot write there.
6. `docker compose up -d` (Flyway migrates on startup). With the default hosted-beta profile it runs with `--no-build`.
7. Waits for readiness healthchecks
8. Runs `scripts/smoke-hosted-beta.sh`
9. Writes `deploy-artifacts/deploy-<sha>-<time>.json` and `deploy-artifacts/current.json`
   (includes `images`, `composeFiles`, `migrationVersions`, `rollbackCommand`)
10. Prints the rollback command

### Verify the running commit

```bash
# From manifest
jq -r .gitSha deploy-artifacts/current.json

# From a running container label: a service the deploy builds
docker inspect parkio-user-service-1 \
  --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}'
```

These must match `git rev-parse HEAD` after a successful deploy.

With the default hosted-beta profile, only the services the deploy builds carry this commit: user, gamification, notification, moderation, ai-validation and analytics. The digest-pinned services (gateway, auth, parking, media and web) carry their pin's source commit. Check those against `pinnedImages` in the manifest, for example `docker inspect parkio-gateway-service-1 --format '{{.Image}}'` against the pin.

## Release step: before the next hosted-beta deploy (CL-F12)

**The `parkio-beta` runner host is not known today** (owner answer, 2026-10-05: "document only").
Confirm it before the next deploy through `.github/workflows/hosted-beta-deploy.yml`, or before
running `deploy-hosted-beta.sh` by hand.

**Two facts block or break the next deploy until the owner decides about the web image:**

1. **Web runs the production web image.**
   - The profile no longer builds web on the host. It runs the digest pin from `docker/docker-compose.web-release-pin.yml` (`ghcr.io/…/web@sha256:aacf9dc9…`, from PR #91).
   - That image has production's public configuration baked in, including the API base URL `https://api.parkio.dev`.
   - On a host whose `PARKIO_DOMAIN` is not production's, the hosted-beta web app calls production's API. The edge CSP blocks those calls, or, if it allows them, beta users reach production.
   - The preflight and the smoke checks do not catch this: they check the env file and the API, not the pinned bundle.
   - **The deploy and the rollback now refuse it (H2).** The web API endpoint check compares the bound image's baked `VITE_API_BASE_URL` with the env's, and stops before anything starts ("web API endpoint check failed; nothing was started"). See `docs/operations/container-hardening-inventory.md`.
   - How hosted-beta gets a web image built for its own domain is an owner decision, and a source or pin change outside CL-F12.
2. **The deploy and the rollback are refused until the web pin moves to an image built from #198 or later.**
   - `docker/docker-compose.hosted-beta.yml` mounts an empty tmpfs at `/etc/nginx/conf.d` (#261).
   - The pinned web image predates #198, so the conf.d check refuses to start it ("web conf.d check failed; nothing was started").
   - See "Web images built before #198" in [the rollback runbook](./rollback-runbook.md#web-images-built-before-198).

**The hosted-beta profile refuses production configuration** (owner decision 2026-10-05, CL-F12 item 4).
- **What it checks.** `deploy-hosted-beta.sh`, `rollback-hosted-beta.sh` and `validate-hosted-beta-compose.sh` stop when the rendered model gives the edge a production hostname. The hostnames are `api.parkio.dev`, `app.parkio.dev` and `media.parkio.dev`, recorded in `scripts/lib/deploy-common.sh`.
  - **Where it looks.** Caddy's `PARKIO_DOMAIN`, `PARKIO_WEB_DOMAIN` and `PARKIO_MEDIA_DOMAIN`, and the hosts in web's `PARKIO_WEB_CSP_CONNECT_SRC`.
  - **When it runs.** Before anything is built, pulled or started. The only docker call before it is the read-only `docker compose config` render.
  - **The message.** "…is a production hostname; the hosted-beta profile refuses production configuration".
- **Values come from the rendered model**, so they are what Compose resolves from the env file and the shell, whatever the formatting: inline comments, `export`, whitespace or quotes. A port, case and a trailing dot are ignored. A model that cannot be rendered is refused too.
- **The deploy preflight reads the env file with a simpler parser** (`env_get`), so its other checks have the same formatting blind spot. This change does not touch it.
- `PARKIO_ENVIRONMENT` cannot tell the two apart: the production example sets it to `hosted-beta` too.
- Production runs through `scripts/parkio-prod-compose.sh`, which this does not affect. The deprecated `azure-hosted-beta` profile is unchanged.
- **Never pass a production env file with `--no-hosted-beta-overlay` either.** That flag selects the local-dev profile, which skips this check and the preflight, and starts the base stack with whatever env file it is given.
- There is no override.
- This checks the env file only. Which host the runner is remains an operational check (below).

**Confirm on that host:**
- **Identity.** Which host it is, and its `docker/.env`.
- **Docker Compose version.**
- **Platform.** It is `linux/amd64`, because the production model sets `platform: linux/amd64`.
- **Registry access.** It can read the GHCR digest pins: gateway, auth, parking, media and web, as it already does for the MinIO images. Packages that are not public need a `docker login ghcr.io` with read access. Without it, the deploy stops at the pull, before any build.

**Operator step on that host (F-INV-3, #290): create the deploy state directory.**
- Run once, before the next deploy: `sudo install -d -m 2775 -g <deployers group> /var/lib/parkio/hosted-beta`. The group must include the runner user and every operator who deploys or rolls back.
- Without it, the deploy refuses (exit 3) before it pulls or builds anything: "the deploy state directory /var/lib/parkio/hosted-beta is not writable".
- That deploy records `deployed-manifest.json` there. Until a deploy has recorded it, every live rollback is refused (see the [rollback runbook](./rollback-runbook.md)).
- An azure-hosted-beta host needs `/var/lib/parkio/azure-hosted-beta` in the same way. Its rollbacks are refused until an azure deploy records its digest pins.

**What changes on that host at the next deploy:**
- **Observability services.** The deploy no longer starts `alertmanager`, `loki`, `promtail` and `tempo`.
  - Containers an earlier deploy started keep running, no longer updated. Compose 5.5.1, in a local probe, leaves a service that moved into an inactive profile running.
  - Stopping them is an operator decision. That includes promtail with its Docker socket mount: see "What remains exposed" in `docs/architecture/docker-socket-proxy-design.md`.
- **Registration settings.** `auth-service` now receives `PARKIO_REGISTRATION_*` from the env file.
  - The old model passed none, so auth ran with its defaults (mode `closed`).
  - Check the registration values in the host's env file first. The examples keep registration closed (owner decision, 2026-10-05); it opens only with an explicit `PARKIO_REGISTRATION_MODE=open`.
- **Production settings.** Tracing off, memory limits, Kafka heap, the production Prometheus command, and parking-service's municipal and ranking settings with their production defaults.
- **Service and image names.**
  - The unpinned services run as `parkio-<service>`.
  - A rollback to a deploy made before this change points those back at the recorded `sha-` tags, and keeps the current pins for the five pinned services.
- **Env file.** It needs no new variable. `PARKIO_IMAGE_TAG` is no longer read by this profile.

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
