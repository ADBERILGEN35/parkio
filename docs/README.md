# docs

Architecture, operations, certification, and release documentation for Parkio.

## Layout

| Path | Contents |
|------|----------|
| [`ai-context/`](ai-context/) | Contributor rules — architecture, security, service boundaries |
| [`architecture/`](architecture/) | System context, production-readiness plan |
| [`beta/`](beta/) | Hosted-beta deploy, rollback, and mobile release runbooks |
| [`operations/`](operations/) | VPS checklist, backups, DR, supply chain, runtime sizing |
| [`certification/`](certification/) | **Authoritative certification reports** (see below) |
| [`releases/`](releases/) | RC1 release notes, checklist, known issues, readiness |

## Certification (v1.0.0-rc1)

| Document | Scope |
|----------|--------|
| [`FINAL-PRODUCTION-CERTIFICATION.md`](certification/FINAL-PRODUCTION-CERTIFICATION.md) | **Authoritative** — whole-project GO/NO GO |
| [`FINAL-BACKEND-CERTIFICATION.md`](certification/FINAL-BACKEND-CERTIFICATION.md) | Backend sprints R-001–R6 |
| [`FINAL-FRONTEND-CERTIFICATION.md`](certification/FINAL-FRONTEND-CERTIFICATION.md) | Frontend F1–FFINAL |
| [`F2-FRONTEND-RUNTIME-CERTIFICATION.md`](certification/F2-FRONTEND-RUNTIME-CERTIFICATION.md) | Historical web runtime matrix |

**Release tag:** `v1.0.0-rc1` @ commit `4cb7ed1` — see [`releases/RC1-READINESS.md`](releases/RC1-READINESS.md).

Service contracts (OpenAPI, event schemas) belong in documentation — **not** as shared code between microservices.
