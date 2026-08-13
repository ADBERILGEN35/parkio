# Restore runbook (R5.2)

**Destructive.** Restoring overwrites live databases and MinIO objects. Take a fresh backup first.

## Single database

```bash
PARKIO_ENV_FILE=docker/.env ./scripts/restore-database.sh auth /var/backups/parkio/<stamp>/auth.sql.gz
```

Supports `.sql`, `.sql.gz`, `.sql.gz.enc` (needs `BACKUP_ENCRYPT_PASSPHRASE`).

## Full hosted-beta restore (from manifest)

```bash
PARKIO_ENV_FILE=docker/.env \
  ./scripts/restore-hosted-beta.sh \
  --manifest backup-artifacts/backup-<timestamp>.json
```

Dry-run (no changes):

```bash
./scripts/restore-hosted-beta.sh --manifest backup-artifacts/backup-current.json --dry-run
```

Partial restore:

```bash
./scripts/restore-hosted-beta.sh --manifest ... --yes --only databases
./scripts/restore-hosted-beta.sh --manifest ... --yes --only minio
```

## After restore

1. `docker compose ... up -d` if services were stopped.
2. Wait for healthchecks (`docker compose ps`).
3. `./scripts/smoke-hosted-beta.sh`
4. Verify Grafana dashboards and outbox/DLQ metrics.

## Verify-only (no production / hosted-beta touch)

```bash
PARKIO_ENV_FILE=docker/.env ./scripts/verify-backup.sh parking /var/backups/parkio/<stamp>/parking.sql.gz
PARKIO_ENV_FILE=docker/.env ./scripts/restore-drill.sh --keep-backups
```

`restore-drill.sh` restores into disposable `*_drill_*` / `*_verify_*` databases only. It never overwrites service databases (only a drill canary table is added and dropped).

## Isolated CI drill

`.github/workflows/backup-restore-drill.yml` starts **all ten** Postgres services + MinIO, then:

1. `restore-drill.sh --keep-backups` — real dump → restore → canary + parking PostGIS + representative rows
2. `restore-drill-minio.sh` — synthetic object checksum round-trip
3. `restore-drill-failure-modes.sh` — missing/corrupt dumps fail closed; encrypted dump round-trip

Never point these scripts at hosted-beta or production.

## Parking / PostGIS

`restore-drill.sh` proves PostGIS extension, GiST index, sync trigger, spatial query, a synthetic `parking_spots` row, and the V39 Kayseri `municipal_data_sources` seed survive backup.

## MinIO emergency restore (live — operator decision)

`restore-hosted-beta.sh --only minio` **overwrites the live bucket**. Take a fresh backup first. Isolated proof is `restore-drill-minio.sh`, not the live command.

## Checksums

Each dump may have a sibling `*.sha256`. Verify before restore:

```bash
sha256sum -c /var/backups/parkio/<stamp>/parking.sql.gz.sha256
```
