# Reliability Guide

This guide covers the P2.1 reliability posture for the Parkio backend and
hosted-beta Docker Compose runtime. It is operational documentation only: no REST
contracts, Kafka event contracts, database schemas, authentication flows, or
frontend behavior are changed by the reliability work.

## Executive Summary

Parkio is resilient to many common single-container failures through:

- gateway-only ingress with downstream fail-closed checks;
- database-per-service isolation;
- transactional outbox for event durability;
- inbox deduplication for idempotent consumers;
- Kafka DLTs and outbox dead-letter recovery;
- explicit container healthchecks and startup ordering;
- graceful Spring shutdown and Docker `stop_grace_period`;
- bounded JVM/container resources;
- Prometheus, Loki, Tempo, Grafana, and Alertmanager.

P2.1 adds explicit client deadlines for Hikari, Kafka producers/consumers, Redis,
Resend, and MinIO/S3-compatible storage, plus a repeatable Docker Compose chaos
validation drill.

## Reliability Score

Current score: **82 / 100** for hosted-beta suitability.

Why not higher:

- stateful dependencies are still single-node in Compose;
- Kafka topic replication is `1` in hosted-beta/local defaults;
- full chaos runtime validation needs a Docker host and must run in CI or on an
  operator machine with Docker;
- no production load/soak test has proven thread and pool sizing under real traffic;
- no managed Postgres/Kafka/S3 failover has been validated.

## Service Dependency Matrix

| Service | PostgreSQL | Kafka | Redis | MinIO | HTTP dependencies |
|---|---|---|---|---|---|
| gateway-service | none | none | hard dependency for rate limiting; fail-closed operationally | none | auth-service JWKS/session epoch and user-service status; protected traffic fails closed on dependency outage |
| auth-service | hard dependency; readiness down and auth flows unavailable | outbox relay stalls; inbound moderation consumer pauses/retries/DLTs poison records | hard dependency for throttles/session support | none | Resend only when email provider is `resend`; bounded and fail-fast |
| user-service | hard dependency; readiness down and profile flows unavailable | outbox relay stalls; auth-user consumer retries/DLTs poison records | none | none | none |
| parking-service | hard dependency; readiness down and parking/search writes unavailable | outbox relay stalls; moderation consumer retries/DLTs poison records | best-effort geocoding cache; degrades to cache miss | none | media-service access-url client; geocoding provider with timeout/rate-limit/bulkhead/circuit-breaker |
| media-service | hard dependency; readiness down and media metadata unavailable | outbox relay stalls | none | hard dependency for object operations; uploads/access URLs fail fast | ClamAV scanner fail-closed with connect/read timeout |
| gamification-service | hard dependency; readiness down | parking consumer retries/DLTs poison records; outbox relay stalls | none | none | none |
| notification-service | hard dependency; readiness down | gamification consumer retries/DLTs poison records | none | none | push provider is currently noop/disabled provider; retry backlog is bounded by scheduled batch settings |
| moderation-service | hard dependency; readiness down | media and AI validation consumers retry/DLT poison records; outbox relay stalls | none | none | none |
| ai-validation-service | hard dependency; readiness down | media consumer retries/DLT poison records; outbox relay stalls | none | none | no external AI provider is wired in the current code path |
| analytics-service | hard dependency; readiness down | gamification consumer retries/DLT poison records | none | none | none |

## Failure Matrix

