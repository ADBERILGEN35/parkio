# Backup and Restore Status

**Docker volumes are not backups.** This document records durability posture per
datastore. Detailed procedures: [backup-runbook.md](backup-runbook.md),
[restore-runbook.md](restore-runbook.md).

**Hosted-beta:** logical `pg_dump` for **10** service databases (see matrix below).
This is **not** managed provider PITR. Production managed Postgres topology/provider
is decided in [ADR-PP-01A](../architecture/adr/ADR-PP-01A-managed-postgresql.md)
(**ACCEPTED WITH CONDITIONS**); **PP-01 implementation remains open**. PP-01A does
not close public production NO-GO.

## Authoritative Datastores


| Store | Database / bucket | Backup method | Schedule | RPO status | RTO status | Restore verified |
|-------|-------------------|---------------|----------|------------|------------|------------------|
| auth | `parkio_auth` | `scripts/backup-databases.sh` pg_dump | Cron 03:30 VPS (`backup-hosted-beta.sh`) | PROPOSED | UNVERIFIED | CI drill (PROD-RESTORE-DRILL-01) |
| gateway | `parkio_gateway` | same | same | PROPOSED | UNVERIFIED | CI drill (must start `postgres-gateway`) |
| user | `parkio_user` | same | same | PROPOSED | UNVERIFIED | CI drill |
| parking | `parkio_parking` (PostGIS) | same | same | PROPOSED | UNVERIFIED | CI drill |
| media metadata | `parkio_media` | same | same | PROPOSED | UNVERIFIED | CI drill |
| gamification | `parkio_gamification` | same | same | PROPOSED | UNVERIFIED | CI drill |
| notification | `parkio_notification` | same | same | PROPOSED | UNVERIFIED | CI drill |
| moderation | `parkio_moderation` | same | same | PROPOSED | UNVERIFIED | CI drill |
| analytics | `parkio_analytics` | same | same | PROPOSED | UNVERIFIED | CI drill |
| ai-validation | `parkio_aivalidation` | same | same | PROPOSED | UNVERIFIED | CI drill |
| MinIO objects | `MINIO_BUCKET` | `scripts/backup-minio.sh` | same | PROPOSED | UNVERIFIED | isolated CI (`restore-drill-minio.sh`); live restore unproven |
| WP-05 ledgers | parking DB tables V20–V26 | included in parking dump | same | PROPOSED | UNVERIFIED | IT only |

## Derived / Rebuildable

| Store | Authority | Rebuild |
|-------|-----------|---------|
| Redis | Cache, rate limits, idempotency keys | Cold miss; not authoritative |
| Kafka | Event transport | Retention-bound; outbox is source for republish |
| Prometheus TSDB | Metrics | Re-scrape; recording rules as code |
| Grafana dashboards | Git JSON | Re-provision from `docker/grafana/` |

## Encryption

Dev/CI: optional `BACKUP_ENCRYPT_PASSPHRASE` for DB dumps.

`BACKUP_PRODUCTION_MODE=1`: encryption **required** (fail-closed). MinIO mirror is not client-side encrypted; offsite SSE + TLS apply. See backup-runbook.

## Restore Order

1. PostgreSQL instances (auth → user → parking → … per service dependency)
2. Run Flyway on each service startup
3. MinIO restore before media-dependent flows
4. Kafka topics auto-provision (`KafkaTopicsConfig`)
5. Verify readiness endpoints

## Verification

- `scripts/verify-backup.sh` — single DB dump test
- `.github/workflows/backup-restore-drill.yml` — automated drill
- `.github/workflows/staging-verification.yml` — WP-06.2 evidence pipeline (WP-06.2)
- `scripts/staging/run-verification-pipeline.sh` — local/scheduled evidence orchestration
- Metric: `parkio_backup_last_success` — alert **BackupFailedOrStale**

## Ownership

| Item | Status |
|------|--------|
| Backup schedule on VPS | OPERATOR |
| Offsite `BACKUP_MC_DEST` / Azure Blob | Azure Blob in `rg-parkio-backups` (westeurope); CI uses ephemeral MinIO |
| Production RPO/RTO approval | NOT APPROVED |
