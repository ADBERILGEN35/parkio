# Follow-up: truthful post-upload offsite receipt

**Status:** open, not in the F-02 COMPLETE-gate patch.
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

## Out of scope here

Do not expand F-02 into a storage redesign, receipt object, or stamp
rewrite. A later change may write a post-upload receipt beside the
stamp or refresh only a non-hashed sidecar after COMPLETE. Any such
work must keep COMPLETE immutable after finalize.
