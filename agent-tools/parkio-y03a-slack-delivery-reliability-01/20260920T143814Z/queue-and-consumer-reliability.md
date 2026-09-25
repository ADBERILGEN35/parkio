# Queue and consumer reliability — PARKIO-Y03A

## SQLite durability

- Path: `PARKIO_SLACK_BIZ_DATA_DIR/slack_biz.sqlite3` (volume-mount for containers)
- WAL + `synchronous=NORMAL`
- Best-effort `chmod 600` on POSIX
- Persist across process/container recreation when volume retained
- Disk-full / OperationalError on enqueue → raised; consumer **does not ack**

## Claim / single worker

- Exclusive `worker_lock` row; second worker → `WorkerLockError`
- Stale lock steal after `PARKIO_SLACK_BIZ_WORKER_STALE_SECONDS`
- Claim uses short DB transaction; **HTTP outside lock**
- Expired `in_flight` leases → `retry` (`lease_expired_reclaimed`)

## Crash cases

| Case | Behavior |
|------|----------|
| Crash after claim | Lease expiry → reclaim → retry |
| Crash after send before record | Appears as lease reclaim or ambiguous on next attempt → duplicate risk |
| Crash after enqueue before Kafka/file ack | Redelivery → `duplicate_suppressed`; then ack |

## Dedup retention / replay window

- Default **168 hours**
- Inside window: suppress duplicates
- Outside window: purge terminal dedup **and** matching terminal queue rows → fresh admission allowed (**duplicate Slack risk**)
- Operators must not treat expiry as “safe to replay casually”

## Registration consumer

| Piece | Status |
|-------|--------|
| Envelope adapter (`UserRegistered` only) | IMPLEMENTED |
| File inbox (ack after durable enqueue) | IMPLEMENTED |
| Live Kafka (`parkio.auth.user`, group `parkio-slack-biz-registration`) | ABSENT unless bootstrap + kafka-python → then IMPLEMENTED_NOT_EXECUTED |
| Offset commit before enqueue | **Forbidden**; commit/ack only after durable enqueue or deterministic suppress |
| Default `auto.offset.reset` | `latest` (no historical flood) |

## Topic / trust

- Topic: `parkio.auth.user`
- Producer trust allow-list includes `auth-outbox`
- Client analytics cannot use this adapter (wrong event type / untrusted producer)
