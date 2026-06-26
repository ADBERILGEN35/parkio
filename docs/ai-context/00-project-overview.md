# 00 — Project Overview

Parkio is a **microservice-first parking spot sharing platform** currently aimed
at a hosted-beta / closed-beta release.

## Core concept

Users upload photos of empty parking spots. Each submission carries:

- **Automatic location** (captured from device), with **optional manual correction**.
- **Description** (free text).
- **Vehicle fit** (which vehicle sizes the spot accommodates).
- **Parking context** (street, lot, private, etc.).
- **Legal status** (legal / restricted / unknown) and any violation-risk reasons.

Users **earn points**:

- A **small** amount for the upload itself.
- A **larger** amount after the spot is **verified** or **successfully claimed** by
  another user.

Progression and reliability are tracked by:

- **Level** — increases access and benefits over time.
- **Trust Score** — long-term reliability of a contributor.
- **Contribution Score** — recent community value (decays over time).

See [`02-domain-rules.md`](02-domain-rules.md) for exact rules.

## Services (Gradle modules under `services/`)

| Service                 | Port | Responsibility                                      |
|-------------------------|------|-----------------------------------------------------|
| `gateway-service`       | 8080 | API gateway / edge routing (Spring Cloud Gateway).  |
| `auth-service`          | 8081 | Authentication, authorization, token issuance.      |
| `user-service`          | 8082 | User profiles and accounts.                         |
| `parking-service`       | 8083 | Parking spots, availability, claims (core domain).  |
| `media-service`         | 8084 | Photo upload & serving (MinIO/S3).                  |
| `gamification-service`  | 8085 | Points, level, trust/contribution scores, ranking.  |
| `notification-service`  | 8086 | Push, email, in-app notifications.                  |
| `moderation-service`    | 8087 | Content moderation, reporting, penalties.           |
| `ai-validation-service` | 8088 | AI-assisted **advisory** validation of submissions. |
| `analytics-service`     | 8089 | Event ingestion and analytics aggregation.          |

## Tech stack

- **Backend:** Java 21, Spring Boot 3.5.x, Spring Cloud, Gradle Kotlin DSL.
- **Data:** PostgreSQL + PostGIS (geospatial), Redis (cache/locks/idempotency).
- **Messaging:** Kafka (asynchronous events).
- **Object storage:** MinIO / S3 (media).
- **Delivery:** Docker / Compose for local and hosted-beta environments.
- **Clients:** React web app with responsive/mobile UX. Native mobile is not the
  current implemented client surface.

## Current state

This repository is **not scaffolding-only**. It contains real domain logic,
REST APIs, Kafka/outbox/inbox flows, Flyway schemas, security/session handling,
media upload and malware scanning, moderation, gamification, notification,
analytics, observability, frontend UX, CI, security scanning, and real-stack E2E
wiring.

Treat the current target as **hosted-beta / closed-beta candidate**, not full
production-ready. Production still needs operational hardening such as managed
or highly available data services, release automation, full production
observability validation, incident/runbook maturity, and live readiness drills.

When modifying code, do **not** assume a service is empty or safe to overwrite.
Read the relevant service README, `docs/architecture/*`, and the service's
existing tests before editing. Preserve existing security/session/media/outbox
patterns unless the task explicitly asks to change them.
