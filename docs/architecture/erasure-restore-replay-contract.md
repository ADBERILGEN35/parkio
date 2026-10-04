# Erasure restore replay and attempt-bound ACKs (U02)

An isolated recovery restores service databases (and media objects) from a backup. Erasures
recorded after that backup must be replayed in every participant before anything is exposed.
Each participant then acknowledges the replay. The acknowledgement is bound to this recovery
attempt, the restored dataset and the erasure set, so an acknowledgement of another attempt,
dataset or set never counts.

This contract is the participant and coordinator half of
`docs/operations/recovery-evidence-contract.md` §3 "ACK binding" and §7 "Participants and restore
ACKs". It reuses the U05 commit-then-ACK outbox (`erasure-ack-outbox-contract.md`): a restore ACK is
an outbox row written in the transaction that replays the erase, so it is never sent for work that
did not commit. Live acknowledgements (`erasure_service_acks`) stay request-scoped and never count
for a restore. Restore ACKs never count for a live request either.

Default-off on both sides; nothing in production starts a replay. `verifiedCoverage` stays false,
and exposing a restored copy is decided by stage 4 (restore consumption), not here.

## Identifiers

| Name | Meaning |
|------|---------|
| `recoveryAttemptId` | UUID chosen for one recovery attempt; never reused |
| `restoredDatasetId` | the identity of the restored dataset (backup stamp or dump id), text |
| `erasureSetDigest` | hex SHA-256 of the canonical `{"kind":"erasure-ledger","entries":[...]}` over the trusted erasure set: one `{authUserId, erasedAt}` per erased user, ordered by `authUserId` (the checkpoint ledger shape and digest) |

The trusted erasure set comes from durable evidence: the latest trusted checkpoint plus the
pending records above it. It never comes from the restored auth database, which may predate the
erasures. Computing that set is stage 4; here the set is an input.

## Coordinator (auth)

Enabled only with `parkio.privacy.account-erasure.restore-replay.enabled=true`
(`PARKIO_ACCOUNT_ERASURE_RESTORE_REPLAY_ENABLED`, default `false`).

**Required participants.** An attempt requires auth plus every participant of
`docs/operations/recovery-evidence-contract.md` §3: user, parking, media, moderation,
gamification, notification, analytics and ai-validation. With restore replay enabled, auth-service
refuses to start unless `parkio.privacy.account-erasure.participants` contains all eight; an empty
or incomplete list would make `COMPLETE` vacuous or ignore a service's restored data. An extra
configured participant is required as well. The live path reads the same setting and is not
affected while restore replay is off.

`startRestoreReplay(recoveryAttemptId, restoredDatasetId, entries)` runs in one transaction:

1. It records the attempt in `erasure_restore_attempts`: dataset, digest computed from `entries`,
   user count and start time. The users go into `erasure_restore_attempt_users`, and the required
   participants into `erasure_restore_attempt_participants` (V27). That set is fixed for the
   attempt: a later configuration change does not change what `COMPLETE` means for it. Calling it
   again with the same attempt id and the same dataset and set is a no-op; the same attempt id
   with another dataset or set is refused.
2. Auth replays its own share for each user and stores its ACK with it: the restored account is
   brought to the erased end state (the tombstone kept, or recreated with the original
   `erasedAt`; active refresh and reset tokens revoked; login identifiers replaced as when an
   erasure completes), and `erasure_restore_acks` gets `(attempt, user, auth, SUCCESS)` in the same
   transaction. Erasure request rows are not created or changed; the public deletion status after
   a recovery is a separate owner decision.
3. For each user it appends one outbox event `UserErasureRestoreReplayRequested` to
   `parkio.privacy.erasure`:

   | Field | Value |
   |-------|-------|
   | `eventId` | `nameUUIDFromBytes("<recoveryAttemptId>:<authUserId>:restore-replay")` |
   | `recoveryAttemptId`, `restoredDatasetId`, `erasureSetDigest` | the attempt's |
   | `authUserId`, `erasedAt` | the entry |
   | `occurredAt` | the start time |

   The record key is `authUserId`.

`UserErasureRestoreAcknowledged` events are consumed by the existing `ErasureAckKafkaConsumer`.
Each is handled in one transaction:

