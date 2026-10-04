# Dated backup acceptance (U14)

A dated record of five outcomes for **one** backup stamp:

| # | outcome | what it proves | what it does not prove |
|---|---|---|---|
| 1 | `complete` | The local stamp is sealed and full. `COMPLETE` binds `SHA256SUMS`, every listed file matches, nothing is unlisted, and the manifest is an encrypted full stamp (`minioOk=1`, all ten databases). This is `restore-stamp-preflight.py --scope full`. | Anything about the offsite copy. |
| 2 | `remote-presence` | The offsite listing for the stamp holds **exactly** the `SHA256SUMS` entries plus `SHA256SUMS` and `COMPLETE`, each at the local byte size: nothing missing, nothing extra. Names and sizes only: nothing is downloaded. | That the remote bytes are right. |
| 3 | `remote-integrity` | A copy pulled from offsite passes its checksums, holds **no file the seal does not list**, and its `SHA256SUMS` digest (and `COMPLETE`) equal the sealed stamp's. | That the passphrase still decrypts it. |
| 4 | `decrypt` | Every encrypted artifact of that copy decrypts with the custody passphrase and decompresses: ten dumps plus `minio.tar.gz.enc`, streamed through `gzip -t` / `tar -tz`. No plaintext is written. | That the data restores and is consistent. |
| 5 | `isolated-restore` | `scripts/restore-drill-01.sh` on that copy succeeds in the disposable container the operator names: preflight, privacy gate, isolation, restore, parity, erasure replay. | Anything about production: nothing live is touched. |

