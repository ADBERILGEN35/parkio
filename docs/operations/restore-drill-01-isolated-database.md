# Restore drill 01: isolated database restore from the offsite copy

**Status:** PREPARED, NOT AUTHORIZED. Running this procedure handles real personal
data and needs its own written operator authorization (§1). Nothing here writes to
production. Context and gaps: [backup-restore-readiness.md](backup-restore-readiness.md).

## Why this drill first

It is the smallest drill that proves, on real data, the chain most likely to break in a real
host loss. No real-production restore evidence was found in the reviewed material. The same
procedure has been **executed** in CI on synthetic encrypted stamps (readiness doc §8):

offsite listing → download of one real `COMPLETE` stamp → integrity → **decryption with the
escrowed passphrase** → erasure set through a declared cutoff → restore of all 10 databases →
parity with the dump → erasure replay before exposure → outbox exposure → measured duration.

The whole procedure is one script, `scripts/restore-drill-01.sh`, so the drill runs exactly
what CI executed.

It does **not** start any Parkio application service, Kafka, Redis, MinIO, relay or
scheduler. So no restored outbox, queue, municipal poller or notification path can run.
MinIO object restore is drill 02, once drill 01 has measured real sizes.

## 0. Read-only facts to collect first

These are operator-run, bounded and read-only on `parkio-civo-prod`. They need no secrets
printed, and no dumps, finds or long queries. Record only the listed outputs.

```bash
# Scheduler and last run (no log bodies beyond the tail)
sudo ls -l /etc/cron.d/parkio-backup 2>&1; sudo cat /etc/cron.d/parkio-backup 2>&1 | grep -v '^#'
systemctl list-timers --all --no-pager | grep -i backup
sudo tail -n 5 /var/log/parkio-backup.log 2>&1

# Newest local stamps: names and COMPLETE presence only (bounded to depth 1)
BD=$(sudo grep -E '^BACKUP_DIR=' /opt/parkio/docker/.env.azure-hosted-beta | cut -d= -f2-)
sudo ls -1t "${BD:-/opt/parkio/backups}" | head -3
for s in $(sudo ls -1t "${BD:-/opt/parkio/backups}" | head -3); do sudo test -f "${BD:-/opt/parkio/backups}/$s/COMPLETE" && echo "$s COMPLETE" || echo "$s INCOMPLETE"; done

# Backup telemetry (numbers only)
sudo cat /var/lib/parkio/observability/textfile/parkio_backup.prom 2>/dev/null \
  || sudo cat /opt/parkio/docker/prometheus/textfile/parkio_backup.prom

# Engine versions needed for the drill (no data)
docker inspect --format '{{.Name}} {{.Config.Image}} {{.Image}}' $(docker ps -q --filter name=parkio-postgres-)
docker exec parkio-postgres-parking sh -lc 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "select extname, extversion from pg_extension order by 1"'
docker exec parkio-postgres-parking sh -lc 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "show server_version"'

# Which secret variable NAMES are set (never values)
sudo grep -oE '^(BACKUP_ENCRYPT_PASSPHRASE|BACKUP_AZURE_[A-Z_]+|BACKUP_PRODUCTION_MODE|BACKUP_OFFSITE_KIND)=' \
  /opt/parkio/docker/.env.azure-hosted-beta

# Operational state present (names, sizes, unit states only; see readiness §6)
sudo ls -la /var/lib/parkio/slack-biz /var/lib/parkio-nr-log-continuous/budget 2>&1 | head -20
systemctl list-units --no-pager 'parkio-slack-biz-*' 'parkio-nr-log-*' | head -20
```

From the Azure portal or CLI, as the backup-account owner: container listing of the three
newest stamp prefixes (names, sizes, last-modified only), lifecycle rule, versioning,
and any immutability policy. **Answer G1 in writing: where is the backup passphrase
escrowed off-host, and who holds it?** If there is no escrow, stop. Fixing custody comes
before any drill.

**Answer G2 in writing: the recovery cutoff and the erasure evidence that reaches it.**
- For this drill, the cutoff is the newest retrievable stamp. Also retrieve that stamp's ledger
  when an older stamp is being restored.
- Record the newest stamp time. Record whether any erasure requests arrived after it and where
  they are recorded.
- If erasures after the newest stamp cannot be enumerated, a real host-loss recovery would be
  **BLOCKED** (readiness §5). The drill can still run with cutoff = newest stamp.

## 1. Authorization and access required

| Item | Required |
|---|---|
| Erasure evidence | The cutoff decision and the stamps (or supplement) that reach it, as above |
| Written authorization | Operator approval to copy **one** real encrypted stamp to a disposable host, decrypt it there, and destroy it afterwards. It names the stamp, the date window, the executor and the data-protection basis for processing personal data in a restore test |
| Offsite read access | A **new** container SAS with `rl` (read and list) only. Expiry ≤ 4 h, IP-restricted to the drill host. Do **not** reuse the production `rcwl` token, which can write |
| Passphrase | Retrieved from escrow by its custodian and typed at a hidden prompt on the drill host. Never in a file, env file, shell history, chat, ticket or CI |
| Drill host | Disposable Linux VM **outside** the production host and outside the shared local Docker daemon. See §2 |
| Not required | Production SSH write, prod DB credentials, Slack/Resend/NR/Gemini/Expo keys, GitHub secrets |

