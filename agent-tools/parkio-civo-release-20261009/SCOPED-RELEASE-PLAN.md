# Scoped release plan - parkio-civo-prod, release 22f96990 (v2, for owner approval)

Status: v2 after P1. Nothing in sections 10-13 runs before the owner approves this plan and its decisions
(section 15) and the preparation gates (section 9) pass. Preparation runs one step at a time, each as an
immutable file with its SHA-256; the operator returns sanitized output after each step. v1 (draft) is commit
f8be3a2a.

Inputs: D0.1 (release checkout /opt/parkio-release clean at 22f9699038cdae15ffff5dd7433c4a64aed0a951;
/opt/parkio bf9cad51 + 100 changes, untouched), D0.2.1 PASS, D0.2.2 and D0.2.3 exit 0 (14 services; all 14
previous config hashes reproduced from recorded inputs), P1 exit 0 (P1-facts-20261009T151919Z.txt; the owner's
verified results 1-10 of 2026-10-09).

Fixed constraints: no whole-project `up`, no bind mount, no /etc/fstab change; databases, Kafka, Redis,
MinIO/minio-setup, ClamAV, Grafana, blackbox-exporter, node-exporter, kafka-exporter and project
parkio-nr-log-continuous untouched unless a specific action is approved; /opt/parkio not changed (no pull, reset,
checkout, file edit, script replacement); only the production wrapper with named services and `--no-deps`; never
`--skip-smoke`, `--allow-dirty` or a guard bypass; no flag change (restricted flags stay default-off,
PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED stays true, PARKIO_WAITLIST_CONSENT_REQUIRED stays unset = true); no
offset reset or record replay without a specific decision; Azure stopped; no production data restore; #104 HOLD;
verifiedCoverage=false. Evidence: `R=~/parkio-release-20261009` (mode 700), outside both checkouts.

## 1. Scope

