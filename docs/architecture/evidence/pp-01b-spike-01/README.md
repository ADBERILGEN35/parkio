# PP-01B-SPIKE-01 evidence index

Evidence snapshot date: **2026-08-04**.

This directory holds **pointers only**. No Azure portal exports, screenshots with
subscription IDs, credentials, pricing calculator PNGs, or CLI transcripts with
account identifiers are stored in Git.

| Item | Location | Class |
|------|----------|-------|
| Spike report (CLOSED) | [`../pp-01b-spike-01.md`](../pp-01b-spike-01.md) | Repository deliverable |
| Region / ZR HA / `$` legend | [Microsoft Learn overview](https://learn.microsoft.com/en-us/azure/postgresql/overview#azure-regions) | EXTERNAL VERIFICATION — **revalidate before paid apply** |
| HA / Burstable exclusion | [HA concepts](https://learn.microsoft.com/en-us/azure/postgresql/high-availability/concepts-high-availability) | EXTERNAL VERIFICATION — **revalidate** |
| PITR retention 7–35d | [Backup concepts](https://learn.microsoft.com/en-us/azure/postgresql/backup-restore/concepts-backup-restore) | EXTERNAL VERIFICATION — **revalidate** |
| PostGIS on PG16 | [Extensions by engine](https://learn.microsoft.com/en-us/azure/postgresql/extensions/concepts-extensions-by-engine) | EXTERNAL VERIFICATION — **revalidate** |
| Private access | [Private networking](https://learn.microsoft.com/en-us/azure/postgresql/connectivity/concepts-networking-private) | EXTERNAL VERIFICATION — **revalidate** |
| TLS | [TLS how-to](https://learn.microsoft.com/en-us/azure/postgresql/security/security-tls-how-to-connect) | EXTERNAL VERIFICATION — **revalidate** |
| Repo cost / region anchor | `docs/azure/AZURE-COST-MODEL.md`, `docs/azure/README.md` | REPOSITORY FACT |

**Temporal rule:** `$` region status and SKU/extension matrices change. Snapshot rows in the spike report are **not** permanent architecture facts.

Operator-held evidence (outside Git), if later collected:

- Pricing Calculator output (redact subscription)
- `az` list-skus / create dry-run (redact IDs)
- Confirmation that `$` ZR HA status changed for a region
