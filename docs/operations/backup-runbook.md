# Backup runbook (R5.2)

Hosted-beta backups protect **10** Postgres databases (logical `pg_dump`) and MinIO media objects.

This is **not** managed PITR. Public production still requires PP-01.

Four modes (do not confuse them):

| Mode | Meaning |
|------|---------|
| LOCAL BACKUP | Stamp under `BACKUP_DIR` on the application VM |
| OFFSITE PROTECTED BACKUP | Encrypted stamp uploaded to a destination that survives VM/disk loss |
| ISOLATED RESTORE DRILL | CI / disposable DBs; never hosted-beta |
| EMERGENCY RESTORE | Operator-confirmed overwrite of live data — see restore-runbook |

A local copy on the same VM disk is **staging**, not disaster recovery.

## Canonical command (operator cron)

**One schedule. One entrypoint.**

```bash
# /etc/cron.d/parkio-backup
30 3 * * * root cd /opt/parkio && \
  PARKIO_ENV_FILE=docker/.env.azure-hosted-beta \
  BACKUP_PRODUCTION_MODE=1 \
  ./scripts/backup-hosted-beta.sh >> /var/log/parkio-backup.log 2>&1
```

Do **not** cron `backup-databases.sh` alone.

CI weekly drill: `.github/workflows/backup-restore-drill.yml` at `23 4 * * 1` (UTC). Isolated — never hosted-beta.

## Production-intended backup mode (fail-closed)

`BACKUP_PRODUCTION_MODE=1` (required on azure-hosted-beta cron):

- `BACKUP_ENCRYPT_PASSPHRASE` must be set — otherwise **backup fails** (no plaintext dumps)
- offsite must be configured (`BACKUP_MC_DEST` **or** Azure Blob) — otherwise **backup fails**
- offsite upload failure → overall backup **FAILED** (local-only is not “protected”)
- dump checksums are required
- stamp is incomplete until `COMPLETE` exists

Local/dev defaults remain optional (`BACKUP_PRODUCTION_MODE=0` or unset).

Secrets live in operator `.env` / Key Vault / GitHub Actions secrets. **Never git, logs, or CI artifacts.**

## Sequence

```
backup-hosted-beta.sh
  1. preflight (production mode: encrypt + offsite required)
  2. pg_dump × 10 → <stamp>/*.sql.gz.enc + .sha256
  3. mc mirror MinIO → <stamp>/minio/<bucket>/
  4. backup-manifest.json + SHA256SUMS + COMPLETE (COMPLETE last)
  5. offsite upload of the COMPLETE stamp (dumps + minio + checksums)
  6. Prometheus textfile
```

Consumers must refuse a remote stamp without `COMPLETE`.

## Offsite destination (approved)

| Property | Value |
|----------|--------|
| Provider | Azure Blob Storage (S3/mc also supported for CI) |
| Failure domain | Dedicated resource group `rg-parkio-backups`, region **westeurope** (VM is **francecentral**) |
| TLS | HTTPS, TLS 1.2+, Azure CLI default verification (do not disable) |
| Encryption at rest | Azure SSE (Microsoft-managed); DB dumps also client-side AES-256-CBC |
| Versioning | Enabled on the backup account |
| Retention | 14 days blob lifecycle (offsite) + 14 days local prune |
| Cost | Standard LRS; cents/month at current dump size |

Account/container names are operator config (not committed with keys).

MinIO objects are **not** client-side encrypted by backup scripts. Protection = TLS in transit + Azure SSE at rest. Do not claim `.enc` covers MinIO.

## Required env

| Variable | Purpose | Default |
|----------|---------|---------|
| `BACKUP_DIR` | Local destination | `./backups` |
| `BACKUP_RETENTION_DAYS` | Local prune | `14` |
| `BACKUP_OFFSITE_RETENTION_DAYS` | Documented offsite lifecycle | `14` |
| `BACKUP_PRODUCTION_MODE` | Fail-closed encrypt+offsite | `0` (dev) |
| `BACKUP_ENCRYPT_PASSPHRASE` | AES-256-CBC for **DB dumps** | empty (off) |
| `BACKUP_OFFSITE_KIND` | `s3` / `azure` / empty=auto | auto |
| `BACKUP_MC_DEST` | `mc` alias/bucket | empty |
| `BACKUP_AZURE_STORAGE_ACCOUNT` | Azure account | empty |
| `BACKUP_AZURE_CONTAINER` | Azure container | empty |
| `MINIO_ROOT_PASSWORD` | MinIO mirror | — |

## Isolated verification (no live overwrite)

```bash
PARKIO_ENV_FILE=docker/.env ./scripts/restore-drill.sh --keep-backups
PARKIO_ENV_FILE=docker/.env ./scripts/restore-drill-minio.sh
PARKIO_ENV_FILE=docker/.env ./scripts/restore-drill-failure-modes.sh
PARKIO_ENV_FILE=docker/.env ./scripts/restore-drill-offsite.sh
```

`restore-drill-offsite.sh` encrypts, uploads, **deletes the local stamp**, pulls to a new directory, verifies checksums, restores all 10 DBs + MinIO from the retrieved copy.

## How to know a backup is actually protected

1. Local stamp has `COMPLETE` + `SHA256SUMS` + only `*.sql.gz.enc` (production mode).
2. Offsite listing shows the same stamp and `COMPLETE`.
3. After deleting the local stamp, `backup-offsite-pull.sh` succeeds and `sha256sum -c SHA256SUMS` passes.
4. `parkio_backup_last_success=1` **and** `parkio_backup_offsite_last_success=1` **and** `parkio_backup_encryption_enabled=1`.

## Metrics

`docker/prometheus/textfile/parkio_backup.prom`:

- `parkio_backup_last_success`
- `parkio_backup_offsite_last_success`
- `parkio_backup_encryption_enabled`
- `parkio_backup_last_bytes`
- `parkio_backup_last_timestamp_seconds`
- `parkio_backup_production_mode`

Alerts (see [alerting.md](./alerting.md)): **BackupFailed**, **BackupStale**, **BackupOffsiteFailed**, **BackupOffsiteStale**, **BackupEncryptionDisabledInProduction**. Do not put credentials or secret paths in labels.

## Failed backups {#failed-backups}

1. Read `/var/log/parkio-backup.log` or script stdout.
2. Check `parkio_backup_last_success` and `parkio_backup_offsite_last_success`.
3. `docker compose ps` for postgres-* and minio.
4. Re-run `backup-hosted-beta.sh` with production mode still set.
5. Do not report success if only the local copy exists.
