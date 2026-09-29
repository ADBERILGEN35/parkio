# FU-1 post-install production backup acceptance (read-only)

**Install cutoff:** 2026-09-23T19:01:12Z
**First scheduled fire:** 2026-09-24T03:30:00Z
**Acceptance stamp:** `2026-09-24T03-30-01Z`
**Constraint:** no stamp rewrite, decrypt, download, restore, new backup,
or secret retrieval. Web and Alertmanager were not restarted.

Pre-install stamp `2026-09-23T03-30-01Z` is context only and is **not**
acceptance.

This record reuses the existing operator observations. It does not
re-poll Azure, the host, or the sealed stamp.

## Decision

| Gate | Result |
|---|---|
| Local integrity | **PASS** (host read-only; privileged MinIO SHA via existing `sudo -n`) |
| Independent remote object presence | **PASS** (operator-observed Azure Portal listing) |
| Remote byte-integrity | **not claimed** |
| Decryption | **not claimed** |
| Isolated real restore | **not claimed** |
| Entra / Azure CLI listing | **not verified** |

Presence is not integrity. Integrity is not restore.

## Active Azure destination

Host env names (non-secret) and the operator Portal view agree:

| Item | Value |
|---|---|
| Storage account | `parkiobackupprod2026` |
| Resource group | `rg-parkio-backup-ne` |
| Region | northeurope |
| Subscription ID | `483040c9-ef2c-4e48-9edd-acf46201bda0` |
| Subscription display name | Azure subscription 1 |
| Container | `parkio-production-backups` |
| Stamp prefix | `2026-09-24T03-30-01Z/` |
| Uploader log | `azure://parkio-production-backups/2026-09-24T03-30-01Z` auth=SAS |

`stparkiobakwesteu` / `rg-parkio-backups` / subscription
`2b3abb5c-8a52-4c03-91c7-f53fd440a7e9` is a **different** leftover
account. Its earlier `AccountIsDisabled` listing is **not** the FU-1
destination and is not transferred here.

Portal authentication shown for the listing was **Access key**, not
Microsoft Entra. Do not treat that as Entra or `az --auth-mode login`
proof. Do not retrieve keys or SAS. Do not infer that leftover account
is disabled or that this account is unused.

## 1. Scheduled run

Observed `2026-09-24T06:55:03Z` on `parkio-civo-prod`. **NOT RUNNING.**
Lock free (mtime is the install flock). Cron unchanged
(`30 3 * * *` → `run-production-backup.sh`).

Stamp `2026-09-24T03-30-01Z` is **COMPLETE**. Uploader then logged
success to the destination above. Telemetry
`parkio_backup_last_success=1`, `offsite_last_success=1` is uploader
evidence only.

## 2. Local integrity (PASS)

Retained from the authorized host read-only pass. The sealed stamp was
not modified.

| Check | Result |
|---|---|
| COMPLETE | present |
| 10 DB dumps | `*.sql.gz.enc` present; log all 10 **OK**; `databasesFailed=0` |
| MinIO | `minioOk=1`; `minio.tar.gz.enc` + `minio-encryption.json` present |
| Erasure ledger | present; valid empty JSON array (structure only; no entries printed) |
| SHA256SUMS | all entries matched, including root-readable MinIO files |
| Stamp `offsite.uploaded` | **false** (known defect; do not edit the stamp) |
| Live `backup-current.json` `offsite.uploaded` | **true** (same stamp; not a stamp rewrite) |

`offsite.uploaded=false` on the sealed stamp is the known copy-before-upload
defect. Presence is proven by COMPLETE + the Portal listing, not by that
flag. Do not rewrite the stamp. See
`docs/operations/backup-offsite-uploaded-receipt-follow-up.md`.

## 3. Independent remote object presence (PASS, operator-observed)

Azure Portal listed **27 active blobs** under
`parkiobackupprod2026` / `parkio-production-backups` /
`2026-09-24T03-30-01Z/`.

That is object-presence proof only. It does not prove downloaded
byte-integrity against `SHA256SUMS`, successful decryption, or a
real restore.

## 4. F-02 relation

This first post-FU-1 stamp is a **successful** full run (`minioOk=1`).
It does not contradict audit F-02: a later failed MinIO or DB/ledger
stage could still have been sealed as COMPLETE before this gate fix.

F-03 unsafe restore entrypoints remain **open**. This record does not
authorize restore, download, or stamp rewrite.

## 5. Remaining recovery gates

1. Isolated real restore of this stamp (decrypt + client-version match +
   erasure replay) on a non-production target. Not authorized here.
2. Remote byte-integrity (download hashes vs `SHA256SUMS`) if a later
   restore requires it. Not done; do not download now.
3. Entra / CLI listing of the same prefix, if operators want that path.
   Portal Access-key listing already closed object presence.
4. Sealed-stamp `offsite.uploaded=false` remains a tooling follow-up.
   Do not edit `2026-09-24T03-30-01Z`.
5. #104 stays **HOLD** (draft, production-disabled). Dedicated
   ops/erasure remote container, WORM/retention choices, live systemd
   adapter, and flag enablement are separate and not opened by this
   FU-1 closeout or by the F-02 COMPLETE-gate draft.
