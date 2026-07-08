# Founder Notes

## Open Decisions

- Initial beta geography: TBD.
- Beta intake path: TBD.
- Contact email for landing page: TBD.
- GitHub visibility and license strategy: TBD.
- Cloud provider for hosted beta: TBD.
- Whether to use AWS, Azure, Oracle, or another provider for first hosted beta: TBD.
- Account/data deletion workflow before public beta: TBD.
- Monetization priority: TBD.
- Market sizing model: TBD.

## Assumptions

- `parkio.dev` is the primary domain.
- The landing page will live at `parkio.dev`.
- Hosted beta is not live until VPS/DNS/env/deploy/smoke evidence exists.
- Application-layer hosted-beta readiness is supported by RC1 certification docs.
- Public production remains not ready.
- Beta should start in one constrained geography.

## Main Risks

- Insufficient local user density.
- Bad or stale spot data reducing trust.
- Moderation workload growing faster than expected.
- Mobile runtime issues not caught by unit tests.
- Smart Return privacy expectations.
- Single-node beta infrastructure risk.
- Public-production claims being made too early.
- Legal/privacy copy not final before broader launch.

## What Must Be Validated With Real Users

- Whether drivers will upload spots at the moment they see them.
- Whether shared spots stay useful long enough to matter.
- Verification and claim rates.
- Report rate and false-positive rate.
- Trust/gamification motivation.
- Smart Return usefulness.
- Notification usefulness vs annoyance.
- Retention after first use.
- Willingness to pay.

## What Not To Overpromise

- Do not claim public launch.
- Do not claim live beta until deployed and smoked.
- Do not claim users, revenue, partnerships, patents, press, or investor interest.
- Do not claim guaranteed parking availability.
- Do not claim municipal partnerships.
- Do not claim production-grade HA.
- Do not claim account self-erasure until implemented and verified.
- Do not claim AI automatically determines legality or safety.

## Talking Points for AWS Activate

- Parkio is a technically mature hosted-beta release candidate.
- The immediate need is cloud support for safe beta infrastructure and managed data services.
- AWS credits would be used for compute, RDS/PostGIS, S3, streaming, secrets, observability, email, staging, and validation.
- Business traction is not yet measured; the ask is to reach measured beta validation responsibly.
- Success means a live controlled beta, real usage data, and clearer public-beta readiness, not premature production claims.

## Talking Points for Microsoft Founders Hub

- Parkio can start on Azure VM infrastructure and move toward Azure Container Apps, Azure Database for PostgreSQL, Blob Storage, Azure Cache for Redis, Azure Monitor, Application Insights, and Key Vault as maturity increases.
- Azure support would help replace avoidable single-host risk with managed services after the first beta evidence is collected.

## Talking Points for Investors

- Parking availability is local, repeated, and high-friction.
- Parkio focuses on community-powered, photo-backed, verified parking intelligence.
- The technical foundation is substantially beyond a mockup.
- Current stage is pre-traction hosted-beta preparation.
- The first business milestone is validating local density, retention, and data reliability.
- The honest risk is whether the contribution loop can reach enough density in one geography.

## Talking Points for Beta Testers

- Parkio is not public production.
- Testers should expect rough edges and report issues.
- The most important flows are upload, discovery, verify, claim, report, notifications, and Smart Return when enabled.
- Do not upload unsafe, illegal, or private information.
- Report bad data and confusing UX with screenshots and trace IDs where possible.
