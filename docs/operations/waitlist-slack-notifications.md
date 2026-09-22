# Waitlist Slack operational notifications

**Status:** implemented, **disabled by default**, not activated anywhere.
No real Slack message or email was sent while building or testing this.

| Notification | Status | Why |
|---|---|---|
| Waitlist subscription confirmed (double opt-in completed) | **Implemented** | The gateway owns the `PENDING → CONFIRMED` transition in its own database transaction. |
| Waitlist email reached terminal delivery failure | **Not implemented** | The current provider integration has no reliable evidence of delivery or terminal failure (see [Provider capability evidence](#provider-capability-evidence)). |

## Signals that are deliberately not notified

| Signal | Why it is not a confirmation / terminal failure |
|---|---|
| `POST /api/v1/waitlist` (submission) | Creates a `PENDING` row only. The visitor has not proven they own the address. |
| Account registration (`UserRegistered`, auth-service) | A different product flow. It already has its own `registration.completed` family and is not touched here. |
| Resend `2xx` on `POST /emails` | The provider **accepted** the message for sending. That does not mean it was delivered. |
| Resend error / network failure on send | Returned to the visitor as `503 WAITLIST_EMAIL_DELIVERY_FAILED`. The row stays retryable through resubmit/resend (`max-resends`). This is a retryable failure, not a terminal one. |
| Repeat confirm with an already-used token | Idempotent success for the visitor. It is not a new transition, so no notification. |
| Withdrawal | Out of scope for this change. |

## Architecture

This reuses the existing slack_biz relay (`scripts/slack_biz`, PR #54: durable
SQLite queue, dedup, single worker, 429/5xx/timeout handling, DLT and
`delivery_unknown`). The gateway never talks to Slack and never holds a
webhook secret. On Civo the relay runs as two hardened systemd units under a
dedicated service user (see [Civo relay deployment](#civo-relay-deployment)).

```
POST /api/v1/waitlist/confirm
  └─ DB transaction (gateway)
       UPDATE waitlist_interest SET status='CONFIRMED' … WHERE status='PENDING'
       SAVEPOINT → INSERT waitlist_ops_notification_outbox (only when the update hit a row)
     COMMIT   ← row exists only if the confirmation committed
  └─ 204 to the visitor (no Slack I/O on the request path)

WaitlistOpsNotificationExporter  (@Scheduled, gateway, only when enabled)
  └─ due PENDING rows (≤ batch-size per poll)
       → atomic write <export-dir>/waitlist-<outboxId>.json  → mark EXPORTED
       → IO error: bounded exponential retry; FAILED after max-export-attempts

slack_biz/consume_waitlist.py  (relay host)
  └─ strict allow-list validation → durable enqueue (dedup by dedupKey) → .acked/
slack_biz/worker.py            (relay host, existing)
  └─ Slack incoming webhook (PARKIO_SLACK_BIZ_WEBHOOK_URL_BIZ; route biz-growth)
```

### Envelope contract (gateway → relay, v1)

The relay accepts **exactly** these keys. Any other key rejects the whole
envelope. Unknown keys are not stripped and forwarded. A rejection is recorded
only as a bounded category (see [Rejections](#rejections)), and by default the
rejected file is deleted, not kept.

```json
{
  "contractVersion": 1,
  "eventId": "<random outbox UUID — not the subscriber id>",
  "eventType": "waitlist.subscription_confirmed",
  "occurredAt": "2026-09-22T11:04:05Z",
  "environment": "production",
  "producer": "gateway-waitlist-outbox",
  "dedupKey": "waitlist:subscription_confirmed:<HMAC-SHA256 hex>"
}
```

`dedupKey` is `HMAC-SHA256(parkio.waitlist.hash-secret, "waitlist.subscription_confirmed:" + subscriberRowId)`.
This is **pseudonymous internal metadata, not anonymous data**. Anyone holding
the waitlist hash secret and the subscriber table can recompute it and link it
back to one subscriber. It is used only for dedup and is **never sent to
Slack**. See [Data inventory](#data-inventory) for exactly where it is stored.

## Example Slack messages (Turkish, synthetic data)

Rendered by `render_message` from a synthetic envelope
(`run_waitlist_acceptance.py` prints the same text):

```
*Bekleme listesi aboneliği onaylandı*
type=`waitlist.subscription_confirmed` severity=`info`
env=`production` service=`gateway-service`
at=`2026-09-22T11:04:05Z`
olay=`e-posta onayı tamamlandı (çift onay)`
```

Staging/acceptance look the same apart from `env=`:

```
*Bekleme listesi aboneliği onaylandı*
type=`waitlist.subscription_confirmed` severity=`info`
env=`acceptance` service=`gateway-service`
at=`2026-09-22T11:04:05Z`
olay=`e-posta onayı tamamlandı (çift onay)`
```

The messages do not contain: email, name, city/role, IP, tokens,
confirm/withdraw URLs, subscriber id, email hash, `dedupKey`, `eventId`, or
provider payload. Email, name, IP, tokens, URLs, subscriber id and provider
data are never part of the envelope at all. `dedupKey` and `eventId` are in the
envelope and relay state (internal metadata) but are not rendered into Slack.

Terminal email failure message: **none**, because the event is not implemented.
If it is added later (see [Remaining decisions](#remaining-decisions)), it must
carry only a bounded category, for example `kategori=\`bounce_hard\`|\`complaint\`|\`suppressed\``.

## Provider capability evidence

Checked against `origin/api` @ `27fe142a`:

| Question | Evidence | Answer |
|---|---|---|
| Is confirmation a durable, transactional state change? | `JdbcWaitlistInterestRepository#confirmByTokenHash`: `UPDATE … SET status='CONFIRMED' WHERE verification_token_hash=? AND status='PENDING' AND verification_expires_at > ?` | Yes. Exactly one transition per subscriber row. |
| Does the send call prove delivery? | `ResendWaitlistEmailSender#send`: `POST /emails` → `.retrieve().toBodilessEntity()` | No. Only provider acceptance. The Resend message id is discarded. |
| Is there a provider delivery-status webhook? | `git grep -i 'webhook\|bounce\|svix\|delivered' services/gateway-service/src/main` → no matches | No endpoint. |
| Is a delivery state persisted? | V1–V3 migrations: only `verification_sent_at` (set after provider acceptance) and `resend_count` | No `delivered`/`bounced`/`failed` column. |
| Are send failures terminal? | `WaitlistApplicationService#deliverConfirmation` → `WaitlistEmailDeliveryException` → HTTP 503; `verification_sent_at` stays `NULL` so the row remains retryable | No, they are retryable and visitor-driven. |

Conclusion: the only waitlist email fact the gateway knows is "provider
accepted" or "provider call failed and can be retried". Neither is terminal
delivery evidence. As instructed, no provider webhook endpoint was added and
no delivery status was invented.

Side observation (not changed here): `WaitlistRestClientConfig` builds the
Resend `RestClient` without an explicit connect/read timeout.

## Data inventory

| Field | Gateway outbox (Postgres) | Envelope file (inbox) | Relay SQLite (queue / dedup / DLT) | Sent to Slack |
|---|---|---|---|---|
| event type | yes | yes | yes | **yes** |
| environment | no (config) | yes | yes | **yes** |
| occurred-at timestamp (UTC seconds) | yes | yes | yes | **yes** |
| service name `gateway-service` | no | no | yes | **yes** |
| `dedupKey` (pseudonymous HMAC) | yes | yes | yes | no |
| `eventId` (random outbox UUID) | yes (`id`) | yes | yes | no |
| export attempts / bounded error category | yes | no | no | no |
| email, name, city, role, IP, UA, tokens, URLs, subscriber id, email hash, provider payload | **no** | **no** | **no** | **no** |

Classification: `dedupKey` is pseudonymous personal-data-linked metadata. It is
covered by the retention below and is not treated as anonymous.

## Duplicate, loss and storm guarantees

| Guard | Where / evidence |
|---|---|
| One outbox row per subscriber confirmation | Emitted only when the conditional `UPDATE … WHERE status='PENDING'` changed a row. `UNIQUE(dedup_key)` catches a replayed application event (`duplicate_suppressed`). Proven on PostgreSQL 16 (`WaitlistOpsNotificationPostgresIT`) and in e2e E03. |
| Rolled-back confirmation → nothing | Same transaction. Outer rollback removes both writes (PostgreSQL IT). |
| Outbox failure never fails the visitor | Savepoint (`PROPAGATION_NESTED`, `JdbcTransactionManager`). A PostgreSQL-raised error in the outbox INSERT is rolled back to the savepoint, the confirmation commits, and `record_failed` increments (PostgreSQL IT). Negative control: without the savepoint the confirmation is lost (`UnexpectedRollbackException`). |
| Crash between file write and `markExported` | The row stays `PENDING`, and the same file name is re-exported after restart. The relay suppresses it by `dedupKey` (e2e E05). |
| Hard kill with a committed row | The row survives `SIGKILL` as `PENDING` and is exported after restart (e2e E06). |
| Relay down / restarted | Files wait in the inbox and are delivered after the relay restarts (e2e E04). |
| Burst of confirmations | Gateway exports at most `batch-size` (20) per `poll-interval` (30 s). The worker claims at most 20 per cycle and honours `Retry-After`. Backlog is delayed, not dropped. |
| Bounded retries | Gateway export: 5 attempts, backoff 30 s → 15 min, then `FAILED`. Relay: `PARKIO_SLACK_BIZ_MAX_ATTEMPTS` (5); 4xx permanent → DLT immediately; ambiguous timeout exhausted → `delivery_unknown`. |

Remaining limitations:

- **At-least-once, not exactly-once.** A Slack timeout is ambiguous. The bounded retry can post a message twice, and after exhaustion the relay records `delivery_unknown`, never `delivered`.
- **Dedup window 168 h.** Terminal relay rows and dedup keys are purged after the window. An envelope re-exported after that would post again. Only the crash window above re-exports, and it does so within seconds.
- **Record failure drops that notification.** This is visible as `parkio_waitlist_ops_notifications_total{outcome="record_failed"}`.
- **One message per confirmation**, by decision for the initial release. A digest is deferred.
- **Multiple gateway replicas** have no row locking. Duplicate exports are suppressed by the relay. Production runs a single gateway.

## Rejections

`WaitlistInboxConsumer` treats the inbox as an untrusted boundary:

- **Bounded categories only:** `invalid_json`, `too_large` (> 4 KiB), `unreadable`, `not_object`, `unknown_field`, `missing_field`, `bad_contract_version`, `unsupported_event_type`, `producer_mismatch`, `untrusted_producer`, `environment_mismatch`, `bad_event_id`, `bad_occurred_at`, `bad_dedup_key`.
- A rejection increments `slack_biz_waitlist_rejected_total{category}` and logs `waitlist envelope rejected category=<category>`. File names, JSON key names and values are never logged or stored.
- **Rejected files are deleted by default.** For forensics only, `PARKIO_SLACK_BIZ_WAITLIST_RETAIN_REJECTED=true` moves them to `<inbox>/.invalid/` (dir 0700, file 0600, hashed name). They are pruned after `PARKIO_SLACK_BIZ_WAITLIST_REJECTED_RETENTION_HOURS` (72). Switching retention off clears the directory on the next prune.
- Acceptance W11 plants secrets in field **names**, field **values** and the **file name**, then checks that none appear in logs, relay state, metrics or leftover files.
- The pre-existing registration file-inbox consumer (PR #54, `registration_consumer.py`) still logs file names and exception text and keeps `.invalid/` files. It is not used by this feature and was left unchanged. It is tracked as a follow-up.

## Retention

| Data | Bound | Mechanism | Pending work |
|---|---|---|---|
| Gateway outbox `EXPORTED` / `FAILED` rows | 30 days (`retention`) | Every exporter poll | `PENDING` never purged |
| Inbox `.acked/` files | 24 h (`PARKIO_SLACK_BIZ_WAITLIST_ACKED_RETENTION_HOURS`; 0 = delete on ack) | Consumer, every 10 min / `consume_waitlist.py --prune` | Unacked `*.json` never pruned |
| Rejected files | Not kept (default); 72 h if opted in | Consumer | — |
| Relay `delivered` / `dead` / `delivery_unknown` rows + dedup keys | 168 h dedup window (`PARKIO_SLACK_BIZ_DEDUP_RETENTION_HOURS`) | Worker, every 10 min / `worker.py --prune`, and on enqueue | `queued` / `retry` / `in_flight` never purged |
| Relay DLT copies | 720 h (`PARKIO_SLACK_BIZ_DLT_RETENTION_HOURS`) | Worker / `--prune` | — |
| Relay metrics table | Bounded by label cardinality (categories and routes) | — | — |
| systemd journal | Host journald policy | — | — |

Note: `delivery_unknown` rows are also purged after the dedup window. Operators
must resolve them (`--list-unknown`, `--resolve-unknown`) within 7 days if a
decision is needed. Acceptance W16 checks that pending work is kept and that
in-window re-exports are still suppressed after pruning.

## Configuration

### Gateway (`parkio.waitlist.ops-notifications.*`)

| Env var | Default | Meaning |
|---|---|---|
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED` | `false` | Master switch. When false, nothing is recorded and there is no exporter bean or scheduler. |
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR` | *(empty)* | Inbox path inside the container. Empty means nothing is recorded, even when enabled (a warning is logged). |
| `PARKIO_ENVIRONMENT` | `local` | Envelope environment. It must equal the relay's `PARKIO_SLACK_BIZ_ENVIRONMENT`. |
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_POLL_INTERVAL` | `PT30S` | Export poll period. |
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_BATCH_SIZE` | `20` | Maximum envelopes per poll (1–500). |
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_MAX_EXPORT_ATTEMPTS` | `5` | After this many attempts the row becomes `FAILED`. |

The gateway needs no secret for this feature. Metric:
`parkio_waitlist_ops_notifications_total{outcome=recorded|duplicate_suppressed|record_failed|exported|export_retry|export_failed}`.

### Relay

Non-secret settings go in `/etc/parkio/slack-biz.conf.env`
(template `scripts/slack_biz/deploy/civo/slack-biz.conf.env.example`). The
webhook goes only in `/etc/parkio/slack-biz.secret.env`.

## Civo relay deployment

Package: `scripts/slack_biz/deploy/civo/`

| File | Purpose |
|---|---|
| `install-relay.sh` | Dry run by default; `--apply` (root) is idempotent. Creates the system user `parkio-slackbiz` (nologin, no home), the group `parkio-waitlist-inbox`, `/var/lib/parkio/slack-biz` (0700), `/var/lib/parkio/waitlist-ops-inbox` (2770 setgid), the conf file (enabled=false) and an empty secret file (0600 root). It installs and enables the units but does **not** start delivery. It refuses to run if a compose `slack-biz-worker` container is running (single-worker rule). |
| `parkio-slack-biz-waitlist-consumer.service` | Reads the inbox. `PrivateNetwork=yes`, `ProtectSystem=strict`, no capabilities, write access only to state and inbox. Never loads the secret file. |
| `parkio-slack-biz-worker.service` | The only process that holds the webhook. Loads the 0600 root secret file through systemd (the service user cannot read the file itself). The inbox is inaccessible to it. |
| `gateway-waitlist-ops.overlay.example.yml` | Example Compose wiring for the Compose owner: `group_add` inbox gid, env, bind mount. Not loaded by anything. |

Verified in a disposable systemd 255 / Ubuntu 24.04 / Python 3.12 container
(`scripts/slack_biz/waitlist-e2e/run-civo-systemd-check.sh`, 11/11): install
and idempotent re-install, ownership and modes, both units active under
hardening, service user cannot read the secret, uid 10001 with the inbox group
can write while uid 20000 cannot, delivery to a local mock, no webhook URL in
the journal. `systemd-analyze security` exposure: consumer 0.6 (SAFE), worker
1.3 (OK).

Code on the host: a root-owned read-only checkout at the release SHA under
`/opt/parkio` (the same tree the production compose file set uses). Units run
`/usr/bin/python3` (≥ 3.10) with the standard library only. No pip packages.

## Secret ownership and rotation

- **Owner:** the Parkio operations owner (the same role that holds `PARKIO_ALERT_SLACK_*`). A named person must be recorded before activation. Only a Slack workspace admin can create the webhook.
- **Scope:** one incoming webhook bound to the business-notification channel. It must never equal the Alertmanager webhook; the worker refuses to start if it does.
- **Storage:** only `/etc/parkio/slack-biz.secret.env` (0600 root:root) on the Civo host. Not in the repo, Compose, CI, gateway env, chat or tickets. Edit it with `sudoedit` and never pass the URL on a command line.
- **Install (future, authorised):** `sudoedit /etc/parkio/slack-biz.secret.env`, then `sudo systemctl restart parkio-slack-biz-worker`.
- **Rotate:** create the new webhook, replace the value, restart the worker, confirm one delivery (or `--once` against the queue), then revoke the old webhook in Slack. The gateway and consumer are unaffected.
- **Suspected leak:** revoke in Slack first (stops abuse immediately), then set `PARKIO_SLACK_BIZ_ENABLED=false` and restart the worker until a new webhook is installed. The queue is kept.
- **Logging:** the relay never logs the URL. Transport errors keep only the HTTP status and Slack's short response body (acceptance W13, e2e E11, systemd-check journal scan).

## Enable procedure (not authorised yet; record of the steps)

1. Merge PR #74, then release and deploy a gateway image built from the release SHA. V4 creates the empty outbox table and the feature stays off.
2. On Civo: check out the same SHA under `/opt/parkio`, then run `sudo scripts/slack_biz/deploy/civo/install-relay.sh` (review the dry run), then `--apply`.
3. The Compose owner applies the gateway wiring (env keys, `group_add`, bind mount; see the example overlay). `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED` stays false.
4. Start both units with `PARKIO_SLACK_BIZ_ENABLED=false`. Set the gateway flag to true and recreate `gateway-service`. Confirm that envelopes reach the relay queue without being sent (`sudo -u parkio-slackbiz … worker.py --once` shows `enabled:false, pending:N`).
5. Only after explicit owner authorisation: install the webhook, set `PARKIO_SLACK_BIZ_ENABLED=true`, and restart the worker.

## Disable, discard and rollback

| Goal | Action | Effect |
|---|---|---|
| Stop Slack posts now | `PARKIO_SLACK_BIZ_ENABLED=false` in the conf file, then `systemctl restart parkio-slack-biz-worker` | The queue is kept and nothing is sent (e2e E07). |
| Stop producing events | Gateway `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED=false`, then recreate the gateway | No new outbox rows. Confirmation still works (e2e E08). |
| **Discard the whole backlog** (all three stages, in this order) | 1. Gateway: `DELETE FROM waitlist_ops_notification_outbox WHERE status='PENDING';` 2. Inbox: `sudo find /var/lib/parkio/waitlist-ops-inbox -maxdepth 1 -name '*.json' -delete` 3. Relay queue: `sudo -u parkio-slackbiz env $(grep -v '^#' /etc/parkio/slack-biz.conf.env \| xargs) python3 /opt/parkio/scripts/slack_biz/worker.py --discard-backlog waitlist.subscription_confirmed --operator <name>` | Queued and retry relay rows become `dead` (no send). Their dedup keys are kept for 168 h, so a late re-export is suppressed (acceptance W17). `in_flight` rows are reported and finish under their lease. Waitlist data is untouched. |
| Code rollback | Deploy the previous gateway image | Verified with the actual pinned artifact (next section). |

## Rollback verification

The previous production gateway artifact is
`ghcr.io/adberilgen35/parkio/gateway-service@sha256:5c66e0fb010c25dc2029a93f0dab146caee1f442ba74140f66cd397a1e721e2c`
(revision `efe241952a106ff126edc7b6ede97e4e7a982904`, pinned in `docker/docker-compose.gmp-release-pins.yml`).

Effective Flyway settings, from that artifact and the production file set:

- Bundled Flyway `flyway-core-11.7.2`. Bundled migrations V1–V3 only.
- `BOOT-INF/classes/application.yml`: `spring.flyway.enabled=true`, `locations=classpath:db/migration`. No `ignore-migration-patterns`, `validate-on-migrate` or `out-of-order` override.
- No `SPRING_FLYWAY_*` / `FLYWAY_*` variable in any file of `docker/compose.production.files` or the env examples. The managed-db overlay that sets `SPRING_FLYWAY_USER` is not in the production set.

Empirical result (e2e E09): on a PostgreSQL 16 database migrated to V4 by the
PR image, the previous artifact started healthy and logged
`Successfully validated 4 migrations`, `Schema "public" has a version (4) that
is newer than the latest available migration (3) !` and
`Schema "public" is up to date. No migration necessary.` It served a
confirmation, and left `flyway_schema_history` unchanged. Rolling forward again
(E10) was healthy. The V4 table stays and is unused by the old code.

## Validation performed

Synthetic data only; local mock Slack receivers; no real Slack or email.

| Suite | Result |
|---|---|
| `./gradlew :services:gateway-service:test` | All green (see release package for counts) |
| `./gradlew :services:gateway-service:integrationTest` → `WaitlistOpsNotificationPostgresIT` (postgres:16-alpine, Flyway V1–V4, `JdbcTransactionManager`) | 6/6: V4 applied and constraints enforced · committed → 1 row · outer rollback → neither · server-raised SQL error in savepoint → confirmation committed + `record_failed` · repeat + replay → 1 row, replay confined to savepoint · retention keeps `PENDING`. Negative control without savepoint fails as expected. |
| `python3 scripts/slack_biz/run_waitlist_acceptance.py` | 18/18 (W01–W18) |
| `scripts/slack_biz/waitlist-e2e/run-e2e.sh` (Docker, internal network, PR image + previous artifact) | 11/11 (E01–E11) |
| `scripts/slack_biz/waitlist-e2e/run-civo-systemd-check.sh` | 11/11 |
| Existing `run_acceptance.py` / `run_reliability_acceptance.py` | 15/15 and 13 PASS + 1 NOT_EXECUTED (Kafka, unchanged from PR #54) |

## Planning decisions (recorded)

- One message per confirmed subscription for the initial release.
- Daily digest and signed Resend delivery webhooks are deferred to separate tasks. Terminal email failure stays unimplemented until then.
- The relay is Civo-hosted under a dedicated least-privilege user with systemd units. The webhook is never given to the gateway.
- The Resend client timeout observation is tracked separately, outside this PR.