| Failure | Expected behavior | Recovery signal | Residual risk |
|---|---|---|---|
| PostgreSQL dies | Affected service readiness becomes unhealthy; in-flight DB calls fail; outbox/inbox data remains durable up to last committed transaction | DB container healthy, Flyway/JPA reconnect, service readiness healthy | no HA/PITR in local Compose; active requests fail |
| Kafka dies | Outbox rows remain unpublished and retry/dead-letter after configured attempts; consumers stop receiving; startup does not fail because Kafka admin fail-fast is disabled | Kafka healthy, relays publish backlog, consumer lag drains | single broker means broker loss stalls async processing |
| Redis dies | gateway rate limiting dependency is lost; auth/parking Redis uses fail according to local adapter behavior; Redis-dependent checks recover on reconnect | Redis healthy, gateway/auth/parking readiness and logs stable | gateway limiter can fail open at library level, so Redis is operationally treated as required |
| MinIO dies | media object operations fail fast; metadata DB transactions are not redesigned; uploads/access URL generation fail until storage returns | MinIO healthy; media readiness and object operations recover | no replicated object store in Compose |
| Downstream microservice dies | gateway route to that service fails; protected account/session checks fail closed; async events remain durable via Kafka/outbox | service health returns, gateway routes succeed | no service mesh retry/circuit breaker on general gateway routes |
| DNS resolution fails | affected clients fail within configured connect/request timeout; no infinite wait | DNS restored and clients reconnect | Compose DNS is single runtime dependency |
| Network latency spikes | explicit HTTP/Kafka/Redis/JDBC/MinIO timeouts bound request waits; geocoding bulkhead/rate-limit avoids thread pile-up | latency metrics normalize; no queue growth | no full latency-injection test in local WSL |
| Kafka becomes slow | producer `delivery.timeout.ms`, `request.timeout.ms`, `max.block.ms`, and outbox relay send timeout bound publisher waits | outbox backlog stops growing and drains | high latency can still create backlog and DLTs |
| One consumer is blocked | DefaultErrorHandler retries briefly, then DLT; manual ack prevents acknowledging failed processing | DLT depth visible; partition continues after poison message is routed | long-running business logic can still hold a listener thread until Kafka max poll interval |
| One relay crashes | Outbox rows remain unpublished; next relay run/container restart resumes from DB | relay metrics/backlog return to normal | no separate leader election; currently one container per service |
| Container restarts | Docker restart policy restarts apps; readiness gates dependents; graceful shutdown drains within 30s phase budget | container healthy and logs show clean startup | in-flight HTTP requests can fail during restart |
| Disk becomes full | Docker log rotation limits app container logs; node-exporter disk alerts fire | disk alert resolved; containers write normally | Postgres/Kafka/MinIO data volumes still require host capacity management |
| JVM OOM | `ExitOnOutOfMemoryError` exits; Docker restarts; memory alert should fire before sustained OOM | container restarts and readiness healthy | repeated OOM needs heap/thread/payload investigation |

## Retry Policy Review

| Channel | Current policy |
|---|---|
| HTTP gateway to downstream routes | no blanket retries; avoids retry storms on user requests |
| gateway user-status/session-epoch | bounded `WebClient.timeout`; fail closed with 503 on timeout/connection/non-2xx |
| parking geocoding provider | no retry; short timeout plus rate limiter, bulkhead, circuit breaker, fallback to application degradation |
| parking media-service client | short connect/read timeouts; no retry; caller receives fast failure |
| Resend email | no retry in request thread; bounded connect/read timeout; failures are logged/metriced and surfaced |
| Kafka producer | idempotent producer, `acks=all`, bounded request/delivery/max-block timeouts; outbox relay retries by polling persisted rows |
| Kafka consumer | manual ack; fixed backoff 500 ms with 2 retries, then DLT |
| Redis | bounded command timeout; no app-level retry loop |
| JDBC/Hikari | bounded connection/validation timeout; no app-level retry loop |
| MinIO/S3 | bounded connect/read/write/call timeout; no app-level retry loop |
| ClamAV | bounded connect/read timeout; fail-closed |
| AI providers | none currently wired |

## Timeout Review

Explicit timeouts now exist for:

- Hikari connection acquisition and validation;
- Kafka producer request, delivery, and max-block waits;
- Kafka consumer request/default API/max poll/session/heartbeat windows;
- Redis command timeout;
- gateway user-status and session-epoch checks;
- parking geocoding HTTP client;
- parking media-service HTTP client;
- auth-service Resend HTTP client;
- media-service ClamAV scanner;
- media-service MinIO/S3-compatible SDK client.

Known timeout gap: Spring Cloud Gateway route-level response timeouts are not yet
configured per route. This is a P2.2 follow-up because it changes edge behavior
for every proxied API and should be tuned with real latency data.

## Circuit Breaker Review

