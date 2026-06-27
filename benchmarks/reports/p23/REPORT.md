# P2.3 - Production Workload Validation

**Date:** 2026-06-27  
**Status:** Not production-validated. This report records what is mapped from the current codebase and what remains unproven because the requested write-heavy end-to-end workload could not be executed from this shell.

## 1. Executive Summary

P2.3 is a **No-Go** for production deployment today.

Fresh end-to-end write workload validation was not executed. The current shell cannot access Docker:

| command | result |
| --- | --- |
| `docker ps --format ...` | failed: `unix:///var/run/docker.sock` missing |
| `docker.exe ps --format ...` | failed: `WSL ... UtilBindVsockAnyPort ... socket failed 1` |

Existing measured evidence is P2.2.1 read-path only. The strongest saved run is `benchmarks/reports/p221/multiuser/mu60push/summary.json`: 176,740 HTTP requests, 1,181.66 req/s, p50 2.426 ms, p90 4.816 ms, p95 5.789 ms, p99 8.915 ms, max 58.842 ms, and zero HTTP failures. That run explicitly did **not** exercise durable upload, spot creation, Kafka fan-out, notification creation, analytics projection, traces, or recovery under write traffic.

Blocking evidence gap: no measured p50/p90/p95/p99/max/avg exists for the complete write path from user click through gateway, DB commit, outbox, Kafka, consumers, notification, analytics, and frontend visibility.

## 2. End-to-End Workflow Diagram

### Primary upload -> spot -> event fan-out workflow

```text
Browser /upload submit
  -> POST /api/v1/media/upload through gateway
  -> media-service validates idempotency key
  -> media-service reads multipart bytes
  -> media-service validates content type, size, magic bytes, duplicate checksum
  -> media-service calls ClamAV before object storage
  -> media-service normalizes image
  -> media-service stores normalized bytes in MinIO
  -> media-service commits PostgreSQL media metadata + validation rows
  -> media-service writes MediaUploaded outbox row
  -> media-service outbox relay publishes MediaUploaded to Kafka
  -> ai-validation-service consumes MediaUploaded, records inbox, emits AiValidationCompleted outbox row
  -> moderation-service consumes AiValidationCompleted and may open moderation case / emit moderation events

Browser continues
  -> POST /api/v1/parking/spots through gateway
  -> parking-service validates idempotency key
  -> parking-service calls media-service internal readiness/access boundary
  -> parking-service creates ParkingSpot domain object
  -> parking-service commits spot, status history, outbox row in PostgreSQL
  -> parking-service outbox relay publishes ParkingSpotCreated to Kafka
  -> gamification-service consumes ParkingSpotCreated, records inbox, updates score, writes gamification outbox events
  -> notification-service consumes ParkingSpotCreated and PointsEarned, records inbox, creates notification rows, writes NotificationCreated outbox rows
  -> analytics-service consumes ParkingSpotCreated and PointsEarned, records inbox, writes analytics event/snapshot rows
  -> frontend redirects to /spots/{id}
  -> search visibility is read through GET /api/v1/parking/spots/nearby and spot visibility rules
```

### Production write workflows identified

| Workflow | Entry point | Durable writes / event behavior |
| --- | --- | --- |
| Register | `POST /api/v1/auth/register` | Auth user + verification state; `UserRegistered` outbox. |
| Login | `POST /api/v1/auth/login` | Refresh token family/session state; HTTP-only refresh cookie; auth metrics. |
| Refresh | `POST /api/v1/auth/refresh-token` | Refresh token rotation/revocation; HTTP-only refresh cookie. |
| Logout / logout-all | `POST /api/v1/auth/logout`, `/logout-all` | Refresh token revocation/session invalidation. |
| Password / email flows | verify, resend, forgot, reset, change password | Auth DB token/status writes; some paths revoke sessions. |
| User profile provisioning/update | user-service write endpoints and `UserRegistered` consumer | User profile rows; `UserProfileCreated` outbox. |
| Upload media | `POST /api/v1/media/upload` | Media DB rows, validation rows, MinIO object, `MediaUploaded` outbox. |
| Delete media | media delete endpoint | Soft-delete metadata; storage/object cleanup behavior not validated here. |
| Create spot | `POST /api/v1/parking/spots` | Parking spot, status history, `ParkingSpotCreated` outbox. |
| Verify / claim spot | `POST /parking/spots/{id}/verify`, `/claim` | Verification/claim state, status history, parking outbox events. |
| Moderation actions | moderation service write endpoints / consumers | Cases, appeals, user/spot moderation events. |
| Gamification projection | Kafka consumer | Inbox dedupe, points/level/contribution rows, gamification outbox events. |
| Notification creation | Kafka consumer | Inbox dedupe, notification rows, `NotificationCreated` outbox. |
| Notification user actions | notification-service write endpoints | Mark read/device-token/delivery state writes. |
| Analytics projection | Kafka consumers | Inbox dedupe, append-only analytics events and aggregate snapshots. |
| Cache-related writes | gateway/auth/parking Redis use | Rate limiter, login lockout, geocoding cache. No measured cache invalidation behavior in P2.3. |

