# Parkio Architecture

Parkio is a gateway-fronted microservices system for community-powered parking
intelligence. This page is the entry point for engineers evaluating how the
system fits together.

## System Shape

```text
Web app / Mobile app
        |
        v
Spring Cloud Gateway
        |
        +-- auth-service
        +-- user-service
        +-- parking-service
        +-- media-service
        +-- gamification-service
        +-- notification-service
        +-- moderation-service
        +-- ai-validation-service
        +-- analytics-service
        |
        +-- PostgreSQL / PostGIS per service
        +-- Kafka events and DLTs
        +-- Redis caches and rate limits
        +-- MinIO-compatible media storage
        +-- Prometheus, Grafana, Loki, Alertmanager
```

## Gateway

Spring Cloud Gateway is the only public backend ingress. It handles routing,
JWT validation through JWKS, public-route allow-listing, CORS, rate limiting,
and edge authorization before forwarding requests to internal services.

The gateway also owns the narrow public waitlist intake path because it is an
edge-facing beta collection workflow, not a parking-domain service.

## Services

Each service owns its own application logic and persistence. Service boundaries
are documented in [Service Boundaries](../ai-context/03-service-boundaries.md).

| Service | Responsibility |
|---------|----------------|
| `auth-service` | Login, refresh tokens, JWKS, sessions, auth lifecycle |
| `user-service` | User profile and account-facing user data |
| `parking-service` | Spot creation, discovery, verification, claim/report flows |
| `media-service` | Presigned uploads, scanning, storage metadata, signed reads |
| `gamification-service` | Score, reputation, and incentive foundations |
| `notification-service` | Notification inbox, delivery attempts, push integration |
| `moderation-service` | Moderation queues and review workflows |
| `ai-validation-service` | AI-assisted validation pipeline boundaries |
| `analytics-service` | Admin analytics surfaces and aggregated signals |

## Database

Services use PostgreSQL with Flyway migrations. Spatial parking queries use
PostGIS where needed. Services must not read or write another service's database.

Reference: [Database Guidelines](../ai-context/05-database-guidelines.md).

## Messaging

Asynchronous service coordination uses Kafka events, transactional outbox/inbox
patterns, and dead-letter topics. Event shape and transport guidance live in:

- [Event Guidelines](../ai-context/06-event-guidelines.md)
- [Event Contracts](event-contracts.md)
- [Kafka Transport](kafka-transport.md)

## Storage

Media storage uses MinIO/S3-compatible object storage. Uploads go through
presigned URLs, malware scanning, metadata persistence, and signed access URLs.
Parking publication depends on media readiness rather than raw client claims.

## Security

Security is layered:

- Gateway validation and route authorization.
- Service-level authorization checks for defense in depth.
- Hashed refresh tokens and session invalidation.
- Web HttpOnly refresh cookies and mobile secure token storage.
- Public waitlist metadata minimized through hashing and rate limits.
- Supply-chain scanning and secret preflight checks.

References:

- [Security Guidelines](../ai-context/07-security-guidelines.md)
- [Security Boundaries](../operations/security-boundaries.md)
- [Supply Chain Security](../operations/supply-chain-security.md)

## Observability

The repository includes Prometheus metrics, Grafana dashboards, Loki logs,
Alertmanager configuration, runtime validation scripts, and performance smoke
assets. See:

- [Observability Metrics](observability-metrics.md)
- [Runtime Validation](../operations/runtime-validation.md)
- [Performance Capacity](../operations/performance-capacity.md)

## Deployment

Local and hosted-beta environments are represented with Docker Compose overlays.
Hosted beta assets include Caddy/TLS routing, preflight checks, deploy/rollback
scripts, backup/restore runbooks, and VPS sizing guidance.

Start with:

- [Hosted Beta Runbook](../../HOSTED-BETA-RUNBOOK.md)
- [docker/README.md](../../docker/README.md)
- [Production Readiness](production-readiness.md)
- [ADR-PP-01A Managed PostgreSQL provider and topology](adr/ADR-PP-01A-managed-postgresql.md) (**ACCEPTED WITH CONDITIONS** — PP-01 implementation remains open)
- [PP-01B Spike Registry](pp-01b-spike-registry.md)
- [PP-01B-SPIKE-01 Azure technical validation](pp-01b-spike-01.md) (**CLOSED — ACCEPT WITH NON-BLOCKING NOTES**)
- [PP-01B-SPIKE-02 PostGIS spatial parity](pp-01b-spike-02-postgis-spatial-parity.md) (**MODE A COMPLETE — PASS WITH NON-BLOCKING NOTES**; Mode B pending)


## Parking Validation / Decision Architecture (WP-05)

