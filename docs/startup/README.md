# Parkio Startup Documentation

This directory is the startup/product-readiness source package for Parkio. It is intended for AWS Activate, Microsoft Founders Hub, Google for Startups, investor conversations, technical due diligence, beta communication, and future `parkio.dev` copy.

## Index

- [00 Executive Summary](00-executive-summary.md)
- [01 Vision and Mission](01-vision-and-mission.md)
- [02 Problem](02-problem.md)
- [03 Solution](03-solution.md)
- [04 Target Market](04-target-market.md)
- [05 Competitive Landscape](05-competitive-landscape.md)
- [06 Business Model](06-business-model.md)
- [07 Roadmap](07-roadmap.md)
- [08 Technical Overview](08-technical-overview.md)
- [09 Go To Market](09-go-to-market.md)
- [10 One-Pager](10-one-pager.md)
- [11 AWS Activate Package](11-aws-activate-package.md)
- [12 Microsoft Founders Hub Package](12-microsoft-founders-hub-package.md)
- [13 Landing Page Copy](13-landing-page-copy.md)
- [14 FAQ](14-faq.md)
- [15 Founder Notes](15-founder-notes.md)
- [Messaging Framework](messaging-framework.md)

## How To Use These Documents

- Use `messaging-framework.md` to keep pitch deck, landing page, and applications consistent.
- Use `10-one-pager.md` for short accelerator, cloud-credit, and founder-program applications.
- Use `11-aws-activate-package.md` for AWS Activate.
- Use `12-microsoft-founders-hub-package.md` for Microsoft Founders Hub.
- Use `13-landing-page-copy.md` as source copy for `parkio.dev`.
- Use `15-founder-notes.md` to keep conversations honest and prevent overclaiming.

## Canonical Positioning

Parkio is a community-powered parking intelligence platform that helps drivers find, share, verify, and manage real-world parking availability.

Stage: `v1.0.0-rc1` release-candidate / hosted-beta preparation.

Correct readiness language:

- Application layer: certified for hosted-beta use.
- Live hosted beta: not yet deployed.
- Public production: not ready.
- Domain: `parkio.dev`.

## Source of Truth

These startup docs summarize repository evidence. When technical status changes, update the underlying source docs first, then update this package.

Primary source materials:

- `README.md`
- `CHANGELOG.md`
- `docs/releases/RC1-RELEASE-NOTES.md`
- `docs/releases/RC1-READINESS.md`
- `docs/releases/KNOWN-ISSUES.md`
- `docs/releases/RC2-HOSTED-BETA-DEPLOYMENT-REPORT.md`
- `docs/certification/FINAL-PRODUCTION-CERTIFICATION.md`
- `docs/certification/FINAL-BACKEND-CERTIFICATION.md`
- `docs/certification/FINAL-FRONTEND-CERTIFICATION.md`
- `docs/architecture/production-readiness.md`
- `docs/architecture/event-contracts.md`
- `docs/architecture/observability-metrics.md`
- `HOSTED-BETA-RUNBOOK.md`
- `docs/operations/security-boundaries.md`
- `docs/mobile-architecture.md`
- `docs/mobile-local-runtime.md`
- `docs/ai-context/00-project-overview.md`
- `docs/ai-context/02-domain-rules.md`
- `docs/ai-context/07-security-guidelines.md`
- `services/parking-service/README.md`
- `docs/beta/CLOSED_BETA_CHECKLIST.md`
- `docs/beta/play-store-listing.md`

## Source Audit Summary

- Product: community-powered parking spot sharing and parking intelligence.
- Core flows: upload spot, discover nearby spots, verify, claim, report, receive notifications, use Smart Return when enabled.
- Technical architecture: Java/Spring Boot microservices, gateway, Postgres/PostGIS, Kafka, Redis, S3-compatible media, React web, Expo mobile, observability stack.
- Readiness: application layer is certified for hosted-beta RC use; live hosted beta is not yet deployed.
- Public production: not ready.
- Security posture: strong hosted-beta foundations, but production hardening remains.
- Privacy posture: sensitive user data minimized, signed media URLs, EXIF stripping, Smart Return/location privacy caveats.
- Infrastructure readiness: deploy scripts and runbooks exist; VPS/DNS/env/deploy/smoke/ops checks remain operator work.
- Roadmap: hosted beta, measured beta cohorts, public beta only after production hardening.

## Update Before Applications

Before submitting AWS Activate, Microsoft Founders Hub, Google for Startups, investor, or press materials, update:

- GitHub repo URL.
- Demo URL.
- Contact email.
- Live deployment status.
- Beta geography.
- User metrics.
- Revenue metrics.
- Any cloud provider choice.
- Any accepted credits or program memberships.
- Any public legal/privacy URLs.

## Claims That Require Evidence

Do not claim any of the following unless repository or operational evidence is added:

- Live public launch.
- Live hosted beta.
- Active users.
- Paying customers.
- Revenue.
- Partnerships.
- Investor interest.
- Patents.
- Market size numbers.
- City coverage.
- AWS/Microsoft/Google acceptance.
- Production-grade HA.
- Account self-erasure.
