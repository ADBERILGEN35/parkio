# Implementation decisions — PARKIO-Y03

## Decision: thin Python relay (not notification-service)

**Inspected:** `notification-service` is Expo push / in-app notifications. No Slack transport exists there.

**Choice:** `scripts/slack_biz/` durable queue worker.

**Why:** Smallest reliable fit for async biz/ops Slack without a new microservice or parallel Kafka framework. Reuses existing `UserRegistered` outbox event (already transactional) and backup script completion signals.

## Decision: no auth-service code change for registration

`AuthApplicationService.register` already appends `UserRegisteredEvent` to the outbox in the same DB transaction as the user insert. Post-commit Kafka publish is the authoritative completion signal.

Y03 adapter accepts only `eventType=UserRegistered` envelopes. Attempt/verification event types are refused. Client analytics are never used as registration proof.

**Commit guarantee:** Slack enqueue happens **after** the durable outbox event exists (consumer/enqueue side), never inside the registration request transaction. Slack outage cannot fail registration.

## Decision: incoming webhook transport

Smallest transport for the first slice. Supports text posts only.

| Feature | Status |
|---------|--------|
| Post message | Implemented |
| Thread / chat.update | **Deferred** (needs bot + stored `ts`) |
| Recovery | Correlated separate message with same fingerprint |

**Permissions:** Incoming webhook URL only. No workspace-read scopes requested. No Slack app installed by this package.

## Decision: incident producer honesty

No runtime AM→biz fan-in exists. Implemented **internal incident contract** + synthetic adapter validation.

**Production producer gap (explicit):** operators must enqueue `phase=opened|recovered` until a dedicated producer is wired. Alertmanager retains ownership of existing metric alerts. Recovery is **never** inferred from log silence. Live New Relic alerts are **not** depended upon.

## Decision: backup dual-plane events

`parkio_backup_write_metrics` already records local success and offsite_ok separately. Hook `parkio_backup_enqueue_slack_biz` emits **two** Slack-biz events when enabled. Local success does not imply offsite success. Missing evidence → `not_observed` / `unknown`.

## Decision: durable SQLite queue

Repository-native for scripts; WAL mode; lease-based claim for concurrent workers; dedup table with 168h retention; DLT table for permanent/exhausted failures.

**Ambiguous timeout:** if HTTP times out after send, classify `ambiguous` and treat as delivered for dedup (Slack may have accepted). Not exactly-once.

## Decision: destination safety

Event payloads cannot supply `webhook_url` / `channel_id`. Routes are logical names from config. Biz webhook must not equal Alertmanager webhook when forbid flag is on.

## Default activation

`PARKIO_SLACK_BIZ_ENABLED` defaults false. Profile `slack-biz` opt-in. Alertmanager unchanged.
