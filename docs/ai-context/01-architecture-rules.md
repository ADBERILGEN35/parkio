# 01 — Architecture Rules

These rules are **non-negotiable**. Violations should be rejected in review.

## Topology

- **Microservice-first, NOT a modular monolith.** Every service builds, deploys,
  runs and scales independently. It is already an independent Gradle module.
- **Database-per-service.** Each service owns its schema/database. No service reads
  or writes another service's tables. **No cross-service database access**, no
  shared DB connection, no foreign keys across services.
- **No shared domain model module.** Services never import each other's entities,
  value objects, or DTOs from a common library. Duplicate small contracts locally
  instead of sharing a domain. The per-service `shared` package is for
  **intra-service** cross-cutting helpers only.

## Clean architecture (per service)

Source under `src/main/java/com/parkio/<service>` is split into:

| Layer            | May depend on                  | Responsibility                                   |
|------------------|--------------------------------|--------------------------------------------------|
| `domain`         | nothing (pure Java)            | Entities, value objects, domain services, ports. |
| `application`    | `domain`                       | Use cases orchestrating the domain.              |
| `infrastructure` | `application`, `domain`        | Adapters: JPA, Kafka, Redis, S3, Feign clients.  |
| `presentation`   | `application`, `domain`        | Inbound adapters: REST controllers, DTOs.        |
| `shared`         | —                              | Cross-cutting helpers scoped to this service.    |

**Dependency rule:** dependencies point inward. `domain` is the center.

- **`domain` must not depend on Spring, JPA, Kafka, HTTP, or any framework
  annotation.** No `@Entity`, `@Component`, `@Autowired`, Jackson, etc. in `domain`.
- Framework wiring lives in `infrastructure` and `presentation`.
- Ports (interfaces) are declared in `domain`/`application`; adapters implementing
  them live in `infrastructure`.

## Communication

- **Synchronous:** REST over HTTP, called via **OpenFeign** clients. Use only for
  queries/commands that genuinely need an immediate response. Avoid synchronous
  call chains across more than one hop.
- **Asynchronous:** **Kafka events** for side effects (points, notifications,
  analytics, moderation triggers). Prefer async to keep services decoupled.

## Reliability patterns (required for production paths)

- **Outbox pattern** for publishing: write the event to an outbox table in the same
  DB transaction as the state change; a relay publishes to Kafka.
- **Inbox pattern** for consuming: record processed message ids to deduplicate.
- **Idempotency** for all commands and consumers: idempotency keys for write APIs;
  consumers must tolerate at-least-once delivery (redelivery must be safe).

## Configuration

- Externalize config; never hardcode secrets. Use env vars / Spring config.
- Each service exposes actuator `health` and `info` (already configured).
- Build conventions are centralized in the `parkio.spring-service` convention
  plugin (`buildSrc`) and the version catalog (`gradle/libs.versions.toml`). Add
  shared dependency versions there, not ad hoc in service build files.