Only the geocoding provider currently uses Resilience4j. It is a good fit because
the provider is third-party, non-critical, rate-limited, and safely degradable.

Current configuration:

- count-based sliding window of 10 calls;
- minimum 5 calls;
- 50% failure threshold;
- 30s open-state wait;
- 2 half-open probes;
- bulkhead max concurrency default 4;
- rate limiter default 1 request/second.

No blanket `catch(Exception)` retry loop was added. Other dependencies are fail-fast
or durable-through-outbox rather than circuit-broken.

## Thread And Resource Review

- App containers have `pids_limit`.
- Hosted-beta overlay sets memory/CPU limits.
- JVM uses container-aware heap percentages, G1, capped metaspace, and exits on OOM.
- Hikari pools are explicitly capped.
- Scheduled jobs use fixed-delay polling with bounded batch sizes.
- Kafka listeners use manual ack and DLT routing.
- No `newCachedThreadPool` or unbounded custom executor was found in application code.

## Chaos Runbook

Run only against local or CI Compose stacks:

```bash
PARKIO_CHAOS_CONFIRM=local-or-ci ./scripts/chaos-compose-validation.sh \
  --env-file docker/.env.runtime-validation
```

The script validates recovery for:

- Kafka unavailable;
- Redis unavailable;
- PostgreSQL unavailable (`postgres-parking` representative DB);
- MinIO unavailable;
- gateway unavailable;
- notification-service unavailable;
- analytics-service unavailable.

It captures `docker compose ps`, health snapshots, compose config, and logs under
`chaos-validation-artifacts/`.

The same drill runs in GitHub Actions via `.github/workflows/chaos-validation.yml`
on demand, weekly, and for PRs that touch runtime-sensitive files.

## Recovery Procedures

| Incident | First response |
|---|---|
| service unhealthy | inspect `/actuator/health/readiness`, container logs, dependency health, and recent deploy diff |
| DB unavailable | restore DB container/managed DB first, then restart only affected service if it does not reconnect |
| Kafka unavailable | restore broker, watch outbox backlog and consumer lag drain; do not manually delete outbox rows |
| Redis unavailable | restore Redis; treat gateway limiting as degraded until Redis is healthy |
| MinIO unavailable | restore storage, then verify media upload/access-url paths; inspect failed upload logs |
| DLT depth > 0 | use DLQ/DLT redrive runbook; do not blindly replay poison records |
| outbox dead-letter > 0 | inspect reason and trace/event id; retry only after cause is fixed |
| JVM OOM | inspect heap, thread count, largest requests/payloads, and recent code/config changes before raising limits |
| disk full | stop noisy source, expand/prune disk, keep DB/Kafka/MinIO volume integrity first |

## Operational Limits

- Hosted-beta is a single-node runtime. It is acceptable for beta, not public
  production HA.
- Kafka replication factor defaults to `1` in local/hosted-beta.
- Postgres, Redis, Kafka, and MinIO are not managed HA services in Compose.
- Gateway protected traffic intentionally fails closed when account-status or
  session-epoch dependencies are unavailable.
- Real production should use managed Postgres, managed Kafka/Redpanda, managed
  Redis, and managed S3-compatible object storage.

## Known Failure Modes

- A stuck external provider can still consume request budget until its timeout
  fires; geocoding has a bulkhead, but general gateway routes do not yet.
- A sustained Kafka outage grows outbox backlog and eventually dead-letters rows.
- A slow consumer can lag even though poison records route to DLT.
- Single-node disk exhaustion can affect every stateful service.
- Runtime validation and chaos validation require Docker; local WSL environments
  without Docker can only verify syntax/config, not live recovery.

## P2.2 Backlog

1. Add route-level gateway connect/response timeouts with per-route defaults.
2. Add latency-injection chaos scenarios, not just container stop/start.
3. Add a soak/load profile that records pool utilization, GC, Kafka lag, and p95/p99.
4. Move stateful dependencies to managed HA services for public production.
5. Add service-specific Hikari sizing after load data rather than using one default.
6. Add bounded concurrency controls for expensive media upload paths.
7. Add backup/PITR drills against the target hosted-beta VPS or managed DB.
