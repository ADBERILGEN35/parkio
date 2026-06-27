# Performance, Scalability and Capacity Guide

This guide is the P2.2 performance baseline for Parkio. It is intentionally
measurement-first: no optimization is accepted unless a benchmark, trace,
profile or query plan proves the bottleneck and verifies the improvement.

## Executive Summary

Parkio now has a repeatable performance harness, but this workstation could not
run Docker-backed benchmarks because the Docker daemon is unavailable from WSL.
The current state is therefore benchmark-ready, not capacity-proven.

No application code or runtime tuning was changed in P2.2. Applying speculative
pool, cache, Kafka or query tuning without measured pressure would risk moving
the bottleneck or hiding a correctness issue.

## Performance Score

Measurement readiness score: **70/100**.

This score reflects available observability, bounded runtime configuration,
service-level metrics, and the new benchmark harness. It is not a production
throughput score. A real performance score must be assigned after controlled
benchmark runs on known hardware.

## Bottleneck Ranking

| Rank | Area | Expected impact | Evidence to collect before optimizing | Current action |
| --- | --- | --- | --- | --- |
| 1 | Parking nearby search | High user-facing read path; geospatial filtering can dominate p95/p99 | k6 nearby p95/p99, Postgres `EXPLAIN (ANALYZE, BUFFERS)`, Hikari active/pending | Measure first |
| 2 | Media upload | Multipart memory copy, ClamAV scan, MinIO write and DB transaction are all on the request path | upload p95/p99, heap allocation/JFR, MinIO latency, scan timing | Measure first |
| 3 | Gateway auth fan-out | Every authenticated request depends on JWT validation plus account/session checks | gateway route p95 by URI, auth/user downstream timing, Redis latency | Measure first |
| 4 | Outbox relay throughput | Async event latency depends on relay batch size, broker ack latency and DB polling | `parkio_outbox_publish_duration`, batch size, oldest unpublished age, Kafka lag | Measure first |
| 5 | Kafka consumers | One slow consumer can create lag and delayed notifications/analytics | `kafka_consumergroup_lag`, consumer processing timers, DLT growth | Measure first |
| 6 | Auth login | BCrypt is intentionally CPU-expensive and can saturate CPU under login bursts | login p95/p99, CPU profile, auth CPU quota saturation | Measure first |
| 7 | Analytics reads | Snapshot reads may be cheap now, but admin aggregate paths can grow with data | analytics endpoint p95, query plans, row counts | Measure first |
| 8 | Redis-backed controls | Rate limiting, idempotency and session support depend on Redis latency | Redis command latency, connection usage, gateway/auth error rate | Measure first |
| 9 | JVM heap/native memory | Hosted-beta uses cgroup caps and MaxRAMPercentage; native/thread pressure still needs proof | heap, metaspace, thread count, GC p95, OOM/restart logs | Measure first |
| 10 | Docker single-host limits | Hosted-beta is a single VPS layout, not horizontally scaled production | container CPU throttling, memory RSS, disk IO, network throughput | Measure first |

## Benchmark Harness

The primary harness is `benchmarks/k6/http-load.js`.

It measures:

- login, refresh and logout;
- profile, gamification and notification reads;
- nearby search, geocoding and spot details;
- moderation self-service reads;
- personal analytics reads;
- optional media upload and spot creation.

Run:

```bash
PARKIO_BASE_URL=http://localhost:8080 \
PARKIO_K6_EMAIL=user@real-e2e.parkio.local \
PARKIO_K6_PASSWORD='StrongParkio123' \
k6 run --summary-export benchmarks/reports/http-load-summary.json benchmarks/k6/http-load.js
```

Collect runtime metrics from Prometheus after or during a run:

```bash
PROMETHEUS_URL=http://localhost:9090 \
bash benchmarks/scripts/collect-prometheus-baseline.sh benchmarks/reports/prometheus-baseline
```

## HTTP Performance

HTTP benchmark output must include throughput, p50, p90, p95, p99, max latency
and error rate. The k6 script emits custom trends for:

- `parkio_login_latency`
- `parkio_refresh_latency`
- `parkio_profile_latency`
- `parkio_nearby_latency`
- `parkio_geocoding_latency`
- `parkio_spot_details_latency`
- `parkio_upload_latency`
- `parkio_create_spot_latency`
- `parkio_moderation_latency`
- `parkio_analytics_latency`

No local HTTP performance numbers are recorded in this document because the
Docker runtime could not be started.

## Kafka Performance

Measure Kafka through Prometheus and event-triggering HTTP flows:

- producer latency: `parkio_outbox_publish_duration_seconds`;
- relay throughput: `parkio_outbox_batch_size` and publish success counters;
- backlog: `parkio_outbox_oldest_unpublished_age_seconds`;
- consumer lag: `kafka_consumergroup_lag`;
- DLT depth: current minus oldest offset for `parkio.dlt.*` topics.

