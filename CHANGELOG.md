# Changelog

All notable changes to Parkio are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-rc1] - 2026-07-06

First **hosted-beta release candidate**. Application layer certified; public production remains **NO GO** until platform/operations blockers close (see `docs/architecture/production-readiness.md`).

### Added

- **Frontend:** React web SPA (Vite), Expo mobile app, shared packages (`api-client`, `validation`, `types`, `geo`, `ui`).
- **Hosted beta:** Docker Compose overlays, Caddy TLS, preflight/deploy scripts, operator runbooks (`docs/beta/`, `docs/operations/`).
- **Observability:** Prometheus, Grafana dashboards, Loki, Alertmanager wiring, documented metrics catalogue.
- **Supply chain:** CycloneDX SBOMs, Trivy scanning, gitleaks, Dependabot, draft GitHub Release workflow (`.github/workflows/release.yml`).
- **Certification:** Frontend final certification report (`docs/certification/FINAL-FRONTEND-CERTIFICATION.md`).
- **Release engineering:** `CHANGELOG.md`, `SECURITY.md`, `CONTRIBUTING.md`, `SUPPORT.md`, `docs/releases/*` RC1 pack.

### Backend — Authentication & session (R-004)

- RS256 JWT access tokens with JWKS; opaque refresh tokens (hashed, rotated, family revocation).
- Session epoch invalidation; gateway live account-status check (fail-closed).
- Bearer + HttpOnly refresh cookie flows for web; mobile SecureStore token persistence.

### Backend — Notifications (R-002)

- Notification service with push payload builder, inbox APIs, Kafka-driven delivery.
- Expo push integration; deep-link metadata for web/mobile staff routes.

### Backend — Media (R-001)

- Presigned upload flow, ClamAV scanning, MinIO storage adapter, readiness gates for spot publish.

### Backend — Smart Return (R-003)

- Smart Return domain events and client surfaces (web map mode, mobile feature module).

### Backend — Deployment / security / observability (R5, R6)

- Fail-closed secrets (`PARKIO_JWT_PRIVATE_KEY_PEM`, `PARKIO_GATEWAY_INTERNAL_SECRET`).
- Gateway rate limiting, CORS allow-list, RBAC route rules.
- Per-service Flyway migrations, outbox/inbox, DLT topics.

### Frontend certification (F1–FFINAL)

- **F1:** Static audit — route guards, env validation, error boundaries.
- **F2:** Web runtime certification (real-stack auth, RBAC, upload).
- **F3 / F3.1:** Mobile session consistency, account-switch cache invalidation.
- **F3.2 / F3.3:** Mobile and web analytics ADMIN-only RBAC alignment.
- **F3.4:** Upload race investigation — closed non-actionable.
- **FFINAL:** 529 frontend unit tests PASS; 7/7 real-stack web smoke PASS; mobile device smoke operator caveat.

### Security

- Web nginx CSP and security headers (`securityHeaders.test.ts`).
- Preflight blocks `CHANGE_ME` placeholders in production env files.
- Push-protection-safe preflight fixtures (no real webhook URLs).

### Fixed

- Web analytics route guard aligned with gateway ADMIN-only policy.
- MinIO media storage adapter compatibility (Boot 3.5 / MinIO 8.x line).
- Mobile SecureStore web compatibility; Expo Go notification guards.

### Known limitations (RC1)

- No `LICENSE` file — required before public open-source distribution.
- Gradle/npm default dev versions (`0.0.1-SNAPSHOT` / `0.0.0`) overridden at release tag via `-PparkioVersion` and image tags.
- Mobile runtime smoke not re-executed on device for FFINAL.
- Public production: HA data plane, secrets manager, CD rollback, on-call — not complete.

[1.0.0-rc1]: https://github.com/parkio/parkio/releases/tag/v1.0.0-rc1