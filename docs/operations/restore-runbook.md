# Restore runbook (R5.2)

**Destructive.** Restoring overwrites live databases and MinIO objects. Take a fresh backup first.

**Production restore is BLOCKED on the current code** (no verified erasure coverage,
`verifiedCoverage=false`). Every command below that would apply data to production exits 3
before decrypt or apply; each is marked **BLOCKED** with the refusing guard from
`scripts/lib/restore-safe-preflight.sh`. What to do instead during an incident is in
[disaster-recovery-runbook.md](disaster-recovery-runbook.md#incident-response-while-data-recovery-is-blocked).

## Single database

The dump must sit inside a COMPLETE stamp. Production restore is BLOCKED
before decrypt or apply: a manifest timestamp is not verified coverage.
Standalone `restore-database.sh` does not replay erasures and is refused.
`--isolated-fixture` is synthetic only.

```bash
# BLOCKED on production: exits 3 (parkio_restore_refuse_standalone_database,
# parkio_restore_refuse_unverified_production). Shown for the argument shape only.
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

# 4. Isolated MinIO proof: BLOCKED outside CI. `restore-hosted-beta.sh --only minio`
#    exits 3 (parkio_restore_refuse_unsupported_production_scope) even with an isolated
#    MINIO_RESTORE_BUCKET; the isolated MinIO proof runs in CI (restore-drill-minio.sh).
```

## Full hosted-beta restore (EMERGENCY) — BLOCKED

A non-dry-run production restore exits 3 (`parkio_restore_refuse_unverified_production`),
even when the recovery cutoff equals the stamp clock. No environment flag bypasses it
(`PARKIO_ALLOW_LIVE_MINIO_RESTORE`, which earlier versions of this runbook cited, is not read
by any script). Shown for the argument shape only:

```bash
PARKIO_ENV_FILE=docker/.env \
  ./scripts/restore-hosted-beta.sh \
  --manifest /var/backups/parkio/<stamp>/backup-manifest.json \
  --recovery-cutoff <ISO-8601-UTC>
```

`--manifest` must be the copy you reviewed (usually `backup-manifest.json` inside
the stamp). The script refuses a stale `.destination` that points at a different
directory. A sealed stamp keeps its seal-time `offsite.uploaded=false`; it is not
local integrity proof and not independent remote presence. Do not rewrite stamps.
Newer stamps record the upload in `<stamp>.offsite-receipt.json` beside the stamp
directory. That records the upload commands' success, not remote presence.

Dry-run (supported: stamp preflight only, nothing is decrypted or applied):

```bash
./scripts/restore-hosted-beta.sh --manifest backup-artifacts/backup-current.json --dry-run
```

Partial (BLOCKED: `--only databases` exits 3 like the full restore; `--only minio` exits 3
via `parkio_restore_refuse_unsupported_production_scope`):

```bash
./scripts/restore-hosted-beta.sh --manifest ... --yes --only databases
MINIO_RESTORE_BUCKET=<isolated-or-live> \
  ./scripts/restore-hosted-beta.sh --manifest ... --yes --only minio
```

Live MinIO restore **overwrites the destination bucket**. Isolated drills must set `MINIO_RESTORE_BUCKET` to a throwaway name. A live MinIO restore is BLOCKED on the current code (see above); no flag enables it.

Operator stop points:

1. Verify `COMPLETE` + `SHA256SUMS` on the **offsite** copy.
2. Dry-run the manifest.
3. Restore databases into isolated verify DBs first (`restore-drill.sh --from-dir`, or the
   separately authorized [restore-drill-01-isolated-database.md](restore-drill-01-isolated-database.md)).
4. Live Postgres restore: BLOCKED on the current code. Stop here and escalate.
5. Live MinIO restore: BLOCKED on the current code.

## After restore

A successful data restore is **not** authorization to expose applications.
`restore-hosted-beta.sh` and `restore-database.sh` do not start applications,
publishers, schedulers, Slack, or Fluent Bit. A copy whose erasure tail after the backup is
unknown must stay unexposed; applying erasures after users are back on it is not accepted.

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
