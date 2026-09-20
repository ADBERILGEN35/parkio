# Parkio Slack business notifications (Y03)

**Status:** Implemented delivery core + three event families. **Disabled by default.**  
**Real Slack delivery:** NOT activated by this package.  
**Alertmanager:** Unchanged; continues to own metric/probe paging via `PARKIO_ALERT_SLACK_*`.

## What this is

An async, durable **biz/ops narrative** relay under `scripts/slack_biz/`:

| Family | Source | Route |
|--------|--------|-------|
| `registration.completed` | Committed auth outbox `UserRegistered` Kafka envelope | `biz-growth` |
| `incident.opened` / `incident.recovered` | Internal incident adapter (explicit recovery only) | `ops-alerts` |
| `backup.local` / `backup.offsite` | Backup script completion signals (separate planes) | `ops-alerts` |

Other Y01 catalog families remain **deferred** (see matrix in docs / PR evidence).

## Why not notification-service?

`notification-service` is Expo push / in-app. Extending it for Slack would mix mobile delivery with ops/biz paging. The smallest reliable fit is this thin Python relay using repository-native durable queue (SQLite) + optional backup hook.

## Transport

**Incoming webhook** (`PARKIO_SLACK_BIZ_WEBHOOK_URL`).

| Capability | Support |
|------------|---------|
| Post text message | Yes |
| Thread update / `chat.update` | **Deferred** (needs bot token + stored `ts`) |
| Recovery correlation | Separate message with same `fingerprint` |
| Permissions | Incoming webhook URL only — no workspace-read scopes |

Do **not** reuse `PARKIO_ALERT_SLACK_WEBHOOK_URL` for biz events (worker refuses equality when `PARKIO_SLACK_BIZ_FORBID_ALERTMANAGER_WEBHOOK=1`).

## Configuration (activation)

| Variable | Default | Meaning |
|----------|---------|---------|
| `PARKIO_SLACK_BIZ_ENABLED` | unset/false | Master switch |
| `PARKIO_SLACK_BIZ_WEBHOOK_URL` | empty | Destination (required to activate) |
| `PARKIO_SLACK_BIZ_WEBHOOK_URL_BIZ` | empty | Optional biz-growth override |
| `PARKIO_SLACK_BIZ_WEBHOOK_URL_OPS` | empty | Optional ops-alerts override |
| `PARKIO_SLACK_BIZ_DATA_DIR` | `.parkio/slack-biz` | Queue / dedup / DLT SQLite |
| `PARKIO_SLACK_BIZ_MAX_ATTEMPTS` | `5` | Bound before DLT |
| `PARKIO_SLACK_BIZ_HTTP_TIMEOUT` | `5` | Seconds |
| `PARKIO_SLACK_BIZ_DEDUP_RETENTION_HOURS` | `168` | Dedup key retention |
| `PARKIO_SLACK_BIZ_ENVIRONMENT` | `local` | Rendered env label |
| `PARKIO_SLACK_BIZ_DIAGNOSTIC_BASE_URL` | empty | Optional link base (no invented dashboards) |
| `PARKIO_SLACK_BIZ_TRUSTED_PRODUCERS` | auth-outbox,backup-script,incident-adapter,acceptance-harness | Allow-list |

Activation requires **both** `ENABLED=true` **and** a webhook URL. Enabling without a URL performs no outbound delivery.

### External Slack setup (operator; not done by this package)

1. Create a dedicated Incoming Webhook for biz/ops narrative channels (not the Alertmanager webhook).
2. Set env vars on the host / compose overlay.
3. Run `python scripts/slack_biz/worker.py --loop`.
4. For registration: feed committed `UserRegistered` envelopes via `enqueue.py --kind registration` (Kafka consumer wiring is a follow-on; outbox event already exists post-commit).
5. For incidents: enqueue explicit `phase=opened|recovered` JSON until a runtime producer exists (**production producer gap**).
6. Backup scripts auto-enqueue when enabled (see `parkio_backup_enqueue_slack_biz`).

## Dedup / retry / DLT

- **Dedup key examples:** `biz:UserRegistered:{event_id}`, `incident:{service}:{fingerprint}:open|recovered`, `backup:{local|offsite}:{scope}:{date}:{outcome}`
- **Retention:** 168h default; terminal rows purged only after expiry. In-flight/queued keys are never purged.
- **Retry:** exponential backoff + jitter; `Retry-After` honored on HTTP 429.
- **Ambiguous timeout:** classified `ambiguous`; treated as delivered for dedup (Slack may have accepted). Metric: `slack_biz_delivered_total{ambiguous=true}`. **Not** exactly-once.
- **DLT:** `python scripts/slack_biz/worker.py --list-dlt`
- **Replay:** re-enqueue only after intentional dedup clear / new event_id; do not delete dedup while Kafka may redeliver.

## Rollback / disable

1. Set `PARKIO_SLACK_BIZ_ENABLED=false` (or unset).
2. Stop the worker process.
3. Leave Alertmanager vars untouched.
4. Queue rows remain durable for later inspection; no production Slack calls occur while disabled.

## Isolated acceptance

```bash
python scripts/slack_biz/run_acceptance.py
```

Uses a local mock HTTP server. Refuses `*.slack.com` destinations.

## Failure of the notification channel

A broken Slack integration cannot reliably report its own outage via Slack. Inspect local metrics (`worker.py --metrics`), DLT, and host logs / Prometheus textfile backups instead.

## G01 / G02

This package does **not** close G01-R1, G01-R2, or G02-R1.
