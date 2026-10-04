# Dated backup acceptance (U14)

A dated record of five outcomes for **one** backup stamp:

| # | outcome | what it proves | what it does not prove |
|---|---|---|---|
| 1 | `complete` | The local stamp is sealed and full. `COMPLETE` binds `SHA256SUMS`, every listed file matches, nothing is unlisted, and the manifest is an encrypted full stamp (`minioOk=1`, all ten databases). This is `restore-stamp-preflight.py --scope full`. | Anything about the offsite copy. |
| 2 | `remote-presence` | The offsite listing for the stamp holds every `SHA256SUMS` entry plus `SHA256SUMS` and `COMPLETE`, each at the local byte size. Names and sizes only: nothing is downloaded. | That the remote bytes are right. |
| 3 | `remote-integrity` | The stamp pulled into a new directory passes its own checksums, and its `SHA256SUMS` digest and `COMPLETE` equal the local sealed stamp's. | That the passphrase still decrypts it. |
| 4 | `decrypt` | Every encrypted artifact of the **pulled** copy decrypts with the custody passphrase and decompresses: ten dumps plus `minio.tar.gz.enc`, streamed through `gzip -t` / `tar -tz`. No plaintext is written. | That the data restores and is consistent. |
| 5 | `isolated-restore` | `scripts/restore-drill-01.sh` on the **pulled** copy succeeds in the disposable container the operator names: preflight, privacy gate, restore, parity, erasure replay. | Anything about production: nothing live is touched. |

Each outcome is `PASS`, `FAIL`, `BLOCKED` (only 5: the drill's privacy gate) or `NOT_RUN` with a
reason. They are recorded independently: a `FAIL` in one never turns another into `PASS` or `FAIL`.
Outcomes 4 and 5 use only a pulled copy that passed 3; without one they are `NOT_RUN`, not `PASS`.
The verdict is `PASS` only when all five pass, `FAIL` when any fails, and otherwise `INCOMPLETE`.

The post-upload receipt beside the stamp (`<stamp>.offsite-receipt.json`, see
[backup-offsite-uploaded-receipt-follow-up.md](backup-offsite-uploaded-receipt-follow-up.md)) is
recorded when present, but no outcome relies on it: 2 and 3 check the offsite copy directly.

## Before running it

Reading a real stamp, pulling it, decrypting it and restoring it need **separate authorization**
(U14). This document and the script are the preparation only.

- [ ] Authorization names the host, the stamp, and outcomes 4 and 5 if they are wanted.
- [ ] The stamp is chosen and its directory is known, normally the newest `COMPLETE` stamp under `BACKUP_DIR`.
- [ ] The offsite settings come from the same env file the backup uses.
- [ ] For 4 and 5: the backup passphrase is available under its custody rules, entered as `BACKUP_ENCRYPT_PASSPHRASE` in the shell, never in a file or in evidence. While custody is unknown, run without `--decrypt` / `--isolated-restore`; those outcomes then stay `NOT_RUN` and the record says why.
- [ ] For 5: a disposable PostgreSQL/PostGIS container and the restore drill's inputs exist, as in [restore-drill-01-isolated-database.md](restore-drill-01-isolated-database.md): `PARKIO_DRILL_ID`, `--recovery-cutoff`, `--container`, and the ledger stamps if any. Production restore stays refused; `verifiedCoverage` stays false.
- [ ] Two **new** directories are chosen. `--evidence` keeps only secret-free files. `--work` holds the pulled copy and raw tool logs and is deleted afterwards.

## Running it

```bash
PARKIO_ENV_FILE=docker/.env.invite-production \
  scripts/backup-dated-acceptance.sh \
  --stamp-dir /var/backups/parkio/<stamp> \
  --evidence /root/acceptance/<date>-<stamp>/evidence \
  --work /root/acceptance/<date>-<stamp>/work \
  [--decrypt] \
  [--isolated-restore -- --recovery-cutoff <ISO-8601 UTC> --container <disposable container> [drill args]]
```

The script sets the drill's `--stamp` (the pulled copy), `--work` and `--evidence` itself. It
refuses them among the drill arguments.

Exit codes:
- `0`: all five `PASS`;
- `1`: at least one `FAIL`;
- `4`: no `FAIL`, but something is `BLOCKED` or `NOT_RUN`;
- `2`: usage.

## Evidence

`--evidence` holds:
- `acceptance.json`: date, stamp, local `SHA256SUMS` digest, offsite kind, receipt status, isolated destination (container name), the five outcomes, and the verdict;
- `acceptance.md`: the same as a table;
- `local-preflight.json`;
- `remote-listing.tsv`;
- `isolated-restore/`: the drill's own secret-free evidence.

None of these files holds the passphrase, credentials or absolute paths.

Keep the evidence with the date and stamp, and record the SHA-256 of `acceptance.json`. Delete
`--work` (pulled ciphertext, raw logs) when the record is complete.

## Interpreting a result

- `remote-presence` **FAIL** with a missing object: the offsite copy is incomplete, whatever the stamp manifest or the receipt says.
- `remote-integrity` **FAIL**, "is not the local sealed stamp": the offsite copy passes its own checksums but belongs to another seal. Do not use it for recovery.
- `decrypt` **FAIL**: the passphrase in custody does not open this stamp. This is a recovery blocker, not a tooling issue.
- `isolated-restore` **BLOCKED**: the erasure evidence through the cutoff is incomplete. The drill applied nothing.

The script is exercised in CI by `scripts/test-backup-dated-acceptance.sh`, with a synthetic stamp, a fake offsite and a stubbed drill.