Each outcome is `PASS`, `FAIL`, `BLOCKED` (only 5: the drill's privacy gate) or `NOT_RUN` with a
reason. They are recorded independently: a `FAIL` in one never turns another into `PASS` or `FAIL`.
Outcomes 4 and 5 use only a copy that passed 3; without one they are `NOT_RUN`, not `PASS`.

The post-upload receipt beside the stamp (`<stamp>.offsite-receipt.json`, see
[backup-offsite-uploaded-receipt-follow-up.md](backup-offsite-uploaded-receipt-follow-up.md)) is
recorded when present, but no outcome relies on it: 2 and 3 check the offsite copy directly.

## Two hosts, one record

The outcomes cannot all come from one host:
- Outcome 1 needs the local stamp, which lives on the production-class backup host.
- Outcome 5 must run on a **disposable drill host**, as [restore-drill-01-isolated-database.md](restore-drill-01-isolated-database.md) describes. The drill's isolation preflight refuses production hostnames, offsite credentials, live senders and production mode.

So an acceptance is two runs plus a combine step:

| run | where | outcomes | command |
|---|---|---|---|
| A | backup host | 1, 2, 3 (offsite copy), and 4 if passphrase custody allows decrypting there | `--stamp-dir` |
| B | drill host, after §3 of the drill procedure (pull with a read-only SAS, then deny egress) | 3 (the drill host's copy, against run A's seal), 4, 5 | `--pulled` + `--expected-sha256sums` |
| C | anywhere | one record: 1 and 2 from A, 3 from both, 4 from B (else A), 5 from B | `--combine` |

On the drill host, the drill runs in a scrubbed environment. A small Python step builds it from an exact allowlist (`PARKIO_RESTORE_*`, `PARKIO_DRILL_*`, the passphrase, `PATH`/`HOME`/`TMPDIR`/`LANG` and the Docker client settings) and execs the drill. Exported bash functions and names that are not identifiers are dropped too. The passphrase therefore stays in the environment and never appears on a process argv, where any local user could read it. It gets only its own drill env file (`--drill-env-file`). The acceptance's offsite credentials and the production env file never reach it. If the drill host still fails the isolation preflight (for example, egress is not denied or a non-database container is running), outcome 5 is `NOT_RUN` with that reason, not a restore `FAIL`.

## Before running it

Reading a real stamp, pulling it, decrypting it and restoring it need **separate authorization**
(U14). This document and the script are the preparation only.

- [ ] Authorization names both hosts, the stamp, and outcomes 4 and 5 if they are wanted, as in §1 of the drill procedure.
- [ ] Both hosts have `jq`, `python3`, `openssl`, `gzip`, `tar` and `sha256sum`. The offsite listing (outcome 2) needs `jq`.
- [ ] The stamp is chosen, normally the newest `COMPLETE` stamp under `BACKUP_DIR` on the backup host.
- [ ] The backup host uses the offsite settings from the env file the backup itself uses.
- [ ] The drill host is a disposable VM, never `parkio-civo-prod` or `vm-parkio-*`. It has a read-only, short-lived SAS for the pull and the drill's inert env file, and egress is denied after the pull.
- [ ] For 4 and 5: the passphrase is available under its custody rules and typed at a hidden prompt, never in a file or in evidence. While custody is unknown, run without `--decrypt` / `--isolated-restore`; those outcomes then stay `NOT_RUN` and the record says why.
- [ ] Each run gets **new** `--evidence` and `--work` directories. `--evidence` keeps only secret-free files; `--work` holds pulled copies and raw logs and is deleted afterwards.

## Running it

```bash
# A. backup host
PARKIO_ENV_FILE=docker/.env.invite-production scripts/backup-dated-acceptance.sh \
  --stamp-dir /var/backups/parkio/<stamp> \
  --evidence ~/acceptance/<date>-<stamp>/backup-host --work ~/acceptance/<date>-<stamp>/work-a [--decrypt]
# note "localSha256sums" from backup-host/acceptance.json

# B. drill host, after the drill procedure's §3 pull into ~/rd/stamps/<stamp> and the egress-deny rule
scripts/backup-dated-acceptance.sh --pulled ~/rd/stamps/<stamp> --expected-sha256sums <localSha256sums> \
  --evidence ~/rd/acceptance/drill-host --work ~/rd/acceptance/work-b --decrypt \
  --isolated-restore --drill-env-file ~/rd/drill.env -- \
  --recovery-cutoff <ISO-8601 UTC> --container rd-postgres --probe-default-egress [--ledger-stamp DIR ...]

# C. one record, with both acceptance.json files side by side
scripts/backup-dated-acceptance.sh --combine backup-host/acceptance.json drill-host/acceptance.json \
  --evidence ~/acceptance/<date>-<stamp>/combined
```

The drill host takes no `--env-file` or `PARKIO_ENV_FILE`; the copy is already pulled. The record's
stamp name comes from the copy's `COMPLETE` (`stamp=`), so a copy pulled into a directory of another name
still combines. The script sets the drill's `--stamp` (the verified copy), `--work`, `--evidence` and `--env-file`
itself. It refuses them among the drill arguments, together with `--allow-privacy-blocked`.

Exit codes:
- `0`: all five `PASS`. Only a combined record can reach this.
- `1`: at least one `FAIL`.
- `4`: no `FAIL`, but something is `BLOCKED` or `NOT_RUN`. A single host's run always ends here: it records the other host's outcomes as `NOT_RUN`.
- `2`: usage.
- `5`: the report itself could not be written. The outcomes are in `--work`/`outcomes.tsv`.

## Evidence

Each `--evidence` directory holds:
- `acceptance.json`: the mode, the date, the stamp, the local or expected `SHA256SUMS` digest, the offsite kind, receipt status, the isolated destination (container name), the five outcomes and the verdict. The combined record also carries the SHA-256 of both inputs.
- `acceptance.md`: the same as a table.
- Backup host: `local-preflight.json`, `remote-listing.tsv`.
- Drill host: `isolated-restore/`, the drill's own secret-free evidence.

None of these files holds the passphrase, credentials or absolute paths. Keep the three records
with the date and stamp, and record the SHA-256 of the combined `acceptance.json`. Delete the `--work`
directories (pulled ciphertext, raw logs) when the record is complete.

## Interpreting a result

- `remote-presence` **FAIL** with a missing or unexpected object: the offsite copy is not exactly the sealed stamp, whatever the manifest or the receipt says. An unexpected object may be a plaintext dump: treat it as an incident.
- `remote-integrity` **FAIL**, "is not the sealed stamp's" or "files outside the seal": the copy does not match the seal. Do not use it for recovery.
- `decrypt` **FAIL**: the passphrase in custody does not open this stamp. This is a recovery blocker, not a tooling issue.
- `isolated-restore` **BLOCKED**: the erasure evidence through the cutoff is incomplete, and nothing was restored. The acceptance refuses `--allow-privacy-blocked`.
- `isolated-restore` **NOT_RUN** after the drill ran: the drill host failed the isolation preflight (see `isolated-restore/isolation.json`). Fix the host, not the backup.

The script is exercised in CI by `scripts/test-backup-dated-acceptance.sh`. It uses a synthetic stamp and a fake offsite. Its drill stub runs the real `restore-drill-isolation-preflight.py` in the environment the acceptance hands over.
