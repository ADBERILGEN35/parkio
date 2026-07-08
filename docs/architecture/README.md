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

## Current Limitations

Parkio is not public-production ready. Known blockers include managed HA data
services, secrets manager and rotation workflow, stronger CD rollback,
production on-call process, and production-scale validation.

See [Known Issues](../releases/KNOWN-ISSUES.md) for the current blocker list.
