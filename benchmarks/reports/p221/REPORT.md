# P2.2.1 — Real Performance Benchmark Execution

**Date:** 2026-06-27 · **Stack:** full Parkio Docker Compose (infra + 10 app services + Prometheus/Grafana)
**Status:** Executed for real on a Docker-capable host. Numbers below are measured, not modeled.

> Headline: under realistic multi-user read load the backend served **1,182 req/s at p95 ≈ 6 ms / p99 ≈ 9 ms with 0 errors** while using a small fraction of the host. The system was **never driven to saturation** — the binding control under load is the **gateway per-user rate limiter** (by design), not backend CPU/DB/Kafka. The write path was **not** exercised and remains the main unknown.

---

## 1. Environment specs

| Item | Value |
| --- | --- |
| Docker engine | 29.5.3 (Docker Desktop, WSL2 backend) |
| Host visible to Docker | **16 vCPU, 15.2 GiB RAM** |
| Reference target in brief | 8 vCPU / 24 GB RAM |
| Deviation from reference | **More cores, less RAM.** Memory (15.2 GiB vs 24 GB) is the relevant deviation — capacity numbers here do not transfer 1:1 to a 24 GB host, and RAM (not CPU) is the headroom limiter for adding JVM replicas. |
| Load generator | k6 v0.54.0, **co-located on the same host** (no network isolation between client and gateway — latencies are lower-bound; a real WAN client adds RTT). |
| Containers running | 9× Postgres (db-per-service), Kafka, Redis, MinIO, ClamAV, Prometheus, Grafana, 10 app services. |
| Observability subset NOT running | Tempo, Loki, Promtail, Alertmanager, node-exporter, kafka-exporter (Docker Desktop only restored a subset). Consequence: traces dropped (`UnknownHostException: tempo`) and **Kafka consumer-group lag exporter unavailable**. |
| Idle memory footprint | ~7.7 GiB used at idle (ClamAV alone ~1 GiB; 10 JVMs ~5.5 GiB; Kafka ~0.6 GiB). |
| Memory under load | ~10 GiB total, stable, no pressure. |

All services reported `{"status":"UP"}` (liveness+readiness) before testing; all 9 Postgres, Kafka, Redis, MinIO, ClamAV healthy.

---

## 2. Test stages run

Two suites were run against `GET`-heavy authenticated flows (profile, gamification, notifications, nearby parking search, geocoding, moderation reads, personal analytics). Write probes (upload/spot-create) and admin analytics stayed **disabled** (they create durable data).

**Suite A — single user (stock `benchmarks/k6/http-load.js`):** smoke 5 VU, light 10 VU, beta 25 VU, stress 50 VU, stress100 100 VU. One seeded user shared by all VUs.

**Suite B — multi-user (`benchmarks/k6/http-load-multiuser.js`, added here):** 60 distinct seeded users, one per VU. mu20/mu40/mu60 at 1 s think time; mu60push at 0.3 s think time.

Harness corrections applied before measuring (see §6).

---

## 3. Raw summary tables

### 3a. Suite A — single user (k6 client-side)

| stage | VUs | iters | reqs | req/s | err% (http) | biz err% | p50 ms | p90 ms | p95 ms | p99 ms | max ms |
|---|---|---|---|---|---|---|---|---|---|---|---|
| smoke | 5 | 428 | 3,487 | 38.5 | 3.50 | 1.78 | 4.1 | 6.4 | 7.4 | 55.0 | 62.6 |
| light | 10 | 1,211 | 9,798 | 81.6 | 8.23 | 6.62 | 2.6 | 4.6 | 5.4 | 54.8 | 75.1 |
| beta | 25 | 162,513 | 977,123 | 8,104 | 98.54 | 98.53 | 1.1 | 1.8 | 2.4 | 5.3 | 130 |
| stress | 50 | 291,305 | 1,749,203 | 11,624 | 98.93 | 98.93 | 2.5 | 4.7 | 5.5 | 8.2 | 89.2 |
| stress100 | 100 | 305,767 | 1,834,789 | 12,231 | 98.97 | 98.97 | 5.7 | 11.6 | 13.7 | 18.5 | 98.1 |

> **The beta/stress/stress100 rows are NOT real throughput.** They are a 429-rejection storm: all VUs share one user → one rate-limit bucket → ~99% of requests get HTTP 429 with empty bodies; the harness's `nearby.json()` then throws and the iteration aborts *before* its `sleep(1)`, so each VU spins in a tight loop. Gateway counters for the whole run: **200 = 64,598 vs 429 = 4,508,637**, server-side 5xx ≈ 0. Use Suite B for real capacity.

### 3b. Suite B — multi-user, distinct user per VU (real backend load)

