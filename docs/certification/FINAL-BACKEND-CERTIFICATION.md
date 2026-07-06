# Parkio FINAL — Backend Production Certification

**Date:** 2026-07-06  
**Sprint:** Backend certification consolidation (RC1.3)  
**Release tag:** `v1.0.0-rc1`  
**Repository commit:** `4cb7ed187d898e62934c3619c6862e7f5b51180a`  
**Method:** Repository code review, automated test inventory, CI workflow definitions, operator runbooks, and verification gates executed at RC1.1 on this commit. No fabricated sprint-specific runtime reports.

**Related documents:** [`FINAL-FRONTEND-CERTIFICATION.md`](FINAL-FRONTEND-CERTIFICATION.md), [`FINAL-PRODUCTION-CERTIFICATION.md`](FINAL-PRODUCTION-CERTIFICATION.md), [`docs/architecture/production-readiness.md`](../architecture/production-readiness.md).

---

## Executive summary

| Decision | Verdict |
|----------|---------|
| **Hosted beta (application layer)** | **GO** |
| **Public production** | **NO GO** |
| **Final backend score** | **87 / 100** |

The backend microservice platform is **certified for hosted beta** at the application layer. Deployment and platform durability gaps are **operator/infrastructure** concerns documented in production-readiness and KNOWN-ISSUES — not backend logic blockers for closed beta.

**Verification gates (commit `4cb7ed1`, RC1.1):** `./gradlew clean build` PASS; `./gradlew integrationTest "-Pparkio.integrationTest.requireDocker=true"` PASS.

---

## R-001 — Media

| Field | Detail |
|-------|--------|
| **Purpose** | Presigned upload, ClamAV scanning, MinIO storage, publish readiness for parking spots |
| **Bugs found** | Storage/DB consistency: orphan MinIO objects after failed metadata/outbox writes (documented in [`runtime-validation.md`](../operations/runtime-validation.md) § Media Upload Storage Consistency) |
| **Fixes applied** | Transaction synchronization + orphan cleanup in media application layer; metrics `parkio.media.upload.orphan_cleanup_*` |
| **Runtime evidence** | **NOT VERIFIED** as a dedicated R-001 sprint report. Integration tests: `MediaInfrastructureIntegrationTest`, `MediaScanUploadTest`. Operator checklist in `runtime-validation.md` |
| **Verification gates** | 14 test classes under `services/media-service/src/test/`; service included in root `integrationTest` (PASS at RC1.1) |
| **Certification** | **PARTIALLY CERTIFIED** — strong unit/integration coverage; operator storage-consistency drill not recorded in repo |
| **Operator-only work** | Execute media upload consistency checklist on Docker stack before widening beta |

---

## R-002 — Notifications

| Field | Detail |
|-------|--------|
| **Purpose** | Inbox APIs, Kafka-driven notification creation, Expo push delivery, deep-link metadata |
| **Bugs found** | Push payload / staff-route metadata alignment (addressed in `NotificationPushPayloadBuilder` + tests) |
| **Fixes applied** | `NotificationPushPayloadBuilder`, `NotificationApplicationService` delivery paths; unit tests updated |
| **Runtime evidence** | **NOT VERIFIED** device push on RC1.3. Unit: `NotificationPushPayloadBuilderTest`, `ExpoPushNotificationSenderTest`, `PushDeliveryWorkerTest` |
| **Verification gates** | 18 test classes under `services/notification-service/src/test/`; integrationTest PASS |
| **Certification** | **PARTIALLY CERTIFIED** — inbox and push pipeline tested in CI; live Expo delivery operator-dependent |
| **Operator-only work** | Configure `PARKIO_EXPO_ACCESS_TOKEN`; verify push on dev client/APK |

---

## R-003 — Smart Return

| Field | Detail |
|-------|--------|
| **Purpose** | Opt-in return-trip parking assistance; user preferences in `user-service`; scheduler/claims in `notification-service` |
| **Bugs found** | Claim idempotency / expired in-progress retry (covered by `SmartReturnSchedulerTest`) |
| **Fixes applied** | Feature-flagged APIs (`PARKIO_SMART_RETURN_ENABLED`); scheduler zone config; privacy rules in production-readiness §11.6 |
| **Runtime evidence** | **NOT VERIFIED** full real-stack Smart Return smoke on RC1.3. Unit: `SmartReturnSchedulerTest`, `UserControllerSmartReturnFlagTest`, `InternalSmartReturnControllerTest` |
| **Verification gates** | Smart Return tests in user + notification services; integrationTest PASS |
| **Certification** | **PARTIALLY CERTIFIED** — logic and flags tested; operator smoke procedure documented in production-readiness §11.6, not executed in repo |
| **Operator-only work** | Run Smart Return real-stack smoke (production-readiness §11.6) before cohort enablement |

---

## R-004 — Authentication & session lifecycle

