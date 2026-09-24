# Restore runbook (R5.2)

**Destructive.** Restoring overwrites live databases and MinIO objects. Take a fresh backup first.

## Single database

The dump must sit inside a COMPLETE stamp. Production restore fail-closes
before decrypt unless `--recovery-cutoff` is supplied and erasure coverage
reaches that cutoff. A stamp-time ledger is not enough for a later cutoff.

```bash
PARKIO_ENV_FILE=docker/.env ./scripts/restore-database.sh auth \
  /var/backups/parkio/<stamp>/auth.sql.gz.enc \
  --recovery-cutoff 2026-09-24T12:00:00Z
```

Supports `.sql`, `.sql.gz`, `.sql.gz.enc` (needs `BACKUP_ENCRYPT_PASSPHRASE`). Missing/wrong key fails closed.
A dump outside a COMPLETE stamp, a DB-only stamp used as a full-system backup,
or a checksum/path-traversal failure is rejected with zero destructive commands.

## Recover from offsite (VM lost)

```bash
# 1. Pull into a NEW directory (not the old local path)
PARKIO_ENV_FILE=docker/.env \
  ./scripts/backup-offsite-pull.sh --stamp <stamp> --dest /tmp/parkio-restore-<stamp>

# 2. Checksums (fail closed)
sha256sum -c /tmp/parkio-restore-<stamp>/SHA256SUMS

# 3. Isolated DB proof (does not overwrite live service DBs).
#    NOTE: --from-dir asserts the CI drill canary row, so it only passes on drill-made
#    stamps. For a real production stamp use restore-drill-01-isolated-database.md.
PARKIO_ENV_FILE=docker/.env \
  ./scripts/restore-drill.sh --from-dir /tmp/parkio-restore-<stamp>

# 4. Isolated MinIO proof
MINIO_RESTORE_BUCKET=drill-restore-<stamp> \
  PARKIO_ENV_FILE=docker/.env \
  ./scripts/restore-hosted-beta.sh \
    --manifest /tmp/parkio-restore-<stamp>/backup-manifest.json \
    --yes --only minio
```

## Full hosted-beta restore (EMERGENCY — operator decision)

Stop. Confirm the stamp, checksums, and that this is not a drill.

```bash
PARKIO_ENV_FILE=docker/.env \
  PARKIO_ALLOW_LIVE_MINIO_RESTORE=yes \
  ./scripts/restore-hosted-beta.sh \
  --manifest /var/backups/parkio/<stamp>/backup-manifest.json \
  --recovery-cutoff <ISO-8601-UTC>
```

`--manifest` must be the copy you reviewed (usually `backup-manifest.json` inside
the stamp). The script refuses a stale `.destination` that points at a different
directory. `offsite.uploaded=false` is a known sealed-stamp defect; it is not
local integrity proof and not independent remote presence. Do not rewrite stamps.

Dry-run:

```bash
./scripts/restore-hosted-beta.sh --manifest backup-artifacts/backup-current.json --dry-run
```

Partial:

```bash
./scripts/restore-hosted-beta.sh --manifest ... --yes --only databases
MINIO_RESTORE_BUCKET=<isolated-or-live> PARKIO_ALLOW_LIVE_MINIO_RESTORE=yes \
  ./scripts/restore-hosted-beta.sh --manifest ... --yes --only minio
```

Live MinIO restore **overwrites the destination bucket**. Isolated drills must set `MINIO_RESTORE_BUCKET` to a throwaway name. Live restore requires `PARKIO_ALLOW_LIVE_MINIO_RESTORE=yes` plus typing `RESTORE` unless `--yes`.

Operator stop points:

1. Verify `COMPLETE` + `SHA256SUMS` on the **offsite** copy.
2. Dry-run the manifest.
3. Restore databases into isolated verify DBs first (`restore-drill.sh --from-dir`).
4. Only then restore live Postgres.
5. Restore MinIO last, after a fresh pre-restore backup if the live bucket still exists.

## After restore

A successful data restore is **not** authorization to expose applications.
`restore-hosted-beta.sh` and `restore-database.sh` do not start applications,
publishers, schedulers, Slack, or Fluent Bit.

1. `docker compose ... up -d` if services were stopped — operator decision, not part of restore.
2. Wait for healthchecks (`docker compose ps`).
3. `./scripts/smoke-hosted-beta.sh`
4. Verify Grafana dashboards and outbox/DLQ metrics.

## Isolated CI drill

`.github/workflows/backup-restore-drill.yml`:

1. `restore-drill.sh --keep-backups`
2. `restore-drill-minio.sh`
3. `restore-drill-failure-modes.sh`
4. `restore-drill-offsite.sh` — encrypt, offsite, delete local, pull, restore 10 DBs + MinIO

Optional `workflow_dispatch` input `azure_offsite` runs the same protocol against Azure Blob (secrets, not PR env).

Never point these scripts at hosted-beta or production.

## Checksums

```bash
sha256sum -c /var/backups/parkio/<stamp>/SHA256SUMS
test -f /var/backups/parkio/<stamp>/COMPLETE
```
