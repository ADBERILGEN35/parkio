# Restore drill 01: isolated database restore from the offsite copy

**Status:** PREPARED, NOT AUTHORIZED. Running this procedure handles real personal
data and needs its own written operator authorization (§1). Nothing here writes to
production. Context and gaps: [backup-restore-readiness.md](backup-restore-readiness.md).

## Why this drill first

It is the smallest drill that proves the chain most likely to break in a real host loss,
which no drill has exercised yet:

offsite listing → download of one real `COMPLETE` stamp → integrity → **decryption with the
escrowed passphrase** → restore of all 10 databases → parity with the dump → erasure-ledger
replay → measured duration.

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
```

From the Azure portal or CLI, as the backup-account owner: container listing of the three
newest stamp prefixes (names, sizes, last-modified only), lifecycle rule, versioning,
and any immutability policy. **Answer G1 in writing: where is the backup passphrase
escrowed off-host, and who holds it?** If there is no escrow, stop. Fixing custody comes
before any drill.

## 1. Authorization and access required

| Item | Required |
|---|---|
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
- **Engine:** one `postgis/postgis` container matching the live parking image from §0.
  It serves all ten databases, since a PostGIS image is a superset of `postgres:16`. It runs on an
  `--internal` Docker network with no published ports. If §0 shows the live major version
  is not 16 or PostGIS is not 3.x, stop and re-plan.
- **Cost:** one small VM for about 4 h, plus blob egress for one stamp's size. Expected to be
  a few US dollars at list prices; confirm on the provider's pricing page before approval.
  Nothing is provisioned by this PR.

## 3. Acquire and verify (egress to the blob endpoint only)

```bash
export PARKIO_DRILL_ID=rd-$(date -u +%Y%m%d)-01
git clone --depth 1 --branch api https://github.com/ADBERILGEN35/parkio.git ~/parkio && cd ~/parkio
umask 077; mkdir -p ~/rd/evidence
STAMP=<newest COMPLETE stamp from §0>
# SAS token: typed at a hidden prompt, used by this command only, then unset.
read -rs -p 'read-only SAS: ' BACKUP_AZURE_SAS_TOKEN; echo
BACKUP_OFFSITE_KIND=azure BACKUP_AZURE_STORAGE_ACCOUNT=<account> BACKUP_AZURE_CONTAINER=<container> \
  BACKUP_AZURE_SAS_TOKEN="$BACKUP_AZURE_SAS_TOKEN" \
  ./scripts/backup-offsite-pull.sh --stamp "$STAMP" --dest ~/rd/stamp
unset BACKUP_AZURE_SAS_TOKEN
python3 scripts/lib/restore-stamp-preflight.py ~/rd/stamp --max-age-hours 48 | tee ~/rd/evidence/stamp-preflight.json
```

Continue only on `"verdict": "PASS"`. Record `summary.dumpBytesTotal` and the transfer time.
**Now apply the egress-deny rule** at the cloud firewall.

## 4. Isolation preflight

```bash
cat > ~/rd/drill.env <<'EOF'
PARKIO_RESTORE_REQUIRE_ERASURE_LEDGER=1
PARKIO_EMAIL_PROVIDER=logging
PARKIO_WAITLIST_EMAIL_PROVIDER=logging
PARKIO_PUSH_DELIVERY_PROVIDER=noop
EOF
docker network create --internal rd-net
docker run -d --name rd-postgres --network rd-net --memory 1500m \
  -e POSTGRES_USER=rd_admin -e POSTGRES_PASSWORD="dummy-$(openssl rand -hex 12)" \
  -e POSTGRES_DB=rd_admin <live parking image digest from §0>
docker ps --format '{{.Names}}' > ~/rd/containers.txt
set -a; . ~/rd/drill.env; set +a
python3 scripts/lib/restore-drill-isolation-preflight.py --env-file ~/rd/drill.env \
  --containers-file ~/rd/containers.txt --probe-default-egress | tee ~/rd/evidence/isolation.json
