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

Default-off on both sides. The only starter is the recovery-replay command
([below](#recovery-replay-command-owner-decision-p6)), which the isolated restore path runs; nothing
in a running service starts a replay. `verifiedCoverage` stays false, and exposing a restored copy
is decided by stage 4 (restore consumption), not here.

## Identifiers

| Name | Meaning |
|------|---------|
| `recoveryAttemptId` | UUID chosen for one recovery attempt; never reused |
| `restoredDatasetId` | the identity of the restored dataset (backup stamp or dump id), text |
| `erasureSetDigest` | hex SHA-256 of the canonical `{"kind":"erasure-ledger","entries":[...]}` over the trusted erasure set: one `{authUserId, erasedAt}` per erased user, ordered by `authUserId` (the checkpoint ledger shape and digest) |

The trusted erasure set comes from durable evidence: the latest trusted checkpoint plus the
pending records above it. It never comes from the restored auth database, which may predate the
erasures. `TrustedErasureSet` (Java) and `trusted_erasure_set` (Python,
`scripts/lib/recovery_evidence_bundle.py`) compute it from an evidence bundle; the shared fixtures
in `durable-erasure-evidence/v2/bundles` pin both.

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

**Media in an isolated recovery: the restored source bucket (recovery only).** The isolated
restore brings the backup's objects back into the #121 ticket's own bucket (`<project>-media`;
the ticket refuses any other name). The restored `media_files` and write-ledger rows still name
the bucket they were written to. Media deletes and confirms only in its configured bucket, so an
isolated recovery sets `parkio.media.storage.restored-source-bucket`
(`PARKIO_MEDIA_STORAGE_RESTORED_SOURCE_BUCKET`) to the backup's bucket. The drill's recovery
overlay sets it from the manifest's bucket, through the ticket, for the media recovery app only.
- **Unset by default.** It is honoured only with `parkio.privacy.restore-replay.enabled=true`.
- **Startup refuses** it when restore replay is off, when it is empty or not an S3 bucket name,
  and when it equals the configured bucket. It is never ignored silently.
- **Erasure listing only.** A key named in the source bucket is listed in the configured bucket,
  and the versions found carry the configured bucket, so version deletes stay strict. Uploads,
  reads, presigned URLs and listings never use it. A row naming any third bucket is still refused,
  and its deletion stays unconfirmed: no `SUCCESS`.
- **Restored rows are never rewritten.** The mapping is configuration of the recovery app, not a
  change to the restored data.

## Recovery-replay command (owner decision P6)

The one entry point that starts a replay. It is a one-shot command, run only by the isolated
restore path, with no network endpoint (`com.parkio.auth.infrastructure.recovery`).

**When it runs.** Auth-service runs it only with the `recovery-replay` profile **and**
`parkio.privacy.account-erasure.restore-replay.enabled=true`.
- The profile comes from the environment (`SPRING_PROFILES_ACTIVE=recovery-replay`, or
  `-Dspring.profiles.active`). The command line takes **only the command's own options**: any
  `--spring.*` or `--logging.*` argument is refused (20), so no command-line option reaches Spring.
  Settings that would redirect a connection are refused by the preflight (see below).
- Recovery options without the profile, and a `--spring.profiles.active=recovery-replay` argument,
  are refused before Spring starts.
- **An ordinary start never runs with the profile** (PR #295 review N10). Whatever activates it
  (`SPRING_PROFILES_INCLUDE`, a lower-case variable, `SPRING_APPLICATION_JSON`, a config file, a
  profile group), an ordinary start with the profile active is refused (20) once the environment is
  prepared, before any context exists. It would otherwise drop the web security configuration and
  move the ACK consumer to the recovery group.
- An ordinary start, with neither the profile nor the options, is unchanged.

```
SPRING_PROFILES_ACTIVE=recovery-replay \
PARKIO_ACCOUNT_ERASURE_RESTORE_REPLAY_ENABLED=true \
java -jar auth-service.jar \
  --evidence=<trusted-set file> --trust=<trust document> \
  --attempt=<uuid> --dataset=<restored dataset id> \
  --target-identity=<isolated target identity from the ticket> \
  --verdict-out=<verdict.json> [--timeout-seconds=900]
```

Only these options are accepted, each once, as `--name=value`. There is **no cutoff and no receipt
option** (see Coverage below).

**Nothing touches the target before the checks pass, and nothing is migrated or written to any other
database** (PR #295 review B1, B2, B5, B6). A redirect setting is refused before any connection
exists. Should one get past that, the pool may open a connection to the other database and run the
identity query on it, and then refuses it. The launch runs in two steps:

1. **Preflight, before any application context exists.** Spring prepares the environment
   (configuration files, environment variables, profiles) and the preflight runs every check that
   can refuse. The target database is read over a plain read-only JDBC transaction, rolled back. A
   refusal ends the run there: no Flyway migration, no write, no Kafka consumer, no scheduler.
2. **The command context, only after every check passed.** It is a plain non-web application
   context whatever `spring.main.web-application-type` says, so it has no HTTP listener; the run is
   refused (26) if a web server context or factory exists anyway.
   - Under the profile, the web security configuration, the gateway filter, the retention job, the
     erasure stuck gauge, the durable-recording worker and the live moderation consumer are not
     part of it.
   - The ACK consumer joins its own group, `parkio.auth.erasure.recovery-replay`, never the live
     `parkio.auth.erasure` group.
   - If the restored schema is older than the image, Flyway migrates it here, after the checks.
   - **Every connection is re-checked** (B6, layer 2). Every `DataSource` bean is wrapped so each
     connection it hands out re-reads `pg_control_system()` and `current_database()` before the
     caller runs a statement. Flyway's migration strategy reads the identity through the data source
     Flyway actually uses before `migrate()`. Any other database is refused (22): the connection is
     closed and nothing is migrated or written. A refusal during the run overrides the command's
     result.
   - **The outbox relay publishes only this attempt's replay commands** (N11). Until the command
     names its attempt it publishes nothing; then only the unpublished
     `UserErasureRestoreReplayRequested` rows of that attempt. Every other unpublished row of the
     restored copy (registrations, live erasure requests, anything copied from the past) stays
     unpublished and untouched.

**Checks, in order.** Each one stops the run before the next. Steps 1 to 7 are the preflight:

1. **Profile and web type.** The profile must be active, and `spring.main.web-application-type`, if
   set anywhere (an argument is already refused; also an environment variable or a file), must be
   `none`.
2. **Flag and durable-store writers.** The flag must be on. If durable recording, its retry worker,
   the object-lock store or the checkpoint producer is enabled, the command refuses: a restored copy
   must never write into the real evidence store. The poll interval must be a positive duration.
   **Connection settings** (B6, layer 1; refused with 22, whatever their value):
   - any setting that gives a connection its own target: `spring.flyway.url`, `.user`, `.password`
     and `.driver-class-name`; `spring.datasource.hikari.jdbc-url`, `.username`, `.password`,
     `.data-source-class-name`, `.data-source-j-n-d-i` and `.data-source-properties.*`;
     `spring.datasource.jndi-name`; `spring.datasource.xa.data-source-class-name` and
     `.xa.properties.*`. A managed environment that sets `SPRING_FLYWAY_USER`/`PASSWORD` for a
     migrator role must drop them for a recovery run;
   - a `spring.datasource.url` that is not `jdbc:postgresql://<one host[:port]>/<database>`: no host
     list (failover or load balancing), no `service`, and only these parameters: `ssl`, `sslmode`,
     `sslrootcert`, `connectTimeout`, `socketTimeout`, `loginTimeout`, `ApplicationName`,
     `tcpKeepAlive`.
3. **Evidence.** The trusted-set file (`parkio-trusted-erasure-set`, written by the isolated restore)
   embeds the evidence bundle. The command loads its own trust document, re-derives the trusted set
   from the bundle, and refuses any difference from the coverage, set and ignored frontier versions
   the file states. It refuses UNKNOWN (no frontier), BLOCKED (a gap) and any invalid object,
   including a signed object under another object's key and a sequence with two owners (see
   `docs/operations/recovery-evidence-contract.md`, stage 4).
4. **Attempt and dataset.** They must equal the file's.
5. **Connected database identity.** It is read from the connection
   (`pg_control_system()`: `postgresql:<system_identifier>:<datname>`), not from configuration. The
   command refuses when:
   - the identity is unreadable or blank;
   - it, or `--target-identity`, does not parse strictly (`postgresql:<digits without a leading
     zero>:<name without ':' or space>`): an ambiguous identity;
   - it, or `--target-identity`, is on the **production cluster** pinned in the trust document:
     the same `system_identifier`, **whatever the database name**. A PITR or physical clone keeps
     the production `system_identifier`, and another database on the production server shares it,
     so both are refused even under another database name;
   - it differs from `--target-identity`, which is taken from the #121 isolation ticket and is
     mandatory.

   The Python restore applies the same rule when it writes the trusted-set file; the shared fixture
   `durable-erasure-evidence/v2/database-identities.json` pins both.
6. **Backup anchor.** Every user the restored auth database's `erasure_requests` marks
   `durable_recording_status = 'DURABLY_RECORDED'`, **whatever the request status** (an
   `IN_PROGRESS` request can already be durably recorded), must be in the trusted set. Otherwise
   the evidence is older than the backup, and the command refuses (21). The anchor needs the
   restored auth database, so the isolated restore applies it to the isolated target first. If the
   state cannot be read, the run is refused (21) **fail-closed**: a backup taken before V24 has no
   `durable_recording_status` column and cannot be anchored, and the command leaves it as it was.
7. **Attempt reuse.** Done in the command context (step 8), since it reads the restore tables.
8. **Command.** The context re-reads the connected identity and refuses (22) anything but the
   database the preflight checked. An attempt id already started for another dataset or set is
   refused (23). Then `startRestoreReplay`, and the command polls `verdict` until COMPLETE, a
   FAILED acknowledgement, or the bounded timeout (default 15 min, at most 60). The wait uses a
   monotonic clock, and no poll sleeps past the deadline.

**Kafka is not verified (a documented limitation).** The command's Kafka bootstrap must be the
isolated recovery broker; the #296 isolation ticket and recovery overlay supply it. The command
checks every database connection, not the broker. Against another broker:
- the relay publishes this attempt's replay commands there (and nothing else, N11);
- the ACK consumer joins `parkio.auth.erasure.recovery-replay`, a group with no committed offsets,
  and reads with `auto-offset-reset: earliest`, so it consumes that broker's whole
  `parkio.privacy.erasure` history into the restored copy;
- `KafkaAdmin` creates auth's topics there if they are missing;
- the ACK consumer's error handler (the listener factory's `DefaultErrorHandler`) republishes a
  record it cannot process, after two retries and with exception headers, to `parkio.dlt.auth` on
  that broker. This is not a restored outbox row, but it is written to whatever broker is
  configured (PR #295 review N15).

The recovery group keeps the command off the live groups' partitions. Treat the broker as part of
the isolation boundary.

The command has no network endpoint, so it exposes nothing. Keeping the restored copy unexposed
until the verdict is COMPLETE is the isolated restore's job (#296: an expose gate, CLOSED until a
COMPLETE verdict for the same attempt, dataset and digest, and no published ports).

**Exit codes.** Only COMPLETE exits with 0:

| Code | Status | Meaning |
|---|---|---|
| 0 | `COMPLETE` | every participant the attempt requires, auth included, acknowledged every user |
| 20 | `REFUSED` | disabled (profile without the flag, or options without the profile), a `--spring.*` or `--logging.*` argument, a web application type other than none, a durable writer enabled, an invalid poll interval, invalid arguments, or an ordinary start with the profile active |
| 21 | `INVALID_EVIDENCE` | the evidence, the trusted-set file, the trust document or the backup anchor does not verify, or the anchor cannot be read |
| 22 | `TARGET_REFUSED` | the production cluster (any database name), an unreadable or ambiguous identity, not the ticket's target, a setting that redirects a connection or a URL that is not one plain PostgreSQL host, or any connection of the command context that reaches another database |
| 23 | `ATTEMPT_MISMATCH` | attempt or dataset other than the file's, or an attempt already started for another dataset or set |
| 24 | `BLOCKED` | a participant reported FAILED |
| 25 | `TIMEOUT` | the bounded wait ended with acknowledgements missing |
| 26 | `INTERNAL` | an unexpected failure, a startup failure, or a verdict file that could not be written |

**The verdict file** (`parkio-recovery-replay-verdict`) is written for every outcome once the
arguments parse, refusals included. It records:
- status, exit code, reason, attempt, dataset and digest;
- the coverage, including `ignoredFrontierVersions`: frontier versions that failed verification are
  tamper evidence, reported rather than hidden;
- the number of users;
- for each required participant, `success`, `failed` and `missing` counts;
- **auth's counts separately** (`auth`).

The expose gate (stage 4, #296) opens only on a COMPLETE verdict for the same attempt, dataset and
digest.

**Coverage.** v2 evidence signs no time, and no offline-verifiable receipt exists today. The
store's `DurableErasureReceipt` is unsigned metadata that is only logged. So:
- coverage is reported only as "erasure coverage verified through sequence N (frontier version V)";
- the report never makes a time-based coverage claim, and never claims that no later erasure exists;
- erasures after the last durable frontier cannot be proven absent;
- the existing time-based ledger check in the restore scripts, and the production restore refusal,
  are unchanged.

## Rollout

| Participant | Slice | Source on `api` |
|-------------|-------|-----------------|
| coordinator (auth) | 1 | #184 |
| gamification (pilot) | 1 | #184 |
| user, parking, moderation, notification, analytics, ai-validation | 2 | #185 |
| media (objects) | 3 | #186 |
| coordinator: required participant set fixed per attempt, auth's own share (V27) | 4 | #187 |
| entry point: recovery-replay command, trusted erasure set from evidence bundles | 5 | this PR |

In slice 2 the participants queue the restore ACK through `ErasureAckOutbox.appendRestoreAck`, next
to the live `append`, and their U05 ACK-outbox ITs cover the replay on real PostgreSQL and Kafka.
Moderation routes outbox rows by event type, so its relay names the restore ACK type explicitly; the
others route the `AccountErasure` aggregate type, which already covers it.

The isolated recovery harness, with real per-service PostgreSQL, Kafka and MinIO across all
participants, is the acceptance test once every participant has its slice.
