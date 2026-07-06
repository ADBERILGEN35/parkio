# Parkio v1.0.0-rc1 — Release Notes

**Release candidate:** `v1.0.0-rc1`  
**Date:** 2026-07-06  
**Track:** Hosted beta (closed / operator-run)  
**Commit baseline:** `4cb7ed187d898e62934c3619c6862e7f5b51180a` (tag `v1.0.0-rc1`)

---

## Summary

Parkio **1.0.0-rc1** is the first release candidate after the full backend and frontend certification program. The **application layer is certified for hosted beta**. **Public production remains NO GO** until infrastructure and operations blockers are closed.

This tag is intended for:

- Operator-run VPS / hosted-beta deployments
- Integration and staging verification
- Mobile dev-client / APK beta distribution

It is **not** intended for unattended public production.

---

## Major features

| Area | Highlights |
|------|------------|
| **Parking** | Spot lifecycle, geocoding, map search, reservations |
| **Media** | Presigned upload, virus scan, publish readiness |
| **Auth** | RS256 + JWKS, refresh rotation, session epoch |
| **Moderation** | Queue, case resolution, content vs account sanctions |
| **Gamification** | Points, trust, badges |
| **Notifications** | Inbox, push (Expo), deep links |
| **Smart Return** | Return-trip parking assistance (web + mobile) |
| **Analytics** | Platform metrics (ADMIN-only) |
| **Web** | React SPA, PWA shell, RBAC guards, real-stack E2E |
| **Mobile** | Expo dev client, SecureStore session, staff routes |

---

## Architecture summary

- **10 Spring Boot microservices** + API gateway (Java 21, Spring Boot 3.5.x)
- **Kafka** event bus with outbox/inbox, DLT, idempotent consumers
- **Postgres** database-per-service (Flyway)
- **MinIO** object storage; **Redis** rate limiting
- **React** web + **Expo** mobile monorepo (`frontend/`)
- **Docker Compose** local and hosted-beta overlays (`docker/`)

See [`docs/architecture/production-readiness.md`](../architecture/production-readiness.md) and [`docs/ai-context/`](../ai-context/).

---

## Security improvements (certification period)

- Fail-closed JWT and gateway internal secret requirements
- Defense-in-depth RBAC (gateway + controller + service)
- HttpOnly refresh cookies (web); no access tokens in `localStorage`
- CSP and security headers on web nginx
- ClamAV media scanning
- gitleaks + Trivy in CI; CycloneDX SBOMs on release workflow
- Preflight script blocks placeholder secrets in production env

---

## Certification summary

### Certification (repository artifacts — RC1.3)

| Document | Status |
|----------|--------|
| [`FINAL-PRODUCTION-CERTIFICATION.md`](../certification/FINAL-PRODUCTION-CERTIFICATION.md) | **Authoritative** project certification |
| [`FINAL-BACKEND-CERTIFICATION.md`](../certification/FINAL-BACKEND-CERTIFICATION.md) | Backend **GO** hosted beta |
| [`FINAL-FRONTEND-CERTIFICATION.md`](../certification/FINAL-FRONTEND-CERTIFICATION.md) | Frontend **GO** (web); mobile operator caveat |
| [`F2-FRONTEND-RUNTIME-CERTIFICATION.md`](../certification/F2-FRONTEND-RUNTIME-CERTIFICATION.md) | Historical web runtime matrix |

**Scores:** Backend 87/100 · Frontend 86/100 · Overall 84/100 — public production **NO GO**.

---

## Breaking changes

None for **1.0.0-rc1** as the first tagged release. Future RC/GA releases will document API migrations in this file.

Internal note: refresh-token and session-epoch behavior is strict; clients must use the supported api-client refresh flows.

---

## Migration notes

### From local dev to hosted beta

1. Copy `docker/.env.hosted-beta.example` → `.env` on the VPS; replace all `CHANGE_ME` values.
2. Run `scripts/preflight-hosted-beta.sh` — must exit 0.
3. Follow [`docs/beta/deploy-runbook.md`](../beta/deploy-runbook.md).
4. Configure TLS (Caddy), DNS, Resend (email), Expo push token, Alertmanager webhooks.

### From untagged `master` to `v1.0.0-rc1`

- Pin images with `PARKIO_IMAGE_VERSION=v1.0.0-rc1` or build from the tag.
- Re-run database migrations via service startup (Flyway).
- No schema rollback scripts provided — backup before upgrade.

---

## Known issues

See [`KNOWN-ISSUES.md`](KNOWN-ISSUES.md).

---

## Operator actions (before deploy)

- [ ] Complete [`RC1-CHECKLIST.md`](RC1-CHECKLIST.md)
- [ ] Run preflight on production `.env`
- [ ] Verify backups and restore drill
- [ ] Configure Alertmanager → Slack or generic webhook
- [ ] Set `PARKIO_CORS_ALLOWED_ORIGINS` to your SPA origin
- [ ] Mobile: build dev client / APK per [`docs/beta/mobile-release.md`](../beta/mobile-release.md)

---

## Beta limitations

- Single-VPS Compose topology (no HA)
- Kafka replication factor 1 in default beta overlay
- No managed PITR / cross-region DR
- Mobile device runtime not re-certified on FFINAL sprint
- `infra/` IaC is placeholder only

---

## Production limitations

Public production is **NO GO** until:

- Managed Postgres with PITR and tested restore
- Kafka RF≥3 or managed streaming
- Secrets manager + rotation
- CD with rollback and manual approval
- Alerting + on-call
- Load and security testing at scale

Full list: [`docs/architecture/production-readiness.md`](../architecture/production-readiness.md) § public-production blockers.

---

## Artifacts (on tag push)

The [`release.yml`](../../.github/workflows/release.yml) workflow (when enabled):

- Builds with `-PparkioVersion=<tag>`
- Runs unit + integration tests
- Generates CycloneDX SBOMs
- Builds OCI images (publish gated by `vars.PUBLISH_IMAGES`)
- Creates a **draft** GitHub Release

---

## Links

- [RC1 readiness decision](RC1-READINESS.md)
- [RC1 checklist](RC1-CHECKLIST.md)
- [FINAL Production Certification](../certification/FINAL-PRODUCTION-CERTIFICATION.md)
- [FINAL Backend Certification](../certification/FINAL-BACKEND-CERTIFICATION.md)
- [FINAL Frontend Certification](../certification/FINAL-FRONTEND-CERTIFICATION.md)
- [Support / troubleshooting](../../SUPPORT.md)
- [Security policy](../../SECURITY.md)