## 2. Isolated destination

- **Host:** a fresh Ubuntu 24.04 VM in a separate project or resource group from production,
  with Docker Engine, Azure CLI (`az`, used by `backup-offsite-pull.sh`) and `python3`, `openssl`, `gzip`, `sha256sum`. Hostname `parkio-rd-<date>`.
  Do not use `parkio-civo-prod`, `vm-parkio-*`, a developer laptop or GitHub-hosted runners.
- **Size (initial, adjust after §3 reports `dumpBytesTotal`):** 2 vCPU, 4 GiB RAM, and a disk of
  at least 20 GiB, or 15× the compressed dump total, whichever is larger. The data disk must be encrypted at rest.
  Postgres needs about 1 GiB of RAM for this drill.
- **Network:** inbound SSH from the operator IP only. Outbound allowed **only** to the backup
  storage account's blob endpoint during §3, then **all egress denied** before §4.
- **Engine:** one `postgis/postgis` container matching the live parking image from §0
  (target **server**). It serves all ten databases, since a PostGIS image is a superset of
  `postgres:16`. Restore SQL is applied by an **identified client image**
  (`PARKIO_RESTORE_PSQL_IMAGE`, default in CI: `postgres:16.10`) that understands the dump's
  meta-commands. The two are not interchangeable: CI run 35883781803 failed when the older
  in-container `psql` rejected `\\restrict` from a newer `pg_dump`. It runs on an
  `--internal` Docker network with no published ports. If §0 shows the live major version
  is not 16 or PostGIS is not 3.x, stop and re-plan.
- **Cost:** one small VM for about 4 h, plus blob egress for one stamp's size. Expected to be
  a few US dollars at list prices; confirm on the provider's pricing page before approval.
  Nothing is provisioned by this PR.


## 3. Acquire and verify (egress to the blob endpoint only)

```bash
export PARKIO_DRILL_ID=rd-$(date -u +%Y%m%d)-01
git clone --depth 1 --branch api https://github.com/ADBERILGEN35/parkio.git ~/parkio && cd ~/parkio
umask 077; mkdir -p ~/rd/stamps ~/rd/evidence
DATA_STAMP=<stamp to restore>   LEDGER_STAMP=<newest stamp, if newer than DATA_STAMP>
read -rs -p 'read-only SAS: ' BACKUP_AZURE_SAS_TOKEN; echo
for s in "$DATA_STAMP" ${LEDGER_STAMP:+"$LEDGER_STAMP"}; do
  BACKUP_OFFSITE_KIND=azure BACKUP_AZURE_STORAGE_ACCOUNT=<account> BACKUP_AZURE_CONTAINER=<container> \
    BACKUP_AZURE_SAS_TOKEN="$BACKUP_AZURE_SAS_TOKEN" \
    ./scripts/backup-offsite-pull.sh --stamp "$s" --dest ~/rd/stamps/"$s"
done
unset BACKUP_AZURE_SAS_TOKEN
```

The newer stamp is needed **only for its erasure ledger**; its dumps are never restored. Now
**apply the egress-deny rule** at the cloud firewall.

## 4. Isolated target

```bash
printf '%s\n' PARKIO_EMAIL_PROVIDER=logging PARKIO_WAITLIST_EMAIL_PROVIDER=logging \
  PARKIO_PUSH_DELIVERY_PROVIDER=noop > ~/rd/drill.env
docker network create --internal rd-net
docker run -d --name rd-postgres --network rd-net --memory 1500m \
  -e POSTGRES_USER=rd_admin -e POSTGRES_PASSWORD="dummy-$(openssl rand -hex 12)" \
  -e POSTGRES_DB=rd_admin <live parking image digest from §0>
# Restore client is identified separately. Do not use the target image's psql if
# pg_dump is newer (\\restrict). Record dump-client / restore-client / server / PostGIS.
export PARKIO_RESTORE_PSQL_IMAGE=postgres:16.10
export PARKIO_RESTORE_PSQL_NETWORK=rd-net
export PARKIO_RESTORE_PSQL_HOST=rd-postgres
```

## 5. Run the procedure

```bash
read -rs -p 'backup passphrase: ' BACKUP_ENCRYPT_PASSPHRASE; echo; export BACKUP_ENCRYPT_PASSPHRASE
./scripts/restore-drill-01.sh \
  --stamp ~/rd/stamps/"$DATA_STAMP" ${LEDGER_STAMP:+--ledger-stamp ~/rd/stamps/"$LEDGER_STAMP"} \
  --recovery-cutoff "${LEDGER_STAMP:-$DATA_STAMP}" \
  --container rd-postgres --env-file ~/rd/drill.env --probe-default-egress --max-age-hours 72 \
  --work ~/rd/work --evidence ~/rd/evidence
echo "exit=$?"; unset BACKUP_ENCRYPT_PASSPHRASE
```