Baseline for replacing AI/moderator-as-final-authority with a Decision Engine
module inside parking-service:

- [WP-05 Current-State Audit](wp-05-parking-validation-current-state.md)
- [ADR-WP05 Decision Engine Placement](adr/ADR-WP05-decision-engine-placement.md)
- [WP-05 Implementation Plan](wp-05-implementation-plan.md)
- [WP-05.2 Decision Domain Model](wp-05-decision-domain-model.md)
- [WP-05.3 Evidence Collection & Normalization](wp-05-evidence-collection-normalization.md)
- [WP-05.4 Evidence Evaluation Model](wp-05-evidence-evaluation-model.md)
- [WP-05.5 Decision Engine Shadow Mode](wp-05-decision-engine-shadow-mode.md)
- [WP-05.6 Decision Calibration & Shadow Analytics](wp-05-decision-calibration-shadow-analytics.md)
- [WP-05.6 Calibration Report Template](wp-05-decision-calibration-report-template.md)
- [WP-05.7 Decision Audit Store](wp-05-decision-audit-store.md)
- [WP-05.8 Controlled Authority Migration](wp-05-controlled-authority-migration.md)
- [WP-05.9 Availability Engine](wp-05-availability-engine.md)
- [WP-05.10 Outcome Validation Engine](wp-05-outcome-validation.md)
- [WP-05.11 Trust Engine Shadow](wp-05-trust-engine-shadow.md)
- [WP-05.11A Trust Verification Closure](wp-05-trust-verification-closure.md)
- [WP-05.12 Pending Reward Engine Shadow](wp-05-pending-reward-engine-shadow.md)
- [WP-05.13 Adaptive Exposure Engine Shadow](wp-05-adaptive-exposure-engine-shadow.md)
- [WP-05.14 Fraud Intelligence Engine Shadow](wp-05-fraud-intelligence-engine-shadow.md)
- [WP-05.15 Continuous Calibration & Policy Governance](wp-05-continuous-calibration-policy-governance.md)
- Related: [AI Vision Validation](ai-vision-validation.md), [Event Contracts](event-contracts.md), [Parking Spot Rejection Reasons](parking-spot-rejection-reasons.md)

## WP-06 — Operational Platform

- [WP-06.1 Operational Readiness & Production Governance](../operations/wp-06-01-operational-readiness-production-governance.md)
- [Operations Index](../operations/README.md)

WP-05.1–05.15 complete. WP-06.1 establishes runbooks, SLO definitions, and governance
templates only — it does **not** claim production launch.

## DATA-WP-01 — Municipal Parking Sources

Canonical municipal source architecture inside parking-service (İzmir / İZUM first):

- [Municipal Parking Source Foundation](wp-data-01-municipal-parking-source-foundation.md)
- [İZELMAN Discovery Report](wp-data-01-izelman-discovery.md)
- [Municipal Source Runbook](../operations/municipal-parking-source-runbook.md)

## Current Limitations

Parkio is not public-production ready. Known blockers include managed HA data
services (PP-01 architecture decided in [ADR-PP-01A](adr/ADR-PP-01A-managed-postgresql.md);
implementation still open), secrets manager and rotation workflow, stronger CD rollback,
production on-call process, and production-scale validation.

See [Known Issues](../releases/KNOWN-ISSUES.md) for the current blocker list.

- [DATA-WP-02 OSM İzmir import](wp-data-02-osm-izmir-facility-import.md)
- [ADR: OSM import tooling](adr/ADR-WP-DATA-02-osm-import-tooling.md)
- [DATA-WP-03 İZELMAN inventory, roadside and tariffs](wp-data-03-izelman-inventory-tariffs.md)
- [DATA-WP-04 canonical registry provenance and link review](wp-data-04-canonical-registry-provenance-link-review.md)
- [Municipal registry review runbook](../operations/municipal-registry-review-runbook.md)
- [DATA-WP-05 bounded registry candidate generation](wp-data-05-engineering-specification.md)
- [DATA-WP-05 acceptance traceability](wp-data-05-acceptance-traceability.md)
  - Specification: complete.
  - Implementation: complete (`deb557d` + closure `b3f1cec`).
  - Hosted-beta DATA-WP-05A: complete (dark deploy + dry-run; 0 eligible candidates).
  - Decision Intelligence WP-05 remains a separate work package.
- [DATA-WP-06 municipal source health, SLA and recovery](wp-data-06-engineering-specification.md)
  - Specification: complete (`090242f`).
  - Implementation: complete (`f4faf279`).
  - DATA-WP-06A hosted-beta gate: complete (ACCEPT WITH NON-BLOCKING NOTES).
  - Decision Intelligence WP-05 remains a separate work package.
