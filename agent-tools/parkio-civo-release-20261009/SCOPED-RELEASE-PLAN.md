# Scoped release plan - parkio-civo-prod, release 22f96990 (draft for owner approval)

Status: DRAFT. Nothing in sections 7-9 runs before the owner approves this plan (with the decisions in
section 11) and the preparation gates in section 5 pass. Preparation runs one step at a time, each as an
immutable file with its SHA-256; the operator returns sanitized output after each step.

Inputs: D0.1 (release checkout clean at 22f96990; /opt/parkio bf9cad51 + 100 changes, untouched), D0.2.1
(PASS), D0.2.2 (exit 0: 14 services), D0.2.3 (exit 0; owner's verified findings 1-9 of 2026-10-09).
Still to come: P1 output; D0.2.3 section G lines (rollback method per service).

Fixed constraints: no whole-project `up`, no textfile bind mount, no /etc/fstab change; databases, Kafka,
Redis, MinIO and project parkio-nr-log-continuous untouched; /opt/parkio not changed (no pull, reset,
checkout, file edits); never `--skip-smoke`, `--allow-dirty` or a guard bypass; Azure stopped; no
production data restore; #104 HOLD; flags default-off; verifiedCoverage=false. Records outside both
checkouts: `R=~/parkio-release-20261009` (mode 700).

## 1. Scope

| Step | Services (recreated with `up -d --no-build --no-deps`) | Migrations at start |
|---|---|---|
| D2a, D2b, D2c | alertmanager, then prometheus, then caddy | none |
| D3 | gateway-service, with `-f docker/docker-compose.waitlist-ops-inbox.yml` | gateway V6 |
| D4 | web (wrapper web guards run) | none |
| D5 | auth-service | auth V24-V27 |
| D6a, D6b | media-service, then parking-service | media V14-V18; parking V41-V42 |
| D7 | user, gamification, notification, moderation, ai-validation, analytics (built from 22f96990) | user V20, notification V15, moderation V14, analytics V10-V11 |

Every command runs from `/opt/parkio-release` with
`PARKIO_ENV_FILE=/opt/parkio/docker/.env.azure-hosted-beta ./scripts/parkio-prod-compose.sh`.
Not recreated: postgres x10, kafka, redis, minio, minio-setup, clamav, grafana, blackbox-exporter,
node-exporter, kafka-exporter; project parkio-nr-log-continuous.

Outside the host: D1, the marketing bundle on Hostinger (section 7). The new gateway answers 400 to a
waitlist submission without a registered `consentTextVersion` (`PARKIO_WAITLIST_CONSENT_REQUIRED`
defaults to true, CL-F18).

## 2. Pending migrations (A)

Flyway on PostgreSQL runs each script in its own transaction. A failing script rolls back alone; earlier
scripts of the same start stay applied. Every service keeps the Spring Boot 3.5.15 defaults
(`ignoreMigrationPatterns=*:future`, `ddl-auto: validate`), the same in the previous and the new images.
So the previous image starts against a schema with newer scripts, and Hibernate checks only the columns
its entities map. In every recreate the previous container is stopped before the new one migrates. Each
database has one owning service, so the only possible lock contenders are the nightly backup's pg_dump
and an operator session.

| Service (previous source) | Scripts | Data change / destructive | Locks, downtime | Previous image on the migrated schema (static review) |
|---|---|---|---|---|
| gateway (f9710aa0) | V6 | adds `consent_text_version NOT NULL DEFAULT 'legacy-unversioned'`; no row rewritten (PG 11+ stored default) | brief ACCESS EXCLUSIVE; API down for the gateway restart | starts and runs: its JDBC insert names its columns and gets the default. Rows it writes are labelled legacy-unversioned (their consent version is not recorded). Loses CL-F15/F18/F28 behaviour. |
| auth (b10c1f7c) | V24-V27 | nullable/defaulted columns, a CHECK on the new column, a partial index, four new tables, a NOT VALID FK; nothing destructive | short ACCESS EXCLUSIVE + table scan for the CHECK and the index (erasure_requests) | starts and runs: new columns default/NULL, new tables unused. Nothing writes them while the erasure flags are off (P1 C). |
| media (4a9ba218) | V14-V18 | new tables and columns; V17 drops NOT NULL on a V14 column; V18 DROPS the global `uq_media_files_checksum` and adds the per-owner partial unique index | V18 builds a unique index on media_files (SHARE lock, scan); duplicates make it fail (P1 A) | starts and runs: its duplicate check is `existsByChecksum` (global, boolean), so several rows with one checksum do not break it. Behaviour returns to the old global duplicate rule (the disclosure V18 removed). |
| parking (1dd2d5bc) | V41-V42 | V41 UPDATE (0 rows in a UTC session, section 3) + `last_confirmed_at` TIMESTAMP -> TIMESTAMPTZ (table rewrite); V42 two `CREATE INDEX` on the search/view log tables | V41 ACCESS EXCLUSIVE for the rewrite of parking_sessions; V42 SHARE lock for each index build. Duration scales with the sizes in P1 A; a running pg_dump blocks V41 | starts and runs: the entity (`Instant`, no explicit type) and Hibernate are identical in both images, and the new image validates against TIMESTAMPTZ (ParkingSessionLastConfirmedAtTimeZonePostgisIT, candidate full stack). Reads and writes absolute instants. |
| user (bf9cad51) | V20 | three nullable BIGINT version columns | instant | starts and runs; it does not maintain the versions. After a later re-upgrade, stale versions let an older snapshot overwrite a newer value until the next event (same class as the U12 residuals). |
| notification (bf9cad51) | V15 | defaulted/nullable outbox columns, a partial index, an audit table | index build on outbox_events | starts and runs. |
| moderation (bf9cad51) | V14 | DROPS two UNIQUE constraints and recreates them as partial unique indexes that exempt the erasure sentinel | two unique index builds (small tables); duplicates make them fail (P1 A) | starts and runs: no SQL of it names the dropped constraints; real-user uniqueness still holds. |
| analytics (bf9cad51) | V10-V11 | defaulted/nullable outbox columns; drops and recreates `idx_outbox_events_unpublished`; an audit table | index builds on a small table | starts and runs; it has no outbox relay, so ACK rows the new release would queue (erasure on only) stay unpublished after a rollback. |
| gamification, ai-validation | none | - | - | no schema change. |

Not established: no test has run a previous image against its migrated schema. The repo's rollback rule
(F-INV-3, scripts/lib/rollback_schema_gate.py, "There is no override") refuses an image rollback when the
live schema holds a script the target lacks. This plan applies the same rule (section 9).

## 3. parking V41 and the session time zone (A)

V41 repairs rows V17 back-filled through a non-UTC session zone, assuming V41 runs in the same zone as
V17. pgjdbc sends the JVM default zone as the session `TimeZone`, and that overrides ALTER DATABASE/ROLE
settings. So the zone that matters is the parking JVM's: TZ in the container or image, else
`-Duser.timezone`, else /etc/timezone or /etc/localtime of the image. The compose model sets none, and
`JAVA_TOOL_OPTIONS` comes from the env file. With a UTC session the UPDATE changes no row by construction.
P1 B reports the facts that decide it:

- the new JVM zone (env file TZ / user.timezone, the running container, its image files);
- signature counts: rows equal to `started_at` as a UTC wall clock, the Europe/Istanbul back-fill
  signature, and the server-default-zone signature;
- the installed_on of V17 and of the latest script vs the creating container's time (hint only).

Rule: UTC JVM zone and an Istanbul signature of 0: proceed, nothing to repair. Istanbul signature > 0:
V17 ran in an Istanbul session, and V41 in UTC leaves those rows 3 h off. That needs an owner decision:
accept, or a reviewed data fix later. Starting parking with a non-UTC zone is not proposed. A non-UTC JVM
zone: stop and re-evaluate.

## 4. Configuration differences (B)

| File (live -> release) | Change | Live behaviour kept |
|---|---|---|
| docker/alertmanager/render-config.sh | live = MODIFIED working copy, git blob a9327cf8... = the file of commit d0da725e (2026-10-01, "include Alertmanager in the Civo compose path"), an ancestor of 22f96990. Release (7ca44eda = c4798204, U06) adds the Watchdog route (to the `heartbeat` receiver when PARKIO_ALERT_HEARTBEAT_URL is set, else `null`) and always defines a `null` receiver. | yes: same receivers, routes, require-receiver check and inhibit rules. Identified by blob id from git history; the host file was never printed. P1 D prints its full blob id and the live routing tree. |
| docker/alertmanager/alertmanager.yml | base config gains the Watchdog -> null route | yes |
| docker/prometheus/alerts.yml | + Watchdog (always firing, `severity: heartbeat`), AlertmanagerNotificationsFailing, PrometheusNotificationsFailing; runbook URLs absolute; erasure wording | yes. Watchdog matches no live route: with the live Alertmanager config it would go to the `warning` receiver (Slack). Hence D2a before D2b. |
| docker/prometheus/municipal-source-health-alert-rules.yml | + MunicipalFeedUnchangedTooLong, MunicipalIzumIncompleteSnapshotsRepeated (new parking metrics) | yes; silent until D6b provides the metrics |
| docker/prometheus/operational-readiness-alert-rules.yml | runbook URLs | yes |
| docker/prometheus/prometheus.yml | + scrape job alertmanager:9093 | yes |
| docker/caddy/Caddyfile | CSP img-src narrowed (CL-F39.2); /actuator/prometheus blocked at the edge (CL-F28); `header_up X-Parkio-Edge-Relay 1` and `header_up -Forwarded` (CL-F28, CL-F15) | yes. The live web (source 3bb89c6c, before #203) loads the auth-page photo from images.unsplash.com, which the new CSP blocks: the photo, not the page, is missing between D2c and D4. |

Heartbeat: if P1 C reports PARKIO_ALERT_HEARTBEAT_URL `set`, D2a starts sending the U06 heartbeat to
the external switch. That is new outbound behaviour, so it needs owner confirmation. If the key is empty
or absent, Watchdog ends at `null`.

## 5. Preparation gates (C), one step at a time

| Step | What | Changes |
|---|---|---|
| P1 | read-only facts for sections 2-4, 10 and U12-A (p1-release-facts.py) | none |
| P2 | rollback tags for the six running images (`<running repository>:rollback-pre-22f96990`, verified to point at the running image id) before anything can move their tags. Every image the 14-service model names present locally; pull the five new digest pins and verify image id = accepted config id (or = the pinned manifest digest on a containerd store). New parking image TZ; base images for the D7 build; free space. | local image tags and pulls only |
| P3 | `scripts/preflight-hosted-beta.sh --env-file <env>` (profile from the env file) must PASS; `smoke-hosted-beta.sh --check-credentials` with the password read by `read -r -s`. Preflight FAIL lines can contain host names or the ACME e-mail: sanitize before pasting. | none |
| P4 | U12 part A: counts from P1 G. Zero dead-lettered gamification rows and zero retained parkio.dlt.user records = gate met. Otherwise each row/record needs an owner decision (retry, acknowledge or redrive) before D7. | only if decided |
| P5 | fresh backup through the scheduled entry point as its user, under its lock, outside the 03:30 cron. The cron form is `flock -n /var/lock/parkio-backup.lock /opt/parkio/scripts/run-production-backup.sh`, as root. Gate: exit 0, new stamp with COMPLETE + SHA256SUMS, `parkio_backup_last_success` and `parkio_backup_offsite_last_success` = 1 for azure-hosted-beta. The live scripts are bf9cad51-based; P1 E says whether they write an offsite receipt file. If not, the offsite evidence is the upload success and the metric. With PARKIO_SLACK_BIZ_ENABLED=true it also posts the usual backup status to Slack. | writes a backup and its metrics (as nightly) |
| P6 | planned record (section 6) | evidence files only |

## 6. Records (D)

- `R/planned/`: written in P6, read-only. Release 22f96990; per service the model config hash (release
  wrapper, gateway with the waitlist overlay), target image refs (5 digests, 6 build names), target Flyway
  scripts from the 22f96990 tree, and the baseline applied versions read from each database. Marked
  `"status": "planned"`. Never copied to /var/lib/parkio.
- `R/journal.tsv`: append-only, one line per event. UTC time, step, service, start/ok/fail/rollback,
  previous and new container id and image id, config hash, health, the Flyway versions applied after
  the step (read from the database), smoke result.
- `R/state.json`: rewritten after every step from the journal and the databases. `in-progress` |
  `partial` | `complete` | `rolled-back`, services on 22f96990, services on the previous release, applied
  versions per database. A partial deployment stays `partial` here, and nothing else claims more.
- Final record, only after D7, the E smoke gate, U12 B/C and the Flyway check pass: a repo-compatible
  manifest (gitSha 22f96990, deploymentProfile azure-hosted-beta, composeFiles = the release wrapper list
  + civo overlay, gateway's waitlist overlay noted, pinnedImages = the five digests, images = the six
  `parkio/<svc>:sha-22f96990` tags + their ids, migrationVersions = the scripts verified applied in each
  database (equal to the release's), operator, smoke log hash, journal hash). Written atomically to
  `/var/lib/parkio/azure-hosted-beta/deployed-manifest.json` (directory created then with
  `sudo install -d -m 2775 -g <deployers> /var/lib/parkio/azure-hosted-beta`) and copied to
  `R/final/current.json`. Nothing is written into either checkout. /opt/parkio/deploy-artifacts stays
  absent.

## 7. Deployment sequence (after approval; each step one command, output returned)

Common pre-checks: release checkout clean at 22f96990; the previous step is `ok` in the journal; the
service's release config hash differs from its container's label; only the expected env keys and binds
disappear (none, with the waitlist overlay on the gateway); no pg_dump/backup running. Common post-checks:
container healthy within its timeout; image id = expected; Flyway applied = release scripts and 0
failed rows (migrating services); journal line.

0. D1 (owner, Hostinger): marketing bundle from 22f96990 per docs/releases/MARKETING-SOURCE-DEPLOYMENT.md,
   right before D3 (old gateway ignores the extra field). Or decide otherwise (section 11).
1. D2a alertmanager. Then `amtool config routes show` must show the Watchdog route ahead of the
   severity routes.
2. D2b prometheus. Then rules loaded;
   `PARKIO_LIVE_EVIDENCE_DIR=$R/d2-delivery-rules scripts/alerting-live-backup-stale-acceptance.sh delivery-rules`
   (read-only; may need a minute after the start). Keep the evidence dir outside the checkout.
3. D2c caddy, directly before D3. Then `https://api.parkio.dev/actuator/health` 200,
   `/actuator/prometheus` 404, `https://app.parkio.dev/` 200, CSP present.
4. D3 gateway (+ waitlist overlay). Then health 200, prometheus 404, Flyway gateway V6. The new container
   carries PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR, the inbox bind and group_add 987 (names and
   GID only). NR check (section 10). Smoke.
5. D4 web. A guard refusal stops the step. Then `https://app.parkio.dev/` 200.
6. D5 auth. Then Flyway auth V27, health, smoke (login), NR check.
7. D6a media. Then Flyway media V18, media readiness, smoke.
8. D6b parking (no pg_dump running). Then Flyway parking V42, column type timestamptz, signature counts
   as predicted by P1 B, NR check, smoke.
9. D7. Build the six one at a time with the wrapper, with build args IMAGE_VERSION, IMAGE_REVISION=22f96990
   and IMAGE_CREATED. Tag each `parkio/<svc>:sha-22f96990`; verify its OCI revision label. Record T_g. Then
   `up -d --no-build --no-deps` for the six. Then six healthy; Flyway user V20, notification V15,
   moderation V14, analytics V11; smoke. If a build fails, nothing is recreated and the moved tags are
   pointed back at their rollback tags.
10. E. Full smoke gate (section 8). U12 B: group parkio.user has consumed parkio.gamification.score up to
    the end offsets recorded at T_g. U12 C: parkio.dlt.user end offsets recorded; 0 unpublished
    gamification rows created before T_g. Final record (section 6). Then the live acceptances of the
    tasks (U03, U04, U05 PRIV-001A, U18 CL-F28). U06 BackupStale live execution stays unauthorized.

Expected during the window: short outages of the API (gateway), web, auth, media and parking. A
GatewayDown or CoreServiceDown alert fires only if a service stays down over 2 min. No silence is
planned.

## 8. Smoke (E)

From /opt/parkio-release, after D3, D5, D6a, D6b, D7 and as the E gate:

```bash
read -r -s -p 'seeded real-e2e password: ' PARKIO_REAL_USER_PASSWORD; echo; export PARKIO_REAL_USER_PASSWORD
export PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta PARKIO_GATEWAY_URL=https://api.parkio.dev \
       PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 PARKIO_ENV_FILE=/opt/parkio/docker/.env.azure-hosted-beta
./scripts/smoke-hosted-beta.sh --check-credentials
./scripts/smoke-hosted-beta.sh 2>&1 | tee "$R/smoke-<step>-$(date -u +%Y%m%dT%H%M%SZ).txt"; echo "smoke exit=${PIPESTATUS[0]}"
# end of the session: unset PARKIO_REAL_USER_PASSWORD
```

The smoke prints PASS/FAIL lines only (no e-mail or password). Gate: exit 0, `fail=0`. The E gate adds
the CSRF/refresh/logout checks of RELEASE-EXECUTION-20261008.md section E (values via `read -r -s`,
cookie jar in /dev/shm, removed afterwards).

## 9. Rollback (E) and its limits

Order: reverse of section 7. Gateway is rolled back before caddy (the new gateway relies on the edge
marker and Forwarded removal). Prometheus is rolled back before alertmanager (Watchdog). Auth is rolled
back before web. Gamification is rolled back only together with user-service (U12).

Commands use /opt/parkio's own inputs, which D0.2.3 G showed reproduce each previous container's config
hash. The exact line per service follows from G:

- live wrapper:
  `cd /opt/parkio && PARKIO_ENV_FILE=/opt/parkio/docker/.env.azure-hosted-beta ./scripts/parkio-prod-compose.sh up -d --no-build --no-deps <svc>`.
  Gateway adds `-f docker/docker-compose.waitlist-ops-inbox.yml` before `up`. Web runs the live wrapper's
  guards.
- the six, first: `docker tag <repo>:rollback-pre-22f96990 <running ref>` (from P2), then the live
  wrapper line;
- the five pins: the live wrapper's pin files name the previous digests, all present locally (D0.2.3).

| State of the service | Rollback |
|---|---|
| step not started | nothing |
| failed before any of its scripts committed (Flyway rows = P1/P6 baseline) | image/config rollback: compatible |
| caddy, prometheus, alertmanager, web, ai-validation | image/config rollback: compatible (no schema) |
| any script committed (gateway, auth, media, parking, user, notification, moderation, analytics) | image rollback refused by the rule above. Forward fix: keep the new release if it serves. Otherwise fix configuration and re-run the step, or ship a fixed image through the normal PR/review/CI/publish/pin path. If it harms users, stop that one container (the gateway answers 503 for its routes; for the gateway itself the API is down). A restore from the P5 backup is unauthorized and blocked by the restore guards; it needs a separate owner decision. |
| gamification after D7 | only with user-service, which has V20: forward fix |

Exceptions (an emergency start of a previous image on its migrated schema, as section 2's static review
allows) are owner decisions. They are not part of this plan unless pre-approved per service.

## 10. NR guard (F)

Facts (scripts/newrelic_log_pilot at 22f96990):

- parkio-nr-log-source.service (docker_log_source.py, poll 2 s) follows gateway-service, auth-service
  and parking-service by project/service labels and re-attaches to a replacement by itself. Logs of a
  new container are collected from attach time; there is no backfill, so a small gap.
- parkio-nr-log-continuous-guard.timer runs about every minute (OnUnitActiveSec=1min, AccuracySec=5s)
  while the transport is active. It requires exactly one running container per source with status
  `attached` to its current id, json-file 10m/5 logging on each, storage bounds, and 5 GiB free on the
  budget filesystem. Any failure stops parkio-nr-log-continuous.service, and stop_pilot.sh stops the
  source helper and the two transport containers. Nothing restarts them; re-activation is the documented
  procedure. Applications are not affected.
- A recreate has a window with no running source container (stop, create, start, attach). It lasts from
  a few seconds up to the stop timeout. A guard run inside that window stops the transport. Timing D3/D5/D6b right after a guard run
  narrows the chance but guarantees nothing. The D7 builds and pulls also reduce free space (P1 F).

Options (exact scope; none changes the project without approval):

| Option | Scope | Effect |
|---|---|---|
| N0 observe only (default if no decision) | no unit or container change; read-only check after D3, D5, D6b | the guard may stop the transport (and the source helper); NR collection of all three sources stays off until an approved re-activation |
| N1 pause the guard timer for D3-D6b | `sudo systemctl stop parkio-nr-log-continuous-guard.timer` before D3; after D6b a read-only check that the helper is `attached` to the three new ids (root-owned status files: sudo read), then `sudo systemctl start ...timer`; the first run must PASS | storage, disk and source invariants unguarded for the window; transport and helper keep running |
| N2 planned stop | `sudo systemctl stop parkio-nr-log-continuous.service` before D3 (its ExecStop runs stop_pilot.sh); re-activation per procedure after E | no collection D3-E; re-activation needs approval |
| N3 accept and pre-approve re-activation | as N0, plus approval now to re-activate after E if the guard stopped it | as N0 |

## 11. Decisions requested from the owner

1. Approve this scope and order (D2a-D2c, D3 with the waitlist overlay, D4, D5, D6a, D6b, D7 via the
   production wrapper: build + `up --no-build --no-deps`).
2. D1: marketing bundle right before D3 (recommended), or PARKIO_WAITLIST_CONSENT_REQUIRED=false for the
   window (env change; submissions recorded as unversioned-client), or accept 400s until D1.
3. Records as in section 6, including creating /var/lib/parkio/azure-hosted-beta at the final record.
4. Rollback after a committed migration: forward fix only (recommended). Or name pre-approved exceptions.
   Or ask for a local rehearsal of the previous pinned images against the migrated schema first.
5. NR option: N0, N1, N2 or N3.
6. Heartbeat (if P1 C says set) and V41 (if P1 B shows an Istanbul signature): decided after P1.
7. U12-A rows/records (if P1 G is not zero): decided per row after P1.
