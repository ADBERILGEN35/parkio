# GMP release image pins (Civo production)

Digest-pinned `gateway-service` / `media-service` / `parking-service` images for
`parkio-civo-prod` live in `docker/docker-compose.gmp-release-pins.yml`.

## Production invocation

```bash
export PARKIO_ENV_FILE=docker/.env.azure-hosted-beta
./scripts/parkio-prod-compose.sh ps
./scripts/parkio-prod-compose.sh up -d --no-build --no-deps <service>
```

File set: `docker/compose.production.files` (no `docker-compose.images.yml`).

Keep `PARKIO_MUNICIPAL_OCCUPANCY_RETENTION_CRON` double-quoted in the host env file.

## Tooling compatibility

`PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta` via `scripts/lib/deploy-common.sh` appends the
same pins overlay last so `parkio_compose` cannot silently drop digest pins when
`docker-compose.images.yml` is also loaded.

## Backup stamp status defect (follow-up)

Stamp `backup-manifest.json` may retain `offsite.uploaded=false` after a successful
Azure upload while `backup-artifacts/backup-current.json` reports `uploaded=true`.
Do not manually edit stamp JSON. Prefer offsite object listing + COMPLETE/SHA256SUMS.
Narrow source fix: re-copy stamp manifest after offsite upload (tracked as tooling
follow-up; not closed by a single successful upload).
