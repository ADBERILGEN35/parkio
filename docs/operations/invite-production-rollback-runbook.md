# Invite-production rollback boundaries

## Pre-write rollback

Before the managed databases receive any real-user write, rollback may stop the
candidate, restore the recorded previous image manifest, return secrets to the
old database, validate the old runtime, and remove maintenance. DNS may be
returned only by the authorized DNS operator. Synthetic foundation data can be
discarded after evidence is retained.

## Post-write recovery

After the managed databases receive real-user writes, simple connection reversal
is unsafe. Do not point the application back at the old database: that loses or
forks accepted writes and can resurrect erased data.

Choose one documented incident strategy with the incident commander:

- forward-fix the managed runtime while writes remain blocked;
- reconcile a precisely bounded set of writes through an audited one-time tool;
- or restore Azure PITR to a new server, validate, replay the erasure ledger, and
  switch forward to that server.

There are no dual writes. Every accepted account erasure is irreversible across
restore/rollback: tombstones are replayed before login or traffic is enabled.
Record timestamps, affected IDs/counts, backup/PITR reference, decision owner,
and final integrity proof in the incident record.

The repository rollback script handles only image/config rollback to a recorded
manifest. It must refuse to claim data rollback and does not reverse migrations
or erasure semantics.

## Dark acceptance endpoint and backup scheduler (PROD-DEPLOY-01A-R3)

Two rollbacks are independent of image/config rollback and of each other.

**Dark gateway endpoint (D1).** Drop
`docker/docker-compose.invite-dark.yml` from the invite-production compose set in
`scripts/lib/deploy-common.sh` and redeploy. `gateway-service` returns to its
unpublished state, reachable only as `gateway-service:8080` inside the Docker
network, and Caddy is again the sole entrypoint. Dark acceptance is unavailable
after this — that is the intended trade, not a regression. The public boundary is
untouched either way: the overlay only ever binds loopback.

**Backup scheduler (D2).**

```bash
sudo scripts/azure/install-invite-production-backup-scheduler.sh --disable
```

This stops the service if it is mid-run and disables the timer. It deliberately
leaves the installed payload in place so the previous revision stays auditable,
and it never touches `/var/backups/parkio` or the offsite container — valid
encrypted backups are never deleted by a rollback. To go back to an earlier
payload revision, re-run the installer from that revision's checkout; `VERSION`
records which revision is currently installed.

Rollback must never recreate a persistent plaintext production env. If one is
found at `/opt/parkio/docker/.env.invite-production`, it is residue from the
pre-R3 model: shred it. The installer refuses to run while it exists.
