# Backup runbook (R5.2)

Hosted-beta backups protect **10** Postgres databases (logical `pg_dump`) and MinIO media objects.

This is **not** managed PITR. Public production still requires PP-01.

Four modes (do not confuse them):

| Mode | Meaning |
|------|---------|
| LOCAL BACKUP | Stamp under `BACKUP_DIR` on the application VM |
| OFFSITE PROTECTED BACKUP | Encrypted stamp uploaded to a destination that survives VM/disk loss |
| ISOLATED RESTORE DRILL | CI / disposable DBs; never hosted-beta |
| EMERGENCY RESTORE | Operator-confirmed overwrite of live data: **BLOCKED** in production on the current code; see restore-runbook |

A local copy on the same VM disk is **staging**, not disaster recovery.

## Canonical command (operator cron)

**One schedule. One entrypoint.**

```bash
# /etc/cron.d/parkio-backup
30 3 * * * root flock -n /var/lock/parkio-backup.lock \
  /opt/parkio/scripts/run-production-backup.sh >> /var/log/parkio-backup.log 2>&1
```

`run-production-backup.sh` sets `PARKIO_ENV_FILE=docker/.env.azure-hosted-beta` and
`BACKUP_PRODUCTION_MODE=1`, then execs `backup-hosted-beta.sh`. Overlap protection is
`flock -n /var/lock/parkio-backup.lock` (second concurrent start exits immediately).

Equivalent inline form (avoid bare `%` in cron.d command text):

