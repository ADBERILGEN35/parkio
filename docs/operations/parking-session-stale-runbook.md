# Parking Session stale lifecycle — production runbook

Operator guide for ACTIVE parking-session confirm / remind / auto-complete /
optional retention on hosted-beta and production-like Compose stacks.

Companion docs:

- Architecture: [`docs/architecture/PARKING-SESSION-LIFECYCLE.md`](../architecture/PARKING-SESSION-LIFECYCLE.md)
- Events: [`docs/architecture/PARKING-SESSION-LIFECYCLE-EVENTS.md`](../architecture/PARKING-SESSION-LIFECYCLE-EVENTS.md)
- Security: [`docs/architecture/PARKING-SESSION-SECURITY-AUDIT.md`](../architecture/PARKING-SESSION-SECURITY-AUDIT.md)
- Indexes: [`docs/operations/sql/parking-session-indexes-concurrent.md`](./sql/parking-session-indexes-concurrent.md)
- Performance: [`docs/operations/parking-session-performance.md`](./parking-session-performance.md)
- Alerts: [`docs/operations/alert-response-runbook.md`](./alert-response-runbook.md)

## Compose / service names (hosted-beta)

```bash
COMPOSE="docker compose -f docker/docker-compose.yml -f docker/docker-compose.apps.yml -f docker/docker-compose.hosted-beta.yml"
$COMPOSE ps
```

| Role | Compose service | Typical container |
|---|---|---|
| API + scheduler | `parking-service` | `parkio-parking-service-1` |
| Reminders / push | `notification-service` | `parkio-notification-service-1` |
| Session analytics | `analytics-service` | `parkio-analytics-service-1` |
| Parking DB | `postgres-parking` | `parkio-postgres-parking` |
| Broker | `kafka` | `parkio-kafka` |
| Metrics | `prometheus` / `grafana` | `parkio-prometheus` / `parkio-grafana` |

Parking actuator (in-network): `http://parking-service:8083`.  
Host publish (local/tunnel): `http://127.0.0.1:8083`.

## Lifecycle

```
ACTIVE
  → (confirm-after, default 24h) FIRST reminder + in-app/push
  → (reminder-2-after, default 48h) SECOND reminder + in-app/push
  → user confirms (confirm-active) → reminder stage resets; windows restart
  → user leaves / completes → COMPLETED (completionType=MANUAL, completionReason=MANUAL)
  → (auto-complete-after, default 72h) scheduler → COMPLETED (AUTO / AUTO_TIMEOUT)
  → optional retention (disabled by default) hard-deletes old COMPLETED/CANCELLED rows
```

Never introduces `EXPIRED`. Forgotten ACTIVE sessions become COMPLETED.

## Configuration

| Property | Env | Default |
|---|---|---|
| `parkio.lifecycle.parking-session-stale.enabled` | `PARKIO_PARKING_SESSION_STALE_ENABLED` | `true` |
| `parkio.parking.session.confirm-after` | `PARKIO_PARKING_SESSION_CONFIRM_AFTER` | `PT24H` |
| `parkio.parking.session.reminder-2-after` | `PARKIO_PARKING_SESSION_REMINDER_2_AFTER` | `PT48H` |
| `parkio.parking.session.auto-complete-after` | `PARKIO_PARKING_SESSION_AUTO_COMPLETE_AFTER` | `PT72H` |
| `parkio.lifecycle.parking-session-stale.fixed-delay-ms` | `PARKIO_PARKING_SESSION_STALE_FIXED_DELAY_MS` | `3600000` |
| `parkio.parking.session.scheduler-rate` | `PARKIO_PARKING_SESSION_SCHEDULER_RATE` | `PT1H` |
| `parkio.parking.session.scheduler-batch-size` | `PARKIO_PARKING_SESSION_SCHEDULER_BATCH_SIZE` | `100` |
| `parkio.lifecycle.parking-session-stale.batch-size` | `PARKIO_PARKING_SESSION_STALE_BATCH_SIZE` | (legacy override; prefer scheduler-batch-size) |
| `parkio.parking.session.reminders-enabled` | `PARKIO_PARKING_SESSION_REMINDERS_ENABLED` | `true` |
| `parkio.parking.session.auto-complete-enabled` | `PARKIO_PARKING_SESSION_AUTO_COMPLETE_ENABLED` | `true` |
| `parkio.parking.session.notification-enabled` | `PARKIO_PARKING_SESSION_NOTIFICATION_ENABLED` | `true` |
| `parkio.parking.session.retention-enabled` | `PARKIO_PARKING_SESSION_RETENTION_ENABLED` | `false` |
| `parkio.parking.session.retention-after` | `PARKIO_PARKING_SESSION_RETENTION_AFTER` | `P365D` |

