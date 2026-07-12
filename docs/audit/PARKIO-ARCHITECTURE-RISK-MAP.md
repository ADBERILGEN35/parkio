# Parkio Architecture Risk Map (Sprint A1 audit)

**Date:** 2026-07-12 · **Commit:** `f5efa0dd`

## 1. System inventory

### Applications & services

| Component | Port | Purpose | Data store | Depends on | Auth model | Maturity |
|-----------|------|---------|-----------|------------|------------|----------|
| gateway-service (Spring Cloud Gateway, reactive) | 8080 | Only public ingress: JWT validation (JWKS), RBAC route matrix, rate limiting, CORS, correlation IDs, waitlist intake | Postgres `parkio_gateway` (waitlist), Redis (rate limits) | auth-service (JWKS, session epoch), user-service (account status), all downstream | Validates RS256 JWT; stamps trusted `X-User-*` + `X-Gateway-Auth` | High |
| auth-service | 8081 | Register/login/verify/reset, RS256 token issuance, refresh rotation + family revocation, session epoch, JWKS | Postgres | Redis (lockout/limiters), Kafka (outbox), email sender (Resend/logging) | Public bootstrap endpoints; rest gateway-only | High |
| user-service | 8082 | Profile, account status (used by gateway fail-closed check) | Postgres | Kafka | Gateway headers | High |
| parking-service | 8083 | Spot create/search/verify/claim/expire; geocoding proxy; PostGIS radius search | Postgres + PostGIS | media-service (readiness), Kafka, geocoding upstream | Gateway headers + ownership rules | High |
| media-service | 8084 | Presigned upload, content-type sniffing, image re-encode, ClamAV scan, MinIO storage, signed GET URLs | Postgres + MinIO | ClamAV, Kafka | Gateway headers + owner/status access policy | High |
| gamification-service | 8085 | Points, levels, leaderboard | Postgres | Kafka | Gateway headers | Medium-high |
| notification-service | 8086 | Inbox, device tokens, Expo push | Postgres | Kafka, Expo push API | Gateway headers | Medium-high (fan-out TODO, BE-001) |
| moderation-service | 8087 | Reports, cases, appeals, sanctions (ADMIN-gated account actions) | Postgres | Kafka | Gateway headers + in-service ADMIN checks | High |
| ai-validation-service | 8088 | Advisory media/spot validation findings | Postgres | Kafka | MODERATOR/ADMIN at edge | Medium |
| analytics-service | 8089 | Per-user analytics (self-only) + platform analytics (ADMIN) | Postgres | Kafka | Edge + in-service ownership check | Medium |
| Web SPA (React/Vite) | nginx :80 (Caddy-fronted) | Landing + app; memory-only access token, HttpOnly refresh cookie | — | gateway API | Cookie+bearer hybrid | High |
| Mobile (Expo RN) | — | Same product surface; SecureStore tokens | — | gateway API | Bearer + body refresh | High (device smoke gap) |
| dlt-redrive (tool) | CLI | Kafka DLT redrive | — | Kafka | operator | Medium |

### Infrastructure (compose)

Postgres ×9 (db-per-service, separate credentials) · Kafka (KRaft, single broker, RF configurable, default 1) · Redis · MinIO (private bucket, presigned GET) · ClamAV · Caddy (TLS/ACME, CSP/HSTS edge headers) · Prometheus (42 alert rules) · Grafana (7 provisioned dashboards) · Loki+Promtail · Tempo (OTLP traces, 100% sampling default) · Alertmanager (null receiver until env-wired) · blackbox exporter.

### Trust boundaries

```
Internet ──443──> Caddy ──> gateway :8080 ──X-Gateway-Auth──> services (private network)
                    │            │ strips inbound X-User-*/X-Gateway-Auth
                    │            │ JWT → trusted X-User-Id/Email/Roles
                    └──> web nginx (static SPA)
                    └──> MinIO presigned GET only
```
Downstream services refuse requests without the shared secret (constant-time compare, rotation window via accepted-secrets list). Verified in code for gateway, parking, auth, media (sampled).

## 2. Risk map (likelihood × impact, hosted-beta frame)

| Risk | Likelihood | Impact | Findings | Notes |
|------|-----------|--------|----------|-------|
| Single-host/data-plane loss | Medium | Critical | INFRA-001 | Backups scripted; PITR absent; restore drill weekly in CI but not on live host |
| Alerts routed to null → silent incidents | High (until env set) | High | OPS-001 | One env var + uptime ping fixes it |
| Redis outage → login outage (fail-closed limiters) | Medium | High | SEC-002 | Single Redis container |
| Redis outage → edge rate-limit fail-open | Medium | Medium | SEC-001 | Framework default; needs chaos verification |
| Erasure request unfulfillable | Certain (given real users) | High (compliance) | PRIV-001 | No deletion flow exists |
| Location-log accumulation | Certain | Medium | PRIV-002 | No retention on search/view logs |
| Cross-service plumbing drift | Medium | Medium | ARCH-002 | 9 copies of relay/retention/auth-filter |
| Ops cost of 25+ containers for 1 operator | High | Medium | ARCH-001 | Documented consolidation path exists |
| CI action-tag compromise | Low | High | SUP-001 | SHA-pin is cheap |
| Untested write-path saturation (ClamAV) | Medium | Medium | PERF-001 | Read path proven to 1,182 req/s |
| Landing artifact ships broken waitlist submit | Medium | Medium | WEB-001 | Deterministic red test in working tree |

## 3. Architecture judgments (as implemented, not as intended)

- **Boundaries are real, not cosmetic.** No shared domain models (verified: `settings.gradle.kts` comment matches reality; platform module has 7 transport-only classes). Events carry service-owned payloads; no cross-service DB access; separate credentials per DB. This is **not** a distributed monolith.
- **The count is still too high for the stage.** Ten services is justified by boundary discipline but not by load or team size. The repo itself flags analytics/ai-validation/gamification as consolidation candidates. Recommendation unchanged: do not consolidate mid-beta; decide with beta data.
- **Event reliability design is textbook**: transactional outbox with relay + DLQ columns and recovery metadata, inbox dedup, per-service DLTs, `acks=all` idempotent producers, manual ack + ErrorHandlingDeserializer, DLT redrive tool + runbook. Runtime behavior under broker loss not verified in this audit (Docker blocked).
- **Consistency model**: eventual, via events; parking↔media readiness is a synchronous check at publish time (sensible). No distributed transactions; compensation is event-driven (suspension/restore observed in auth consumer). Idempotency records table exists in parking.
- **Failure propagation is deliberately fail-closed** at the gateway (JWT, user-status, session-epoch, missing secrets refuse startup). The known trade-off: user-service or auth-service down ⇒ entire authenticated API 503s. Acceptable at this scale; cache TTL 30s bounds the blast radius per outage second.
- **Startup/shutdown**: readiness-gated `depends_on` ordering, graceful shutdown + `stop_grace_period`, `ExitOnOutOfMemoryError` with heap pinned to 65% of container limit. Sound.
- **Single points of failure**: the VPS itself, Caddy, gateway, Redis, Kafka, each Postgres, MinIO, ClamAV (upload path only). All documented in runtime-sizing/production-readiness.
- **VPS suitability**: measured idle ~7.7 GiB; recommended 8 vCPU/24 GB host (16 GB minimum with observability off-box). Realistic; matches the benchmark evidence.
- **AWS suitability**: architecture maps cleanly (stateless JVMs + externalized config + S3-compatible media + standard Postgres/Kafka/Redis). Cost, not design, is the constraint — see the AWS section of the main report.
