# Activation and replay runbook — PARKIO-Y03A

## Activate (operator; not done here)

1. Dedicated Incoming Webhook ≠ Alertmanager URL  
2. `PARKIO_SLACK_BIZ_ENABLED=true` + webhook + persistent `DATA_DIR`  
3. Registration ingest: file inbox **or** Kafka bootstrap + kafka-python  
4. `auto.offset.reset=latest` on first enable  
5. One worker only: `python scripts/slack_biz/worker.py --loop`  
6. Optional: `python scripts/slack_biz/consume_registration.py --loop`

## Inspect

```bash
python scripts/slack_biz/worker.py --metrics
python scripts/slack_biz/worker.py --list-dlt
python scripts/slack_biz/worker.py --list-unknown
python scripts/slack_biz/consume_registration.py --status
```

## Resolve uncertain (`delivery_unknown`)

```bash
python scripts/slack_biz/worker.py --resolve-unknown <event_id> \
  --resolution accept_as_delivered|requeue|discard --operator <name>
```

- `accept_as_delivered`: mark confirmed without resend  
- `requeue`: may **duplicate** Slack message  
- `discard`: mark dead  

## Replay window

- Inside 168h: suppressed  
- Outside 168h: new admission possible → duplicate risk  
- Prefer operator `requeue` for known uncertain rows over blind Kafka replay  

## Disable / rollback

`PARKIO_SLACK_BIZ_ENABLED=false`; stop worker/consumer; leave Alertmanager intact.

## Channel self-failure

Broken Slack cannot page itself — use metrics/DLT/logs.