Ordering constraint (startup): `confirm-after < reminder-2-after < auto-complete-after` (strict).

Clients should read effective thresholds from
`GET /api/v1/parking/sessions/lifecycle-config` (see architecture doc) instead of
hardcoding 24h / 48h / 72h.

Keep `PARKIO_PARKING_SESSION_STALE_FIXED_DELAY_MS` aligned with
`PARKIO_PARKING_SESSION_SCHEDULER_RATE` (PT1H ⇒ 3600000).

## Metrics

Micrometer names use dots; Prometheus renders underscores + `_total` on counters:

| Micrometer | Prometheus |
|---|---|
| `parking.sessions.active` | `parking_sessions_active` |
| `parking.sessions.auto_completed` | `parking_sessions_auto_completed_total` |
| `parking.sessions.confirmation` | `parking_sessions_confirmation_total` |
| `parking.sessions.reminder_sent` | `parking_sessions_reminder_sent_total{stage=FIRST\|SECOND}` |
| `parking.sessions.scheduler.duration` | `parking_sessions_scheduler_duration_seconds_*` |
| `parking.sessions.scheduler.processed` | `parking_sessions_scheduler_processed_total` |
| `parking.sessions.scheduler.failed` | `parking_sessions_scheduler_failed_total` |
| `parking.sessions.retention.deleted` | `parking_sessions_retention_deleted_total` |

Grafana: **Parkio — Parking Sessions (stale lifecycle)**.

### Useful PromQL

```promql
parking_sessions_active{service="parking-service"}
increase(parking_sessions_scheduler_failed_total{service="parking-service"}[30m])
increase(parking_sessions_auto_completed_total{service="parking-service"}[1h])
sum by (stage) (increase(parking_sessions_reminder_sent_total{service="parking-service"}[6h]))
rate(parking_sessions_scheduler_duration_seconds_sum{service="parking-service"}[5m])
  / clamp_min(rate(parking_sessions_scheduler_duration_seconds_count{service="parking-service"}[5m]), 1)
parkio_outbox_unpublished_count{service="parking-service"}
parkio_outbox_oldest_unpublished_age_seconds{service="parking-service"}
parkio_notification_delivery_pending_count
max by (consumergroup, topic) (
  kafka_consumergroup_lag{topic="parkio.parking.session"}
)
```

### Prometheus alerts (`docker/prometheus/alerts.yml`)

| Alert | Meaning |
|---|---|
| `ParkingSessionSchedulerFailures` | Any scheduler failure in 30m (warning) |
| `ParkingSessionAutoCompleteSpike` | >500 auto-completes in 1h (warning) |
| `ParkingSessionSchedulerFailuresSustained` | >3 scheduler failures in 6h (critical). Formerly misnamed `ParkingSessionReminderPublicationIdleWhileActive` — it tracks **repeated scheduler failures**, not idle reminder publication. |

## Quick health checks

```bash
# Parking readiness + metrics
curl -fsS http://127.0.0.1:8083/actuator/health/readiness
curl -fsS http://127.0.0.1:8083/actuator/prometheus | rg 'parking_sessions_'

# Effective lifecycle config (via gateway; requires bearer)
curl -fsS -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8080/api/v1/parking/sessions/lifecycle-config

# Logs
docker logs parkio-parking-service-1 --tail 200
docker logs parkio-notification-service-1 --tail 200
docker logs parkio-analytics-service-1 --tail 200

# Flyway history on parking DB
docker exec -i parkio-postgres-parking \
  psql -U parkio -d parkio_parking -c \
  "SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"
```

---

## Incident playbooks

### 1. Scheduler stuck

**Symptoms:** `parking_sessions_active` flat/high; no growth in
`parking_sessions_reminder_sent_total` / `parking_sessions_auto_completed_total`;
`ParkingSessionSchedulerFailures*` firing; logs lack `REMINDER_SENT` /
`AUTO_COMPLETED`.

**Checks:**

```bash
curl -fsS http://127.0.0.1:8083/actuator/prometheus | rg 'parking_sessions_scheduler_'
docker logs parkio-parking-service-1 --tail 300 | rg -i 'Stale parking-session|scheduler|AUTO_COMPLETED|REMINDER_SENT'
$COMPOSE exec parking-service printenv | rg 'PARKIO_PARKING_SESSION_'
```

**Actions:**

1. Confirm `PARKIO_PARKING_SESSION_STALE_ENABLED=true` and
   `reminders-enabled` / `auto-complete-enabled` as intended.
2. Confirm duration ordering still valid (bad env ⇒ startup failure; if service
   is up, ordering passed).
