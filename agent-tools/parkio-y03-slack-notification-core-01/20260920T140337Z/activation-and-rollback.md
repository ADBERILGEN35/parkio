# Activation and rollback — PARKIO-Y03

## Activate (operator; not performed by this package)

1. Create a **dedicated** Slack Incoming Webhook (separate from Alertmanager).
2. Set on host / compose overlay:
   - `PARKIO_SLACK_BIZ_ENABLED=true`
   - `PARKIO_SLACK_BIZ_WEBHOOK_URL=https://hooks.slack.com/services/...` (biz destination)
   - Optional: `_BIZ` / `_OPS` overrides
   - `PARKIO_SLACK_BIZ_DATA_DIR` on persistent volume
3. Confirm webhook ≠ `PARKIO_ALERT_SLACK_WEBHOOK_URL`.
4. Start worker: `python scripts/slack_biz/worker.py --loop`  
   or compose profile `slack-biz`.
5. Enqueue sources:
   - Registration: pipe committed `UserRegistered` envelopes to `enqueue.py --kind registration`
   - Incident: explicit JSON with `phase=opened|recovered`
   - Backup: automatic via `parkio_backup_enqueue_slack_biz` when enabled

## Inspect

```bash
python scripts/slack_biz/worker.py --metrics
python scripts/slack_biz/worker.py --list-dlt
```

## Controlled replay

1. Inspect DLT reason.
2. Fix destination / payload.
3. Re-enqueue with a **new** event_id **or** wait for dedup expiry (default 168h).
4. Do not delete dedup rows while Kafka may redeliver.

## Rollback / disable

1. `PARKIO_SLACK_BIZ_ENABLED=false`
2. Stop worker / disable compose profile
3. Leave `PARKIO_ALERT_SLACK_*` unchanged
4. No production mutations required beyond env + process stop

## Metrics cardinality

Counters labeled by `route` (+ `ambiguous` for deliveries). Avoid high-cardinality labels (no user ids, emails, fingerprints as metric labels).

## Channel failure visibility

Slack cannot page its own outage reliably. Use `--metrics`, DLT, host logs, and existing Alertmanager/Prometheus paths.
