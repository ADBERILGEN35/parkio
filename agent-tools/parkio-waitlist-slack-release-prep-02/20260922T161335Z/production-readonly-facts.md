# parkio-civo-prod — read-only facts (2026-09-22)

Collected over SSH as `civo` with read-only commands only: `docker ps/inspect`,
`docker exec … cat /app/app.jar` streamed to an in-memory zip reader, `psql`
SELECT statements, `git status/log`, `df`, `getent`, `systemctl list-unit-files`.
No container was started, stopped or recreated. No file was written, no
secret value was printed (env keys listed by **name** only; datasource URL
without credentials or query string), and New Relic units were only listed.

| Fact | Value |
|---|---|
| Host | `parkio-civo-prod`, Ubuntu 24.04.5 LTS, kernel 6.8.0-134, systemd 255, Python 3.12.3, Docker 29.8.1 |
| Gateway container | `parkio-gateway-service-1`, created 2026-09-21T17:12:26Z, healthy |
| **Running gateway image** | `ghcr.io/adberilgen35/parkio/gateway-service@sha256:8b8a08ba974aeec91ec690ada406552a474c2319880752d4d4ea5ff1798028aa`, revision `83fa625b9e37d6c0819862459d5570e305dd5121`, created 2026-09-21T16:55:55Z, `USER parkio` |
| Gateway pin in repo **and** host `docker-compose.gmp-release-pins.yml` | `sha256:5c66e0fb…` (revision `efe24195`), which does **not** match the running image (drift, WSN-F6) |
| Compose files used to create the running gateway (container labels) | docker-compose.yml, apps, hosted-beta, azure-hosted-beta, gmp-release-pins, web-release-pin |
| Host `docker/compose.production.files` | …, gmp-release-pins, **auth-release-pin**, web-release-pin (same as `origin/api`) |
| Host checkout | `/opt/parkio` at `bf9cad51` with 69 modified or untracked paths, including pin backups (`*.bak-*`) and `docker-compose.gmp-recovery-prior.yml` (WSN-F7) |
| Gateway env (names only, filtered) | `JAVA_TOOL_OPTIONS`, `PARKIO_WAITLIST_ADMISSIONS_ENABLED`, `SPRING_DATASOURCE_URL`. There is **no** `SPRING_FLYWAY_*`, `FLYWAY_*`, `PARKIO_ENVIRONMENT` or `PARKIO_WAITLIST_OPS_*`, no `group_add` and no mounts. |
| Datasource | `jdbc:postgresql://postgres-gateway:5432/parkio_gateway` |
| Effective Flyway (running jar) | `flyway-core-11.7.2`, `flyway-database-postgresql-11.7.2`. Bundled migrations V1, V2, V3. `application.yml`: `spring.flyway.enabled=true`, `locations=classpath:db/migration`. No `ignore-migration-patterns` / `validate-on-migrate` / `out-of-order` / baseline override in jar or env, so the Flyway 11 defaults apply (`ignoreMigrationPatterns=*:future`, validate on migrate). |
| Gateway DB engine | container `parkio-postgres-gateway`, image `postgres:16-alpine` (`sha256:cf78e76683b9…`), **PostgreSQL 16.15** on x86_64-pc-linux-musl |
| Gateway DB schema | `flyway_schema_history`: `1:true,2:true,3:true`. `waitlist_ops_notification_outbox` absent. `waitlist_interest` has 3 rows. DB owner `parkio_gateway` (the same role runs Flyway and the app). DB size 7.7 MB. |
| Storage | `/` 136 G, 48 G used, 88 G free (36%). Inodes 4% used. `/var/lib/parkio` does not exist yet. |
| Relay state | No slack-biz containers, no `parkio-slackbiz` user, no `parkio-waitlist-inbox` group, no `parkio-slack-biz-*` units |
| New Relic (listed only) | `parkio-nr-log-continuous-fluent-bit-nr-pilot-1`, `parkio-nr-log-continuous-nr-budget-gate-1` containers; `parkio-nr-log-continuous.service`, `parkio-nr-log-source.service`, `parkio-nr-log-continuous-guard.timer` enabled. None of them is touched by this change. |
| Monitoring | `parkio-prometheus`, `parkio-alertmanager`, `parkio-node-exporter` running |
