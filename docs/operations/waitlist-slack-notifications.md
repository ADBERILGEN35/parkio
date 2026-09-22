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
Deferred work is tracked in [waitlist-slack-follow-ups.md](waitlist-slack-follow-ups.md).

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
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED` | `false` | Master switch. When false, nothing is recorded and there is no exporter bean or scheduler. Passed by `docker-compose.apps.yml` (default `false`). |
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENVIRONMENT` | `${PARKIO_ENVIRONMENT:local}` | Envelope environment. It must equal the relay's `PARKIO_SLACK_BIZ_ENVIRONMENT`. It is a dedicated key, so `info.deployment.environment` is not affected. Passed by `docker-compose.apps.yml`. |
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR` | *(empty)* | Inbox path inside the container. Set only by the activation overlay. Empty means nothing is recorded, even when enabled (a warning is logged). |
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_POLL_INTERVAL` | `PT30S` | Export poll period. |
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_BATCH_SIZE` | `20` | Maximum envelopes per poll (1–500). |
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_MAX_EXPORT_ATTEMPTS` | `5` | After this many failed writes the row becomes `FAILED`. Backpressure deferrals do not count as attempts. |
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_MAX_INBOX_BACKLOG` | `5000` | Export pauses while the inbox holds this many unconsumed envelopes. |
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_MIN_FREE_BYTES` | `536870912` (512 MiB) | Export pauses below this usable space on the inbox filesystem. |

The gateway needs no secret for this feature. Metrics:
- `parkio_waitlist_ops_notifications_total{outcome=recorded|duplicate_suppressed|record_failed|exported|export_retry|export_failed|export_deferred_inbox_backlog|export_deferred_low_disk}`
- gauges `parkio_waitlist_ops_outbox_pending` and `parkio_waitlist_ops_inbox_backlog`

### Relay

Non-secret settings go in `/etc/parkio/slack-biz.conf.env`
(template `scripts/slack_biz/deploy/civo/slack-biz.conf.env.example`):

- `PARKIO_SLACK_BIZ_ENABLED=false` until activation.
- `PARKIO_SLACK_BIZ_TRUSTED_PRODUCERS=gateway-waitlist-outbox` (isolation).
- `PARKIO_SLACK_BIZ_MAX_PENDING=5000` and `PARKIO_SLACK_BIZ_MIN_FREE_MB=256` (backpressure).
- The retention values in the Retention table.

The webhook goes only in `/etc/parkio/slack-biz.secret.env`.

## Compose integration (production file set)

| File | In `compose.production.files`? | Change |
|---|---|---|
| `docker/docker-compose.apps.yml` | yes (already) | The gateway gets `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED` (default `false`) and `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENVIRONMENT` (default `local`). Nothing else. |
| `docker/docker-compose.waitlist-ops-inbox.yml` | **no**; added only in rollout step 3 | Gateway `group_add: ["${PARKIO_WAITLIST_OPS_INBOX_GID:?…}"]`, `EXPORT_DIR`, and a bind mount of `/var/lib/parkio/waitlist-ops-inbox` with `create_host_path: false`. The mount fails instead of silently creating a root-owned directory. |
| `docker/.env.azure-hosted-beta.example` | n/a | Documents `…_ENABLED=false`, `…_ENVIRONMENT=production` and the commented `PARKIO_WAITLIST_OPS_INBOX_GID`. |

`scripts/slack_biz/deploy/civo/validate-compose-integration.sh [BASE_REF]`
renders the production file set with synthetic env only and asserts:

1. The disabled default differs from `BASE_REF` **only** by the two gateway env keys. All 32 services, images and pins (GMP gateway/media/parking, auth, web), volumes and networks are identical.
2. The activation overlay changes only `gateway-service`, by exactly `group_add`, one bind mount and `EXPORT_DIR`.
3. The overlay refuses to render without `PARKIO_WAITLIST_OPS_INBOX_GID`.

