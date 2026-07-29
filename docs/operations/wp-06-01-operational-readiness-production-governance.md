# WP-06.1 — Operational Readiness & Production Governance

Repository-backed operational foundation. **Does not claim production launch.**

## Lifecycle

Repository state → Build evidence → Release candidate → PRR → Controlled deploy →
Runtime health → SLO monitoring → Incident/rollout → Rollback → Post-incident evidence

## Service Inventory

| Service | Port | DB | Redis | Kafka | MinIO |
|---------|------|----|-------|-------|-------|
| gateway-service | 8080 | - | yes | - | - |
| auth-service | 8081 | postgres-auth | yes | yes | - |
| user-service | 8082 | postgres-user | - | yes | - |
| parking-service | 8083 | postgres-parking PostGIS | cache | yes | - |
| media-service | 8084 | postgres-media | - | yes | yes |
| gamification-service | 8085 | postgres-gamification | - | yes | - |
| notification-service | 8086 | postgres-notification | - | yes | - |
| moderation-service | 8087 | postgres-moderation | - | yes | - |
| ai-validation-service | 8088 | postgres-ai-validation | - | yes | - |
| analytics-service | 8089 | postgres-analytics | - | yes | - |

Source: settings.gradle.kts, docker/docker-compose.yml, docker-compose.apps.yml

## Health Semantics

| Probe | Question | Implementation |
|-------|----------|----------------|
| Liveness | Restart process? | /actuator/health/liveness — no Grafana/Prometheus dep |
| Readiness | Receive traffic? | /actuator/health/readiness — DB required where configured |
| Startup | Init complete? | Spring Boot probes enabled all services |
| Deep health | Dependency detail? | /actuator/health — not used as Docker liveness |

Docker apps: readiness only (docker-compose.apps.yml x-app-healthcheck).
Geocoding circuit breaker: register-health-indicator false (parking application.yml).

## Operational Readiness Matrix (summary)

| Dimension | Status |
|-----------|--------|
| Build reproducibility | VERIFIED (Gradle CI) |
| Unit tests | VERIFIED |
| Integration tests | PARTIALLY_VERIFIED (nightly + path PR) |
| Migration safety | PARTIALLY_VERIFIED (Testcontainers V1-V26 parking) |
| Readiness/liveness | VERIFIED (Actuator + compose) |
| Backup scripts | VERIFIED (backup-runbook + CI drill) |
| Restore production | UNVERIFIED (CI drill only) |
| Gateway downstream timeouts | DOCUMENTED_ONLY (no global route timeout) |
| SLO approval | BASELINING |
| On-call / paging ownership | PRODUCT/INFRA INPUT REQUIRED |
| Production HA | PRODUCTION_BLOCKER (single-node compose) |

Full matrix: linked documents below.

## WP-05 Guardrails (unchanged)

Decision authority off; reward pending-only; exposure shadow-only; fraud non-enforcing;
calibration advisory. See kill-switch-catalogue.md.

## Data Authority

| Authoritative | Derived |
|---------------|---------|
| Service PostgreSQL DBs | Redis cache |
| WP-05 append-only ledgers (parking) | Prometheus/Grafana config |
| MinIO objects | Kafka retained events |

## Production Blockers

See docs/architecture/production-readiness.md and docs/releases/KNOWN-ISSUES.md.

## Document Index

- service-criticality.md
- critical-user-journeys.md
- slo-sli-catalogue.md
- error-budget-policy.md
- kill-switch-catalogue.md
- rollback-runbook.md
- incident-management.md + runbooks/
- backup-restore.md, disaster-recovery.md
- database-migration-policy.md
- release-readiness-checklist.md
- production-readiness-review-template.md
- progressive-delivery.md
- capacity-and-load-plan.md
- security-operational-readiness.md

## Roadmap

WP-05.1–05.15 Complete. WP-06.1 current. Future WP-06 packages not started.