# Follow-up: truthful post-upload offsite receipt

**Status:** implemented in source (U14): a post-upload receipt beside the stamp.
Stamps written before this change have no receipt. Their sealed manifests still say
`offsite.uploaded=false`.
**Do not rewrite** production stamp `2026-09-24T03-30-01Z`.

## Defect

`backup-hosted-beta.sh` copies `backup-manifest.json` into the stamp
while `PARKIO_BACKUP_OFFSITE_UPLOADED=0`, uploads that copy, then
rewrites only the live artifact `backup-current.json` after upload.

Observed on the first post-FU-1 stamp:

- sealed stamp `offsite.uploaded=false`
- live `backup-current.json` `offsite.uploaded=true` for the same stamp

This is a manifest-copy timing defect. It does not overturn local
integrity or operator-observed remote object presence.

## The receipt

A stamp cannot record its own upload. `SHA256SUMS` and `COMPLETE` are written before the
upload, and `COMPLETE` never changes after finalize. The sealed `backup-manifest.json`
therefore keeps its seal-time `offsite.uploaded=false`, and the upload is recorded **beside**
the stamp, at `<BACKUP_DIR>/<stamp>.offsite-receipt.json`:

```json
{
  "schemaVersion": 1,
  "stamp": "<stamp>",
  "uploaded": true,
  "uploadedAt": "<UTC time the receipt was written>",
  "offsite": { "kind": "s3", "target": "<BACKUP_MC_DEST>/<stamp>" },
  "sealed": { "complete": "COMPLETE", "sha256sums": "<SHA256SUMS digest from COMPLETE>" }
}
```

For Azure, `target` is `azure://<container>/<stamp>`. The storage account is left out, as in the upload log.

Rules (`parkio_backup_write_offsite_receipt` in `scripts/lib/backup-common.sh`):

- **When it is written.** Only after `parkio_backup_offsite_upload` returned 0, in both the
  orchestrator and the standalone DB-only path.
- **Before an upload.** Any receipt for the stamp is removed first, so a failed upload leaves
  none.
- **Never inside the stamp.** `restore-stamp-preflight.py` fails on files that `SHA256SUMS` does
  not list. The stamp's files, `SHA256SUMS` and `COMPLETE` are never touched.
- **What it binds.** `sealed.sha256sums` is the digest that `COMPLETE` records. A receipt
  applies to a stamp only while `sha256(SHA256SUMS)` still equals that digest.
- **If it cannot be written,** the run counts the offsite step as failed. The live manifest
  says `uploaded=false`, `parkio_backup_offsite_last_success` is 0, the run exits 1,
  `backup-current.json` is not replaced, and nothing is pruned.
  - `offsite_last_success` and the live manifest's `offsite.uploaded` are 1/true exactly when
    a receipt was written.
- **Retention.** Pruning removes a receipt, or a receipt write that never finished, once its
  stamp directory is gone.
- **What it is not.** A receipt records that the upload commands succeeded. It is not
  independent proof of remote presence or remote byte integrity. The dated backup acceptance
  still lists and pulls the remote copy.