A negative control against an older base (before the PR #75 auth/web pins)
correctly fails. New Relic runs from its own Compose project and systemd units
(`parkio-nr-log-*`); none of its files or settings are touched.

## Capacity monitoring and disk pressure

What already exists on `parkio-civo-prod`:
- Prometheus, Alertmanager and node-exporter are running.
- `docker/prometheus/alerts.yml` has `HostDiskSpaceLow` (< 20% free, 10 m), `HostDiskSpaceCritical` (< 10%, 5 m), `HostDiskWillFillSoon`, `HostInodesLow`, `HostInodesCritical` and `HostFilesystemReadOnly`.
- Prometheus already scrapes `gateway-service:8080/actuator/prometheus`, so the new gauges and counters are collected with no config change.
- The root filesystem (which holds Docker volumes, Postgres, the future inbox and the relay state) is 136 G with 88 G free (36% used) and 4% of inodes used.

Growth without the safeguards (all per confirmation, human-scale volume):

| Stage | Size | Grows while | Bound |
|---|---|---|---|
| Gateway outbox row | ~250 B | Export blocked | Terminal rows purged after 30 d. `PENDING` is kept, as required. |
| Inbox envelope | ~320 B file + inode | Consumer stopped or deferring | Gateway backpressure at 5000 files or < 512 MiB free |
| Relay queue row | ~1 KB | Slack down, delivery disabled | Consumer backpressure at 5000 pending or < 256 MiB free |

Added safeguards (these never delete pending work):
- **Relay consumer:** at `PARKIO_SLACK_BIZ_MAX_PENDING` queued rows, or below `PARKIO_SLACK_BIZ_MIN_FREE_MB` free space, it admits nothing. Files stay in the inbox. It increments `slack_biz_waitlist_consume_deferred_total{reason=queue_full|low_disk}` and logs once per state change (acceptance W19).
- **Gateway exporter:** at `max-inbox-backlog` unconsumed files, or below `min-free-bytes`, it exports nothing. Rows stay `PENDING` **without consuming an attempt** (so they never become `FAILED` because of pressure). It increments `export_deferred_inbox_backlog` or `export_deferred_low_disk` and logs once per state change. Unit tests prove the rows stay `PENDING` with 0 attempts over repeated polls, then export after pressure is relieved.
- **Confirmation availability:** the request path only inserts one small outbox row, inside the confirmation's own transaction and behind a savepoint. The bounds above keep the inbox and relay state from growing without limit. The only unbounded-by-count store is `PENDING` rows in the gateway DB, at ~250 B each: 1 M backlogged confirmations would be ~250 MB. If the disk is really full, PostgreSQL fails confirmations with or without this feature; `HostDiskSpace*` alerts fire long before that.

Proposed alert rules (not applied; shared file, see follow-up WSN-F5):
`parkio_waitlist_ops_outbox_pending > 100 for 30m`,
`increase(parkio_waitlist_ops_notifications_total{outcome=~"export_deferred_.*|export_failed|record_failed"}[1h]) > 0`.

## Civo relay deployment

Package: `scripts/slack_biz/deploy/civo/`

| File | Purpose |
|---|---|
| `install-relay.sh` | Dry run by default; `--apply` (root) is idempotent. It creates: the system user `parkio-slackbiz` (nologin, no home); the group `parkio-waitlist-inbox`; `/var/lib/parkio/slack-biz` (0700); `/var/lib/parkio/waitlist-ops-inbox` (2770 setgid); the conf file (enabled=false); and an empty secret file (0600 root). It installs and enables **only** the waitlist consumer and worker units and does **not** start delivery. It refuses if a compose `slack-biz-worker` is running, if the conf enables the registration or Kafka consumer path, if trusted producers are not exactly `gateway-waitlist-outbox`, or if any other `parkio-slack-biz-*` unit exists. |
| `install-webhook.sh` | Hidden-input (`read -rs`) webhook installer. The value only travels over stdin and is never echoed, put in argv, or written to a temp file outside `/etc/parkio`. The write is atomic, 0600 root. It refuses non-Slack URLs and the Alertmanager webhook. It does not enable delivery. |
| `parkio-slack-biz-waitlist-consumer.service` | Reads the inbox. `PrivateNetwork=yes`, `ProtectSystem=strict`, no capabilities, write access only to state and inbox. Never loads the secret file. |
| `parkio-slack-biz-worker.service` | The only process that holds the webhook, through the 0600 root secret file loaded by systemd. The inbox is inaccessible to it. |
| `validate-compose-integration.sh` | Merged-config validator (above). |

**Legacy registration consumer: out of scope and OFF.** No unit is installed
for it. The installer refuses its settings, and the relay's trusted producers
exclude `auth-outbox`, so registration envelopes are rejected even if
enqueued manually. Its logging and retention issue is tracked as WSN-F4 in
[waitlist-slack-follow-ups.md](waitlist-slack-follow-ups.md).

Verified in a disposable systemd 255 / Ubuntu 24.04 / Python 3.12 container
(the same versions as `parkio-civo-prod`) with
`scripts/slack_biz/waitlist-e2e/run-civo-systemd-check.sh`: 18/18.
`systemd-analyze security` exposure: consumer 0.6 (SAFE), worker 1.3 (OK).

Code on the host: the root-owned checkout of the release SHA at `/opt/parkio`.
Units run `/usr/bin/python3` (3.12.3 on the host) with the standard library
only.

## Secret ownership and rotation

- **Owner:** the repository owner/operator. They create the Slack incoming webhook and enter it themselves when activation is authorised. Nobody else handles the value.
- **Scope:** one incoming webhook bound to the business-notification channel. It must never equal the Alertmanager webhook; the installer and the worker both refuse that.
- **Storage:** only `/etc/parkio/slack-biz.secret.env` (0600 root:root) on `parkio-civo-prod`. Not in the repo, Compose, CI, the gateway env, chat, tickets or shell history.
- **Install:** on the host, in an interactive SSH session: `sudo /opt/parkio/scripts/slack_biz/deploy/civo/install-webhook.sh`, paste at the hidden prompt, then `sudo systemctl restart parkio-slack-biz-worker`. Delivery stays off until `PARKIO_SLACK_BIZ_ENABLED=true`.
- **Rotate:** create a new webhook in Slack, run `install-webhook.sh --restart`, confirm one delivery, then revoke the old webhook in Slack. The gateway and consumer are unaffected.
- **Suspected leak:** revoke in Slack first, then set `PARKIO_SLACK_BIZ_ENABLED=false` and restart the worker. The queue is kept and nothing is lost.
- **Logging:** the relay never logs the URL (acceptance W13, e2e E11, systemd-check journal scan and installer-output check).

## Rollout (each phase separately authorised)

1. **Deploy disabled.** Pin the release-built gateway image, whose `org.opencontainers.image.revision` must equal the merge SHA, and recreate `gateway-service` with the production file set **without** the inbox overlay. V4 creates the empty outbox table. `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED` is unset or false.
2. **Verify health.** Gateway healthy. `flyway_schema_history` shows `1..4:true`. Outbox table empty. Submit, confirm and withdraw behave as before. No `PARKIO_WAITLIST_OPS_*` behaviour: no exporter bean, `parkio_waitlist_ops_notifications_total` absent or 0.
3. **Configure relay.** Reconcile `/opt/parkio` to the release SHA (WSN-F7). Run `sudo scripts/slack_biz/deploy/civo/install-relay.sh` (review the dry run), then `--apply`. Add `PARKIO_WAITLIST_OPS_INBOX_GID=$(getent group parkio-waitlist-inbox | cut -d: -f3)` and `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENVIRONMENT=production` to the host env file. Append `docker/docker-compose.waitlist-ops-inbox.yml` to `compose.production.files`. Start both units with `PARKIO_SLACK_BIZ_ENABLED=false`. Set `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED=true` and recreate the gateway. Check: one real confirmation produces an envelope, it lands in the relay queue as `queued`, and nothing is sent.
4. **Activate separately.** The owner runs `install-webhook.sh` (hidden input), sets `PARKIO_SLACK_BIZ_ENABLED=true` in `slack-biz.conf.env`, and restarts the worker. The queued items from step 3 are then delivered. Discard them first (below) if they should not be posted.

## Disable, discard and rollback

| Goal | Action | Effect |
|---|---|---|
| Stop Slack posts now | `PARKIO_SLACK_BIZ_ENABLED=false` in the conf file, then `systemctl restart parkio-slack-biz-worker` | The queue is kept and nothing is sent (e2e E07). |
| Stop producing events | Gateway `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED=false`, then recreate the gateway | No new outbox rows. Confirmation still works (e2e E08). |
| **Discard the whole backlog** (all three stages, in this order) | 1. Gateway: `DELETE FROM waitlist_ops_notification_outbox WHERE status='PENDING';` 2. Inbox: `sudo find /var/lib/parkio/waitlist-ops-inbox -maxdepth 1 -name '*.json' -delete` 3. Relay queue: `sudo -u parkio-slackbiz env $(grep -v '^#' /etc/parkio/slack-biz.conf.env \| xargs) python3 /opt/parkio/scripts/slack_biz/worker.py --discard-backlog waitlist.subscription_confirmed --operator <name>` | Queued and retry relay rows become `dead` (no send). Dedup keys are kept for 168 h, so a late re-export is suppressed (acceptance W17). Waitlist data is untouched. |
| Remove the feature from the gateway | Remove the inbox overlay from `compose.production.files`, then recreate the gateway | No mount, no group; the feature is inert. |
| Code rollback | Re-pin gateway `sha256:8b8a08ba…` (see below), then recreate the gateway | Verified in isolation on a V4 schema. |

## Rollback verification

**Planned rollback target** = the image running in production at preparation time:
`ghcr.io/adberilgen35/parkio/gateway-service@sha256:8b8a08ba974aeec91ec690ada406552a474c2319880752d4d4ea5ff1798028aa`
(revision `83fa625b9e37d6c0819862459d5570e305dd5121`). It was read-only verified on
`parkio-civo-prod` (`agent-tools/parkio-waitlist-slack-release-prep-02/…/production-readonly-facts.md`).
Do **not** roll back to `5c66e0fb…`: that is the stale value still in the pin
file (WSN-F6), an older recovery artifact.

Effective Flyway settings of the target, read from its jar on the live host:
`flyway-core-11.7.2`, migrations V1–V3,
`spring.flyway.enabled=true`, `locations=classpath:db/migration`. There is no
override in the jar, the container env (no `SPRING_FLYWAY_*`/`FLYWAY_*`) or the
production file set, so Flyway 11 defaults apply: `ignoreMigrationPatterns=*:future`, validate on migrate.

Empirical result (isolated e2e E09 with the target image on a PostgreSQL 16
schema migrated to V4 by the candidate): Flyway validated 4 migrations and
reported the schema version (4) as newer than the latest available (3) with no
migration. The gateway became healthy, a confirmation worked, and
`flyway_schema_history` was unchanged. Rolling forward to the candidate (E10)
was healthy. The V4 table stays and is unused by the old code. The earlier
round verified the same for the older `5c66e0fb` artifact.

## Validation performed

Synthetic data only; local mock Slack receivers; no real Slack or email.

| Suite | Result |
|---|---|
| `./gradlew :services:gateway-service:test` | All green (see release package for counts) |
| `./gradlew :services:gateway-service:integrationTest` → `WaitlistOpsNotificationPostgresIT` (postgres:16-alpine, Flyway V1–V4, `JdbcTransactionManager`) | 6/6: V4 applied and constraints enforced · committed → 1 row · outer rollback → neither · server-raised SQL error in savepoint → confirmation committed + `record_failed` · repeat + replay → 1 row, replay confined to savepoint · retention keeps `PENDING`. Negative control without savepoint fails as expected. |
| `python3 scripts/slack_biz/run_waitlist_acceptance.py` | 18/18 (W01–W18) |
| `scripts/slack_biz/waitlist-e2e/run-e2e.sh` (Docker, internal network, PR image + previous artifact) | 11/11 (E01–E11) |
| `scripts/slack_biz/waitlist-e2e/run-civo-systemd-check.sh` | 18/18 |
| Existing `run_acceptance.py` / `run_reliability_acceptance.py` | 15/15 and 13 PASS + 1 NOT_EXECUTED (Kafka, unchanged from PR #54) |

## Planning decisions (recorded)

- One message per confirmed subscription for the initial release.
- Daily digest and signed Resend delivery webhooks are deferred to separate tasks. Terminal email failure stays unimplemented until then.
- The relay is Civo-hosted under a dedicated least-privilege user with systemd units. The webhook is never given to the gateway.
- The Resend client timeout observation is tracked separately, outside this PR.
