# AWS Activate Package

## Startup Description

Parkio is an early-stage, community-powered parking intelligence platform that helps drivers find, share, verify, and manage real-world parking availability. It is mobile-first, privacy-conscious, and currently at release-candidate / hosted-beta preparation stage.

## Product Summary

Drivers can share parking spots with photos and context, discover nearby spots, verify availability, claim spots after parking, report issues, and earn trust through contribution history. Parkio's first validation goal is to prove that community-powered parking observations can create useful local density in a constrained geography.

## Current Stage

- Release: `v1.0.0-rc1`.
- Application certification: hosted-beta RC.
- Live hosted beta: not yet deployed.
- Public production: not ready.
- Domain: `parkio.dev`.
- Business validation: traction, revenue, retention, and willingness to pay are not yet measured.

## Technical Architecture

- 10 Spring Boot microservices plus API gateway.
- Java 21, Spring Boot 3.5.x, Spring Cloud Gateway.
- PostgreSQL/PostGIS database-per-service model.
- Kafka event bus with outbox/inbox and DLT patterns.
- Redis for edge/runtime support.
- S3-compatible object storage for media.
- ClamAV media scanning.
- React web SPA.
- Expo React Native mobile app.
- Prometheus/Grafana/Loki/Alertmanager observability.
- Docker Compose hosted-beta deployment path.

## Why AWS Fits

Parkio's current beta risk is mostly infrastructure maturity, not missing application scaffolding. AWS can support the natural migration path from a single-host beta to managed compute, managed PostgreSQL/PostGIS, durable media storage, observability, secrets, email, and safer event streaming.

## How AWS Credits Would Be Used

- Host a staging and hosted-beta environment.
- Run application containers on EC2 first, or ECS/ECR when ready for managed container operations.
- Move PostgreSQL/PostGIS workloads to RDS with backups and PITR when AWS is the landing zone.
  Per [ADR-PP-01A](../architecture/adr/ADR-PP-01A-managed-postgresql.md), **AWS RDS for PostgreSQL**
  is the **approved alternate** (Azure Flexible Server is primary). ADR acceptance does not close
  PP-01 or public production NO-GO.
- Store media in S3 with private buckets and signed access.
- Evaluate MSK or a managed Kafka-compatible alternative.
- Use ElastiCache for Redis if moving beyond single-host beta.
- Use CloudWatch for logs, metrics, alarms, and operational visibility.
- Use SES or equivalent for transactional email.
- Use Secrets Manager or SSM Parameter Store for secrets.
- Fund load, security, and reliability testing before public beta.

## Expected AWS Services

- EC2.
- ECS and ECR.
- RDS for PostgreSQL/PostGIS (ADR-PP-01A approved alternate).
- S3.
- MSK or managed Kafka-compatible streaming.
- ElastiCache for Redis.
- CloudWatch.
- SES.
- Secrets Manager or SSM Parameter Store.

## 3-Month Plan

- Complete hosted-beta deployment on real infrastructure.
- Configure `parkio.dev` beta subdomains.
- Run preflight, HTTPS smoke, backup/restore, observability, and mobile device checks.
- Recruit first private beta cohort.
- Measure activation, upload, verification, claim, report, notification, retention, support, and uptime metrics.
- Decide whether the next AWS step is EC2 hardening or ECS/RDS/S3/MSK migration.

## 6-Month Plan

- Expand to beta cohort 2 only if cohort 1 shows useful density and reliability.
- Move critical data services toward managed AWS equivalents.
- Add stronger secret management and operational alerting.
- Run capacity and security validation.
- Prepare a public beta decision package.

## 12-Month Plan

- Operate a production-hardened beta or public beta if metrics and infrastructure are ready.
- Validate monetization options such as premium alerts, Smart Return, local partnerships, or privacy-safe analytics.
- Build repeatable deployment, rollback, incident, and evidence-capture processes.
- Expand only where community density and reliability are proven.

## Success After Credits

Success means Parkio has a live controlled beta, measured product usage, reliable operational evidence, and a clear decision on whether to continue toward public beta. It does not mean claiming public production before the documented blockers are closed.

## Honest Current Metrics

- Active users: Not yet measured.
- Revenue: none documented.
- Retention: Not yet measured.
- Partnerships: none documented.
- Market size: TBD.
- Live demo: TBD.

## Links and Placeholders

- Domain: `parkio.dev`.
- GitHub repo: TBD.
- Demo URL: TBD.
- Certification docs: `docs/certification/`.
- Release docs: `docs/releases/`.
- Operations docs: `docs/operations/`.
