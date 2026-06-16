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
| `./gradlew build`                                    | Build & unit-test every service.     |
| `./gradlew integrationTest`                          | Testcontainers tests (needs Docker). |
| `./gradlew :services:<service>:bootRun`              | Run a single service locally.        |
| `./gradlew :services:<service>:test`                 | Test a single service.               |
| `./gradlew :services:<service>:bootJar`              | Produce a runnable jar.              |
| `./gradlew printServices`                            | List the service module paths.       |

Requires JDK 21 (the Gradle toolchain will resolve it). No local Gradle install
is needed — use the bundled wrapper (`./gradlew`).

`build` runs unit tests only (the `integration` JUnit tag is excluded), so it
never needs Docker. `integrationTest` runs the `@Tag("integration")`
Testcontainers suites (PostgreSQL, Kafka, MinIO) and **requires a running Docker
daemon**; it is deliberately not wired into `build`/`check`.

Run the full integration suite, a single service's, or a single class with:

```bash
./gradlew integrationTest                              # all services (needs Docker)
./gradlew :services:parking-service:integrationTest    # one service
./gradlew :services:media-service:integrationTest --tests '*MediaInfrastructureIntegrationTest'
```

**Docker behaviour.** Each suite is `@Testcontainers(disabledWithoutDocker = true)`,
so when no Docker daemon is reachable the tests are **skipped, not failed** —
convenient for a quick local `integrationTest` without Docker. Add
`-Pparkio.integrationTest.requireDocker=true` to instead **fail fast** when Docker
is missing; CI uses this flag so a misconfigured runner can never report a
false-green by silently skipping every suite.

## Continuous integration

Two workflows split the backend quality gate so PR feedback stays fast while the
slow, Docker-backed distributed-systems tests still run regularly.

**[`backend-ci.yml`](.github/workflows/backend-ci.yml) — the fast PR gate.**
Every pull request and every push to `master` runs `./gradlew --no-daemon build`
(compile + unit tests for all services) on `ubuntu-latest` with JDK 21 and a
cached Gradle home. It excludes the `integration` tag, so it never needs Docker.
Test reports are uploaded as an artifact when the build fails.

**[`backend-integration.yml`](.github/workflows/backend-integration.yml) — the
Testcontainers suite.** Runs `./gradlew --no-daemon integrationTest
-Pparkio.integrationTest.requireDocker=true` (PostgreSQL/Kafka/MinIO via
Testcontainers; `ubuntu-latest` ships Docker). It triggers:

- **on pull requests that touch backend paths** — `services/**`, `buildSrc/**`,
  `gradle/**`, `build.gradle*`, `settings.gradle*`, `gradle.properties`,
  `docker/**`, and the workflow itself — so frontend-/docs-only PRs skip it and
  stay fast;
- **nightly** (`schedule`), to catch drift even on weeks with no backend PRs;
- **on demand** from the Actions tab (`workflow_dispatch`).

The `requireDocker` flag makes the run **fail fast** if a runner lacks Docker,
rather than silently skipping every suite and reporting a false green. Both
workflows use least-privilege `contents: read` permissions, depend on no secrets,
and have job timeouts; integration runs are de-duplicated per ref via a
`concurrency` group. Integration test reports are uploaded on failure.

**[`frontend-ci.yml`](.github/workflows/frontend-ci.yml) — the frontend gate.**
On PRs/pushes that touch `frontend/**` it runs typecheck, lint, unit tests (vitest)
and the production build across the pnpm workspace (least-privilege `contents: read`,
20-min timeout, path-filtered so backend-/docs-only PRs skip it). Playwright e2e is
intentionally excluded from this fast gate.

**[`backup-restore-drill.yml`](.github/workflows/backup-restore-drill.yml) — the
disaster-recovery drill.** Brings up the postgres/postgis containers and runs
[`scripts/restore-drill.sh`](scripts/restore-drill.sh): seed canary → real backup →
real restore → assert the data **and** parking's PostGIS objects (extension, GiST
index, location trigger, live spatial query) survive the round-trip. Runs weekly, on
demand, and on PRs that change the backup scripts, the compose stack, or the parking
migrations. This makes "are our backups actually restorable?" a continuously-proven,
repeatable fact instead of a one-off manual ritual.

[`.github/dependabot.yml`](.github/dependabot.yml) raises conservative weekly
update PRs for Gradle dependencies and the GitHub Actions used by CI.

## Line-ending policy

[`.gitattributes`](.gitattributes) normalizes line endings so Windows/WSL
clones stop producing noise diffs (e.g. `gradlew.bat`):

- text files are stored with LF in the repository (`* text=auto`);
- `*.sh` and `gradlew` are always checked out with LF (they run on Linux/CI);
- `*.bat`/`*.cmd` and `gradlew.bat` are always checked out with CRLF;
- images and jars are marked `binary`.

Existing clones created before this policy should renormalize once:

```bash
git add --renormalize .
git commit -m "Normalize line endings"
```

Fresh checkouts need no manual steps — `gradlew.bat` materializes as CRLF and
`gradlew` as LF on every platform. AI-tool local files (`.claude/`, `.cursor/`,
…) are git-ignored and never tracked, so the attributes do not affect them.

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

## OpenAPI documentation

Each REST service exposes OpenAPI 3 docs when `PARKIO_OPENAPI_ENABLED=true`
(default locally; disable in production):

| Service | Swagger UI (direct port) | Gateway prefix |
|---------|--------------------------|----------------|
| auth | http://localhost:8081/swagger-ui.html | `/api/v1/auth/**` |
| user | http://localhost:8082/swagger-ui.html | `/api/v1/users/**` |
| parking | http://localhost:8083/swagger-ui.html | `/api/v1/parking/**` |
| media | http://localhost:8084/swagger-ui.html | `/api/v1/media/**` |
| gamification | http://localhost:8085/swagger-ui.html | `/api/v1/gamification/**` |
| notification | http://localhost:8086/swagger-ui.html | `/api/v1/notifications/**` |
| moderation | http://localhost:8087/swagger-ui.html | `/api/v1/moderation/**` |
| ai-validation | http://localhost:8088/swagger-ui.html | `/api/v1/ai-validations/**` |
| analytics | http://localhost:8089/swagger-ui.html | `/api/v1/analytics/**` |

Clients call the **gateway** on port 8080; service-level docs are for contract
reference and client generation. Internal `/internal/**` endpoints are hidden
from the public spec. See [`docs/architecture/openapi.md`](docs/architecture/openapi.md).

## Further reading

- [`docs/`](docs) — architecture decisions and service contracts.
- [`docs/ai-context/`](docs/ai-context) — project rules for contributors and AI agents.
- [`infra/`](infra) — deployment and infrastructure definitions.
- [`scripts/`](scripts) — helper scripts for local development and CI.
