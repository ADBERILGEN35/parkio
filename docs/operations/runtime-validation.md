# Runtime Validation Workflow

`runtime-validation.yml` is the release-candidate runtime gate for P1.5
hardening. It exists because local WSL environments may not have reliable Docker
daemon access, while GitHub-hosted Linux runners provide Docker.

## How To Run

Run it manually from GitHub Actions:

1. Open **Actions**.
2. Select **Runtime validation**.
3. Choose **Run workflow**.

It also runs weekly and on pull requests that touch Dockerfiles, compose files,
application runtime YAML, or the workflow itself.

## What It Validates

- Backend `./gradlew --no-daemon clean build`.
- Backend `integrationTest` with `parkio.integrationTest.requireDocker=true`.
- Frontend install, typecheck, lint, tests, and production build.
- Hosted-beta compose render from `docker/.env.hosted-beta.example`.
- Full compose stack build and startup on `ubuntu-latest`.
- Required container healthchecks for apps, databases, Kafka, Redis, MinIO,
  ClamAV, Caddy, Prometheus, Grafana, Loki, Promtail, and Alertmanager.
- App readiness endpoints from inside each container.
- Gateway JWKS route.
- Unauthenticated protected gateway route returns `401`.
- Unauthenticated geocoding route returns `401`.
- Direct service access is rejected with `GATEWAY_AUTH_REQUIRED`.
- App container memory, CPU, and PID limits are applied.
- `analytics-service` restart recovery.
- Observability smoke endpoints:
  Prometheus, Grafana, Loki, Tempo, Alertmanager, Kafka exporter, and node exporter.

## CI Environment

The workflow copies `docker/.env.hosted-beta.example` to
`docker/.env.runtime-validation` and patches in throwaway CI-only values:

- an ephemeral RSA key for auth-service JWT signing;
- a non-secret gateway internal secret;
- logging email provider;
- valid Kafka cluster id;
- non-secret MinIO/Grafana passwords;
- blank Slack webhook.

No real hosted-beta or production secrets are required or used.

## What It Intentionally Does Not Validate

- Real public DNS.
- Real ACME certificate issuance.
- Real Slack alert delivery.
- Real frontend hosting behind the Caddy SPA upstream.
- Destructive OOM tests.
- Public-production high availability.
- Manual browser flows against a deployed beta host.

The workflow validates runtime hardening on a CI Docker host. It does not by
itself prove production readiness.

## Failure Artifacts

On failure the workflow uploads:

- rendered compose config;
- `docker compose ps` output;
- healthcheck result tables;
- resource-limit inspection output;
- logs for every compose service.

The stack is always cleaned up with `docker compose down -v --remove-orphans`.
