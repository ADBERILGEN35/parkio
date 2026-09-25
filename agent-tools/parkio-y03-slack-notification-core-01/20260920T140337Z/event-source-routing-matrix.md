# Event source and routing matrix — PARKIO-Y03

## Implemented

| Event type | Authoritative source | Producer id | Route | Dedup key | Slack payload notes |
|------------|---------------------|-------------|-------|-----------|---------------------|
| `registration.completed` | Kafka/outbox `UserRegistered` envelope | `auth-outbox` | `biz-growth` | `biz:UserRegistered:{event_id}` | env, time, pseudonym `u_*`; **no email** |
| `incident.opened` | Internal adapter JSON (`phase=opened`) | `incident-adapter` | `ops-alerts` | `incident:{service}:{fingerprint}:open` | fingerprint, count?, error_code? |
| `incident.recovered` | Internal adapter JSON (`phase=recovered`) | `incident-adapter` | `ops-alerts` | `incident:{service}:{fingerprint}:recovered` | explicit recovery only |
| `backup.local` | Backup script metrics hook | `backup-script` | `ops-alerts` | `backup:local:{scope}:{date}:{outcome}` | independent of offsite |
| `backup.offsite` | Backup script metrics hook | `backup-script` | `ops-alerts` | `backup:offsite:{scope}:{date}:{outcome}` | failed upload vs unknown/not_observed |

## Deferred (Y01 catalog — documented only)

`invite.*`, `first_value`, contribution/moderation digests, municipal stale, media failures, CI/release, security findings, budget, daily digests, notification meta-alerts.

## Ownership split

| Concern | Owner |
|---------|-------|
| Probe/SLO/metric paging | **Alertmanager** (`PARKIO_ALERT_SLACK_*`) |
| Biz/ops narrative (this package) | **Slack biz relay** (`PARKIO_SLACK_BIZ_*`) |
| Log search (optional) | NR log pilot (Y02, not activated) / Loki |

## Trusted producers

Allow-list: `auth-outbox`, `backup-script`, `incident-adapter`, `acceptance-harness` (override via `PARKIO_SLACK_BIZ_TRUSTED_PRODUCERS`).