3. Validate indexes (see SQL docs). Bad/missing indexes make ticks slow, not
   usually silent.
4. If tick exceptions: fix root cause (DB, OOM, optimistic lock storms), then
   restart `parking-service` once.
5. Feature-flag rollback: set `PARKIO_PARKING_SESSION_STALE_ENABLED=false` and
   recreate parking-service to stop the job while investigating.

### 2. Outbox backlog

**Symptoms:** `parkio_outbox_unpublished_count{service="parking-service"}` rising;
`parkio_outbox_oldest_unpublished_age_seconds` high; reminders/completions
persisted but Kafka quiet.

**Checks:**

```promql
parkio_outbox_unpublished_count{service="parking-service"}
parkio_outbox_oldest_unpublished_age_seconds{service="parking-service"}
parkio_outbox_deadlettered_count{service="parking-service"}
rate(parkio_outbox_publish_failed_total{service="parking-service"}[15m])
```

```bash
docker logs parkio-parking-service-1 --tail 200 | rg -i 'outbox|relay|kafka'
$COMPOSE ps kafka parking-service
```

**Actions:**

1. Confirm Kafka healthy (`parkio-kafka`, exporter lag panels).
2. Use **Parkio - Outbox and DLQ** dashboard; for poison rows
   `scripts/outbox-deadletter-recovery.sh`.
3. Do not truncate outbox. Relays are at-least-once; consumers must be idempotent.

### 3. Notification backlog

**Symptoms:** Users report no reminder push/in-app; parking outbox drains;
`parkio_notification_delivery_pending_count` rising.

**Checks:**

```bash
curl -fsS http://127.0.0.1:8086/actuator/prometheus | rg 'parkio_notification_delivery_'
docker logs parkio-notification-service-1 --tail 200 | rg -i 'Reminder|ParkingSession|delivery'
```

```promql
parkio_notification_delivery_pending_count
rate(parkio_notification_delivery_worker_failure_count_total[15m])
kafka_consumergroup_lag{topic="parkio.parking.session", consumergroup=~".*notification.*"}
```

**Actions:**

1. Confirm `PARKIO_PARKING_SESSION_NOTIFICATION_ENABLED=true` on parking-service
   (controls whether reminder events are published for notification).
2. Confirm notification-service consumes `parkio.parking.session` and handles
   `ParkingSessionReminderRequested`.
3. Check device-token / provider skips (`delivery.skipped`) vs terminal failures.
4. Restart `notification-service` after Kafka is healthy if lag is stuck.

### 4. Kafka lag

**Symptoms:** `KafkaConsumerLagHigh` / `KafkaConsumerLagSustained` on
`parkio.parking.session`; delayed analytics or notifications.

**Checks:**

```promql
max by (consumergroup, topic) (
  kafka_consumergroup_lag{topic="parkio.parking.session"}
)
```

```bash
# Placeholder broker lag inspection (adjust group ids to match deploy)
docker exec parkio-kafka kafka-consumer-groups \
  --bootstrap-server kafka:9092 --describe --all-groups \
  | rg 'parkio.parking.session|GROUP|TOPIC|LAG'
```

**Actions:**

1. Identify lagging group (notification vs analytics).
2. Restart that consumer service after broker health is OK.
3. Inspect DLT depth:
   `sum by (topic) (kafka_topic_partition_current_offset{topic=~"parkio\\.dlt\\..+"} - kafka_topic_partition_oldest_offset{topic=~"parkio\\.dlt\\..+"})`
4. Redrive with `scripts/kafka-dlt-redrive.sh` (dry-run first).

### 5. Analytics failures

**Symptoms:** Analytics DLT growth; missing session KPIs; consumer errors on
reminder deploy.

**Facts:** Analytics must **ignore + ack** `ParkingSessionReminderRequested`
(forward-compatible). Started/Completed/Cancelled are projected. See
[`PARKING-SESSION-ANALYTICS-INGESTION.md`](../architecture/PARKING-SESSION-ANALYTICS-INGESTION.md).

**Checks:**

```bash
docker logs parkio-analytics-service-1 --tail 200 | rg -i 'ParkingSession|contract|DLT|Reminder'
curl -fsS http://127.0.0.1:8089/actuator/health/readiness
```

**Actions:**

1. If DLT after reminder rollout: confirm analytics build includes ignore path
   for `ParkingSessionReminderRequested`.
2. Contract exceptions (negative duration, etc.) → inspect payload; do not
   redrive poison without a fix.
3. Partial deploy: park analytics behind until parking-service + analytics are
   on compatible versions (additive fields are OK; unknown event types must not
   crash).

### 6. Flyway failure

