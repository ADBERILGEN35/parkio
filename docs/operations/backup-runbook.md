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
- offsite must be configured (`BACKUP_MC_DEST`, Azure Blob, **or** `BACKUP_OFFSITE_KIND=gcs` with `BACKUP_GCS_DEST`) — otherwise **backup fails**
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

Since 2026-11-23 production runs on Google Compute Engine and the offsite copy goes to Google
Cloud Storage (owner decision). The earlier Azure Blob target no longer exists; the `azure` kind
stays in the scripts, and `s3`/mc is what the CI drill uses. See
[Google Cloud Storage offsite](#google-cloud-storage-offsite-gcs).

| Property | Value |
|----------|--------|
| Provider | Google Cloud Storage, offsite kind `gcs` |
| Failure domain | Bucket in **europe-west1**; the VM runs in **europe-west4** |
| TLS | HTTPS through `gcloud storage` (default certificate verification; do not disable) |
| Encryption at rest | Cloud Storage default encryption; DB dumps and the MinIO artifact also client-side AES-256-CBC |
| Protection | 14-day bucket retention policy, 7-day soft delete, uniform access, public access prevention |
| Retention | Bucket lifecycle deletes objects at 35 days; local prune at 14 days |
| Credentials | None stored: the VM's attached service account (create, read and list objects only) |

Bucket and prefix are operator config in the env file.

MinIO objects are client-side encrypted too: with `BACKUP_ENCRYPT_PASSPHRASE` set (always so in
production mode), the mirror is sealed into `minio.tar.gz.enc` with the same AES-256-CBC passphrase
before `COMPLETE`, and the plaintext `minio/` tree is removed. An offsite upload refuses a stamp
that still holds a plaintext `minio/` tree.

## Required env

| Variable | Purpose | Default |
|----------|---------|---------|
| `BACKUP_DIR` | Local destination | `./backups` |
| `BACKUP_RETENTION_DAYS` | Local prune | `14` |
| `BACKUP_OFFSITE_RETENTION_DAYS` | Documented offsite lifecycle | `14` |
| `BACKUP_PRODUCTION_MODE` | Fail-closed encrypt+offsite | `0` (dev) |
| `BACKUP_ENCRYPT_PASSPHRASE` | AES-256-CBC PBKDF2 for **DB dumps** and **MinIO** offsite artifact | empty (off) |
| `BACKUP_OFFSITE_KIND` | `s3` / `azure` / `gcs` / empty=auto (auto never picks `gcs`) | auto |
| `BACKUP_GCS_DEST` | `gs://<bucket>/<prefix>` for the `gcs` kind | empty |
| `BACKUP_GCLOUD_BIN` | Absolute path of `gcloud` for the `gcs` kind (optional) | `gcloud` on `PATH`, then `/snap/bin/gcloud` |
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

## Offsite outage

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
   the next 03:30 UTC run. If any outage stamp must be kept, the schedule stays held until
   the step 5 copies exist.
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
   - Only the command line counts. A `NO_PRUNE`, `DRY_RUN`, `OPERATOR`, `ARTIFACT_DIR`, `ROOT`
     or `ENV_FILE` line in the env file is ignored with a `WARN`. Without `--operator` and
     `--artifact-dir`, those values come from `PARKIO_BACKUP_OPERATOR` and
     `PARKIO_BACKUP_ARTIFACT_DIR` in the caller's environment, else `$USER` (`unknown` when
     `USER` is unset) and `backup-artifacts` under the checkout; never from the env file. Without
     `--env-file`, the env file is `PARKIO_ENV_FILE` from the caller's environment, and the steps
     always run from the orchestrator's own checkout. The env file also cannot change what the
     orchestrator hands its steps: `BACKUP_SKIP_MC_UPLOAD` and `BACKUP_DEST_DIR` for
     `backup-databases.sh`, and the destination and the dry-run decision for `backup-minio.sh`,
     which takes both from its command line only. A `DRY_RUN=1` line cannot skip the MinIO
     mirror, and a `DEST_DIR` line cannot move it out of the stamp.
   - A line that assigns one of the scripts' private `arg_*` names stops that script at the line
     with exit 1: the orchestrator before it writes anything, `backup-minio.sh` before it mirrors
     (the run then fails like any MinIO failure, without `COMPLETE`).
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
   reached offsite. Copy any stamp the owner wants to keep to a place outside `BACKUP_DIR`
   first: the prune removes every directory in `BACKUP_DIR` older than the retention, not
   only stamps.

The upload never touches another stamp. Azure `upload-batch` and `upload` write only under
`<container>/<stamp>/`; `mc mirror` (without `--remove`) and `mc cp` write only under
`<BACKUP_MC_DEST>/<stamp>/`; `gcs` creates only new objects under `<BACKUP_GCS_DEST>/<stamp>/`
and fails rather than replace one. The backup scripts never delete remote objects. `--overwrite`
(azure, s3) can replace objects only under a stamp of the same name. A stamp name is the run's start
time in UTC seconds, so that takes two runs starting in the same second; runs that take the
shared lock cannot. Old remote objects expire through the destination's lifecycle
rule (see Offsite destination), not through these scripts.

## Google Cloud Storage offsite (`gcs`)

In the env file the backup uses:

```bash
BACKUP_OFFSITE_KIND=gcs
BACKUP_GCS_DEST=gs://parkio-prod-backups-euw1/stamps
# BACKUP_GCLOUD_BIN=/snap/bin/gcloud   # optional: without it, gcloud on PATH, then /snap/bin/gcloud
```

`gcs` is used only when `BACKUP_OFFSITE_KIND=gcs` says so; it is never picked from other
settings. A run with a malformed `BACKUP_GCS_DEST` (it must be `gs://<bucket>/<prefix>`) or
without an executable `gcloud` stops at preflight with exit 2, before it dumps anything, in
production mode and outside it. `--dry-run` runs that preflight and writes only a dry-run manifest.
Cron's `PATH` (`/usr/bin:/bin`) has no `/snap/bin`, which is why the script looks there too.

**Keyless.** No key, token or HMAC secret is stored, and the scripts pass no credential to
`gcloud`: it uses its active account. On the VM that is the attached service account, through
the metadata server. Its only roles on the bucket are `roles/storage.objectCreator` and
`roles/storage.objectViewer`: it can create, read and list objects, but cannot delete or replace
them or read the bucket's metadata. On the host, as root (the backup's user), `gcloud auth list`
must show that service account as the only credentialed account. The env file must set no
`CLOUDSDK_*` variable at all: it is sourced with `set -a`, so any such line reaches `gcloud` and can
switch its account, configuration directory or credential file.

**Bucket protections**, set on the bucket and not by these scripts:

- a 14-day bucket retention policy: no object can be deleted or replaced for 14 days after upload;
- 7-day soft delete: a deleted object can be restored for 7 more days;
- a lifecycle rule that deletes objects 35 days after upload, which is what ages old stamps out;
- uniform bucket-level access and public access prevention.

**What a run uploads.** Each file of the sealed stamp becomes one object, `<prefix>/<stamp>/<file>`,
copied with `gcloud storage cp --if-generation-match=0`. If that object already exists, the copy
fails (HTTP 412) and the object is kept; the run then counts as an offsite failure (no receipt,
no prune, `parkio_backup_offsite_last_success=0`). `--no-clobber` is not used, because it skips an
existing object and still succeeds. Parallel composite uploads are off: they would need bucket
reads and object deletes. The stamp must be flat (regular files only), as an encrypted production
stamp is. `COMPLETE` goes last, and only after a listing shows every other file at its local size.
A second listing must then show the whole stamp before the receipt beside it
(`<stamp>.offsite-receipt.json`, target `gs://<bucket>/<prefix>/<stamp>`) is written.

**Verify a stamp remotely.** These only read; the service account may run them. The two scripts
take the offsite settings from the env file the backup uses, so run them from `/opt/parkio` with
`PARKIO_ENV_FILE` set (or `--env-file`); without it they find no offsite.

- Names and sizes: `gcloud storage ls --long --recursive gs://parkio-prod-backups-euw1/stamps/<stamp>/`.
- The dated record: `PARKIO_ENV_FILE=docker/.env.azure-hosted-beta scripts/backup-dated-acceptance.sh
  --stamp-dir /var/backups/parkio/<stamp> --evidence <new dir> --work <new dir>` compares the
  listing with `SHA256SUMS` (outcome 2) and verifies a pulled copy (outcome 3); see
  [backup-dated-acceptance.md](./backup-dated-acceptance.md).
- A copy: `scripts/backup-offsite-pull.sh --env-file docker/.env.azure-hosted-beta --stamp <stamp>
  --dest <new directory>` downloads each object and runs `sha256sum -c SHA256SUMS`.

**Replacing the interim job.** Until this kind is deployed, `/etc/cron.d/parkio-gcs-interim` runs
`/usr/local/sbin/parkio-gcs-interim-sync.sh` at 04:20 UTC under the backup lock. It copies
`/var/backups/parkio` into `gs://parkio-prod-backups-euw1/stamps` without replacing objects: the
same `stamps/<stamp>/` layout, plus each `<stamp>.offsite-receipt.json` beside its stamp prefix,
outside every stamp's listing. The outage stamps are also archived under `archive/civo-outage-20261123/`.
Once the env file says `gcs`, a scheduled run prunes, so do steps 1 to 4 with the schedule held,
or all of them before the next 03:30 UTC run:

1. Update the checkout with the `gcs` kind and set the env lines above. Remove the Azure lines;
   that subscription no longer exists.
2. Run `backup-hosted-beta.sh --dry-run` under the lock, with the scheduled run's environment. The
   preflight must pass.
3. Make the first real run the bounded `--no-prune` run of [Offsite outage](#offsite-outage).
4. Verify that stamp remotely, as above.
5. Then remove the interim job: delete `/etc/cron.d/parkio-gcs-interim` and
   `/usr/local/sbin/parkio-gcs-interim-sync.sh`. While both run, the 03:30 run uploads its stamp
   first and the 04:20 copy skips those objects. If a `gcs` upload stops partway, the next interim
   copy completes that stamp, `COMPLETE` included, but no receipt is written for it. Such a stamp
   is unverified, even though its remote copy looks complete, until its dated acceptance
   (outcomes 2 and 3) passes.
6. Release the schedule. Pruning resumes with the next scheduled run (Offsite outage, step 5).
