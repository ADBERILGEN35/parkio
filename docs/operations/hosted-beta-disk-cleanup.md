# Hosted-beta disk cleanup

Azure hosted-beta deploys build application images on the VM. Free disk below
**12 GiB** blocks deploy via `scripts/deploy-hosted-beta.sh` (see
`scripts/lib/disk-space.sh`). Preferred headroom is **15 GiB** (~80% root usage
or better on the current 61 GiB disk).

## What deploy does *not* do

- Deploy never auto-prunes images, volumes, or build cache.
- `PARKIO_DEPLOY_ALLOW_LOW_DISK=1` is an explicit operator override only; it
  does not lower the configured threshold.

## Configuration

| Variable | Meaning |
|----------|---------|
| `PARKIO_DEPLOY_MIN_FREE_GIB` | Minimum free GiB (default `12`) |
| `PARKIO_DEPLOY_MIN_FREE_BYTES` | Exact byte threshold (wins over GiB) |
| `PARKIO_DEPLOY_ALLOW_LOW_DISK=1` | Warn and continue despite low disk |

## Safe cleanup order

1. Record `df -h /`, `docker system df`, `docker builder du`, and build a
   **protected image allowlist** from running containers plus current and
   selected rollback manifests.
2. Bounded builder cache: `docker builder prune -f --keep-storage 2GB`
   (or the engine’s `reserved-space` equivalent).
3. Dangling images only: `docker image prune` (not `-a`).
4. Reviewed old `parkio/*:sha-*` tags whose image IDs are **not** in the
   allowlist. Delete by image ID after review — never by wildcard, never
   `docker system prune -a --volumes`, never `docker volume prune`.

## Must protect

- Current deploy image set (e.g. `sha-fde5588…` / `beta-latest`)
- Selected rollback image set (e.g. `sha-7ada83b…`)
- Every image ID used by a running container (Postgres, Kafka, MinIO, etc.)

## Must not delete

- Named data volumes (Postgres / Redis / Kafka / MinIO)
- Environment files, SSH keys, official OSM/İZELMAN inputs, deploy evidence
