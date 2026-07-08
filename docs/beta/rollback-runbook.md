# Hosted-beta rollback runbook (R5.1)

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
