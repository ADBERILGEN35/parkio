# Parkio

Parkio is a Java 21 / Spring Boot 3 microservice platform, organised as a Gradle
(Kotlin DSL) monorepo. Each backend capability is an **independently runnable**
Spring Boot service with its own build, Dockerfile and bounded context.

> **Status:** scaffolding only. Project structure, build wiring and base Spring
> Boot applications exist; business logic is intentionally not implemented yet.

## Repository layout

```
parkio/
├── build.gradle.kts          # Root aggregator (no production code)
├── settings.gradle.kts       # Declares all service modules
├── gradle.properties         # Shared build + Spring Cloud BOM version
├── gradle/
│   └── libs.versions.toml    # Version catalog (single source of versions)
├── buildSrc/                 # `parkio.spring-service` convention plugin
├── services/                 # The microservices (one Gradle module each)
│   ├── gateway-service/      # API gateway / edge routing        (:8080)
│   ├── auth-service/         # Authentication & authorization    (:8081)
│   ├── user-service/         # User profiles & accounts          (:8082)
│   ├── parking-service/      # Parking spots & reservations      (:8083)
│   ├── media-service/        # Media upload & serving            (:8084)
│   ├── gamification-service/ # Points, badges, leaderboards      (:8085)
│   ├── notification-service/ # Notifications                     (:8086)
│   ├── moderation-service/   # Content moderation                (:8087)
│   ├── ai-validation-service/# AI-assisted validation            (:8088)
│   └── analytics-service/    # Event ingestion & analytics       (:8089)
├── docs/                     # Architecture & design documentation
├── infra/                    # Infrastructure-as-code (k8s, terraform, …)
├── docker/                   # Local compose stack & container assets
└── scripts/                  # Developer & CI helper scripts
```

## Architecture principles

- **Microservices, not a modular monolith.** Every service builds, runs, deploys
  and scales on its own.
- **No shared domain.** Services never import each other's models. Each service
  owns its data and exposes contracts at its boundary only. The `shared` package
  inside a service is for *intra-service* cross-cutting helpers, not cross-service
  reuse.
- **Clean architecture per service.** Each service's source under
  `src/main/java/com/parkio/<service>` is split into:

  | Layer            | Responsibility                                                     |
  |------------------|--------------------------------------------------------------------|
  | `domain`         | Enterprise rules: entities, value objects, domain ports.           |
  | `application`    | Use cases that orchestrate the domain.                             |
  | `infrastructure` | Outbound adapters: persistence, messaging, external clients.       |
  | `presentation`   | Inbound adapters: REST controllers, request/response models.       |
  | `shared`         | Cross-cutting helpers scoped to this service only.                 |

- **Spring Cloud compatible.** The gateway uses Spring Cloud Gateway and the
  Spring Cloud BOM is imported for every service, ready for config, discovery
  and resilience starters.

## Build conventions

Shared build logic lives in [`buildSrc`](buildSrc) as the precompiled
`parkio.spring-service` convention plugin. It applies the Java 21 toolchain, the
Spring Boot and dependency-management plugins, imports the Spring Cloud BOM and
configures JUnit 5. Versions are centralised in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml) and
[`gradle.properties`](gradle.properties).

A service `build.gradle.kts` therefore stays tiny:

```kotlin
plugins {
    id("parkio.spring-service")
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    // …
}
```

## Common commands

| Command                                              | Description                          |
|------------------------------------------------------|--------------------------------------|
| `./gradlew build`                                    | Build & test every service.          |
| `./gradlew :services:<service>:bootRun`              | Run a single service locally.        |
| `./gradlew :services:<service>:test`                 | Test a single service.               |
| `./gradlew :services:<service>:bootJar`              | Produce a runnable jar.              |
| `./gradlew printServices`                            | List the service module paths.       |

Requires JDK 21 (the Gradle toolchain will resolve it). No local Gradle install
is needed — use the bundled wrapper (`./gradlew`).

## Running with Docker

Each service ships a multi-stage [`Dockerfile`](services/auth-service/Dockerfile).
See [`docker/`](docker) for a local Compose stack:

```bash
docker compose -f docker/docker-compose.yml up --build
```

## Documentation & AI-tool hygiene

**Project documentation is versioned.** The canonical docs live under
[`docs/`](docs) and especially [`docs/ai-context/`](docs/ai-context) — the rule
set every contributor (human or AI agent) reads before working. These files are
committed and reviewed like any other source; **do not delete or git-ignore them**.

**AI-tool local files must NOT be committed.** Coding assistants (Claude Code /
claude-mem, Cursor, Aider, Codex, etc.) drop per-developer memory and config files
into the tree — e.g. auto-generated `CLAUDE.md` stubs, `.claude/`, `.cursor/`,
`.cursorrules`, `.aider*`. These are local to a developer's machine, not project
artifacts, and are excluded in [`.gitignore`](.gitignore).

- The scattered `CLAUDE.md` files in this repo are **auto-generated by a plugin**
  and carry no project meaning, so `CLAUDE.md` is git-ignored at every depth.
  Authoritative guidance lives in `docs/ai-context/`, not in `CLAUDE.md`.
- The ignore patterns never match `docs/ai-context/` itself (it has no leading dot
  and is not named `CLAUDE.md`), so project docs stay versioned.
- If you ever need to commit a tool-specific file deliberately, force-add it:
  `git add -f <path>`.

## Further reading

- [`docs/`](docs) — architecture decisions and service contracts.
- [`docs/ai-context/`](docs/ai-context) — project rules for contributors and AI agents.
- [`infra/`](infra) — deployment and infrastructure definitions.
- [`scripts/`](scripts) — helper scripts for local development and CI.