| stage | VUs | think s | iters | reqs | req/s | fail% | 429% | p50 ms | p90 ms | p95 ms | p99 ms | max ms |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| mu20 | 20 | 1.0 | 2,071 | 14,517 | 121 | 0.00 | 0.00 | 2.2 | 4.2 | 5.3 | 12.0 | 55.8 |
| mu40 | 40 | 1.0 | 4,127 | 28,929 | 240 | 0.00 | 0.00 | 2.7 | 5.5 | 6.6 | 13.9 | 65.6 |
| mu60 | 60 | 1.0 | 6,183 | 43,341 | 360 | 0.00 | 0.00 | 2.6 | 5.2 | 6.2 | 13.6 | 66.1 |
| **mu60push** | **60** | **0.3** | **25,240** | **176,740** | **1,182** | **0.00** | **0.00** | **2.4** | **4.8** | **5.8** | **8.9** | **58.8** |

Per-endpoint p95 (ms), mu60push: profile 5.1 · gamification 4.7 · notifications 4.6 · nearby 8.8 · geocoding 4.6 · moderation 4.5 · analytics 4.4.

### 3c. Backend resource utilization at peak (mu60push, 1,182 req/s)

| service | proc CPU% | docker CPU% peak (cores) | heap MiB | server req/s | Hikari pending | Hikari timeout | GC max ms | Redis max ms | 5xx/s |
|---|---|---|---|---|---|---|---|---|---|
| gateway | 6.0 | 119 (~1.2) | 315 | 1,298 | – | – | 7 | 10.4 | 0 |
| parking | 0.9 | 36 (~0.36) | 166 | 357 | 0 | 0 | 14 | 2.8 | 0 |
| user | 0.6 | 20 | 73 | 187 | 0 | 0 | 5 | 0 | 0 |
| moderation | 0.6 | 12 | 111 | 186 | 0 | 0 | 4 | 0 | 0 |
| gamification | 0.4 | 17 | 60 | 179 | 0 | 0 | 4 | 0 | 0 |
| notification | 0.5 | 13 | 92 | 179 | 0 | 0 | 4 | 0 | 0 |
| analytics | 0.5 | 17 | 119 | 179 | 0 | 0 | 6 | 0 | 0 |
| auth | 0.1 | 18 | 85 | 2.3 | 0 | 0 | 5 | 0.4 | 0 |
| media | 0.0 | 0.5 | 126 | 0.1 | 0 | 0 | 0 | 0 | 0 |

Total backend CPU at 1,182 req/s ≈ **2–3 of 16 cores**. DB pools (max 10 each) never queued (**pending = 0, timeout = 0** at every stage). No 5xx anywhere.

---

## 4. Prometheus baseline comparison

Snapshots saved: `prometheus-before/` (idle) and `prometheus-after/` (post-load), plus per-stage instant captures under each stage dir.

| Signal | Idle (before) | Under load (mu60push) | Reading |
| --- | --- | --- | --- |
| Gateway heap | ~45 MiB eden | 315–696 MiB across runs, GC keeps pace | Healthy; G1 reclaims, no growth trend |
| GC pause max | ~0–8 ms | ≤ 14 ms (parking) | No GC pressure |
| Hikari active/pending/timeout | 0 / 0 / 0 | 0 / **0** / **0** | No connection-pool contention |
| Redis (lettuce) cmd max | <1 ms | ~10 ms (gateway rate-limiter Redis ops) | Redis is the rate-limiter datastore; latency low |
| Outbox unpublished / oldest age | 0 / 0 | 0 / 0 | No outbox backlog (read-only load) |
| Kafka consumer lag | n/a | n/a | Exporter not running; read load produces ~no events anyway |
| HTTP 5xx rate | 0 | 0 | No server errors under load |

---

## 5. Bottleneck analysis (evidence-based)

1. **Binding constraint under concurrency = gateway per-key rate limiter (by design, not a defect).**
   Spring Cloud Gateway `RequestRateLimiter` (Redis token bucket) keyed by **authenticated userId, else client IP** (`RateLimitConfig`). Tiers: auth 5/10, default 30/60, parking 10/20, geocoding 5/10, media 2/5 (replenish/burst per second). Evidence: at ≥25 VUs sharing one user, gateway returned **4.5 M × 429** vs 64.6 k × 200; raising distinct users to 60 eliminated 429s entirely (0.00% in all Suite B stages).

2. **Backend read path is cheap and far from saturation.**
   1,182 req/s mixed authenticated reads cost ~2–3 cores total, p95 ≈ 6 ms, with zero DB-pool queuing, zero 5xx, GC ≤ 14 ms. Suite B was bounded by **test design** (60 users × 0.3 s think), not by any system resource. The true knee was not found.

3. **No secondary bottleneck surfaced:** Hikari pending/timeout = 0; Redis < 11 ms; outbox empty; no GC stalls; memory stable at ~10/15.2 GiB.

4. **Non-performance findings (operational):**
   - OTel traces are being dropped — app services can't resolve host `tempo` (Tempo container not running). ~1 error/14 s/service, load-independent.
   - Kafka consumer-lag exporter and Loki/Promtail/Alertmanager were not running this session, so those signals are blind here.

---

## 6. Optimizations applied

**No backend performance optimization was applied — no bottleneck was proven** (constraint is an intentional rate limit; backend is idle behind it). Per the brief, optimization was withheld for lack of evidence.

