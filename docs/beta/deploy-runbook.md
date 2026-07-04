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

## Deploy

From the **repository root**:

```bash
# Clean tree required unless you intentionally override
PARKIO_ENV_FILE=docker/.env \
PARKIO_GATEWAY_URL=http://127.0.0.1:8080 \
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
- [Supply-chain security](../operations/supply-chain-security.md)