No application class named "timeline" was found in the current search. Frontend spot detail explicitly notes there is no verification timeline endpoint in `SpotDetailPage.tsx`, so "user timeline updates" are unproven/not currently mapped as a production write workflow.

## 3. Latency Tables

### Required P2.3 end-to-end write latency

| Workflow | p50 | p90 | p95 | p99 | max | avg | Evidence |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Login complete path | Not measured | Not measured | Not measured | Not measured | Not measured | Not measured | Docker unavailable; no P2.3 run. |
| Refresh complete path | Not measured | Not measured | Not measured | Not measured | Not measured | Not measured | Docker unavailable; no P2.3 run. |
| Upload media: click -> MinIO -> DB -> outbox -> Kafka | Not measured | Not measured | Not measured | Not measured | Not measured | Not measured | P2.2.1 disabled durable upload probes. |
| Create spot: click -> DB -> outbox -> Kafka -> consumers | Not measured | Not measured | Not measured | Not measured | Not measured | Not measured | P2.2.1 disabled durable spot-create probes. |
| Notification creation from spot/gamification events | Not measured | Not measured | Not measured | Not measured | Not measured | Not measured | No write fan-out run. |
| Analytics projection from spot/gamification events | Not measured | Not measured | Not measured | Not measured | Not measured | Not measured | No write fan-out run. |
| Frontend visibility/search after create | Not measured | Not measured | Not measured | Not measured | Not measured | Not measured | No browser + real-stack P2.3 run. |

### Existing read-path evidence, not a substitute for P2.3

| Run | Requests | Throughput | p50 | p90 | p95 | p99 | max | avg | Errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| P2.2.1 `mu60push` mixed reads | 176,740 | 1,181.66 req/s | 2.426 ms | 4.816 ms | 5.789 ms | 8.915 ms | 58.842 ms | 2.957 ms | 0 |

## 4. Throughput Tables

### Required P2.3 write workloads

| Workload | Result | Evidence |
| --- | --- | --- |
| 20 concurrent uploads | Not executed | Docker unavailable in current shell. |
| 50 concurrent uploads | Not executed | Docker unavailable in current shell. |
| 100 concurrent uploads | Not executed | Docker unavailable in current shell. |
| Mixed: 70% reads / 20% uploads / 10% authentication | Not executed | Existing k6 script can enable upload with `PARKIO_K6_ENABLE_UPLOAD=true`, but no saved P2.3 run exists. |

### Required resource signals

| Signal | P2.3 value |
| --- | --- |
| CPU | Not measured under write traffic |
| Memory | Not measured under write traffic |
| Disk IO | Not measured under write traffic |
| Network | Not measured under write traffic |
| Kafka lag | Not measured under write traffic; prior P2.2.1 report says kafka-exporter was not running |
| Outbox backlog | Not measured under write traffic |
| Redis latency | Not measured under write traffic |
| Hikari utilization | Not measured under write traffic |
| GC | Not measured under write traffic |
| Thread count | Not measured under write traffic |

## 5. Recovery Results

No recovery-under-traffic result exists for P2.3.