```bash
# /etc/cron.d/parkio-backup
30 3 * * * root flock -n /var/lock/parkio-backup.lock -c 'cd /opt/parkio && \
  PARKIO_ENV_FILE=docker/.env.azure-hosted-beta \
  BACKUP_PRODUCTION_MODE=1 \
  ./scripts/backup-hosted-beta.sh >> /var/log/parkio-backup.log 2>&1'
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
- full production `COMPLETE` also requires a successful MinIO capture/seal (`minioOk=1`) plus a valid erasure ledger
- a required-stage failure exits nonzero, does not upload, and does not prune previous good stamps
- `backup-databases.sh` standalone is DB+ledger only; it must not `COMPLETE` or prune after a dump/ledger failure

Local/dev defaults remain optional (`BACKUP_PRODUCTION_MODE=0` or unset).

Secrets live in operator `.env` / Key Vault / GitHub Actions secrets. **Never git, logs, or CI artifacts.**

## Sequence

```
backup-hosted-beta.sh
  1. preflight (production mode: encrypt + offsite required)
  2. pg_dump × 10 → <stamp>/*.sql.gz.enc + .sha256
  3. mc mirror MinIO → <stamp>/minio/<bucket>/ then seal to minio.tar.gz.enc
  4. COMPLETE only if dumps + valid ledger + MinIO + integrity succeed
  5. offsite upload of that COMPLETE stamp only (never a failed stamp)
  6. Prometheus textfile (success and failure both recorded truthfully)
  7. prune expired local stamps only after a successful complete run
     (--no-prune skips it; the manifest records "localPrune")
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
| `BACKUP_ENCRYPT_PASSPHRASE` | AES-256-CBC PBKDF2 for **DB dumps** and **MinIO** offsite artifact | empty (off) |
| `BACKUP_OFFSITE_KIND` | `s3` / `azure` / empty=auto | auto |
| `BACKUP_MC_DEST` | `mc` alias/bucket | empty |
| `BACKUP_AZURE_STORAGE_ACCOUNT` | Azure account | empty |
| `BACKUP_AZURE_CONTAINER` | Azure container | empty |
| `BACKUP_AZURE_SAS_TOKEN` | Preferred Azure auth (container SAS, rcwl) | empty |
| `AZURE_STORAGE_SAS_TOKEN` | Native Azure CLI SAS env (fallback) | empty |
| `BACKUP_AZURE_STORAGE_KEY` | Legacy account-key fallback | empty |
| `MINIO_ROOT_PASSWORD` | MinIO mirror | — |

Azure auth precedence: `BACKUP_AZURE_SAS_TOKEN` → `AZURE_STORAGE_SAS_TOKEN` → `BACKUP_AZURE_STORAGE_KEY` / `AZURE_STORAGE_KEY` → `az login`.
MinIO offsite uploads are client-side sealed to `minio.tar.gz.enc` (same passphrase); plaintext `minio/` is never uploaded.

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

Alerts (see [alerting.md](./alerting.md)): **BackupFailed**, **BackupStale**, **BackupOffsiteFailed**, **BackupOffsiteStale**, **BackupEncryptionDisabledInProduction**, **BackupTelemetryAbsent** (PA-16: node-exporter up but production backup series gone), **BackupRequiredSeriesMissing**, **BackupTextfileCollectorError**. Do not put credentials or secret paths in labels.

The authorized live acceptance of `BackupStale` delivery (synthetic second textfile for an unused production scope, FIRING and RESOLVED exactly once) is planned in [alerting-live-backup-stale-acceptance.md](./alerting-live-backup-stale-acceptance.md).

## Failed backups {#failed-backups}

1. Read `/var/log/parkio-backup.log` or script stdout.
2. Check `parkio_backup_last_success` and `parkio_backup_offsite_last_success`.
3. `docker compose ps` for postgres-* and minio.
4. Re-run `backup-hosted-beta.sh` with production mode still set. After an offsite outage,
   follow [Offsite outage](#offsite-outage) instead: a successful plain run prunes every stamp
   older than `BACKUP_RETENTION_DAYS`, whether or not it ever reached offsite.
5. Do not report success if only the local copy exists.

## Offsite outage and the first run after it {#offsite-outage}

While offsite uploads fail, every run fails and none prunes. A full local stamp is added to
the host disk every night, and none of these stamps has an offsite copy. Watch free space
on the disk that holds `BACKUP_DIR`: nothing removes these stamps until a run succeeds.

The first run whose offsite step succeeds would delete, at once, every local stamp older
than `BACKUP_RETENTION_DAYS` (14), before anyone has checked the new remote copy. Setting
`BACKUP_RETENTION_DAYS` for one run does not help: the env file sets it and wins over the
caller's value. Make the first run against the restored target a bounded run with
`--no-prune`:

1. Make sure it is the first run to reach the target. `--no-prune` protects only the run
   it is given to; a scheduled run against a working target prunes as usual. Hold the
   schedule while the target is restored (owner decision), or finish steps 3 and 4 before
   the next 03:30 UTC run.
2. Preflight: no backup is running (the lock is free), and the `BACKUP_DIR` disk has room
   for one more full stamp.
3. Run once, under the same lock and with the same environment as the scheduled run in
   `/etc/cron.d/parkio-backup`:

   ```bash
   flock -n /var/lock/parkio-backup.lock -c 'cd /opt/parkio && \
     PARKIO_ENV_FILE=docker/.env.azure-hosted-beta \
     BACKUP_PRODUCTION_MODE=1 \
     ./scripts/backup-hosted-beta.sh --no-prune >> /var/log/parkio-backup.log 2>&1'
   ```

   - The log shows `localPrune=skipped` at the start and `Local prune skipped (--no-prune)`
     at the end. The manifests (`backup-<stamp>.json`, `backup-current.json` and the sealed
     `<stamp>/backup-manifest.json`) record `"localPrune": "skipped"`; a run without the
     flag records `"after-success"`.
   - A checkout without this option rejects it (`ERROR: unknown argument '--no-prune'`,
     exit 2) before it writes or deletes anything. Do not drop the flag to get past that;
     update the checkout first.
   - Do not use `run-production-backup.sh` for this run: it does not pass arguments on, so
     the run would prune.
   - Do not run `backup-databases.sh` on its own: after a successful DB-only run it prunes
     `BACKUP_DIR`, and it has no `--no-prune`.
4. Verify the remote copy of that stamp (objects, sizes, hashes, `COMPLETE`, receipt) as
   [backup-dated-acceptance.md](./backup-dated-acceptance.md) describes.
5. Pruning resumes with the next scheduled run, after that verification. That run deletes
   every local stamp older than `BACKUP_RETENTION_DAYS`, including outage stamps that never
   reached offsite. Copy
   any stamp the owner wants to keep to a place outside `BACKUP_DIR` first: the prune
   removes every directory in `BACKUP_DIR` older than the retention, not only stamps.

The upload never touches another stamp. Azure `upload-batch` and `upload` write only under
`<container>/<stamp>/`; `mc mirror` (without `--remove`) and `mc cp` write only under
`<BACKUP_MC_DEST>/<stamp>/`. The backup scripts never delete remote objects. `--overwrite`
can replace objects only under a stamp of the same name. A stamp name is the run's start
time in UTC seconds, so that takes two runs starting in the same second; runs that take the
shared lock cannot. Old remote objects expire through the destination account's lifecycle
rule (see Offsite destination), not through these scripts.