**Symptoms:** `parking-service` crash-loops; logs show Flyway checksum/SQL
errors on V17/V18; CoreServiceDown.

**Checks:**

```bash
docker logs parkio-parking-service-1 --tail 400 | rg -i 'Flyway|Migration|V17|V18'
docker exec -i parkio-postgres-parking \
  psql -U parkio -d parkio_parking -c \
  "SELECT * FROM flyway_schema_history WHERE version IN ('17','18') OR success = false ORDER BY installed_rank;"
```

**Actions:**

1. Do **not** hand-edit `flyway_schema_history` without a planned repair.
2. If migration partially applied outside Flyway: stop parking-service, restore
   DB from backup or finish DDL to match V17/V18, then `flyway repair` only with
   senior approval.
3. Rollback path: restore `postgres-parking` volume/snapshot from pre-deploy
   backup ([restore-runbook.md](./restore-runbook.md)), redeploy previous image.
4. Index-only issues: use CONCURRENTLY rebuild docs — do not re-run Flyway
   create statements that already succeeded.

### 7. Partial deployment

**Safe order:**

1. Migrate parking DB (V17+V18) with parking-service deploy.
2. Restart/redeploy `parking-service`.
3. Redeploy `notification-service` (reminder consumer).
4. Redeploy `analytics-service` (ignore reminder + additive completed fields).

Any order after migration is usually OK for additive contracts, but avoid
running an old analytics that hard-fails on unknown `eventType`.

**Verify:**

```bash
curl -fsS http://127.0.0.1:8083/actuator/prometheus | rg parking_sessions
curl -fsS http://127.0.0.1:8086/actuator/health/readiness
curl -fsS http://127.0.0.1:8089/actuator/health/readiness
```

### 8. Rollback (deploy)

```bash
# Hosted-beta helper (prefer documented script when available)
scripts/rollback-hosted-beta.sh

# Or pin previous image tags in compose override / deploy artifacts, then:
$COMPOSE up -d parking-service notification-service analytics-service
```

Notes:

- Rolling back application code does **not** automatically undo Flyway V17/V18.
  Forward-fix DB or restore from backup if old code cannot read new columns.
- Prefer feature-flag rollback (below) before DB restore when schema is
  compatible.

### 9. Feature flag rollback

Disable work without schema revert:

| Goal | Env |
|---|---|
| Stop entire job | `PARKIO_PARKING_SESSION_STALE_ENABLED=false` |
| Stop reminders only | `PARKIO_PARKING_SESSION_REMINDERS_ENABLED=false` |
| Stop auto-complete only | `PARKIO_PARKING_SESSION_AUTO_COMPLETE_ENABLED=false` |
| Stop reminder publish for notifications | `PARKIO_PARKING_SESSION_NOTIFICATION_ENABLED=false` |
| Keep retention off (default) | `PARKIO_PARKING_SESSION_RETENTION_ENABLED=false` |

Then recreate parking-service:

```bash
$COMPOSE up -d --force-recreate parking-service
```

### 10. Manual recovery

**Confirm for a user (preferred):** user taps confirm-active in app, or:

```bash
curl -fsS -X POST -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" \
  http://127.0.0.1:8080/api/v1/parking/sessions/$SESSION_ID/confirm-active
```

**Complete manually:** user leave/complete flow (MANUAL), not SQL updates.

**SQL inspection only (no mutations without approval):**

```sql
SELECT id, user_id, status, reminder_stage, last_confirmed_at, started_at,
       completion_type, completion_reason, ended_at
FROM parking_sessions
WHERE status = 'ACTIVE'
ORDER BY last_confirmed_at ASC
LIMIT 50;
```

Do **not** hand-set `status` / `completion_*` in production without an
engineering-approved script — domain invariants and outbox events will diverge.

**Stuck invalid index after ops rebuild:** see
[`sql/parking-session-indexes-concurrent.md`](./sql/parking-session-indexes-concurrent.md).

## Hosted-beta smoke (manual)

1. Deploy Flyway V17+V18 with parking-service.
2. Restart parking-service → notification-service → analytics-service.
3. Confirm metrics scrape: `curl -s localhost:8083/actuator/prometheus | rg parking_sessions`
4. Create ACTIVE session; advance clock in staging or wait; verify reminder
   outbox + inbox notification.
5. Confirm-active clears stale UI; leave dialog requires second confirm.

## Escalation

Capture: alert name/labels, Grafana **Parking Sessions** + Outbox panels,
`docker compose ps`, recent git SHA from `deploy-artifacts/current.json`, and
whether feature flags were changed. If data loss suspected, stop writes and use
[restore-runbook.md](./restore-runbook.md).