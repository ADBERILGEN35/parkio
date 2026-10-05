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

With the default hosted-beta profile (CL-F12), the model is `docker/compose.production.files`, rendered from this checkout. Steps 2 and 3 differ:

- **Built services.** For each service the list builds, the target manifest must record `parkio/<service>:<imageTag>`, and that image must be present. Otherwise the rollback refuses (exit 2) before it changes anything. The script then points the model's name for the service (`parkio-<service>`) at that image.
- **Pinned services.** The digest-pinned services keep this checkout's pins; a missing pin is pulled. To roll a pin back, revert its pin file (for example `docker/docker-compose.web-release-pin.yml`) and deploy.
- **Start.** `up -d --no-build`.
- **Older manifests.** A manifest written before CL-F12 recorded every app service. Its images for the five pinned services are not used.
- **A different file set.** When the target deploy rendered other compose files, the script prints a NOTE with both lists. That holds for the hosted-beta profile only.
  - With the invite-production profile, the rollback refuses a target whose `composeFiles` differs from its own list (F-INV-2), before it writes or starts anything.
  - See "Compose file list" in `docs/operations/invite-production-rollback-runbook.md`.
- **Known limitation (F-INV-3, reported; not fixed by CL-F12).** A local rollback with `deploy-artifacts/current.json` present is refused with exit 3.
  - The schema gate (`parkio_assert_rollback_schema_compatible`) reads a `migrations` key that manifests never write; they write `migrationVersions`. It therefore reports "target manifest missing migrations".
  - The workflow's rollback job runs on a clean checkout without `current.json`, so there the gate is skipped.
  - In neither case does the gate compare migrations.
- **Web image.** The rollback runs the current web pin, so it is refused like the deploy until that pin moves to an image built from #198 or later (next section).

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

## Web images built for another API (H2)

The rollback, the deploy and `scripts/parkio-prod-compose.sh` refuse a web image whose baked `VITE_API_BASE_URL` is not the one the env intends: web's `VITE_API_BASE_URL` build argument, on `PARKIO_DOMAIN` ("web API endpoint check failed; nothing was started").
- **Why.** A web image built for another environment would send this deploy's users to that environment's API. That includes the production pin on a hosted-beta host.
- **No break-glass.** The check has none, and the map and conf.d break-glasses do not skip it.
- **The fix.** Start a web image built for this env's API, or correct the env's `VITE_API_BASE_URL` and `PARKIO_DOMAIN` if they are wrong.

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
