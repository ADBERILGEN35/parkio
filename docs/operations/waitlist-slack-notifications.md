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
webhook secret.

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

The relay accepts **exactly** these keys. Any other key rejects the envelope
into `.invalid/`. Unknown keys are not stripped.

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

`dedupKey` is `HMAC(parkio.waitlist.hash-secret, "waitlist.subscription_confirmed:" + subscriberRowId)`.
It cannot be reversed or joined to the subscriber without the hash secret. It
is used for dedup only and is not shown in Slack. The outbox table has no FK
and no subscriber columns.

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
confirm/withdraw URLs, subscriber id, dedup hash, outbox id, or provider
payload. These are not only filtered out; they are never part of the envelope.

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

## Duplicate, loss and storm guarantees

| Guard | Where |
|---|---|
| One outbox row per subscriber confirmation | Emitted only when the conditional `UPDATE` changed a row. `UNIQUE(dedup_key)` catches a replayed application event (`duplicate_suppressed`). |
| Rolled-back confirmation → nothing | Outbox insert runs in the same transaction. |
| Outbox failure never fails the visitor | Savepoint (`PROPAGATION_NESTED`). On error the transaction rolls back to the savepoint, the confirmation still commits, and the `record_failed` counter increments. |
| Re-export after crash | Deterministic file name plus relay `dedupKey` dedup (168 h window) → `duplicate_suppressed`. |
| Burst of confirmations | Gateway exports at most `batch-size` (20) envelopes per `poll-interval` (30 s). The relay worker claims at most 20 per cycle and honours `Retry-After` on 429. The backlog is delayed, not dropped. |
| Bounded retries | Gateway export: `max-export-attempts` (5), exponential 30 s → 15 min cap, then `FAILED`. Relay: `PARKIO_SLACK_BIZ_MAX_ATTEMPTS` (5); 4xx permanent → DLT immediately. |

Remaining limitations, stated plainly:

- **At-least-once, not exactly-once.** A Slack timeout is ambiguous. The relay retries a bounded number of times, so a message can occasionally appear twice. When retries are exhausted it records `delivery_unknown`, never `delivered` (existing Y03A behaviour).
- **Dedup window.** Relay dedup lasts 168 h. A gateway envelope re-exported after that window would post again. In practice the gateway marks rows `EXPORTED` right after the write, so only a crash between the write and the mark causes a re-export.
- **Record-failure loss.** If the outbox insert itself fails, that confirmation gets no notification. The `parkio_waitlist_ops_notifications_total{outcome="record_failed"}` counter shows it. The confirmation is still correct.
- **One message per confirmation.** There is no digest or coalescing. At launch-scale volumes the channel gets one line per confirmation, rate-shaped but not summarised. A daily digest is a remaining decision.
- **Multiple gateway replicas.** There is no row locking. Two exporters may write the same row, but the relay suppresses the duplicate by `dedupKey`. The invite production runs a single gateway.
- **Quarantine retention.** Rejected envelopes are kept verbatim in `<inbox>/.invalid/` for operators. The gateway producer cannot emit PII, but anything written into the inbox by another process stays there until an operator deletes it.

## Configuration

### Gateway (`parkio.waitlist.ops-notifications.*`)

| Env var | Default | Meaning |
|---|---|---|
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED` | `false` | Master switch. When false, nothing is recorded and there is no exporter bean or scheduler. |
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR` | *(empty)* | slack_biz waitlist inbox as seen from inside the container. Empty means nothing is recorded, even when enabled (a warning is logged). |
| `PARKIO_ENVIRONMENT` | `local` | Goes into the envelope. It must equal the relay's environment or the relay rejects the envelope. |
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_POLL_INTERVAL` | `PT30S` | Export poll period. |
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_BATCH_SIZE` | `20` | Maximum envelopes per poll (1–500). |
| `PARKIO_WAITLIST_OPS_NOTIFICATIONS_MAX_EXPORT_ATTEMPTS` | `5` | After this many attempts the row becomes `FAILED` (1–20). |

The gateway needs no secret for this feature. Metric:
`parkio_waitlist_ops_notifications_total{outcome=recorded|duplicate_suppressed|record_failed|exported|export_retry|export_failed}`.

### Relay (`scripts/slack_biz`)

| Env var | Meaning |
|---|---|
| `PARKIO_SLACK_BIZ_ENABLED` | Relay master switch (default false). |
| `PARKIO_SLACK_BIZ_WAITLIST_INBOX` | Same host directory the gateway writes to. |
| `PARKIO_SLACK_BIZ_ENVIRONMENT` / `PARKIO_ENVIRONMENT` | Must match the gateway's `PARKIO_ENVIRONMENT`. |
| `PARKIO_SLACK_BIZ_WEBHOOK_URL_BIZ` | **Secret.** Incoming webhook for the business channel. Waitlist uses route `biz-growth`. |
| `PARKIO_SLACK_BIZ_TRUSTED_PRODUCERS` | If you override it, include `gateway-waitlist-outbox` (it is in the default). |

### Hidden-secret setup (webhook)

1. A Slack workspace admin creates an incoming webhook bound to the intended business channel. It must **not** be the Alertmanager webhook: `worker.py` refuses to start if `PARKIO_SLACK_BIZ_WEBHOOK_URL*` equals `PARKIO_ALERT_SLACK_WEBHOOK_URL`.
2. Store it only in a root-owned env file on the relay host, for example `/etc/parkio/slack-biz.env` with mode `0600`. Never put it in the repo, Compose files, CI variables printed in logs, or a shell history (use `read -rs` and write the file with `install -m 600`).
3. The relay never logs the URL. Transport errors keep only the HTTP status and the Slack response body (acceptance W13 checks queue, DLT, metrics and logs for the URL).
4. Rotation: revoke the webhook in Slack, replace the file, and restart the relay. The gateway is unaffected.

