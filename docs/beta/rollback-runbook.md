# Hosted-beta rollback runbook (R5.1)

> **Production:** with `PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta`, this script is a
> deprecated production path (owner decision B7, 2026-10-03). It still runs unchanged, and
> prints a warning. The supported production path is `scripts/parkio-prod-compose.sh` with
> `docker/compose.production.files`. See `docs/azure/AZURE-DEPLOYMENT-PROFILE.md`.

Restore a previous hosted-beta deployment using an immutable `sha-<gitSha>` image
set recorded in a deploy manifest.

## Prerequisites

- The previous images still exist **locally** on the host (or have been pulled
  from GHCR if you publish images).
- A manifest file from a prior deploy, e.g.:
  - `deploy-artifacts/current.json` (before the bad deploy overwrote it — copy it first), or
  - `deploy-artifacts/deploy-<sha>-<timestamp>.json`

**Tip:** Before every deploy, copy `deploy-artifacts/current.json` to
`deploy-artifacts/previous.json` if you want a stable pointer (the deploy script
also embeds `previousManifest` when `current.json` exists).

## Rollback

```bash
PARKIO_ENV_FILE=docker/.env \
PARKIO_GATEWAY_URL=https://<PARKIO_DOMAIN> \
PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 \
./scripts/rollback-hosted-beta.sh \
  --manifest deploy-artifacts/deploy-<previous-sha>-<time>.json
```

Flags:

| Flag | Purpose |
|------|---------|
| `--dry-run` | Write rollback manifest only |
| `--skip-smoke` | Skip smoke after rollback |
| `--no-hosted-beta-overlay` | Match the original deploy topology |

What the script does:

1. Reads `imageTag` / `gitSha` / `images` from the manifest
2. Verifies each `parkio/<service>:<imageTag>` exists locally
3. Sets `PARKIO_IMAGE_TAG` and runs `docker compose up -d` **without** `--build`
4. Waits for healthchecks
5. Runs smoke checks
6. Writes `deploy-artifacts/rollback-to-<sha>-<time>.json` and updates `current.json`

## Web images built before #198

From #261 on, the compose files give web a read-only root and an empty tmpfs at
`/etc/nginx/conf.d`. Images built from #198 on render their server config there at start. Older
images ship it in that directory, so the tmpfs would hide it and Caddy would answer 502. The conf.d
check (`scripts/lib/web_conf_d_guard.py`) therefore refuses such an image before anything starts
(`web conf.d check failed; nothing was started`):

- `scripts/parkio-prod-compose.sh` and `scripts/rollback-hosted-beta.sh` read the compose files of
  the checkout they run from. From a checkout that includes #261, a rollback of web to an image
  built before #198 is refused. That includes the "Rollback for this corrected pin" digest in
  `docker/docker-compose.web-release-pin.yml` while it predates #198.
- `scripts/rollback-invite-production.sh` renders the compose files staged in the target commit's
  runtime release. A release staged before #261 has no tmpfs there, so the check does not apply.

Supported paths:

- **Forward:** move web to an image built from #198 or later (the pin in
  `docker/docker-compose.web-release-pin.yml`, a release and pin action) and deploy it.
- **Back:** run the rollback from a checkout of the older release, before #261. Its compose files
  have no tmpfs at `/etc/nginx/conf.d`, so the older image starts with the config it ships.

The map guard's break-glass `PARKIO_SKIP_WEB_MAP_GUARD` does not skip this check: without a binding,
the check inspects the image the model names. The check has its own break-glass,
`PARKIO_SKIP_WEB_CONF_D_CHECK=I_ACCEPT_UNCHECKED_WEB_CONF_D`, off by default. Do not use it for this,
because it starts web without a server config.

## If images are missing

```bash
# If you publish to GHCR (vars.PUBLISH_IMAGES=true):
docker pull ghcr.io/<org>/parkio/gateway-service:sha-<fullsha>
# ...repeat per service, retag to parkio/<service>:sha-<fullsha>
```

Or re-checkout the old commit and run `./scripts/deploy-hosted-beta.sh` (that is a
**forward** deploy of old code, not a tag-only rollback).

## Verify

```bash
jq -r .gitSha deploy-artifacts/current.json
docker inspect parkio-gateway-service-1 \
  --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}'
./scripts/smoke-hosted-beta.sh
```

## Related

- [Hosted Beta Runbook](../../HOSTED-BETA-RUNBOOK.md)
- [Deploy runbook](./deploy-runbook.md)
