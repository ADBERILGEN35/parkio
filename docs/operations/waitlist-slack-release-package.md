# Release package — waitlist Slack operational notifications (PR #74)

**Verdict:** ready for human review and merge decision. **Not authorised for
activation.** No production change, real Slack message, email, merge or
deployment has been made. New Relic and PR #68 were not touched.

Design, guarantees and runbooks: [waitlist-slack-notifications.md](waitlist-slack-notifications.md).
Evidence bundle: `agent-tools/parkio-waitlist-slack-release-prep-01/20260922T114456Z/`.

## 1. Identities

| Item | Identity |
|---|---|
| Base | `origin/api` @ `3d3ff3c9` (includes merged PR #68) |
| Code under test (gateway + relay runtime) | `0d256394163a4d9ab78836b4af772a0125eb842b`. Later commits on the branch change only e2e/test tooling, the Civo deploy package, docs and evidence: `git diff --stat 0d256394 <head> -- services scripts/slack_biz` lists only `deploy/civo/*` and `waitlist-e2e/*`. |
| Gateway image under test (local build, real `services/gateway-service/Dockerfile`, not pushed) | `parkio-gateway-waitlist-e2e:0d256394163a`, image id `sha256:ce14047f904c8e01173482218e498ed4cf61561299f7c00506fa3c7bc15bde18`, label `org.opencontainers.image.revision=0d256394…`, `USER parkio` (uid 10001) |
| Previous production gateway (rollback target) | `ghcr.io/adberilgen35/parkio/gateway-service@sha256:5c66e0fb010c25dc2029a93f0dab146caee1f442ba74140f66cd397a1e721e2c`, revision `efe241952a106ff126edc7b6ede97e4e7a982904` (current pin in `docker/docker-compose.gmp-release-pins.yml`) |
| Deployable gateway image | **Not produced yet.** It must come from the release pipeline at the merged SHA. Accept it only if its `org.opencontainers.image.revision` equals that SHA. Image bytes are not reproducible, so the digest will differ from the local test build. |
| PostgreSQL | `postgres:16-alpine` (production default `POSTGRES_IMAGE`). Tested `postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea` = server 16.15 (IT and e2e). An earlier IT run on 16.14 also passed. The live Civo server version was **not probed**. |
| Relay runtime | Python stdlib only. e2e `python:3.12-alpine` (`sha256:4c47124a…`); systemd check Ubuntu 24.04, Python 3.12.3, systemd 255 |
| Redis (e2e only) | `redis:7-alpine` (`sha256:858f009f…`) |

## 2. Verification results

| Suite | Result | Evidence |
|---|---|---|
| Gateway unit tests (`:services:gateway-service:test`) | 210 / 0 failures | `postgres-it/gradle-summary.txt` |
| **PostgreSQL IT** `WaitlistOpsNotificationPostgresIT` (Flyway V1–V4, Spring `JdbcTransactionManager`) | 6 / 0 failures: committed → 1 row · outer rollback → neither · PostgreSQL-raised error inside savepoint → confirmation committed + `record_failed` · repeat + replay → 1 row · V4 constraints enforced · retention keeps `PENDING` | `postgres-it/TEST-…PostgresIT.xml` |
| Negative control (savepoint removed) | Fails as expected with `UnexpectedRollbackException`; code restored | `postgres-it/negative-control-*` |
| Relay acceptance `run_waitlist_acceptance.py` | 18/18 | `relay-acceptance/` |
| Existing relay suites (PR #54) | 15/15; 13 PASS + 1 NOT_EXECUTED (live Kafka, unchanged) | `relay-acceptance/y03*` |
| **Isolated Docker e2e** `run-e2e.sh` (internal network, mock Slack) | 11/11: UID/perms · committed · duplicate · relay restart · crash between export and mark · SIGKILL recovery · relay disabled · gateway disabled · **rollback to previous artifact** · roll-forward · privacy scan | `e2e/e2e-summary.md`, `e2e/*.log`, `e2e/slack-*.json*` |
| **Civo package under systemd** `run-civo-systemd-check.sh` | 11/11; exposure consumer 0.6 SAFE, worker 1.3 OK | `civo-systemd/` |
| CI on PR #74 | See the PR checks. The new PostgreSQL IT runs in the existing "Integration tests (Testcontainers)" job. The relay Python suites have no CI job; adding one needs a shared-workflow change (not done here). | GitHub |

## 3. Runtime wiring required (owners outside this PR)

1. **Gateway Compose (Compose owner):** `docker-compose.apps.yml` passes only the env keys it lists, so an overlay or the apps file must add:
   `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED` (default `false`),
   `PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR=/var/lib/parkio/waitlist-ops-inbox`,
   `PARKIO_ENVIRONMENT`; `group_add: [<gid of parkio-waitlist-inbox>]`;
   bind mount `/var/lib/parkio/waitlist-ops-inbox:/var/lib/parkio/waitlist-ops-inbox:rw`.
   Template: `scripts/slack_biz/deploy/civo/gateway-waitlist-ops.overlay.example.yml`.
2. **Civo host (operator):** check out the release SHA at `/opt/parkio`, then run `install-relay.sh` (dry run), then `--apply`. Do not run the compose `slack-biz` profile worker at the same time.
3. **Secret (named owner):** webhook only in `/etc/parkio/slack-biz.secret.env` (0600 root). See "Secret ownership and rotation".

## 4. Retention (summary)

Gateway outbox terminal rows: 30 d. Inbox `.acked`: 24 h. Rejected files:
not kept (opt-in 72 h, 0600). Relay terminal rows and dedup keys: 168 h dedup
window. DLT: 720 h. Pending work is never purged at any stage. Full table in
the ops doc.

## 5. Enable / disable / rollback (summary)

- **Enable:** a five-step gated sequence. Delivery stays off until the owner authorises the webhook (ops doc "Enable procedure").
- **Disable:** relay `PARKIO_SLACK_BIZ_ENABLED=false` (sending stops, queue kept) and/or gateway flag false (recording stops, confirmation unaffected).
- **Discard backlog:** gateway `PENDING` rows → inbox `*.json` → relay `worker.py --discard-backlog waitlist.subscription_confirmed`.
- **Rollback:** redeploy the pinned previous gateway digest. Proven on a V4 schema (Flyway 11.7.2 reports a future version and makes no change; no Flyway overrides in the production file set). The V4 table stays and is unused.

## 6. Open items and decisions

| # | Item | Owner |
|---|---|---|
| 1 | Name the webhook owner; create the webhook only when activation is authorised | Ops owner |
| 2 | Apply the gateway Compose wiring (section 3.1) | Compose owner |
| 3 | Confirm the live Civo `parkio_gateway` PostgreSQL major version is 16 before deploy (not probed) | Operator |
| 4 | Where to track: the Resend client timeout observation, the deferred daily digest, the deferred signed Resend webhooks. This repository is **public** and has no issues yet, so no public issue was filed without approval. | Maintainer |
| 5 | Follow-up: the PR #54 registration file-inbox consumer still logs file names and exception text and keeps `.invalid/` files verbatim. The waitlist path does not use it. | slack_biz owner |
| 6 | Optional: CI job for `scripts/slack_biz` acceptance suites (shared workflow) | CI owner |
