# Technical Overview

## Technical Thesis

Parkio's technical work matters because the product depends on trust. A parking spot is only useful if it is recent, location-aware, photo-backed, safe to display, visible only when appropriate, and connected to verification, reporting, moderation, notifications, and contribution history.

The architecture is designed to support that workflow without treating the current release as public production.

## Architecture Summary

Parkio is a Java 21 / Spring Boot 3.5.x microservice platform with React web and Expo mobile clients. Each backend capability is independently runnable with its own bounded context, database, build, Dockerfile, and tests. Shared code is limited to service-agnostic infrastructure helpers; domain models and service DTOs remain service-owned.

## Backend Stack

- Java 21.
- Spring Boot 3.5.x.
- Spring Cloud Gateway.
- Gradle Kotlin DSL.
- PostgreSQL/PostGIS with Flyway.
- Kafka with outbox/inbox and DLT patterns.
- Redis for rate limiting, idempotency, and related runtime support.
- MinIO/S3-compatible object storage.
- ClamAV media scanning.

Services:

- gateway-service.
- auth-service.
- user-service.
- parking-service.
- media-service.
- gamification-service.
- notification-service.
- moderation-service.
- ai-validation-service.
- analytics-service.

## Product Value of the Architecture

- PostGIS supports geospatial parking discovery.
- Media scanning and image normalization reduce risk before spot photos are served.
- Signed media URLs keep storage private while allowing authorized spot viewing.
- Event-driven workflows connect parking actions to trust, notifications, moderation, and analytics.
- Service boundaries let auth, parking, media, moderation, and gamification evolve without sharing domain models.
- Observability and runbooks make the hosted beta operable instead of demo-only.

## Frontend Web

- React/Vite SPA.
- PWA shell.
- RBAC route guards.
- Real-stack smoke coverage for core web flows.
- Hosted web security headers and CSP tests.

## Mobile

- Expo React Native app.
- Shared API client, DTOs, and validation packages.
- SecureStore-backed native token storage.
- Modules for map, upload, Smart Return, notifications, profile, and spot detail.
- Mobile device runtime smoke remains an operator caveat before widening beta.

## Infrastructure Path

Hosted-beta target:

- Single VPS.
- Docker Compose overlays.
- Caddy TLS edge.
- Public ports 80/443 and SSH only.
- Data and observability ports private.
- Prometheus, Grafana, Loki, Tempo, Alertmanager.

Public-production target:

- Managed compute or container platform.
- Managed PostgreSQL/PostGIS with PITR.
- Managed Kafka or Kafka-compatible service.
- Managed object storage.
- Secrets manager.
- Production alerting and on-call.

## Security and Privacy Posture

- RS256 JWT access tokens with JWKS.
- Opaque refresh tokens stored as hashes and rotated.
- HttpOnly refresh cookies for web.
- SecureStore token persistence for mobile.
- Gateway live account-status and session-epoch checks.
- Gateway-only public ingress.
- CORS allow-list.
- Rate limiting.
- Media scanning before storage.
- Signed media URLs.
- Separation of moderator and admin capabilities.
- CI security scanning with gitleaks and Trivy.

## Certification Status

- Backend: hosted-beta application layer GO, 87/100.
- Frontend: hosted-beta GO with mobile operator caveat, 86/100.
- Overall project: hosted-beta RC only, 84/100.
- Live hosted beta: not yet deployed.
- Public production: NO GO.

## Why Cloud Credits Help

Cloud credits would convert a technically mature release candidate into an evidence-producing beta environment. The most valuable uses are managed data services, storage, secrets, monitoring, staging, email/push validation, and capacity/security testing. This lets Parkio measure product risk without also carrying avoidable infrastructure risk.
