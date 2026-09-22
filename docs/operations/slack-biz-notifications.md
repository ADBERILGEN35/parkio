# Parkio Slack business notifications (Y03 / Y03A)

**Status:** Delivery core + three event families + Y03A reliability corrections.  
**Disabled by default.** Real Slack: NOT activated.  
**Alertmanager:** Unchanged (`PARKIO_ALERT_SLACK_*`).

## Guarantees (honest)

| Claim | Actual |
|-------|--------|
| At-least-once to Slack | **Best-effort** with durable queue; **not** exactly-once |
| Ambiguous timeout | **Bounded retry with duplicate risk**, then terminal `delivery_unknown` — **never** confirmed `delivered` |
| Dedup admission | Separate from delivery outcome; 168h retention window |
| Workers | **Single worker enforced** (SQLite `worker_lock`); second worker rejected |
| Registration path | Envelope adapter + **file-inbox consumer** implemented; **live Kafka consumer ABSENT** unless bootstrap + kafka-python |

## Delivery states

`queued` → `in_flight` → `delivered` | `retry` | `dead` | `delivery_unknown`

- `delivered`: confirmed HTTP success (`ok`)
- `delivery_unknown`: ambiguous exhausted; operator-visible (`--list-unknown`, `--resolve-unknown`)
- Lease expiry returns `in_flight` → `retry` (crash recovery)

## Configuration

See env vars in prior Y03 docs plus:

| Variable | Default | Meaning |
|----------|---------|---------|
| `PARKIO_SLACK_BIZ_AMBIGUOUS_RETRY_BASE` | `2` | Base delay for ambiguous retries |
| `PARKIO_SLACK_BIZ_LEASE_SECONDS` | `30` | Claim lease |
| `PARKIO_SLACK_BIZ_WORKER_STALE_SECONDS` | `60` | Steal stale worker lock |
| `PARKIO_SLACK_BIZ_REGISTRATION_INBOX` | unset | File inbox for `UserRegistered` envelopes |
| `PARKIO_SLACK_BIZ_KAFKA_BOOTSTRAP` | unset | Optional live Kafka |
| `PARKIO_SLACK_BIZ_KAFKA_TOPIC` | `parkio.auth.user` | Auth outbox topic |
| `PARKIO_SLACK_BIZ_KAFKA_GROUP` | `parkio-slack-biz-registration` | Consumer group |
| `PARKIO_SLACK_BIZ_KAFKA_AUTO_OFFSET_RESET` | `latest` | Avoid historical flood on first enable |

## Registration connectivity

```
register() TX + outbox UserRegistered
  → AuthOutboxRelay → parkio.auth.user
  → [file inbox | optional Kafka consumer]
  → durable SQLite enqueue
  → THEN ack/commit offset
  → worker → Slack webhook
```

Disabled integration: worker sends nothing; queue may retain backlog. Do not enable with `earliest` against a populated topic.

## Operator resolve (uncertain)

```bash
python scripts/slack_biz/worker.py --list-unknown
python scripts/slack_biz/worker.py --resolve-unknown EVENT_ID --resolution accept_as_delivered|requeue|discard --operator alice
```

`requeue` may duplicate a Slack message.

## Acceptance

```bash
python scripts/slack_biz/run_acceptance.py              # Y03 mock 15/15
python scripts/slack_biz/run_reliability_acceptance.py  # Y03A fault injection
```

## Waitlist confirmations

Gateway waitlist `waitlist.subscription_confirmed` (file inbox `PARKIO_SLACK_BIZ_WAITLIST_INBOX`, `consume_waitlist.py`): see [waitlist-slack-notifications.md](waitlist-slack-notifications.md).

## Incident production producer

Still an **explicit gap** (synthetic/adapter only).
