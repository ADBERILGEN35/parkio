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