- [DATA-WP-07 public facility discovery duplicate-presentation safety](wp-data-07-engineering-specification.md)
  - Specification: hardened safety contract complete.
  - Implementation: query-time nearby duplicate-presentation policy (DATA-WP-07).
  - DATA-WP-07A hosted-beta gate: complete (ACCEPT WITH NON-BLOCKING NOTES).
  - Repository **WP-07** (mobile/session foundation) remains a separate work package.
- [DATA-WP-09 public facility provenance publication](wp-data-09-engineering-specification.md)
  - Specification + implementation: bounded nearby/detail provenance.
  - DATA-WP-09A hosted-beta gate: complete (ACCEPT WITH NON-BLOCKING NOTES).
- [DATA-WP-10 municipal field provenance selection on ingest](wp-data-10-engineering-specification.md)
  - Specification + implementation: İZUM/OSM ingest writes allow-listed provenance.
  - Backfill omitted (no safe field ownership without guessing).
  - DATA-WP-10A hosted-beta gate: complete (ACCEPT).
- [DATA-WP-11 enable public municipal provenance publication](wp-data-11-engineering-specification.md)
  - Canonical default **true**; production profile pins **false**; Azure hosted-beta Compose `:-true`.
  - No semantic/DTO/migration changes; kill-switch restores null fields.
  - DATA-WP-11A hosted-beta leave-on gate: complete (ACCEPT WITH NON-BLOCKING NOTES).
- [DATA-WP-12 enable nearby duplicate-presentation by default](wp-data-12-engineering-specification.md)
  - Canonical default **true**; production profile pins **false**; Azure hosted-beta Compose `:-true`.
  - Matching thresholds unchanged; detail endpoint unaffected; kill-switch restores legacy nearby.
  - DATA-WP-12A hosted-beta leave-on gate: complete (ACCEPT WITH NON-BLOCKING NOTES).
- [DATA-WP-13 OSM facility display-label hygiene](wp-data-13-engineering-specification.md)
  - Policy `osm-label-v1` (kill-switch `legacy`); NAME provenance only for real name tags.
  - No migration / DTO change; reimport refreshes labels; DATA-WP-13A hosted-beta gate: complete.
- [DATA-WP-14 field provenance reconciliation](wp-data-14-engineering-specification.md)
  - Same-source stale selection withdrawal on successful İZUM/OSM ingest (hard delete).
  - No migration / DTO / label-precedence change; DATA-WP-14A hosted-beta gate: complete.
- [DATA-WP-15 municipal source quality and coverage report API](wp-data-15-engineering-specification.md)
- [DATA-WP-16 source-mode-aware municipal SLA for operator imports](wp-data-16-engineering-specification.md)
- [DATA-WP-17 source-level availability semantics alignment](wp-data-17-engineering-specification.md)
  - Read-only ADMIN/SUPER_ADMIN aggregates; kill-switch default **false**; reuses WP-06 health + WP-13 label outcomes.
  - No score, linking, İZELMAN pub, migration, or frontend; DATA-WP-15A leave-on gate: not started.
- [DATA-WP-18 municipal district coverage quality report](wp-data-18-engineering-specification.md)
  - Additive `districtCoverage` on WP-15 overall report; independent kill-switch default **false**; reuses WP-08 ilceler asset.
  - Hosted-beta leave-on (DATA-WP-18A) accepted with non-blocking notes (legacy PIP false-positive overlaps).
- [DATA-WP-19 İzmir district geometry topology reconciliation](wp-data-19-engineering-specification.md)
  - Root cause: even-odd ray casting false positives; JTS + normalized MakeValid asset; topology policy default off until DATA-WP-19A.
  - No migration / no deployment in this package; province clip unchanged.
- [DATA-WP-08 İzmir administrative-boundary clip](wp-data-08-engineering-specification.md)
  - Clip `izmir-admin-izbb-2024-10-18-v1` from İZBB `ilceler.geojson` (CC BY 4.0).
  - Operator-managed boundary + polygon osmium extract; rollback to `izmir-bbox-v1`.
  - Status: **COMPLETE** (asset accepted); DATA-WP-08A hosted-beta reimport: **not started**.

> **Name collision:** Repository **WP-05** is Decision Intelligence. Repository
> **WP-06** is Operational Platform (`docs/operations/wp-06-*`). Repository
> **WP-07** is Mobile Application Foundation & Sprint 01 closure
> (`docs/architecture/wp-07-*`, `frontend/architecture/sprint-3/WP-07-MOBILE.md`).
> Municipal data packages use **DATA-WP-NN** / `wp-data-NN-*` and must not reopen
> `wp-05-*`, ops `wp-06-*`, or mobile/session `wp-07-*`.
