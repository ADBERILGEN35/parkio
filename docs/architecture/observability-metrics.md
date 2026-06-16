# Observability — Metrics (Micrometer + Prometheus)

Every backend service exports Micrometer metrics in Prometheus format at
`GET /actuator/prometheus`. The endpoint is exposed alongside `health` and `info`
only — no sensitive actuator endpoints (`env`, `beans`, `heapdump`, ...) are ever
exposed. The per-service `GatewayAuthFilter` deliberately skips `/actuator/**`, so
Prometheus can scrape services directly without the gateway secret.

All metrics additionally carry an `application` tag
(`management.metrics.tags.application = ${spring.application.name}`), matching the
`service` label applied by the local Prometheus scrape config.

## Local stack

`docker/prometheus/prometheus.yml` scrapes `/actuator/prometheus` from all ten
services over the `parkio-backend` Docker network every 15s.

- Prometheus UI: <http://localhost:9090> (Status → Targets shows per-service health;
  `/alerts` shows alert-rule state)
- Grafana: <http://localhost:3000> (`GRAFANA_PORT`, default 3000; Prometheus is the
  provisioned default datasource and the "Parkio — Hosted Beta Overview" dashboard is
  bundled — see Alerting & dashboards below)
- Ad-hoc check: `curl http://localhost:8081/actuator/prometheus | grep parkio_`

Spring Boot's built-in Micrometer bindings (JVM memory/GC, process CPU,
`http.server.requests`, HikariCP, Kafka client metrics where applicable) are exported
automatically in addition to the custom metrics below.

## Custom metric catalogue

Prometheus rendering replaces dots with underscores and suffixes counters with
`_total` (e.g. `parkio.auth.login.success.count` → `parkio_auth_login_success_count_total`).

### Outbox / inbox (every service with an outbox and/or inbox)

| Metric | Type | Services | Meaning |
|---|---|---|---|
| `parkio.outbox.unpublished.count` | gauge | auth, user, parking, media, gamification, notification, moderation, ai-validation | Relayable outbox rows (`published = false AND dead_lettered = false`). Sustained growth ⇒ the outbox relay is not draining to Kafka. Dead-lettered rows are **excluded** so a poison row doesn't masquerade as backlog. |
| `parkio.outbox.oldest.unpublished.age.seconds` | gauge | same | Age of the oldest relayable row (0 when empty). Alert when it exceeds a few relay intervals. |
| `parkio.outbox.deadlettered.count` | gauge | auth, user, parking, media, gamification, moderation, ai-validation | Dead-lettered (poison) outbox rows retained in-table for inspection/redrive. **Any non-zero value is actionable** — a publish has permanently failed `parkio.kafka.relay.max-attempts` times. |
| `parkio.outbox.publish.failed` | counter | same as dead-lettered | Per-row publish attempts that failed (all causes: broker error, unreadable payload, no topic mapping). A rising rate signals broker/contract trouble before rows dead-letter. |
| `parkio.outbox.deadlettered` | counter | same | Rows that crossed into the dead-lettered state. Pairs with the gauge: the counter is the rate of new poison rows, the gauge is the current depth. |
| `parkio.inbox.processed.count` | gauge | auth, user, parking, gamification, notification, moderation, ai-validation, analytics | Inbox dedup rows currently retained. A flat line while events flow ⇒ the consumer stopped processing. Retention cleanup makes this dip periodically — that is expected. |

Gauges are backed by one cheap COUNT/MIN repository query per scrape; they never run
on a request path. The two outbox counters are incremented in the relay (no extra
query). The `parkio.outbox.publish.failed` / `parkio.outbox.deadlettered` counters
render in Prometheus as `..._total` per the suffix rule above.

> **notification-service** exports the outbox backlog gauges but has **no relay yet**,
> so it does not emit the dead-letter gauge/counters. **analytics-service** has no
> producer outbox, so it exports neither.

### Notification delivery (notification-service)

| Metric | Type | Meaning |
|---|---|---|
| `parkio.notification.delivery.pending.count` | gauge | Attempts queued or awaiting a retry. Growth ⇒ worker not draining. |
| `parkio.notification.delivery.sent.count` | gauge | Attempts successfully handed to the provider (lifetime rows). |
| `parkio.notification.delivery.failed.count` | gauge | Terminally failed attempts (retries exhausted). |
| `parkio.notification.delivery.skipped.count` | gauge | Attempts intentionally skipped (e.g. no active device token). |
| `parkio.notification.delivery.worker.success.count` | counter | Sends handed off by this worker instance since start. |
| `parkio.notification.delivery.worker.failure.count` | counter | Failed sends (retried or terminal) by this worker instance. |