Two **measurement-harness corrections** were made (test artifacts only; no API/business-logic change):

| Change | Why |
| --- | --- |
| `http-load.js` nearby query `latitude/longitude/radiusMeters` → **`lat/lng/radius`** | API expects `lat/lng/radius` (`ParkingController#searchNearby`). The stock harness 400'd every nearby call, inflating error rate and never exercising parking search. |
| Added `http-load-multiuser.js` | The stock single-user harness can only ever measure one rate-limit bucket. A per-VU distinct-user probe was required to measure real backend capacity. |

---

## 7. Before / after results

There is no perf-fix before/after (no fix applied). The relevant before/after is the **harness correction**:

| | Before harness fix (single user, light 10 VU) | After (multi-user, mu60 60 VU) |
| --- | --- | --- |
| Successful req/s | ~75 (then 429-wall above ~10 VU) | 360 clean, scaling linearly to 1,182 at push |
| Error rate | 8 % (429 + nearby 400) | 0.00 % |
| nearby endpoint | 400 every call | 200, p95 ≈ 9 ms |

---

## 8. Capacity estimate for hosted beta

Scope: **read-path only**, on this 16-vCPU / 15.2 GiB host, client co-located.

- **Measured, proven sustainable:** ≥ **1,182 req/s** of mixed authenticated reads at p95 ≈ 6 ms, p99 ≈ 9 ms, 0 errors, ~2–3 cores. This is a **floor**, not the ceiling.
- **Translating to users:** a real active user generates far less than the 0.3 s-think synthetic VU. At a realistic ~1 request / 3 s per active user, 1,182 req/s ≈ **~3,500 concurrent active users** before backend CPU matters — and CPU headroom (13+ idle cores) suggests the real backend ceiling is several × higher. **RAM (15.2 GiB), not CPU, is the first limit** to adding JVM replicas on this host.
- **Per-user throughput is capped by the rate limiter,** not the backend: default reads 30 req/s (burst 60), auth 5/s, geocoding 5/s per user. A single client/user cannot exceed these regardless of backend capacity.

This is **not** a production guarantee: write path untested, single host, no WAN latency, 15.2 GiB (not 24 GB) RAM.

---

## 9. Recommended safe traffic limit

For a hosted beta on hardware comparable to or better than this host, with the current rate-limit config:

- **Plan for up to ~1,000 authenticated read req/s** as a conservative, fully-proven steady-state target (≈ 85 % of the measured 1,182 req/s floor), expecting p95 < 15 ms server-side.
- **Keep the existing per-user rate-limit tiers** (auth 5/10, default 30/60, parking 10/20, geocoding 5/10, media 2/5). They are the effective abuse/runaway guard and were validated as functioning under load.
- **Gate the real launch ceiling on a write-path test** (below) before advertising any higher number.
- Provision **≥ 16 GiB RAM** headroom; CPU is not the near-term constraint for reads.

---

## 10. Remaining unknowns

1. **Write path — the biggest gap.** Upload → ClamAV scan → MinIO store → spot create → **outbox → Kafka → consumers** was not exercised (durable-data probes disabled). This is the heavy path (I/O, AV scan, transactional outbox, Kafka fan-out) and is where real bottlenecks are most likely. Must be tested before production claims.
2. **True backend saturation point** for reads — not reached. Need more distinct users / lower think time (or distributed load) to find the knee.
3. **Kafka consumer lag & end-to-end event latency** — exporter not running; unmeasured.
4. **24 GB reference host** — untested; results are from 15.2 GiB. RAM behavior at higher replica counts unknown.
5. **WAN realism** — client was co-located; add real network RTT for user-facing latency.
6. **Spot-details endpoint** — nearby returned 0 spots near the test coordinates, so `GET /parking/spots/{id}` was never hit.
7. **Sustained soak / memory-leak behavior** — longest stage was 120 s; no multi-hour soak.
8. **Operational:** restore Tempo/Loki/Promtail/Alertmanager/kafka-exporter so traces, logs, and consumer lag are observable during future runs.

---

### Reproduction

```bash
# stack already built; bring up: docker compose -f docker/docker-compose.yml -f docker/docker-compose.apps.yml up -d
# seed users: 1 primary + loadtest+1..60@real-e2e.parkio.local (StrongParkio123)
# single-user suite:
PARKIO_K6_EMAIL=user@real-e2e.parkio.local PARKIO_K6_PASSWORD=StrongParkio123 \
  PARKIO_K6_VUS=10 k6 run benchmarks/k6/http-load.js
# multi-user suite (real capacity):
VUS=60 THINK=0.3 DUR=120s k6 run benchmarks/k6/http-load-multiuser.js
```

Artifacts: `benchmarks/reports/p221/{smoke,light,beta,stress,stress100}/`, `.../multiuser/{mu20,mu40,mu60,mu60push}/` (k6 `summary.json`, `k6.log`, `dockerstats.csv`, `prom-*.json`), and `prometheus-before/`, `prometheus-after/`.