## Enable procedure (not authorised yet; record of the steps)

1. Merge this PR and deploy a gateway image that contains it. V4 creates the empty `waitlist_ops_notification_outbox` table. The feature stays off.
2. Apply the shared-file integration below (Compose volume and relay runtime) through its owners.
3. Start the relay consumer and worker with `PARKIO_SLACK_BIZ_ENABLED=false`, then set the gateway `…_ENABLED=true` and `…_EXPORT_DIR` and restart the gateway. Confirm that envelopes queue in the relay without being sent (`worker.py --once` reports `enabled:false, pending:N`).
4. Only with explicit owner approval, set `PARKIO_SLACK_BIZ_ENABLED=true` and restart the relay.

## Disable and rollback

| Goal | Action | Effect |
|---|---|---|
| Stop Slack posts immediately | Relay `PARKIO_SLACK_BIZ_ENABLED=false` and restart the worker | The queue is kept and nothing is sent. |
| Stop producing events | Gateway `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED=false` and restart | No new outbox rows. Existing `PENDING` rows stay and are exported only if re-enabled. |
| Drop backlog | `DELETE FROM waitlist_ops_notification_outbox WHERE status='PENDING';` and empty the inbox dir | The backlog is gone. Waitlist data is untouched. |
| Code rollback | Deploy the previous gateway image | Safe. The applied V4 is a "future" migration for the older image, and Flyway's default `ignoreMigrationPatterns=*:future` accepts it. The table stays and is unused. |

Waitlist admission, confirmation and withdrawal behave identically whichever
way these switches are set.

## Integration requirements outside this PR (not modified here)

These files belong to other owners or are shared. The exact requirements:

1. **Compose (invite-production / hosted-beta gateway service)**:
   - env: `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED=${PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED:-false}`, `PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR=/var/lib/parkio/waitlist-ops-inbox`
   - volume: `${PARKIO_WAITLIST_OPS_INBOX_HOST_DIR:-/var/lib/parkio/slack-biz/waitlist-inbox}:/var/lib/parkio/waitlist-ops-inbox:rw`
   - The host dir must be writable by container uid `10001` (gateway `USER parkio`) and readable and movable by the relay user. Suggested: group-owned dir with mode `2770`.
2. **Relay runtime on the host** (also needed by the existing PR #54 families, not deployed today): long-running `python3 scripts/slack_biz/consume_waitlist.py --loop` and `python3 scripts/slack_biz/worker.py --loop` (a systemd unit or timer), with `EnvironmentFile=/etc/parkio/slack-biz.env`. Only **one** worker (enforced by SQLite lock).
3. **`.env.*.example` files** (optional): document the gateway variables above with `false`/empty defaults.

## Validation performed

All with synthetic data. Local mock Slack receiver only; no real Slack or email.

| Suite | Result |
|---|---|
| `./gradlew :services:gateway-service:test` → `WaitlistOpsNotificationOutboxTest` (H2, PostgreSQL mode) | committed confirmation → one envelope with allow-listed keys only · repeat confirm + replayed event → one row / one file · rolled-back transaction → no row, no file · failure after status update → confirmation and notification both rolled back · outbox write failure → confirmation still succeeds (`record_failed`) · missing export dir → backoff, then `FAILED`, never retried again · bounded backoff · batch cap · runtime-disabled → no rows · invalid token → no row · envelope and captured logs contain no email/IP/city/tokens/URLs/subscriber id/email hash |
| `WaitlistOpsNotificationDisabledTest` | Default config: no exporter bean, confirmation works, no rows |
| Full `:services:gateway-service:test` | 210 tests, 0 failures, 0 errors |
| `python3 scripts/slack_biz/run_waitlist_acceptance.py` | 14/14 PASS: committed, rollback (idle relay), duplicate, disabled, timeout→retry, timeouts exhausted→`delivery_unknown`, 429 + Retry-After, 5xx→retry, 5xx exhausted→DLT, 400/404 permanent→DLT with no retry, prohibited-field envelopes rejected, non-confirmation / env-mismatch / malformed rejected, webhook URL absent from state and logs, relay logs free of prohibited values |
| `run_acceptance.py` / `run_reliability_acceptance.py` (existing) | 15/15 and 13 PASS + 1 NOT_EXECUTED (Kafka, unchanged from PR #54) |

Not executed: PostgreSQL run of V4 and of the savepoint path (tests use H2 in
PostgreSQL mode; the SQL is standard and savepoints are supported by the
PostgreSQL JDBC driver), and a gateway-container → host-volume → relay run
(it needs the Compose change above).

## Remaining decisions

1. **Terminal email failure.** Should Parkio add Resend webhooks (signed, Svix) with a persisted Resend message id, as a separate task? Only then can `bounce_hard` / `complaint` / `suppressed` be reported truthfully.
2. **Channel and volume.** Should confirmations go per-event to `biz-growth`, or should a daily count digest replace them at launch?
3. **Relay hosting.** Which host and user run the slack_biz consumer and worker, and who owns the webhook secret?
4. **Compose owner** applies the volume and env wiring above.
5. **Production enablement** (gateway flag, relay flag, real webhook) needs separate explicit authorisation.