### Parking (parking-service)

| Metric | Type | Meaning |
|---|---|---|
| `parkio.parking.active.count` | gauge | Spots currently `ACTIVE`. |
| `parkio.parking.verified.count` | gauge | Spots currently `VERIFIED`. |
| `parkio.parking.suspicious.count` | gauge | Spots currently `SUSPICIOUS`. |
| `parkio.parking.expired.count` | gauge | Spots in the terminal `EXPIRED` state. |
| `parkio.parking.expiry.job.expired.count` | counter | Spots transitioned to `EXPIRED` by the expiry job since start. |

### Auth (auth-service)

| Metric | Type | Meaning |
|---|---|---|
| `parkio.auth.login.success.count` | counter | Successful logins. |
| `parkio.auth.login.failure.count` | counter | Failed logins (bad credentials, suspended account, ...). A spike suggests credential stuffing. |

No email, user id, or other PII is ever used as a tag.

### Media (media-service)

| Metric | Type | Meaning |
|---|---|---|
| `parkio.media.upload.count` | counter | Uploads accepted and stored (idempotent replays are not re-counted). |
| `parkio.media.rejected.count` | counter | Uploads rejected by validation. |

### Gateway (gateway-service)

| Metric | Type | Meaning |
|---|---|---|
| `parkio.gateway.rate.limit.rejected.count` | counter | Requests answered `429` at the edge (the Redis `RequestRateLimiter`). Backend services never return 429 themselves. |

Spring Cloud Gateway's built-in `spring.cloud.gateway.requests` timer (per route/status)
is also exported.

## Kafka DLT and consumer lag — broker-level monitoring

There is intentionally **no app-level `parkio.kafka.consumer.dlt.count`**: services
publish poison messages to `parkio.dlt.<service>` and move on; consuming the DLTs from
the apps just to count them would couple triage to runtime. Monitor at the broker:

- **DLT depth**: alert when any `parkio.dlt.*` topic's end offset grows (e.g.
  `kafka-run-class.sh kafka.tools.GetOffsetShell --topic 'parkio.dlt.*'`, a Kafka
  exporter, or Redpanda Console locally).
- **Consumer lag**: `kafka-consumer-groups.sh --describe --all-groups`, Burrow, or the
  Kafka exporter's `kafka_consumergroup_lag` metric.

Spring's Kafka client metrics (consumer fetch/commit rates) are exported per service
via the automatic Micrometer binding and complement the broker-side view.

## Alerting & dashboards

### Alert rules

`docker/prometheus/alerts.yml` defines the hosted-beta alert baseline (wired into
`prometheus.yml` via `rule_files` and mounted at `/etc/prometheus/alerts.yml`). Thresholds
are sized for a small single-VPS beta — tune as traffic grows.

| Alert | Severity | Expr (summary) | Fires when |
|---|---|---|---|
| `ServiceDown` | critical | `up{job="parkio-services"} == 0` | a service is unscrapeable for 2m |
| `GatewayHigh5xxRate` | critical | gateway 5xx / total > 0.05 | >5% edge 5xx over 5m, sustained 10m |
| `OutboxDeadlettered` | critical | `parkio_outbox_deadlettered_count > 0` | any poison row persists ≥5m |
| `OutboxOldestUnpublishedTooOld` | critical | `parkio_outbox_oldest_unpublished_age_seconds > 300` | oldest relayable row >5m old, for 10m |
| `DatabaseConnectionPoolExhausted` | critical | `hikaricp_connections_pending > 0` | threads block on a DB connection for 5m |
| `HighJvmMemoryUsage` | warning | heap used/max > 0.9 | heap >90% for 10m |
| `MediaUploadFailuresHigh` | warning | rejects / attempts > 0.5 | >50% uploads rejected over 15m |
| `AuthLoginFailuresHigh` | warning | `rate(login_failure) > 0.2/s` | ~>12 failed logins/min for 10m |
| `GatewayRateLimitSpike` | warning | `rate(rate_limit_rejected) > 1/s` | >60 × 429/min for 10m |
| `NotificationDeliveryFailuresHigh` | warning | worker failures / attempts > 0.5 | >50% deliveries fail over 15m |

### Viewing alerts (no Alertmanager yet)

