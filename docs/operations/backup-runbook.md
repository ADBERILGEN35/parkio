# Backup runbook (R5.2)

Hosted-beta backups protect **10** Postgres databases (logical `pg_dump`) and MinIO media objects.

This is **not** managed PITR. Public production still requires PP-01.

## Canonical command (operator cron)

**One schedule. One entrypoint.**

```bash
# /etc/cron.d/parkio-backup
30 3 * * * root cd /opt/parkio && PARKIO_ENV_FILE=docker/.env ./scripts/backup-hosted-beta.sh >> /var/log/parkio-backup.log 2>&1
```

Do **not** cron `backup-databases.sh` alone: that path uploads dumps only and skips MinIO + Prometheus metrics.

CI weekly drill: `.github/workflows/backup-restore-drill.yml` at `23 4 * * 1` (UTC). Isolated — never hosted-beta.

## Sequence

```
backup-hosted-beta.sh
  1. pg_dump × 10  →  <BACKUP_DIR>/<stamp>/*.sql.gz[+ .enc][+ .sha256]
  2. mc mirror MinIO bucket  →  <stamp>/minio/<bucket>/
  3. optional offsite  →  BACKUP_MC_DEST/<stamp>/  (dumps AND minio/)
  4. manifest + Prometheus textfile
```

Step 3 runs **after** step 2. Unset `BACKUP_MC_DEST` → local copy only (optional, default empty).

## What runs

| Script | Purpose |
|--------|---------|
| `scripts/backup-hosted-beta.sh` | Orchestrator: DB + MinIO + offsite + manifest + metrics |
| `scripts/backup-databases.sh` | `pg_dump` for all **ten** service databases + checksums |
| `scripts/backup-minio.sh` | `mc mirror` of `MINIO_BUCKET` (plaintext objects) |

## Required env (`docker/.env`)

| Variable | Purpose | Default |
|----------|---------|---------|
| `BACKUP_DIR` | Local destination | `./backups` |
| `BACKUP_RETENTION_DAYS` | Local prune (directories) | `14` |
| `BACKUP_ENCRYPT_PASSPHRASE` | Optional AES-256-CBC for **DB dumps only** | empty (off) |
| `BACKUP_MC_DEST` | Optional `mc` alias (e.g. `s3/parkio-backups`) | empty (off) |
| `MINIO_ROOT_PASSWORD` | Required for MinIO mirror | — |

Offsite and encryption are **not** configured in repo examples. Do not invent a destination. MinIO mirror is **not** encrypted independently (rely on disk/offsite TLS). A non-empty process-env `BACKUP_ENCRYPT_PASSPHRASE` / `BACKUP_MC_DEST` wins over blank `.env` placeholders.

## Isolated verification (no live overwrite)

```bash
PARKIO_ENV_FILE=docker/.env ./scripts/restore-drill.sh --keep-backups
PARKIO_ENV_FILE=docker/.env ./scripts/restore-drill-minio.sh
PARKIO_ENV_FILE=docker/.env ./scripts/restore-drill-failure-modes.sh
PARKIO_ENV_FILE=docker/.env ./scripts/verify-backup.sh parking backups/<stamp>/parking.sql.gz
```

## Manual backup

```bash
PARKIO_ENV_FILE=docker/.env ./scripts/backup-hosted-beta.sh
PARKIO_ENV_FILE=docker/.env ./scripts/backup-hosted-beta.sh --dry-run
```

## Manifest

- `backup-artifacts/backup-<timestamp>.json`
- `backup-artifacts/backup-current.json`

## Metrics

`docker/prometheus/textfile/parkio_backup.prom` — alert **BackupFailedOrStale** if missing or >25h old.

## Failed backups {#failed-backups}

1. Read `/var/log/parkio-backup.log` or script stdout.
2. Check `parkio_backup_last_success{scope="hosted-beta"}`.
3. `docker compose ps` for postgres-* and minio.
4. Re-run `backup-hosted-beta.sh`.
5. Offsite only if `BACKUP_MC_DEST` is a real configured alias.