Backpressure is considered healthy only if lag and outbox age drain after the
load stops and no DLT growth occurs.

## Database Performance

Before adding indexes or changing queries, capture:

- endpoint p95/p99 tied to SQL spans/traces;
- Hikari active, idle and pending connection metrics;
- row counts for the target tables;
- `EXPLAIN (ANALYZE, BUFFERS)` for slow queries;
- transaction duration where outbox writes occur.

Current highest-priority DB candidates are parking nearby search, media upload
metadata writes, auth login/user lookup and analytics snapshot reads.

## JVM Analysis

Use JFR during a controlled load run:

```bash
jcmd <pid> JFR.start name=parkio-load settings=profile duration=120s filename=/tmp/parkio-load.jfr
```

Review:

- heap and allocation rate;
- GC pause p95/p99;
- thread counts and blocked threads;
- direct buffer/native memory;
- CPU hotspots;
- lock contention.

Do not tune heap, GC or thread pools without matching container memory and CPU
evidence.

## Redis Analysis

Measure:

- Redis command latency;
- rate-limit/idempotency/session error rates;
- connection usage;
- cache hit ratio where a cache is explicitly implemented;
- TTL behavior and key growth.

No new caching should be added until a measured read path proves repeated,
expensive computation or IO.

## Memory Analysis

Watch for:

- multipart upload byte-array copies;
- large JSON payload serialization;
- map/search result lists;
- outbox/inbox batch materialization;
- Kafka producer buffers;
- direct memory and thread stack growth.

The media upload path is the first memory profile candidate because it reads the
multipart file into memory before scan/store.

## Capacity Planning

Capacity numbers are intentionally pending until benchmark data exists. Use this
template after collecting a stable baseline:

| Load level | CPU | Memory | DB connections | Kafka throughput | Redis usage | Storage growth | Network | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 100 users | TBD | TBD | TBD | TBD | TBD | TBD | TBD | k6 + Prometheus |
| 500 users | TBD | TBD | TBD | TBD | TBD | TBD | TBD | k6 + Prometheus |
| 1,000 users | TBD | TBD | TBD | TBD | TBD | TBD | TBD | k6 + Prometheus |
| 5,000 users | TBD | TBD | TBD | TBD | TBD | TBD | TBD | k6 + Prometheus |
| 10,000 users | TBD | TBD | TBD | TBD | TBD | TBD | TBD | k6 + Prometheus |

Existing hosted-beta resource ceilings are documented in
`docs/operations/runtime-sizing.md`; they are safety caps, not proven user
capacity.

## SLO Proposal

Initial SLOs should be treated as proposed targets until benchmarked:

| Operation | Availability target | Latency target | Error budget | Measurement |
| --- | --- | --- | --- | --- |
| Login | 99.5% beta | p95 < 2s | 0.5% monthly | k6 login trend + gateway/auth metrics |
| Refresh | 99.7% beta | p95 < 1s | 0.3% monthly | k6 refresh trend |
| Nearby search | 99.5% beta | p95 < 1.5s | 0.5% monthly | k6 nearby trend + parking DB metrics |
| Geocoding | 99.0% beta | p95 < 2.5s | 1.0% monthly | k6 geocoding trend + provider errors |
| Upload | 99.0% beta | p95 < 5s | 1.0% monthly | optional k6 upload + media metrics |
| Kafka publish | 99.5% beta | p95 < 500ms | 0.5% monthly | outbox publish timer |
| Kafka consume | 99.5% beta | lag drains < 5m | 0.5% monthly | consumer lag + processing timers |
| Notification | 99.0% beta | p95 < 5s async | 1.0% monthly | notification event-to-visible latency |
| Analytics | 99.0% beta | p95 < 2s | 1.0% monthly | k6 analytics trend |

## Optimizations Applied

None.

No benchmark or profile data was collected locally in P2.2 due to Docker daemon
unavailability, so there was no justified optimization target.

## Remaining Bottlenecks

- No controlled load-test result on known hardware yet.
- No JFR profile captured under load yet.
- No query plans captured under realistic parking/media/auth traffic yet.
- No Kafka relay saturation test yet.
- No Redis latency baseline under gateway/auth load yet.
- No long soak test for heap/native memory stability yet.

## Production Scalability Assessment

Parkio is suitable for hosted-beta performance validation once the runtime stack
can be exercised on a Docker host. It is not yet capacity-proven for production
traffic tiers because throughput, tail latency and saturation points have not
been measured.

## Recommended P2.3 Sprint

1. Run the k6 harness on a known 8 vCPU / 24 GB host and publish raw summaries.
2. Capture Prometheus baselines before, during and after the run.
3. Capture JFR profiles for gateway, parking, auth and media under load.
4. Capture `EXPLAIN (ANALYZE, BUFFERS)` for the top three slow DB spans.
5. Run a Kafka relay saturation test and verify lag drains without duplicates.
6. Apply only measured optimizations, one at a time, with before/after reports.