There is **no Alertmanager** in the stack. Rules evaluate and firing alerts are visible in
the **Prometheus UI → `/alerts`** (and via the `ALERTS` metric), but nothing is
**notified** (no email/Slack/PagerDuty) until an Alertmanager is added. To enable
notifications later: add an `alertmanager` service and uncomment the `alerting:` block in
`prometheus.yml`. In hosted-beta both Prometheus (`:9090`) and Grafana (`:3000`) are bound
to **loopback only** — reach them via SSH tunnel
(`ssh -L 3000:localhost:3000 -L 9090:localhost:9090 user@vps`).

### Grafana dashboard

`docker/grafana/provisioning/dashboards/parkio-hosted-beta.json`
("**Parkio — Hosted Beta Overview**", uid `parkio-hosted-beta`) is auto-provisioned and
references the Prometheus datasource by stable uid `parkio-prometheus`. Panels: services
down / outbox DLQ depth / oldest-unpublished age / gateway 429 (stat row), service health
(`up`), gateway 5xx ratio, outbox dead-lettered + oldest-age + backlog by service, auth
login success vs failure, media uploads vs rejects, JVM heap %, and DB pool active/pending.

### Not yet scraped (known gaps)

These alerts are **documented but not enabled** because the stack has no exporter for them
(templates are kept, commented, at the bottom of `alerts.yml`):

- **`KafkaConsumerLagHigh`** — broker-level. Needs a Kafka exporter exposing
  `kafka_consumergroup_lag`. Until then, monitor lag manually
  (`kafka-consumer-groups.sh --describe --all-groups`).
- **`DiskSpaceLow`** — host-level. Needs `node_exporter` exposing
  `node_filesystem_avail_bytes`. Until then, watch disk on the VPS manually (`df -h`).
- **Kafka DLT depth** (`parkio.dlt.*` end offsets) — broker-level, same Kafka-exporter gap;
  the app-side `OutboxDeadlettered` alert covers the *producer* poison path in the meantime.

### Runbook — `OutboxDeadlettered`

A dead-lettered row means the relay tried to publish an event
`parkio.kafka.relay.max-attempts` times and gave up; the row is retained (`dead_lettered =
true`) for inspection and is **excluded** from both the backlog gauge and the relay's claim
query, so it will never publish on its own. Events are being lost for that aggregate until
you act.

1. **Identify the service** from the alert's `service` label, then inspect the rows
   (`<svc>` = auth, parking, media, …; connect with `docker exec -it parkio-postgres-<svc>
   psql -U parkio_<svc> -d parkio_<svc>`):

   ```sql
   SELECT id, aggregate_type, event_type, failure_count, last_failure_reason, last_failed_at
   FROM outbox_events
   WHERE dead_lettered = true
   ORDER BY last_failed_at DESC;
   ```

2. **Diagnose `last_failure_reason`.** Common cases:
   - *Broker unreachable / timeout* → Kafka was down; the fix is infrastructure, the payload
     is fine → safe to redrive.
   - *No topic mapping / serialization error* → a contract/code bug → fix the mapping or
     payload handling and deploy **before** redriving, or the row will just re-fail.
   - *Genuinely malformed/obsolete event* → may be correct to leave dead-lettered.

3. **Redrive after the root cause is fixed** (resets the row so the relay re-claims it):

   ```sql
   UPDATE outbox_events
   SET dead_lettered = false, published = false, failure_count = 0,
       last_failure_reason = NULL, last_failed_at = NULL
   WHERE dead_lettered = true AND id = '<row-id>';   -- redrive one row; omit `id` to redrive all
   ```

   The scheduled relay picks it up within one poll interval; the alert clears once
   `parkio_outbox_deadlettered_count` returns to 0. Consumers are idempotent (inbox dedup),
   so a redrive that double-delivers is safe.

4. **When NOT to redrive:** the event is obsolete/superseded, the payload is irreparably
   malformed, or the downstream contract no longer accepts it. In that case leave it
   dead-lettered (or archive/delete the row deliberately) and record why — redriving would
   only loop back into the DLQ.

## Adding a new metric

- Keep metric components in `infrastructure/metrics` per service; never in `domain`.
- Names: `parkio.<area>.<thing>.<unit-ish>` (dots; Prometheus rewrites them).
- Gauges must be backed by cheap (indexed COUNT) queries — they run on every scrape.
- Never tag with unbounded or personal values (user ids, emails, file names, tokens).
- Add a representative unit test with `SimpleMeterRegistry`.
