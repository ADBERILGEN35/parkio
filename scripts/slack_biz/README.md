# slack_biz — Parkio Y03/Y03A Slack business notification relay

Async durable delivery. Disabled by default. Single-worker enforced.

```bash
python scripts/slack_biz/run_acceptance.py
python scripts/slack_biz/run_reliability_acceptance.py

python scripts/slack_biz/consume_registration.py --status
python scripts/slack_biz/consume_registration.py --once   # needs REGISTRATION_INBOX or Kafka

python scripts/slack_biz/worker.py --once
python scripts/slack_biz/worker.py --list-unknown
python scripts/slack_biz/worker.py --resolve-unknown EVENT_ID --resolution requeue --operator alice
```

See `docs/operations/slack-biz-notifications.md`.