The script runs these phases in order and fails closed at each one:

| Phase | What it does | Stops with |
|---|---|---|
| 1 | `restore-stamp-preflight.py` on every stamp: `COMPLETE`/`SHA256SUMS`, ciphertext magic, no plaintext, manifest, ledger shape | exit 1, nothing decrypted |
| 2 | `restore-erasure-ledger.py`: union of ledgers (append-only check) + optional supplement, coverage vs cutoff | exit 3 **BLOCKED**, nothing decrypted |
| 3 | `restore-drill-isolation-preflight.py`: no live outbound credentials or credential-shaped values, inert email/push, sender/poller flags off, only DB containers, egress denied | exit 1 |
| 4 | Per DB, auth first: dump profile, **client-compatibility preflight** (dump-client / restore-client / target-server / PostGIS), then roles and database created, then identified `psql -v ON_ERROR_STOP=1` (never strip `\\restrict`), then row-count parity | exit 1 on first failure |
| 5 | Replay the erasure set into restored auth. **Require** zero ACTIVE accounts in the set | exit 1 |
| 6 | Unpublished `outbox_events` per DB and waitlist outbox status counts: the re-publish exposure | always recorded |

Operator-compiled erasures after the newest stamp, for a real recovery rather than this drill,
are passed with `--supplemental-ledger FILE --supplemental-covered-through TS`. The file holds
identifiers, so it stays on the drill host.

### Pass criteria

1. `summary.json` verdict `PASS`, exit 0.
2. Every `<svc>.parity.json` is `PASS`. `parking.profile.json` lists `postgis`.
   `flywayHead` is recorded and `flywayFailedRows=0` for each DB.
3. `erasure-set.json` verdict `PASS`. `erasure-replay.txt` shows `active_in_erasure_set_after_replay=0`.
4. `timings.txt` `total_seconds` plus the §3 transfer time gives the **measured restore duration**.
   This is the first real RTO input.

## 6. Failure handling

- **Exit 1 in phase 1:** checksum, `COMPLETE`, plaintext or ledger failure. Stop. Report the preflight JSON; it contains no data.
- **Exit 3:** erasure evidence does not reach the cutoff. Nothing was decrypted. Close the gap,
  or record that privacy-safe recovery is BLOCKED.
- **Decryption or profile failure:** wrong passphrase or damaged dump. That is a **G1 custody
  finding**, not a drill bug.
- **Client compatibility failure:** dump-client, restore-client, target-server or PostGIS do
  not match. Typical case: `pg_dump` 16.10+ emitted `\\restrict` and the restore `psql` is older
  (`invalid command \\restrict`, CI run 35883781803). Use an identified restore client at least
  as new as that restrict-capable release (`postgres:16.10` in the synthetic workflow). Do **not**
  strip those commands or ignore SQL errors.
- **Restore failure:** `~/rd/work/<svc>.restore.err` may quote data, so it stays on the host.
  Record the SQLSTATE and object by hand. Typical causes are a missing role or an extension version mismatch.
- **Any outbound traffic or non-database container:** stop, destroy the host, report.

## 7. Cleanup (task-owned only)

```bash
unset BACKUP_ENCRYPT_PASSPHRASE BACKUP_AZURE_SAS_TOKEN
docker rm -f rd-postgres && docker network rm rd-net && docker volume prune -f   # disposable drill host only
rm -rf ~/rd/stamps ~/rd/work
```

Then delete the VM **and its disks**, and let the read-only SAS expire or revoke it. Nothing on the
production host or in the storage account is touched.

## 8. Evidence to keep (secret-free)

Keep only `~/rd/evidence/`. Its files contain versions, table names, counts, timings and verdicts,
never identifiers:
- `summary.json`
- `stamp-preflight.json`, `ledger-stamp-*-preflight.json`
- `erasure-set.json`, `erasure-replay.txt`
- `isolation.json`
- `*.profile.json`, `*.parity.json`
- `outbox-pending.txt`, `timings.txt`

Review it, then commit it under `agent-tools/parkio-restore-drill-01/<UTC>/`. **Never** commit
or upload stamps, `~/rd/work` (merged ledger, counts, restore errors), SAS tokens or the passphrase.

## Next drills (not prepared here)

- **02:** MinIO `minio.tar.gz.enc` into an isolated MinIO, with object-count parity against the manifest.
- **03:** An application-level smoke on the restored copy, with all senders, pollers and relays
  disabled and egress still denied. It exercises participant PII re-erasure
  (`/internal/erasure/replay`), which drill 01 cannot.
- **Operational state:** relay and NR ledger snapshot restore per readiness §6. This needs a
  separate change that adds consistent SQLite snapshots to the stamp.
