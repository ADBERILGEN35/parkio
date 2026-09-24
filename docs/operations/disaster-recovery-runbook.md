# Disaster recovery runbook (R5.2)

Single-VPS hosted-beta recovery. This is **not** multi-region HA; RPO/RTO depend on backup
cadence and operator response.

## Scenarios

| Scenario | Recovery path |
|----------|----------------|
| Bad deploy | `scripts/rollback-hosted-beta.sh` (image tags, no DB restore) |
| Data corruption (one DB) | `restore-database.sh <service> <dump>` |
| Full host loss | New VPS → restore compose + `restore-hosted-beta.sh` from offsite backup |
| MinIO only | `restore-hosted-beta.sh --only minio` |
| Kafka/outbox poison | DLQ/outbox runbooks; no full restore usually needed |

## RPO / RTO targets (beta)

| Asset | RPO | RTO (operator) |
|-------|-----|----------------|
| Postgres | Last successful nightly backup (~24h) | 1-2h restore + smoke |
| MinIO media | Same backup set | 30-60m mirror restore |
| Kafka topics | Ephemeral (outbox is source of truth) | Redeploy / replay from outbox |
| App images | `sha-*` tags + manifest | 30-60m redeploy |

## Full host rebuild (outline)

1. Provision VPS (see `docs/operations/runtime-sizing.md`).
2. Install Docker, clone repo at known good tag, copy `docker/.env` from secrets store.
3. `docker compose -f docker/docker-compose.yml -f docker/docker-compose.apps.yml -f docker/docker-compose.images.yml -f docker/docker-compose.hosted-beta.yml up -d`
4. Copy backup set from offsite (`BACKUP_MC_DEST`) to `BACKUP_DIR`.
5. `restore-hosted-beta.sh --manifest <stamp>/backup-manifest.json --recovery-cutoff <ISO-8601-UTC> --yes`
6. `deploy-hosted-beta.sh` if images must match current commit.
7. Smoke + alert silence removal.

## Evidence to keep

- `backup-artifacts/backup-*.json`
- `deploy-artifacts/deploy-*.json`
- Weekly `restore-drill.sh` CI result
- Post-incident: note git SHA, manifest timestamps, and root cause

## What backups do NOT cover

- In-flight Kafka messages not yet in outbox
- Ephemeral Redis state
- Grafana/Prometheus historical data (rebuilt from scratch)
- Secrets in `docker/.env` (store separately)
- SPA static assets in the `web` image (rebuild from git at known SHA)

## Exact recovery commands

### Bad deploy (no data loss)

```bash
cd /opt/parkio
PARKIO_ENV_FILE=docker/.env ./scripts/rollback-hosted-beta.sh
```

### Single database corruption

```bash
cd /opt/parkio
PARKIO_ENV_FILE=docker/.env ./scripts/restore-database.sh <service> \
  /var/backups/parkio/<stamp>/<service>.sql.gz.enc \
  --recovery-cutoff <ISO-8601-UTC>
```

### Full stack restore (same or new VPS)

```bash
# 1. Provision VPS (sections 1–4 of vps-hosted-beta-checklist.md)
# 2. Restore docker/.env from secrets store
# 3. Copy backup set to BACKUP_DIR
cd /opt/parkio
PARKIO_ENV_FILE=docker/.env ./scripts/restore-hosted-beta.sh \
  --manifest /var/backups/parkio/<stamp>/backup-manifest.json \
  --recovery-cutoff <ISO-8601-UTC> --yes
# 4. Redeploy known-good images if needed
PARKIO_ENV_FILE=docker/.env ./scripts/deploy-hosted-beta.sh
# 5. Smoke + remove alert silences
PARKIO_ENV_FILE=docker/.env ./scripts/smoke-hosted-beta.sh
```

### MinIO-only restore

```bash
PARKIO_ENV_FILE=docker/.env ./scripts/restore-hosted-beta.sh \
  --manifest /var/backups/parkio/<stamp>/backup-manifest.json \
  --recovery-cutoff <ISO-8601-UTC> --yes --only minio
```

## Contacts / secrets (owner)

- `docker/.env` on the VPS (not in git)
- Slack/webhook URLs for alerts
- `BACKUP_ENCRYPT_PASSPHRASE` if encryption enabled
- Offsite `mc` credentials for `BACKUP_MC_DEST`