- The event is claimed in the inbox by `eventId`; a duplicate is a no-op.
- The event is **ignored** (logged with the reason, nothing stored) when:
  - it claims to come from auth: auth's ACK is written locally, never taken from Kafka;
  - its attempt is unknown, for example a prior attempt;
  - its service is not a participant the attempt requires;
  - its user is not in the attempt's set;
  - its `restoredDatasetId` or `erasureSetDigest` differs from the attempt's. Such an
    acknowledgement belongs to another restore and must not satisfy this one.
- Otherwise the coordinator upserts `erasure_restore_acks` by
  `(recovery_attempt_id, auth_user_id, service_name)` with the status (`SUCCESS` or `FAILED`).

`restoreReplayVerdict(recoveryAttemptId)` is `COMPLETE` only when every participant the attempt
required at its start, auth included, has a `SUCCESS` for every user of the attempt. Otherwise it
is `BLOCKED`, with the missing `(participant, count)` pairs and any `FAILED`. An unknown attempt,
and an attempt without recorded participants, is `BLOCKED` too.

### Operating rule: enable every participant before starting

A participant whose restore replay flag is off acknowledges the replay command on Kafka and skips
it, so it never sends a restore ACK for that attempt. The attempt then stays `BLOCKED` for that
participant: this fails closed, it never completes falsely. Starting the same attempt id again
queues nothing new.

Safe recovery:

1. Enable `parkio.privacy.restore-replay.enabled` in every participant and confirm it is in effect.
2. Start a **new** attempt id for the same restored dataset and erasure set.
3. Leave the old attempt `BLOCKED`. Never mark it complete and never reuse its id.

## Participant

Enabled only with `parkio.privacy.restore-replay.enabled=true`
(`PARKIO_RESTORE_REPLAY_ENABLED`, default `false`). Otherwise the consumer acknowledges the Kafka
record and skips the event.

In one `@Transactional` method:

1. Run the same local erase as for a live request: tombstone first, then deletes, anonymization
   or sentinel rewrite. This is idempotent.
2. Append one outbox row `UserErasureRestoreAcknowledged`:

   | Column | Value |
   |--------|-------|
   | `aggregate_type` | `AccountErasure` (routed to `parkio.privacy.erasure`, as U05) |
   | `aggregate_id` | `authUserId` (the record key) |
   | `event_id` | `nameUUIDFromBytes("<replayEventId>:<recoveryAttemptId>:<authUserId>:<service>:restore-ack")` |
   | `payload` | `{eventId, recoveryAttemptId, restoredDatasetId, erasureSetDigest, authUserId, serviceName, status, occurredAt}` |

A failure throws, and the transaction rolls back together with the outbox row; Kafka redelivers.
A redelivery re-runs the erase and re-derives the same `event_id`, which the outbox appender
skips. The ACK echoes the attempt, dataset and digest it was asked for. The coordinator, not the
participant, decides whether they match the attempt.

**Media** must additionally delete the restored objects before `SUCCESS`, as in U05: object
deletion confirmed and no write of unknown outcome. The replay runs both U05 phases: the
metadata erase commits with a `media_erasure_jobs` row that carries the restore binding instead
of an erase request (V17: `recovery_attempt_id`, `restored_dataset_id`, `erasure_set_digest`;
exactly one binding per job), and the worker queues `UserErasureRestoreAcknowledged`, with the
job id as event id, in the transaction that deletes the job once every stored object of the user
is confirmed gone. A redelivery reopens the same job; another attempt opens its own.

## Rollout

| Participant | Slice | Source on `api` |
|-------------|-------|-----------------|
| coordinator (auth) | 1 | #184 |
| gamification (pilot) | 1 | #184 |
| user, parking, moderation, notification, analytics, ai-validation | 2 | #185 |
| media (objects) | 3 | #186 |
| coordinator: required participant set fixed per attempt, auth's own share (V27) | 4 | this PR |

In slice 2 the participants queue the restore ACK through `ErasureAckOutbox.appendRestoreAck`, next
to the live `append`, and their U05 ACK-outbox ITs cover the replay on real PostgreSQL and Kafka.
Moderation routes outbox rows by event type, so its relay names the restore ACK type explicitly; the
others route the `AccountErasure` aggregate type, which already covers it.

The isolated recovery harness, with real per-service PostgreSQL, Kafka and MinIO across all
participants, is the acceptance test once every participant has its slice.