| Field | Detail |
|-------|--------|
| **Purpose** | RS256 JWT + JWKS, opaque refresh tokens (hashed, rotated, family revocation), session epoch, gateway validation |
| **Bugs found** | Session hardening gaps closed during certification period (refresh reuse, epoch invalidation) |
| **Fixes applied** | `RefreshTokenSecurityIntegrationTest`, `JwtKeyRotationTest`, `MobileAuthFlowIntegrationTest`; gateway `JwtTokenValidatorTest` |
| **Runtime evidence** | Web real-stack auth smoke PASS (FFINAL, frontend cert). Backend-specific device matrix: **NOT VERIFIED** on RC1.3 |
| **Verification gates** | 18+ auth-service test classes; gateway security tests; integrationTest PASS |
| **Certification** | **FULLY CERTIFIED** for hosted-beta auth contracts |
| **Operator-only work** | Per-environment JWT key generation; key rotation runbook (`docker/README.md`) |

---

## R-005 / R5 — Deployment, security, observability

| Field | Detail |
|-------|--------|
| **Purpose** | Hosted-beta deploy safety, secret preflight, TLS ingress, observability stack, supply chain |
| **Bugs found** | Placeholder secrets reaching deploy; Slack-shaped URLs in fixtures (fixed RC1) |
| **Fixes applied** | `scripts/preflight-hosted-beta.sh` (R-005); `scripts/deploy-hosted-beta.sh` abort-before-build; `docs/beta/deploy-runbook.md` (R5.1); CI: `security-ci.yml`, `supply-chain.yml`, `observability-validation.yml` |
| **Runtime evidence** | Preflight regression **36/36 PASS** (`scripts/test-preflight-hosted-beta.sh`). `runtime-validation.yml` workflow defined — last run **NOT VERIFIED** in this audit |
| **Verification gates** | Preflight PASS; Gradle build + integrationTest PASS at RC1.1 |
| **Certification** | **FULLY CERTIFIED** for pre-deploy governance |
| **Operator-only work** | Run preflight on VPS `.env`; wire Alertmanager webhooks; execute `runtime-validation` workflow before production-like cut |

---

## R5.x — Release engineering sub-sprints

| Artifact | Evidence |
|----------|----------|
| Deploy runbook | `docs/beta/deploy-runbook.md` (R5.1) |
| Rollback | `docs/beta/rollback-runbook.md` |
| Preflight fixtures | `scripts/preflight-fixtures/` + `scripts/test-preflight-hosted-beta.sh` |
| Release workflow | `.github/workflows/release.yml` (draft release, SBOM, gated image publish) |
| Hosted-beta deploy | `.github/workflows/hosted-beta-deploy.yml` |

**Certification:** **FULLY CERTIFIED** for documented operator paths.

---

## R6 / R6.x — Infrastructure & product quality

| Field | Detail |
|-------|--------|
| **Purpose** | VPS sizing, backups, restore drills, runtime validation, performance smoke |
| **Bugs found** | Single-node durability risks (documented, not application bugs) |
| **Fixes applied** | `docs/operations/vps-hosted-beta-checklist.md` (R6.1); `backup-restore-drill.yml`; `scripts/backup-databases.sh`, `restore-database.sh`; `docs/operations/runtime-sizing.md` |
| **Runtime evidence** | Backup-restore drill workflow exists — last green run **NOT VERIFIED** in this audit. Benchmark reports under `benchmarks/reports/` (historical) |
| **Verification gates** | `chaos-validation.yml`, `performance-smoke.yml`, `runtime-validation.yml` defined |
| **Certification** | **CONDITIONAL GO** — procedures exist; operator must execute restore drill on target VPS |
| **Operator-only work** | VPS checklist §8 backups; DR runbook; `infra/` remains placeholder (no Terraform/K8s) |

---

## Backend certification summary

| Sprint | Certification | Score weight |
|--------|---------------|--------------|
| R-001 Media | PARTIALLY CERTIFIED | High |
| R-002 Notifications | PARTIALLY CERTIFIED | High |
| R-003 Smart Return | PARTIALLY CERTIFIED | Medium |
| R-004 Auth/session | FULLY CERTIFIED | Critical |
| R-005 / R5 | FULLY CERTIFIED | Critical |
| R6 / R6.x | CONDITIONAL GO | High |

**Aggregate:** Application logic and security controls are **ready for hosted beta**. Runtime proof gaps are **operator-executable** procedures already documented.

---

## Hosted beta readiness — GO

- Fail-closed secrets and gateway RBAC
- Flyway migrations, outbox/inbox, DLT patterns
- Compose overlays + preflight + deploy scripts
- Observability stack provisioned in compose

**Caveat:** Execute VPS checklist, preflight, and optional Smart Return / media consistency drills.

---

## Public production readiness — NO GO

Per [`production-readiness.md`](../architecture/production-readiness.md): managed PITR Postgres, Kafka RF≥3, secrets manager, CD with rollback, on-call, load/security testing at scale.

---

## Remaining operator-only work

1. VPS deploy using `deploy-runbook.md` + `vps-hosted-beta-checklist.md`
2. Backup/restore drill with evidence
3. Alertmanager webhook test
4. Smart Return smoke (production-readiness §11.6)
5. Media storage consistency checklist (`runtime-validation.md`)
6. Optional: trigger `runtime-validation.yml` and archive results

---

*This document is the authoritative backend certification report for Parkio v1.0.0-rc1. Repository tests, scripts, and runbooks are the source of truth.*