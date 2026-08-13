# Azure Backup and Exit Plan

## Data classification

| Data | Preserve? | Method | Restore target |
|---|---|---|---|
| Ten PostgreSQL/PostGIS databases | **Yes** | repository `pg_dump` backup; encrypted; verify every dump | PostgreSQL 16; parking restore must prove PostGIS objects |
| MinIO media bucket | **Yes** | `backup-minio.sh` mirror; checksum/object count | MinIO, AWS S3, Oracle Object Storage S3 compatibility, or VPS MinIO |
| Redis | Usually no | sessions/cache/rate limits can expire; preserve AOF only if an explicit session RPO requires it | Redis 7 |
| Kafka logs | Usually no | drain consumers, verify outbox/DLT; retain only incident evidence | compatible Kafka if required |
| Grafana dashboards/config | Yes | repo provisioning JSON plus export any UI-created dashboards | Grafana |
| Prometheus TSDB | No for migration | export screenshots/query evidence only | discard after evidence |
| Loki logs / Tempo traces | No by default | export only incident/legal evidence | disabled in constrained profile |
| Caddy certificates | No for migration | keep until DNS cutover; new host can reissue | new Caddy data volume |
| `.env` and secrets | config yes, values only in encrypted operator vault | encrypted bundle; never Git | rotate all secrets at new target |
| deploy/backup manifests and smoke evidence | Yes | copy off-host | audit trail |

## Backup policy

- RPO: nightly maximum for databases/media; additionally back up before every deploy, destructive operation, and exit.
- RTO: not proven. Measure the parking restore drill and full restore before beta. The single operator must record actual durations.
- Keep at least three verified daily sets off the Azure VM. A backup on the same managed disk is staging, not disaster recovery.
- Canonical offsite: Azure Blob in dedicated RG `rg-parkio-backups` (westeurope; VM is francecentral). TLS 1.2+, SSE, versioning, 14-day lifecycle. Set `BACKUP_PRODUCTION_MODE=1` so encryption + offsite are fail-closed.
- Use `BACKUP_ENCRYPT_PASSPHRASE`; keep the passphrase separately from archives. Never git.
- Do not use Azure Backup for this 30-day plan: its protected-instance/storage cost and crash-consistent VM focus are inferior to the existing app-consistent logical path for portable exit.

## Nightly procedure

```bash
cd /opt/parkio
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta \
PARKIO_ENV_FILE=docker/.env.azure-hosted-beta ./scripts/backup-hosted-beta.sh
jq . backup-artifacts/backup-current.json
```

Offsite pull (preferred over scp from the VM):

```bash
PARKIO_ENV_FILE=docker/.env.azure-hosted-beta \
  ./scripts/backup-offsite-pull.sh --stamp <BACKUP_STAMP> --dest /tmp/parkio-restore-<BACKUP_STAMP>
sha256sum -c /tmp/parkio-restore-<BACKUP_STAMP>/SHA256SUMS
```

Optional extra copy from the operator machine (does not replace Blob offsite):

```bash
mkdir -p <LOCAL_ENCRYPTED_BACKUP_ROOT>/<YYYY-MM-DD>
scp -r <SSH_USER>@<PUBLIC_IP>:/var/backups/parkio/<BACKUP_STAMP> \
  <LOCAL_ENCRYPTED_BACKUP_ROOT>/<YYYY-MM-DD>/
scp <SSH_USER>@<PUBLIC_IP>:/opt/parkio/backup-artifacts/backup-current.json \
  <LOCAL_ENCRYPTED_BACKUP_ROOT>/<YYYY-MM-DD>/
```

Verify local file sizes/hashes without decrypting into an unprotected location. Weekly and before exit:

```bash
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta \
PARKIO_ENV_FILE=docker/.env.azure-hosted-beta ./scripts/restore-drill.sh --keep-backups
```

## Migration mapping

| Destination | Applications | PostgreSQL | Media | Kafka/Redis | Required validation |
|---|---|---|---|---|---|
| AWS | EC2 Compose initially; later ECS | RDS PostgreSQL with PostGIS or self-hosted | S3, update S3 endpoint/signing | MSK/ElastiCache later or self-hosted | restore dumps, S3 signed URLs, Kafka SASL/TLS, DNS/CORS |
| Oracle Cloud | AMD64 VM preferred until ARM verified | self-hosted PostgreSQL/PostGIS | Object Storage S3 compatibility or MinIO | self-hosted | image architecture, endpoint/signing, restore, firewall |
| Generic VPS | current Compose path | existing containers or one server/ten DBs after proof | MinIO | current containers | Docker/Compose version, disk mount, restore, DNS/TLS |

DNS names isolate clients from provider moves. Build the new environment, restore, run full smoke, lower TTL, freeze writes if necessary, perform final delta backup, switch A records, observe, then destroy Azure.

## Exit sequence (days 26-30)

1. Stop new tester invitations and announce maintenance.
2. Record Azure cost/resources and application health.
3. Run final database/MinIO backup and full restore drill.
4. Copy backups, env template, encrypted secrets, manifests, dashboards, cost evidence, and incident notes off Azure.
5. Verify hashes and open sample restored records/object on the destination.
6. Remove or repoint Hostinger `app/api/media` records; verify authoritative DNS.
7. Revoke Resend/Expo/MapTiler/alert credentials and rotate JWT/gateway/DB/Redis/MinIO/waitlist secrets if continuing elsewhere.
8. Deallocate the VM while final deletion approval is checked.
9. Delete the entire resource group.
10. Search the subscription for residual billable resources and verify cost for 48 hours.

## Emergency cleanup

```bash
az vm deallocate -g "$RESOURCE_GROUP" -n "$VM_NAME"
az vm get-instance-view -g "$RESOURCE_GROUP" -n "$VM_NAME" \
  --query instanceView.statuses[?starts_with(code,'PowerState/')].displayStatus -o tsv
az resource list -g "$RESOURCE_GROUP" -o table
az group delete -n "$RESOURCE_GROUP" --yes --no-wait
az group exists -n "$RESOURCE_GROUP"
```

Portal: **Resource groups -> `<RESOURCE_GROUP>` -> Delete resource group**. Deleting only the VM is insufficient.

## Full cleanup checklist

```text
[ ] VM deleted
[ ] OS and data disks deleted
[ ] snapshots / image versions deleted
[ ] static public IP deleted
[ ] NIC deleted
[ ] NSG deleted
[ ] VNet/subnet deleted
[ ] storage accounts / containers deleted
[ ] Recovery Services / Backup vaults and protected items deleted
[ ] Log Analytics workspace / diagnostic settings deleted
[ ] managed identities / role assignments deleted
[ ] Container Registry, Key Vault, private endpoints, DNS zones absent or deleted
[ ] budget removed after final cost settles
[ ] resource group absent
[ ] Hostinger Azure A records removed/repointed
[ ] third-party secrets revoked/rotated
[ ] subscription resource inventory and cost analysis show no unintended resources
```

Do not delete local/off-provider backups until the destination restore is accepted and retention obligations are defined.
