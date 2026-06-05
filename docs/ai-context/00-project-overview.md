# 00 — Project Overview

Parkio is a **production-grade, microservice-first parking spot sharing platform**.

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

- **Backend:** Java 21, Spring Boot 3, Spring Cloud, Gradle Kotlin DSL.
- **Data:** PostgreSQL + PostGIS (geospatial), Redis (cache/locks/idempotency).
- **Messaging:** Kafka (asynchronous events).
- **Object storage:** MinIO / S3 (media).
- **Delivery:** Docker.
- **Clients:** React (web), React Native Expo (mobile).

## Current state

Scaffolding only: module structure, build wiring, base Spring Boot apps, empty
clean-architecture packages. **Business logic is not implemented yet.** These docs
define the rules for implementing it.