| Step | Services (named, `up -d --no-build --no-deps`) | Migrations |
|---|---|---|
| D1 | marketing bundle on Hostinger (outside the host), before D3 | none |
| D2a, D2b, D2c | alertmanager, then prometheus, then caddy | none |
| D3 | gateway-service, with `-f docker/docker-compose.waitlist-ops-inbox.yml` | gateway V6 |
| D4 | web (the wrapper's web guards run) | none |
| D5 | auth-service | auth V24-V27 |
| D6a, D6b | media-service, then parking-service | media V14-V18; parking V41-V42 |
| D7 | build, then recreate: user, gamification, notification, moderation, ai-validation, analytics | user V20, notification V15, moderation V14, analytics V10-V11 |
| E | smoke gate, U12 B/C, final record, live acceptances | none |

Every host command runs from `/opt/parkio-release` as
`PARKIO_ENV_FILE=/opt/parkio/docker/.env.azure-hosted-beta ./scripts/parkio-prod-compose.sh ...` (the canonical
list plus the Civo Alertmanager overlay). Gamification and ai-validation have no pending migration.

## 2. Migration effects (A)

Flyway on PostgreSQL runs each script in its own transaction; a failing script rolls back alone, earlier scripts
of the same start stay applied. All services keep the Spring Boot 3.5.15 defaults (`ignoreMigrationPatterns`
`*:future`, `ddl-auto: validate`). The previous container is stopped before the new one migrates; each database
has one owning service, so the only possible lock contenders are a running pg_dump (nightly backup at 03:30 or
P5) and an operator session. Sizes from P1: every touched table is tiny, so every script finishes in well under
a second. The outage per service is the container restart (about 30-90 s until healthy), not the migration.

| Service (previous source) | Scripts | Data change / destructive | Size (P1) | Previous image on the migrated schema (static review, not tested) |
|---|---|---|---|---|
| gateway (f9710aa0) | V6 | adds `consent_text_version NOT NULL DEFAULT 'legacy-unversioned'`; no row rewritten | waitlist_interest ~4 rows | starts; rows it writes get legacy-unversioned |
| auth (b10c1f7c) | V24-V27 | additive columns, CHECK, partial index, four tables, NOT VALID FK | erasure_requests ~0 | starts; nothing writes the new structures while the erasure flags are unset |
| media (4a9ba218) | V14-V18 | V18 drops the global `uq_media_files_checksum`, adds the per-owner partial unique index | media_files ~1; V18 duplicates 0 | starts; the old global duplicate rule returns |
| parking (1dd2d5bc) | V41-V42 | V41 UPDATE (section 3) + `last_confirmed_at` TIMESTAMP -> TIMESTAMPTZ (rewrite); V42 two indexes | parking_sessions 0 rows; search logs ~108, view logs ~2 | starts; same Instant mapping and Hibernate |
| user (bf9cad51) | V20 | three nullable version columns | user_trust_profiles ~1 | starts; does not maintain the versions (U12 residual class) |
| notification (bf9cad51) | V15 | additive outbox columns, partial index, audit table | outbox_events ~0 | starts |
| moderation (bf9cad51) | V14 | drops two UNIQUE constraints, recreates them as partial unique indexes | ~0 rows; duplicates 0 | starts |
| analytics (bf9cad51) | V10-V11 | additive outbox columns, index rebuilt, audit table | outbox_events ~0 | starts; no relay for rows queued while erasure is on (it stays off) |

All databases have 0 failed Flyway rows (P1).

### Rollback and forward-fix limits, and how they are enforced

- Rule (repo F-INV-3, `scripts/lib/rollback_schema_gate.py`, "There is no override"): an image rollback is
  compatible only when the live schema holds no script the target release lacks.
- Enforcement in this path: P6 records, per database, the applied script list read from
  `flyway_schema_history` before D2 (the baseline). The rollback command of the release tool (T1, section 11)
  reads the same table at rollback time and refuses an image rollback of a service whose database holds any
  successful script outside its baseline. It has no override flag. The refusal names the forward-fix path.
- So, per service: before its migrations commit, and for alertmanager, prometheus, caddy, web and ai-validation,
  the image/config rollback is compatible. After a commit (gateway, auth, media, parking, user, notification,
  moderation, analytics), only forward fix: keep the new release if it serves; fix configuration and re-run the
  step; or ship a fixed image through PR, review, CI, publication and a pin change (separate approval).
  Gamification is rolled back only together with user-service (U12), so after V20 it is forward fix too.
- Containment if a migrated service harms users: stop that one container with the wrapper (`stop <svc>`); the
  gateway then answers 503 for its routes (for the gateway itself, the API is down). A restore from P5 is
  unauthorized and blocked by the restore guards; it would need a separate owner decision.
- Not established: no test has run a previous image on its migrated schema. Exceptions (an emergency start of
  a previous image) are owner decisions and not part of this plan unless decision 4 pre-approves them.

## 3. parking V41 and the session time zone (A)

V41's only zone-dependent statement is its UPDATE: rows where `last_confirmed_at = started_at AT TIME ZONE
current_setting('TimeZone')` and not equal to the UTC form. In a UTC session that condition is contradictory, so
it changes no row. Its ALTER uses an explicit `AT TIME ZONE 'UTC'`. pgjdbc sends the JVM default zone as the
session `TimeZone` (overriding ALTER DATABASE/ROLE settings), and Flyway uses the service's DataSource (the
default profile sets no flyway url or init SQL). Source at 22f96990: no `TimeZone.setDefault` and no
`user.timezone` in main code, compose files, Dockerfiles or scripts; the image runs `java -jar` (JAVA_OPTS is not
read).

P1: database default Etc/UTC, no ALTER DATABASE/ROLE TimeZone, column still `timestamp without time zone`,
parking_sessions exactly 0 rows. P2 section F determines the NEW image's effective zone from its own config and
files (user.timezone, then TZ, then /etc/timezone, then /etc/localtime) combined with the release model's
environment. Gate before D6b (in T1): row count read again; 0 rows -> V41 has no data effect; rows present ->
the new image's zone must be UTC-equivalent (P2) and the Istanbul-signature count 0, else stop. Starting
parking with a non-UTC zone is not proposed.

## 4. Configuration differences (B)

| File (live -> release) | Change | Live behaviour kept |
|---|---|---|
| alertmanager/render-config.sh | live = MODIFIED working copy, blob a9327cf8 = the file of d0da725e (ancestor of the release). Release 7ca44eda adds the U06 Watchdog route and always a `null` receiver | yes: same receivers, routes (default warning; critical -> critical; warning -> warning), require-receiver check, inhibit rules. Heartbeat URL and secret are absent (P1), so Watchdog ends at `null`; no heartbeat is sent |
| alertmanager/alertmanager.yml | base gains the Watchdog -> null route | yes |
| prometheus/alerts.yml | + Watchdog, AlertmanagerNotificationsFailing, PrometheusNotificationsFailing; absolute runbook URLs | yes; Watchdog must not reach the live `warning` receiver, hence D2a before D2b |
| prometheus/municipal-source-health-alert-rules.yml | + two rules on new parking metrics | silent until D6b |
| prometheus/operational-readiness-alert-rules.yml | runbook URLs | yes |
| prometheus/prometheus.yml | + scrape job alertmanager:9093 | yes |
| caddy/Caddyfile | CSP img-src narrowed; /actuator/prometheus blocked at the edge; `X-Parkio-Edge-Relay`, `-Forwarded` | yes; the live web (pre-#203) misses only the auth-page photo between D2c and D4 |

D2 keeps the alertmanager, prometheus and caddy images (P2 section B verifies that the same reference resolves to
the running image). Their rollback is the live wrapper with the live files.

## 5. U12 (offsets, drain)

P1: open dead-lettered gamification outbox rows 0; unpublished not-dead-lettered rows 0; parkio.dlt.user
retained 0 (p0-p2); group parkio.user reported lag 0 where it has committed offsets; no committed offset on
parkio.auth.user (1 partition), parkio.gamification.score (6), parkio.moderation.action (3).

Consumer policy from source (identical at bf9cad51 and 22f96990): all three listeners of group `parkio.user` use
`authUserKafkaListenerContainerFactory`, whose consumer factory hard-codes `auto.offset.reset=earliest` and
`enable.auto.commit=false` (environment properties cannot change it), with `AckMode.MANUAL`; every listener path
acknowledges, and the DefaultErrorHandler commits after publishing a poison record to parkio.dlt.user. With
`earliest`, Spring Kafka commits no initial position on assignment. So a partition without a committed offset is
one from which this group has acknowledged nothing since its offsets last existed, and a consumer without a
committed offset starts at the log start. Consequence for D7: if such a partition retains records, the new
user-service reads them from the log start (a replay, deduplicated by the inbox where its rows still exist); for
parkio.gamification.score those would be version-less pre-U12 events (event-contracts.md "Ordering (U12)").

P3 (read-only) reports per partition of the three topics: log start, log end, committed offset or none,
retained records, records a restart would read; plus the group state and members and the broker's
`offsets.retention.minutes`. Gate U12-A: dead-lettered rows 0, DLT retained 0, and 0 records a restart would
read on parkio.gamification.score; for the other two topics a non-zero count is reported for decision. Expected
(an active `earliest` consumer would already have read and committed any retained record): 0. Anything else
stops before D7 for a per-partition decision; no offset reset or replay without it.

U12 B/C (after D7, in T1 `final`): T_g = start of the new gamification container. B: for every partition of
parkio.gamification.score, the group's committed offset reaches the log end recorded at T_g (or the partition
held no records then). C: parkio.dlt.user end offsets at E equal those recorded before D7; gamification outbox has
0 dead-lettered rows and 0 unpublished rows created before T_g.

## 6. Fresh backup and offsite evidence (C)

P1: newest backup 2026-10-09T03-30-01Z COMPLETE; metrics last_success=1, offsite_last_success=1,
encryption_enabled=1, production_mode=1; the live scripts (bf9cad51-based, several locally modified) write no
offsite receipt. Historical metrics do not prove a fresh upload.

P5, outside the 03:30 cron, after the owner's go for the deployment window:

1. P5a, the live backup exactly as cron runs it, as root, same lock and log:
   `sudo -H flock -n /var/lock/parkio-backup.lock /opt/parkio/scripts/run-production-backup.sh >> /var/log/parkio-backup.log 2>&1; echo "backup exit=$?"`.
   It writes the stamp under BACKUP_DIR, its manifest under /opt/parkio/backup-artifacts (gitignored, as every
   night) and the textfile metrics; PARKIO_SLACK_BIZ_ENABLED is unset, so no Slack status. The live scripts are
   used as they are, not replaced.
2. P5b, read-only verification script (root; published as a file): new stamp newer than the P5a start;
   `COMPLETE` + `SHA256SUMS` verify locally (`sha256sum -c --strict`); no plaintext dump; metrics for
   azure-hosted-beta = 1 with timestamps after the start; then the offsite copy of exactly that stamp is pulled
   with the live library's own `parkio_backup_offsite_pull` into a private temporary directory, its
   `SHA256SUMS` and `COMPLETE` compared byte for byte with the local ones and every file checked with
   `sha256sum -c --strict`; the temporary copy is deleted. Output: stamp, file count, total bytes, SHA-256 of
   SHA256SUMS, "offsite copy identical". A receipt JSON goes to `R/backup/` (no account, bucket or URL).
3. Gate: exit 0 for both; the deployment (D2a) starts within 2 hours of P5a and outside 03:00-04:30 host time.

## 7. Marketing D1 (compatibility, artifact, target, verification, rollback)

Findings (public files fetched 2026-10-09, git blob comparison):

- The live parkio.dev is not a commit of the release. It is the tree of PR #93's head db3f4a3d (OPEN, not merged
  into api, "match waitlist name and email field styles") plus two hand edits that are in no commit:
  `i18n.js` success text (TR and EN: "first time: verify your e-mail; already confirmed: nothing to do") and the
  landing page's `i18n.js?v=w01m2` cache-buster. Its CSP matches db3f4a3d's `.htaccess` (script `unsafe-inline`,
  no HSTS).
- Live `waitlist.js` sends no consentTextVersion. The new gateway (CL-F18, PARKIO_WAITLIST_CONSENT_REQUIRED unset
  = true) answers 400 without `consent: true` and a registered `consentTextVersion`.
- The release tree (web/marketing at 22f96990) sends both (`waitlist-consent-v1`) and is compatible with the OLD
  gateway too: its SubmitWaitlistRequest ignores unknown JSON fields (Spring Boot default; no custom
  ObjectMapper), and both gateways use the error codes the new form maps (WAITLIST_EMAIL_DELIVERY_FAILED,
  WAITLIST_TOKEN_INVALID, WAITLIST_ADMISSIONS_DISABLED). So D1 can go live any time before D3, ideally well
  before the window.
- The release tree also drops script `unsafe-inline` and adds `Strict-Transport-Security: max-age=31536000`
  (CL-F39.5; browsers keep HSTS up to a year and a rollback does not withdraw it); token pages get distinct
  invalid/server-error texts; a11y fixes. It does NOT contain PR #93's field style or the live success text, and
  its landing page keeps `waitlist.js?v=w01l7`, the same cache-buster as live although waitlist.js changed.
  Live waitlist.js is served with `Cache-Control: max-age=300` through Hostinger's CDN, so a stale copy can
  survive the upload unless the cache is purged and the content verified.

Options:

- M2 (recommended): a small marketing source PR into api: PR #93's change, the live success text (TR/EN), and new
  cache-busters for the changed assets (waitlist.js, i18n.js, styles.css) on every page; validator and marketing
  Playwright suite; independent review; merge. Artifact = `scripts/package-marketing-waitlist-bundles.sh launch`
  from that merge commit. Needs approval for the source work and the merge. No live regression.
- M1: the release tree as it is. Artifact = the same packaging at 22f96990: 19 files, listed with their SHA-256
  in `marketing-m1-manifest.sha256` next to this file. Live regressions: PR #93's field style and the live
  success text; reliance on a CDN purge for waitlist.js.

Procedure (either option; Hostinger hPanel, by the owner):

1. Package from a clean checkout of the chosen commit, outside any other checkout:
   `git worktree add --detach /tmp/parkio-mkt <SHA> && cd /tmp/parkio-mkt && scripts/package-marketing-waitlist-bundles.sh launch /tmp/parkio-mkt-out`,
   then compare every file with the published manifest.
2. Back up `public_html` with Hostinger's facility (File Manager compress + download, or a backup point) and
   record its name; that archive is the exact rollback source (the live tree exists in no commit).
3. Upload the bundle's contents into `public_html` (not its parent), overwriting.
4. Purge the Hostinger CDN cache; wait 5 minutes (max-age 300).
5. Verify (public GETs; a small verification script will be published with T1): `/`, `/privacy/`, `/terms/`,
   `/waitlist/confirm/`, `/waitlist/unsubscribe/`, `/robots.txt`, `/sitemap.xml`, `/404.html` answer 200; each
   served file equals the manifest (line endings normalized); landing meta `parkio-waitlist-mode=api`;
   waitlist.js carries `consentTextVersion` and `waitlist-consent-v1`; CSP without script `unsafe-inline`; HSTS
   present; `http://` redirects to https; a browser shows no CSP violation on `/` and `/waitlist/confirm/`. No
   live submission is made (it would write a row and send e-mail).
6. Rollback: before D3, re-upload the step-2 archive (compatible with the old gateway); HSTS stays in browsers.
   After D3, a marketing rollback would bring the 400s back: fix forward instead.

## 8. NR guard (F)

Facts (scripts/newrelic_log_pilot; identical at a41f2f7d, the documented install revision, and at 22f96990). The
guard (`continuous_guard.sh`, timer every 1 min, AccuracySec 5 s, runs only while the transport is active)
resolves the CURRENT running gateway, auth and parking containers by labels on every run and requires exactly one
running container per source, json-file 10m/5 logging, readable `docker logs`, and helper status `attached` with
the current container id; plus transport containers running and not OOM, storage bounds and 5 GiB free. Any
failure stops parkio-nr-log-continuous.service, and stop_pilot.sh stops the source helper and the two transport
containers. Nothing restarts them. A recreate leaves a window of a few seconds (old container exited, new one not
yet attached; the helper polls every 2 s). Estimate: with about 5 s per window and one guard run per minute,
each of D3, D5 and D6b has roughly a 1 in 12 chance to stop the transport, about 1 in 5 over the three. Timing
guarantees nothing. P1: release logging equals live (json-file 10m/5), 82.6 GiB free, guard last result success.

Common read-only check (T1 runs it after D3, D5, D6b):
`systemctl is-active parkio-nr-log-source.service parkio-nr-log-continuous.service parkio-nr-log-continuous-guard.timer`
and `sudo env PARKIO_NR_COMPOSE_PROJECT=parkio PARKIO_NR_SOURCE_ROOT=/var/lib/parkio-nr-log-continuous/source/logs PARKIO_NR_SOURCE_STATE_ROOT=/var/lib/parkio-nr-log-continuous/source/state /opt/parkio-nr-log-continuous/current/scripts/newrelic_log_pilot/resolve_production_sources.sh --check-live-helper`
(three `live-helper=attached` lines).

| Option | Exact actions | Effect |
|---|---|---|
| N1 pause the guard timer D3-D6b (recommended) | before D3: `sudo systemctl stop parkio-nr-log-continuous-guard.timer`, then `systemctl is-active parkio-nr-log-continuous-guard.service` must say inactive. After D6b: the common check must show three attached; then `sudo systemctl start parkio-nr-log-continuous-guard.timer` and `sudo systemctl start parkio-nr-log-continuous-guard.service`; `journalctl -u parkio-nr-log-continuous-guard.service -n 3 --no-pager -o cat` must end with `continuous-guard PASS`. If the check fails, the timer stays stopped and the owner decides | no trip; collection continues through the deploy (new containers from attach time, no backfill); the guard's storage, OOM and disk-floor checks are paused for that window (the budget gate keeps enforcing budgets; T1 checks free space before each step) |
| N3 no change, pre-approved re-activation | no unit change. If a check after D3/D5/D6b shows the transport stopped, after D6b run the documented activation (new-relic-log-continuous-operation.md, "Prepared installation and lifecycle"): resolve sources into /run/parkio-nr-log-continuous/sources.env, start the source unit, `--check-helper`, `--check-live-helper`, start the transport, the guard timer and one guard run | protections never paused; by the estimate above, about 1 in 5 chance of losing NR logs from the trip until re-activation after D6b |
| N0 no change | as N3 without pre-approval | a trip leaves collection off until a later approval |
| N2 planned stop | before D3: `sudo systemctl stop parkio-nr-log-continuous-guard.timer parkio-nr-log-continuous-guard.service`, then `sudo systemctl stop parkio-nr-log-continuous.service parkio-nr-log-source.service` and confirm no container of the project runs; after E: the documented activation | no collection D3-E; the most manual steps |

## 9. Preparation gates (C), one at a time

| Step | What | Changes |
|---|---|---|
| P1 | read-only facts | done (exit 0) |
| P2 | done 2026-10-09T16:12:29Z, 12/12 gates, exit 0 (eclipse-temurin:21-jre absent locally). `p2-release-images.py`: 11 rollback tags `<repository>:rollback-pre-22f96990` on the running images (all checked before the first is written; an existing different tag, or a running image that has lost its name on a containerd image store, stops it before any change); the five pins pulled by digest and verified (classic store: image id = accepted config id; containerd: manifest digest = pin, config digest compared when exposed; RepoDigests, linux/amd64, OCI revision a658edcb / 843ae7cb); D2 images unchanged; the wrapper's three web guards dry-run on the new web image; the new parking image's effective zone; base images and buildx (reported, not pulled); free space | local tags and pulls; containers created and never started, then removed |
| P3 | `p3-readonly-gates.py`: release checkout clean and its env-file copy (presence, identical or not); preflight of the live env file (`--deployment-profile azure-hosted-beta --skip-compose`, reduced to categories, FAIL/WARN subject names and the summary line; the deployed model is the wrapper's, rendered with `config --quiet`, canonical and with the waitlist overlay); Kafka per partition for all 17 groups the release recreates (section 5) and every parkio.dlt.* topic; U12-A; V41 rows gate (section 3); NR state; free space; newest backup age. `smoke --check-credentials` only checks that the password variable is set (no login, no network), so it stays at the start of the deployment session (section 12) and P3 asks for no password | none |
| P4 | U12-A decision, only if P3 is not zero | only if decided |
| P5 | fresh backup + offsite evidence (section 6) | a backup, its metrics and manifest, as nightly |
| P6 | planned record (T1 `plan`): baseline Flyway scripts per database, previous containers (id, image, config hash, recorded files and env file), the rollback method per service re-verified (live wrapper or recorded files, as D0.2.3 G), target hashes and images, P2 and P5 references | evidence files only |

## 10. Records (D)

- `R/planned/planned-<stamp>.json` (P6): `"status": "planned"`, never copied to /var/lib/parkio. Targets are
  marked as targets; nothing in it claims an applied state.
- `R/journal.tsv`: append-only; one line per event: UTC time, step, service, event (start, ok, fail, rollback,
  refused), previous and new container id, image id, config hash, health, Flyway scripts applied after the step
  as read from the database, smoke result and log SHA-256.
- `R/state.json`: rewritten atomically after every event from the journal and database reads: `in-progress`,
  `partial` (a step failed; lists services on 22f96990 and on the previous release), `complete`, `rolled-back`;
  applied scripts per database exactly as read. A failed step stays `partial`; nothing else claims more.
- Final record, only after D7, the E smoke gate, U12 B/C and the Flyway check pass: a repo-compatible manifest
  (gitSha 22f96990, deploymentProfile azure-hosted-beta, composeFiles = the canonical list + Civo overlay, gateway
  overlay noted, pinnedImages = the five digests, images = the six `parkio/<svc>:sha-22f96990` with ids,
  migrationVersions = the scripts read from each database, operator, smoke log and journal hashes). The owner
  creates the directory first (`sudo install -d -m 0755 -o civo -g civo /var/lib/parkio/azure-hosted-beta`,
  decision 5); T1 writes `deployed-manifest.json` there atomically and copies it to `R/final/current.json`.
  /opt/parkio/deploy-artifacts stays absent; nothing is written into either checkout.

## 11. Deployment sequence (after approval; one T1 command per step, output returned)

T1 (`release-step.py`, published with a self-test before D2) runs each step as: pre-checks; print the exact
wrapper command; run it; post-checks; journal and state. Pre-checks: release checkout clean at 22f96990; the
previous step `ok`; no pg_dump running (pg_stat_activity) and no backup process; free space on / >= 12 GiB; the
service's current container recorded. Post-checks: healthy within its timeout; image id as expected; config hash
= the release model's; for migrating services the applied scripts read from the database = the release's and 0
failed rows; NR check where applicable.

| Step | Wrapper command (from /opt/parkio-release, with PARKIO_ENV_FILE=/opt/parkio/docker/.env.azure-hosted-beta) | Extra checks | Smoke |
|---|---|---|---|
| D1 | (Hostinger, section 7) | D1 verification | - |
| D2a | `./scripts/parkio-prod-compose.sh up -d --no-build --no-deps alertmanager` | `amtool config routes show`: Watchdog -> null ahead of the severity routes; live receivers present | - |
| D2b | `... up -d --no-build --no-deps prometheus` | rules loaded; `PARKIO_LIVE_EVIDENCE_DIR=$R/d2-delivery-rules scripts/alerting-live-backup-stale-acceptance.sh delivery-rules` | - |
| D2c | `... up -d --no-build --no-deps caddy` (directly before D3) | api health 200, /actuator/prometheus 404, app 200, CSP present | - |
| (N1) | stop the guard timer (section 8) | | |
| D3 | `./scripts/parkio-prod-compose.sh -f docker/docker-compose.waitlist-ops-inbox.yml up -d --no-build --no-deps gateway-service` | gateway V6; export-dir key, inbox bind, group_add 987 present (names and GID only) | yes |
| D4 | `... up -d --no-build --no-deps web` (guards bind the verified image) | app 200 | - |
| D5 | `... up -d --no-build --no-deps auth-service` | auth V24-V27 | yes |
| D6a | `... up -d --no-build --no-deps media-service` | media V14-V18; media readiness | yes |
| D6b | `... up -d --no-build --no-deps parking-service` (V41 gate, section 3) | parking V41-V42; column timestamptz; rows unchanged | yes |
| (N1) | resume the guard (section 8) | | |
| D7 | for each of the six, one at a time: `... build --build-arg IMAGE_VERSION=22f96990 --build-arg IMAGE_REVISION=22f9699038cdae15ffff5dd7433c4a64aed0a951 --build-arg IMAGE_CREATED=<UTC> <svc>`, then `docker tag parkio-<svc>:latest parkio/<svc>:sha-22f96990` and an OCI revision check; record the U12 offsets and T_g; then `... up -d --no-build --no-deps` the six | user V20, notification V15, moderation V14, analytics V10-V11; six healthy | yes |
| E | smoke gate (section 12) + CSRF/refresh/logout checks; U12 B/C; final record; then the live acceptances of U03, U04, U05 PRIV-001A, U18 CL-F28 (U06 BackupStale stays unauthorized) | | gate |

D7 build failure: nothing is recreated; every moved `parkio-<svc>:latest` is pointed back at its rollback tag
(`docker tag parkio-<svc>:rollback-pre-22f96990 parkio-<svc>:latest`), journal `fail`, state `partial` (D2-D6b
done). The build needs network for Gradle, Maven Central and apt; base images are used from the local store when
present. P2: eclipse-temurin:21-jre is absent, so the D7 preparation pulls it once before the window and records its
digest (a pull of a base image moves no service tag).

Expected during the window: short outages of the API (D3), web (D4), auth (D5), media (D6a), parking (D6b) and
the six (D7). GatewayDown/CoreServiceDown alert only if a service stays down over 2 minutes. No silence planned.

## 12. Smoke and E gate (E)

Once per session, before D3 (values never echoed or logged):

```bash
cd /opt/parkio-release
read -r -s -p 'seeded real-e2e password: ' PARKIO_REAL_USER_PASSWORD; echo; export PARKIO_REAL_USER_PASSWORD
export PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta PARKIO_GATEWAY_URL=https://api.parkio.dev \
       PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 PARKIO_ENV_FILE=/opt/parkio/docker/.env.azure-hosted-beta
./scripts/smoke-hosted-beta.sh --check-credentials
```

T1 runs, after D3, D5, D6a, D6b, D7 and as the E gate:
`./scripts/smoke-hosted-beta.sh 2>&1 | tee "$R/smoke-<step>-<UTC>.txt"` from /opt/parkio-release, and records
exit code, PASS/FAIL counts and the log's SHA-256. Gate: exit 0 and `fail=0`. A smoke failure stops the sequence
with state `partial`; there is no skip. End of session: `unset PARKIO_REAL_USER_PASSWORD`.

## 13. Rollback commands (E) and limits

Order: reverse of section 11. Gateway before caddy; prometheus before alertmanager; auth before web; gamification
only with user-service. T1 `rollback <svc>` first applies the schema rule of section 2 (refusal = forward fix),
then uses the previous container's own inputs recorded in P6: the live wrapper when it reproduces the recorded
config hash, otherwise the recorded file list (and, for web, the rebuilt binding), exactly as D0.2.3 G verified:

- typical line: `cd /opt/parkio && PARKIO_ENV_FILE=/opt/parkio/docker/.env.azure-hosted-beta ./scripts/parkio-prod-compose.sh up -d --no-build --no-deps <svc>`;
  gateway adds `-f docker/docker-compose.waitlist-ops-inbox.yml`; web runs the live wrapper's guards;
- the six: first `docker tag parkio-<svc>:rollback-pre-22f96990 parkio-<svc>:latest`;
- the five pins: the live files name the previous digests; their images stay present under the rollback tags.

| Service state | Rollback |
|---|---|
| step not started | nothing |
| step failed before any script committed (database = P6 baseline) | image/config rollback (compatible) |
| alertmanager, prometheus, caddy, web, ai-validation | image/config rollback (compatible) |
| any script committed | refused by T1; forward fix (section 2) |
| gamification after D7 | only with user-service -> forward fix |
| marketing | before D3: re-upload the backup; after D3: forward fix |

## 14. P2 operator command

```bash
C=<commit>
P=agent-tools/parkio-civo-release-20261009/p2-release-images.py
H=<sha256>
curl -fsSL -o /tmp/p2-release-images.py "https://raw.githubusercontent.com/ADBERILGEN35/parkio/$C/$P"
echo "$H  /tmp/p2-release-images.py" | sha256sum -c -
python3 -I -c 'import ast,sys; ast.parse(open(sys.argv[1]).read())' /tmp/p2-release-images.py && echo "syntax ok"
python3 -I /tmp/p2-release-images.py; echo "exit=$?"
```

The commit and SHA-256 are given with the publication. Expected end: `P2 COMPLETE`, exit 0.

## 15. Decisions requested from the owner

1. Scope and order of sections 1 and 11 (D7 via wrapper build + `up --no-build --no-deps`).
2. D1: M2 (recommended: small marketing PR, review, merge, then upload) or M1 (release tree as is, with the two
   regressions); in both, HSTS max-age=31536000 for parkio.dev goes live (not withdrawn by a rollback).
3. NR: N1 (recommended), N3, N0 or N2, with the exact actions of section 8.
4. Rollback after a committed migration: forward fix only, enforced by T1 (recommended); or named pre-approved
   exceptions; or a local rehearsal of the previous images on migrated schemas first.
5. Records of section 10, including the directory `/var/lib/parkio/azure-hosted-beta` (owner civo:civo, 0755,
   or another owner/group you name).
6. P5 method of section 6, including the pull-back of the encrypted stamp into a temporary directory on the host
   (deleted after the comparison), and the 2-hour window.

Settled by the facts (no decision unless P2/P3 change them): heartbeat stays disabled (URL and secret absent);
V41 has no data effect (0 rows, UTC) subject to the D6b gate; U12-A pending P3.
