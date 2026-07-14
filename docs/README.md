# Parkio Documentation

This directory is the canonical map for Parkio architecture, operations,
release readiness, startup material, brand guidance, and contributor rules.

## First 10 Minutes

Read these in order if you are evaluating the project:

1. [Root README](../README.md) - what Parkio is and current maturity.
2. [Architecture Overview](architecture/README.md) - how the system is shaped.
3. [Production Readiness](architecture/production-readiness.md) - what is and
   is not production-ready.
4. [Known Issues](releases/KNOWN-ISSUES.md) - current blockers and limitations.
5. [Startup README](startup/README.md) - company, market, and roadmap package.

## Documentation Map

| Area | Start here | Purpose |
|------|------------|---------|
| Contributor rules | [ai-context/README.md](ai-context/README.md) | Architecture rules, service boundaries, API/database/event/security standards |
| Architecture | [architecture/README.md](architecture/README.md) | Gateway, services, data, messaging, storage, security, observability, deployment |
| Hosted beta | [../HOSTED-BETA-RUNBOOK.md](../HOSTED-BETA-RUNBOOK.md) | Single operator deploy, rollback, backup, incident, and env guide |
| Operations | [operations/HOSTED-BETA-INFRASTRUCTURE-PLAN.md](operations/HOSTED-BETA-INFRASTRUCTURE-PLAN.md) | Infrastructure, backups, DR, alerts, runtime validation |
| Administration | [operations/ADMIN-PANEL.md](operations/ADMIN-PANEL.md) | Admin RBAC, APIs, audit, SUPER_ADMIN bootstrap, web `/admin` |
| Certification | [certification/FINAL-PRODUCTION-CERTIFICATION.md](certification/FINAL-PRODUCTION-CERTIFICATION.md) | Evidence-backed readiness decisions |
| Releases | [releases/RC1-READINESS.md](releases/RC1-READINESS.md) | Release notes, checklists, known issues |
| Startup | [startup/README.md](startup/README.md) | Executive summary, market, GTM, accelerator packages, roadmap |
| Brand | [brand/README.md](brand/README.md) | Voice, visual identity, copy, UI principles |
| Design | [design/README.md](design/README.md) | Landing page, CTA, waitlist, FAQ, SEO flows |
| Mobile | [mobile-architecture.md](mobile-architecture.md) | Mobile architecture and local runtime guidance |

## Authoritative Engineering Rules

Use [`ai-context/`](ai-context/) as the source of truth for code changes:

- [Project Overview](ai-context/00-project-overview.md)
- [Architecture Rules](ai-context/01-architecture-rules.md)
- [Domain Rules](ai-context/02-domain-rules.md)
- [Service Boundaries](ai-context/03-service-boundaries.md)
- [API Guidelines](ai-context/04-api-guidelines.md)
- [Database Guidelines](ai-context/05-database-guidelines.md)
- [Event Guidelines](ai-context/06-event-guidelines.md)
- [Security Guidelines](ai-context/07-security-guidelines.md)
- [Coding Standards](ai-context/08-coding-standards.md)

Service contracts belong in documentation, not shared domain code between
microservices.

## Certification

| Document | Scope |
|----------|--------|
| [`FINAL-PRODUCTION-CERTIFICATION.md`](certification/FINAL-PRODUCTION-CERTIFICATION.md) | **Authoritative** — whole-project GO/NO GO |
| [`FINAL-BACKEND-CERTIFICATION.md`](certification/FINAL-BACKEND-CERTIFICATION.md) | Backend sprints R-001–R6 |
| [`FINAL-FRONTEND-CERTIFICATION.md`](certification/FINAL-FRONTEND-CERTIFICATION.md) | Frontend F1–FFINAL |
| [`F2-FRONTEND-RUNTIME-CERTIFICATION.md`](certification/F2-FRONTEND-RUNTIME-CERTIFICATION.md) | Historical web runtime matrix |

Release tag: `v1.0.0-rc1` at commit `4cb7ed1`. See
[`releases/RC1-READINESS.md`](releases/RC1-READINESS.md).

## Status Language

Use consistent status language across docs:

- `hosted-beta preparation` means application and operator assets exist.
- `operator-verified hosted beta` means a live deployment has been run and
  recorded with smoke evidence.
- `public production` is not ready until platform, operations, and scale
  blockers are closed.
- Do not claim users, revenue, funding, partnerships, awards, production HA, or
  live public beta without evidence.
