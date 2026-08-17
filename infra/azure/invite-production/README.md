# Invite-production Azure foundation

This is the dedicated `PROD-DEPLOY-01A` foundation. It does not reuse the
hosted-beta VM, database semantics, secrets, or backup namespace.

The template creates, in `rg-parkio-invite-production-we` / West Europe:

- one `Standard_E4bds_v5` Linux VM (4 vCPU, 32 GiB) with 128 GiB OS and
  256 GiB Premium SSD data storage;
- a static public IP whose NSG permits inbound TCP 443 only (no SSH rule and no
  public ports 8081-8089);
- one PostgreSQL 16 Flexible Server, General Purpose `Standard_D2ds_v5`, 128
  GiB auto-growing storage, 30-day PITR, HA disabled for the tiny invite;
- a delegated database subnet and linked private DNS zone; the server has
  `publicNetworkAccess=Disabled`;
- all ten empty logical application databases and the `POSTGIS` allow-list
  setting (roles and extension creation are performed by the separately audited
  database bootstrap);
- a Standard Key Vault using RBAC, purge protection, soft delete, and an
  app-subnet firewall; the VM receives `Key Vault Secrets User`;
- a dedicated Standard GRS backup account/container with OAuth-only access,
  versioning, 30-day delete retention, and an app-subnet firewall; the VM
  receives `Storage Blob Data Contributor`.

No public DNS record, domain validation, application deployment, data migration,
or traffic switch is part of this template.

## Cost boundary

The current 730-hour retail estimate is:

| Resource | Monthly estimate (EUR) |
|---|---:|
| `Standard_E4bds_v5` Linux VM | 255.00 |
| PostgreSQL GP `Standard_D2ds_v5` | 135.78 |
| VM disks, PostgreSQL storage, GRS backup, public IP, and monitoring reserve | 30-60 |
| **Expected incremental total** | **421-451** |

This remains inside the operator-authorized EUR 410-550 planning range.
`Standard_D4s_v5` is not used because the subscription currently has zero DSv5
family quota in West Europe; EBDSv5 quota is available.

## Validation and deployment

Compile without creating resources:

```bash
az bicep build --file infra/azure/invite-production/main.bicep
```

Use `scripts/azure/provision-invite-production.sh` for a redacted ARM what-if or
an explicitly confirmed apply. The wrapper creates a mode-0600 temporary
parameter document, generates the initial PostgreSQL administrator password,
passes it as a secure ARM parameter, has ARM store it directly in Key Vault,
and removes the temporary material without printing it.

The checked-in `main.parameters.example.json` is documentation only. Never put a
real password or private key into it.