```

It must be `PASS`: no live outbound credential, no credential-shaped value, email and push
inert, no sender or poller flag, only database containers, and every default egress target
unreachable. The blob endpoint must also be unreachable by now.

## 5. Restore order and validation

Order: **auth** first, because the erasure replay depends on it. Then gateway, user,
parking, media, gamification, notification, moderation, analytics, ai-validation.
Names and users come from `PARKIO_DB_SERVICES` in `scripts/lib/backup-common.sh`.

```bash
read -rs -p 'backup passphrase: ' BACKUP_ENCRYPT_PASSPHRASE; echo; export BACKUP_ENCRYPT_PASSPHRASE
dec() { openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_ENCRYPT_PASSPHRASE < ~/rd/stamp/$1.sql.gz.enc | gunzip; }
q()   { docker exec -i rd-postgres psql -v ON_ERROR_STOP=1 -U rd_admin "$@"; }
T0=$(date -u +%s)
for entry in auth:parkio_auth gateway:parkio_gateway user:parkio_user parking:parkio_parking \
             media:parkio_media gamification:parkio_gamification notification:parkio_notification \
             moderation:parkio_moderation analytics:parkio_analytics ai-validation:parkio_aivalidation; do
  svc=${entry%%:*}; db=${entry#*:}
  set -o pipefail
  dec "$svc" | python3 scripts/lib/restore-dump-profile.py profile > ~/rd/evidence/$svc.profile.json || { echo "FAIL profile $svc"; break; }
  for role in $(python3 -c 'import json,sys;print(" ".join(json.load(open(sys.argv[1]))["grantRoles"]))' ~/rd/evidence/$svc.profile.json) $db; do
    q -d rd_admin -c "DO \$\$BEGIN CREATE ROLE \"$role\" LOGIN PASSWORD 'dummy-drill'; EXCEPTION WHEN duplicate_object THEN NULL; END\$\$" >/dev/null
  done
  # Mirrors production, where each service user is its container's superuser; the
  # --clean dump drops/recreates extensions (postgis) and needs that privilege.
  q -d rd_admin -c "ALTER ROLE \"$db\" SUPERUSER" >/dev/null
  q -d rd_admin -c "CREATE DATABASE \"$db\" OWNER \"$db\"" >/dev/null
  s=$(date -u +%s)
  dec "$svc" | docker exec -i rd-postgres psql -q -v ON_ERROR_STOP=1 -U "$db" -d "$db" >/dev/null 2>~/rd/$svc.restore.err \
    || { echo "FAIL restore $svc (see ~/rd/$svc.restore.err; do not publish it)"; break; }
  python3 scripts/lib/restore-dump-profile.py count-sql ~/rd/evidence/$svc.profile.json \
    | q -d "$db" -At -F'|' > ~/rd/$svc.counts
  python3 scripts/lib/restore-dump-profile.py compare ~/rd/evidence/$svc.profile.json ~/rd/$svc.counts \
    > ~/rd/evidence/$svc.parity.json || echo "FAIL parity $svc"
  echo "$svc restore_seconds=$(( $(date -u +%s) - s ))" | tee -a ~/rd/evidence/timings.txt
done
unset BACKUP_ENCRYPT_PASSPHRASE
```

Erasure replay and inert-state checks. These produce counts only, never identifiers:

```bash
source scripts/lib/erasure-tombstones.sh
PARKIO_RESTORE_REQUIRE_ERASURE_LEDGER=1 parkio_replay_erasure_tombstones \
  ~/rd/stamp/erasure-tombstones.json rd-postgres parkio_auth parkio_auth
q -d parkio_auth -At -c "select count(*) from auth_users u join erased_user_tombstones t on t.auth_user_id=u.id where u.status='ACTIVE'" \
  | sed 's/^/active_after_erasure=/' | tee ~/rd/evidence/erasure.txt
for db in parkio_auth parkio_user parkio_parking parkio_media parkio_gamification parkio_notification \
          parkio_moderation parkio_analytics parkio_aivalidation; do
  q -d $db -At -c "select '$db', coalesce(sum(case when not published then 1 end),0) from outbox_events" 2>/dev/null
done | tee ~/rd/evidence/outbox-unpublished.txt
q -d parkio_gateway -At -c "select status, count(*) from waitlist_ops_notification_outbox group by 1" \
  | tee ~/rd/evidence/waitlist-outbox.txt
echo "total_restore_seconds=$(( $(date -u +%s) - T0 ))" | tee -a ~/rd/evidence/timings.txt
```

The unpublished-outbox counts are the events that **would be re-published** if this point in
time became live. That is the practical duplicate exposure of a restore, and an input to the RPO decision.

### Pass criteria

1. The stamp preflight passes, and the isolation preflight passes before decryption.
2. All 10 databases restore with `ON_ERROR_STOP=1`. Every `parity.json` is `PASS`, meaning the
   row count equals the dump's COPY count for every table.
3. `extensions` include `postgis` for parking, and `flywayHead` is recorded for each DB with `flywayFailedRows=0`.
4. `active_after_erasure=0`.
5. Wall-clock time from the start of the download to the last parity check is recorded as the
   **measured restore duration**. This is the first real input to the RTO in the readiness doc.

## 6. Failure handling

- **Checksum, COMPLETE or ledger failure:** stop. Do not try an older stamp in the same
  authorization unless it names one. Report the preflight JSON; it contains no data.
- **Wrong passphrase or decryption error:** stop. That is a **G1 custody finding**, not a drill bug.
- **Restore error:** keep `~/rd/<svc>.restore.err` on the drill host only; it may quote data. Record the SQLSTATE
  and object name by hand in the evidence. Typical causes are a missing role (add it to the
  profiler's `grantRoles` handling) or an extension version mismatch (re-run with the §0 image).
- **Any sign of outbound traffic or a non-database container:** stop, destroy the host, report.

## 7. Cleanup (task-owned only)

```bash
unset BACKUP_ENCRYPT_PASSPHRASE BACKUP_AZURE_SAS_TOKEN
docker rm -f rd-postgres && docker network rm rd-net && docker volume prune -f   # drill host only
shred -u ~/rd/stamp/* 2>/dev/null; rm -rf ~/rd/stamp ~/rd/*.counts ~/rd/*.restore.err
```

Then delete the VM **and its disks**, and revoke or let expire the read-only SAS. Record the
deletion time. Nothing on the production host or in the storage account is touched.

## 8. Evidence to keep (secret-free)

Keep only `~/rd/evidence/`: `stamp-preflight.json`, `isolation.json`, `*.profile.json`
(versions, table names, counts), `*.parity.json`, `timings.txt`, `erasure.txt` (a count),
the outbox count files, and the stamp name. Review these before committing them under
`agent-tools/parkio-restore-drill-01/<UTC>/`. **Never** commit dumps, restore error logs,
ledger files, SAS tokens or the passphrase, and never upload them as GitHub artifacts.
Table names come from migrations and are not personal data.

## Next drills (not prepared here)

- **02:** MinIO `minio.tar.gz.enc` into an isolated MinIO (`restore-hosted-beta.sh --only minio`
  with a throwaway `MINIO_RESTORE_BUCKET`), with object-count parity against the manifest.
- **03:** An application-level smoke on the restored copy, with every sender, poller and relay disabled
  and egress still denied. It needs the same isolation preflight plus service-level kill switches.