The repo contains `scripts/chaos-compose-validation.sh`, which stops and restarts Kafka, Redis, `postgres-parking`, MinIO, gateway, notification-service, and analytics-service, then waits for the required stack to return healthy and collects compose/log artifacts. That script is a recovery drill, not a write load test, and it was not executable here because Docker is unavailable.

| Restart target | While P2.3 traffic running | Result |
| --- | --- | --- |
| Gateway | Not executed | Unproven |
| Media | Not executed | Unproven |
| Notification | Not executed | Unproven |
| Analytics | Not executed | Unproven |
| Kafka | Not executed | Unproven |
| Redis | Not executed | Unproven |
| MinIO | Not executed | Unproven |
| One PostgreSQL instance | Not executed | Unproven |

## 6. Consistency Validation

| Check | Result |
| --- | --- |
| No duplicate events | Not proven under P2.3 traffic |
| No missing notifications | Not proven under P2.3 traffic |
| No missing analytics | Not proven under P2.3 traffic |
| No lost uploads | Not proven under P2.3 traffic |
| No orphan media | Not proven under P2.3 traffic |
| No duplicate parking spots | Not proven under P2.3 traffic |
| No broken traces | Not proven under P2.3 traffic |
| No stuck outbox rows | Not proven under P2.3 traffic |
| No inbox duplication | Not proven under P2.3 traffic |

Important existing signal from P2.2.1 artifacts: `benchmarks/reports/p221/multiuser/mu60push/prom-outbox_unpublished.json` shows notification-service with 17 unpublished outbox rows, and `prom-outbox_age.json` shows oldest unpublished age `1194185.992` seconds. This was captured during a read-path run, not a write-heavy P2.3 validation. It must be explained or cleared before production confidence can increase.

## 7. Trace Validation

No randomly selected upload trace was validated for P2.3.

Required trace proof remains missing:

| Required segment | Result |
| --- | --- |
| HTTP/browser -> gateway | Not validated |
| Gateway -> media | Not validated |
| Media -> Kafka producer | Not validated |
| Parking -> Kafka producer | Not validated |
| Kafka consumer spans | Not validated |
| Notification spans | Not validated |
| Analytics spans | Not validated |
| `traceparent` propagation | Not validated |
| Span hierarchy and timing correctness | Not validated |

Existing compose wiring includes Tempo and OTLP configuration, but the P2.2.1 report states that Tempo was not running in that measured session and traces were dropped. Therefore there is no trace evidence that satisfies P2.3.

## 8. Resource Usage

No P2.3 write-load resource snapshot was collected.

Existing P2.2.1 read-path artifacts available for comparison only:

| Artifact | Path |
| --- | --- |
| k6 read summary | `benchmarks/reports/p221/multiuser/mu60push/summary.json` |
| Docker stats | `benchmarks/reports/p221/multiuser/mu60push/dockerstats.csv` |
| Prometheus CPU/heap/GC/Hikari/Redis/outbox snapshots | `benchmarks/reports/p221/multiuser/mu60push/prom-*.json` |
| Read-path report | `benchmarks/reports/p221/REPORT.md` |

Required P2.3 evidence not attached because it does not exist from this run: Prometheus snapshots, Grafana screenshots, Tempo traces, live Docker stats, write-load container logs, GC logs, Kafka metrics, Redis metrics, and outbox metrics under write pressure.

## 9. Findings

### Critical

| Finding | Evidence | Impact |
| --- | --- | --- |
| P2.3 production write workload was not executed | Docker commands failed in this shell; no P2.3 artifacts exist under `benchmarks/reports/p23` before this report | Cannot deploy based on write-heavy production validation. |

### High

| Finding | Evidence | Impact |
| --- | --- | --- |
| Complete write-path latency is unmeasured | No p50/p90/p95/p99/max/avg exists for upload -> MinIO -> DB -> outbox -> Kafka -> consumers -> frontend visibility | Production SLO and capacity claims for the primary write path are unsupported. |
| Recovery under active write traffic is unmeasured | `scripts/chaos-compose-validation.sh` exists, but was not run with P2.3 traffic | Data-loss/duplicate-processing recovery claims are unsupported. |
| Distributed trace continuity for uploads is unmeasured | Prior P2.2.1 report says Tempo was not running and traces were dropped | Broken trace propagation may remain hidden in the exact workflow that needs trace proof. |
| Notification-service outbox backlog existed in prior artifacts | P2.2.1 `mu60push` Prometheus snapshot: 17 unpublished rows, oldest age ~1,194,186s | Must be explained before claiming no stuck outbox rows. |

