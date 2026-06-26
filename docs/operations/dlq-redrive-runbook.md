# DLQ / DLT Redrive Runbook

This runbook is for operators with shell access to the Parkio host. Recovery tools are intentionally private; there are no public HTTP recovery endpoints.

## Current Model

- **Outbox dead-lettering:** relayable rows live in each producer service database. After `parkio.kafka.relay.max-attempts`, relays set `dead_lettered = true` and stop claiming the row. The original `event_id`, payload, aggregate metadata and trace/correlation metadata stay unchanged.
- **Outbox recovery metadata:** relay-owning services store `recovery_attempt_count`, acknowledgment fields, last recovery action fields, and immutable `outbox_recovery_audit` rows.
- **Kafka DLT:** consumers publish poison records to `parkio.dlt.<service>` via `DeadLetterPublishingRecoverer`. DLT depth is observed through Kafka exporter retained offset depth, not an app counter.
- **Traceability:** redrive preserves Kafka headers, including `traceparent`, `tracestate`, `baggage`, `eventId` and legacy `traceId` correlation id. DLT redrive adds `parkio-redrive-*` audit headers.

## Decision Rules

- Retry/redrive only after the cause is understood or fixed.
- Acknowledge only when the event is obsolete, superseded, manually remediated, or intentionally not safe to replay.
- Do not repeatedly retry unchanged poison records. If the same row dead-letters again after recovery, escalate to a code/config fix.
- Never edit event payloads in place. Preserve the original event contract and event id.
- Consumers are idempotent by `eventId`; redrive may duplicate delivery, but handlers must tolerate it.

## Outbox Inspection

```bash
PARKIO_ENV_FILE=docker/.env PARKIO_OPERATOR=<name> \
  scripts/outbox-deadletter-recovery.sh list --service parking --limit 25
```

Filter examples:

```bash
scripts/outbox-deadletter-recovery.sh list --service parking --event-type ParkingSpotCreated
scripts/outbox-deadletter-recovery.sh list --service media --reason-like TimeoutException
scripts/outbox-deadletter-recovery.sh list --service gamification --aggregate-id <uuid>
```

Details are payload-redacted by default:

```bash
scripts/outbox-deadletter-recovery.sh details --service parking --id <outbox-row-id>
```

Only show payload when required for incident response:

```bash
scripts/outbox-deadletter-recovery.sh details --service parking --id <outbox-row-id> --show-payload
```

## Outbox Retry

Retry a single row after the broker/topic/config issue is fixed:

```bash
PARKIO_OPERATOR=<name> scripts/outbox-deadletter-recovery.sh retry \
  --service parking \
  --id <outbox-row-id> \
  --reason "Kafka topic restored" \
  --yes
```

Bulk retry is capped by `--limit` and the script hard-caps at 100:

```bash
PARKIO_OPERATOR=<name> scripts/outbox-deadletter-recovery.sh retry-bulk \
  --service parking \
  --event-type ParkingSpotCreated \
  --limit 25 \
  --reason "topic mapping fixed" \
  --yes
```

The script sets the row back to relayable pending state, increments `recovery_attempt_count`, and inserts an audit row. `PARKIO_OUTBOX_RECOVERY_MAX_ATTEMPTS` defaults to `3`; rows at or above that limit are not retried by the tool.

## Outbox Acknowledge / Suppress

Use acknowledge when a row should remain retained but stop paging:

```bash
PARKIO_OPERATOR=<name> scripts/outbox-deadletter-recovery.sh acknowledge \
  --service parking \
  --id <outbox-row-id> \
  --reason "obsolete; superseded by manual correction in incident INC-123" \
  --yes
```

Acknowledged rows remain `dead_lettered = true`, but `acknowledged_deadletter = true` excludes them from open dead-letter gauges and alerts.

## Kafka DLT Dry-Run

Always inspect first:

```bash
scripts/kafka-dlt-redrive.sh \
  --bootstrap-servers localhost:29092 \
  --source-topic parkio.dlt.notification \
  --target-topic parkio.parking.spot \
  --max-records 10
```

Dry-run is the default. The tool reads from the beginning of the DLT topic using a unique non-committing consumer group and prints event id, trace headers, source partition/offset and payload size. It does not delete or commit DLT records.

## Kafka DLT Redrive

Redrive only after confirming the target original topic and fixing the consumer problem:

```bash
PARKIO_OPERATOR=<name> scripts/kafka-dlt-redrive.sh \
  --execute \
  --bootstrap-servers localhost:29092 \
  --source-topic parkio.dlt.notification \
  --target-topic parkio.parking.spot \
  --max-records 10 \
  --reason "notification consumer fixed"
```

Safety controls:

- `--execute` requires `--reason` and `PARKIO_OPERATOR` or `--operator`.
- `--target-topic` cannot be `parkio.dlt.*`.
- `--max-records` is capped at 100.
- Existing headers are preserved; `parkio-redrive-source-topic`, `parkio-redrive-source-partition`, `parkio-redrive-source-offset`, `parkio-redrive-attempt`, `parkio-redrive-operator`, and `parkio-redrive-reason` are added.
- `--max-redrive-attempts` defaults to 3 and prevents endless replay loops.

## Verification

After retry/redrive:

1. Check service logs by `eventId` and `correlationId`.
2. Check Tempo using the OTel `traceId` from logs or `traceparent`.
3. Confirm `parkio_outbox_deadlettered_count` returns to 0 for outbox recovery.
4. Confirm Kafka exporter DLT retained offset depth stops growing. DLT records remain until topic retention removes them; depth may not immediately go to zero.
5. Confirm target consumer inbox/dedup table contains the `eventId` when processing succeeds.

## Rollback

- Outbox retry rollback is operational: if a retried row dead-letters again, do not retry repeatedly. Acknowledge only after manual remediation or escalate to a code/config fix.
- Kafka DLT redrive cannot remove already-produced target records. Rely on consumer idempotency and stop further redrive. If a bad redrive used the wrong topic, pause the affected consumer and escalate.

## Escalation

Escalate to engineering when:

- failure reason is deterministic payload/contract parsing;
- the row exceeds `PARKIO_OUTBOX_RECOVERY_MAX_ATTEMPTS`;
- DLT dry-run shows missing required event headers or unknown event types;
- redrive succeeds at produce time but the same consumer sends the record back to DLT.
