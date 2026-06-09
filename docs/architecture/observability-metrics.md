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

- Prometheus UI: <http://localhost:9090> (Status → Targets shows per-service health)
- Grafana: <http://localhost:3001> (Prometheus is provisioned as the default datasource;
  build dashboards against the metric names below)
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
| `parkio.outbox.unpublished.count` | gauge | auth, user, parking, media, gamification, notification, moderation, ai-validation | Outbox rows with `published = false`. Sustained growth ⇒ the outbox relay is not draining to Kafka. |
| `parkio.outbox.oldest.unpublished.age.seconds` | gauge | same | Age of the oldest unpublished row (0 when empty). Alert when it exceeds a few relay intervals. |
| `parkio.inbox.processed.count` | gauge | auth, user, parking, gamification, notification, moderation, ai-validation, analytics | Inbox dedup rows currently retained. A flat line while events flow ⇒ the consumer stopped processing. Retention cleanup makes this dip periodically — that is expected. |

Gauges are backed by one cheap COUNT/MIN repository query per scrape; they never run
on a request path.

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

## Adding a new metric

- Keep metric components in `infrastructure/metrics` per service; never in `domain`.
- Names: `parkio.<area>.<thing>.<unit-ish>` (dots; Prometheus rewrites them).
- Gauges must be backed by cheap (indexed COUNT) queries — they run on every scrape.
- Never tag with unbounded or personal values (user ids, emails, file names, tokens).
- Add a representative unit test with `SimpleMeterRegistry`.
