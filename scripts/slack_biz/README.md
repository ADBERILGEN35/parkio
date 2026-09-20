# slack_biz — Parkio Y03 Slack business notification relay

Async durable delivery for a bounded event catalog. Disabled by default.

```bash
# Isolated acceptance (mock Slack only)
python scripts/slack_biz/run_acceptance.py

# Enqueue (examples)
python scripts/slack_biz/enqueue.py --kind registration --file envelope.json
python scripts/slack_biz/enqueue.py --kind incident --file incident.json
python scripts/slack_biz/enqueue.py --kind backup --file backup.json

# Worker
python scripts/slack_biz/worker.py --once
python scripts/slack_biz/worker.py --list-dlt
python scripts/slack_biz/worker.py --metrics
```

See `docs/operations/slack-biz-notifications.md`.