### Medium

| Finding | Evidence | Impact |
| --- | --- | --- |
| Kafka lag was not observable in prior benchmark session | P2.2.1 report states kafka-exporter was not running | Consumer lag and event-drain behavior cannot be verified from existing artifacts. |
| User timeline update requirement does not map to a current implementation | Search found no timeline service; frontend spot detail comments say no timeline/history endpoint | Requirement cannot be validated as stated without clarifying expected product surface. |

### Low

| Finding | Evidence | Impact |
| --- | --- | --- |
| Existing k6 upload path uses a tiny JPEG | `benchmarks/k6/http-load.js` upload helper posts a minimal JPEG | Useful for plumbing, but not representative for realistic media size, scan time, MinIO IO, or browser-visible upload UX. |

## 10. Production Readiness Score

**Score: 35 / 100 for production readiness after P2.3 review.**

Rationale:

- Read-path capacity has credible prior evidence.
- Architecture includes the expected transactional outbox, inbox dedupe, Kafka consumers, Prometheus, and Tempo wiring.
- The requested P2.3 write-heavy validation was not executed and therefore cannot support production approval.
- Trace, recovery, consistency, Kafka lag, and write-resource behavior remain unproven.

## 11. Go / No-Go Recommendation

**No-Go. Do not deploy Parkio to production today based on available P2.3 evidence.**

Blocking issues:

1. Execute fresh write-heavy P2.3 workloads on a Docker-capable host.
2. Capture real end-to-end write latency percentiles for login, refresh, upload, spot creation, notification creation, analytics projection, and frontend visibility.
3. Prove outbox drain and explain/clear the existing notification-service unpublished outbox rows.
4. Run recovery drills while write traffic is active and prove no data loss or duplicate processing.
5. Restore and validate Tempo trace continuity for randomly selected upload workflows.
6. Restore Kafka lag visibility before claiming consumer recovery or event-drain correctness.
7. Collect and attach Prometheus, Grafana, Tempo, Docker stats, logs, GC, Kafka, Redis, and outbox evidence for the write run.

Production confidence estimate: **35%**. This is intentionally low because the main P2.3 objective is unmeasured, not because the code was proven defective.

## 12. Remaining P2.4 Backlog

P2.4 should not start until P2.3 has real artifacts. Minimum backlog:

1. Run P2.3 on a Docker-capable host using the full infra + apps stack.
2. Add or run a write-heavy k6 scenario that separates:
   - login/refresh
   - media upload
   - spot creation
   - post-create search/detail visibility
   - notification and analytics convergence checks
3. Use realistic media payloads in addition to the tiny plumbing JPEG.
4. Capture Prometheus snapshots before, during, and after every workload stage.
5. Capture Docker stats continuously for every service and dependency.
6. Query PostgreSQL service databases after the run for duplicates, missing projections, outbox backlog, inbox duplicates, orphan media, and duplicate spots.
7. Query Kafka consumer groups through a running exporter or CLI capture.
8. Export selected Tempo traces and verify span hierarchy manually.
9. Run chaos scenarios during active write traffic, not only idle health recovery.
10. Produce a final P2.3 evidence package with raw artifacts plus a concise verdict.

## Reproduction Target For A Docker-Capable Host

The existing read harness has an upload gate, but it must be extended or wrapped for the full P2.3 assertions.

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.apps.yml up -d --build

PARKIO_K6_EMAIL=user@real-e2e.parkio.local \
PARKIO_K6_PASSWORD=StrongParkio123 \
PARKIO_K6_ENABLE_UPLOAD=true \
PARKIO_K6_VUS=20 \
PARKIO_K6_DURATION=5m \
k6 run benchmarks/k6/http-load.js
```

That command alone is insufficient for P2.3 because it does not verify notification/analytics convergence, traces, recovery, Kafka lag, or database consistency. It is only a starting point for the write-load harness.
