# Parkio production alerting

Operator paging path for hosted-beta / production-intended Compose:

**failure → Prometheus metric → alert rule → Alertmanager → operator receiver → human → resolve**

Grafana dashboards and Alertmanager "firing" in the UI are **not** acceptance of operator notification.

## Architecture

- **Prometheus** (`docker/prometheus/prometheus.yml`) scrapes services, blackbox probes, kafka-exporter, node-exporter (including backup **textfile** metrics), and evaluates `docker/prometheus/alerts.yml`.
- **Alertmanager** (`docker/alertmanager/`) receives alerts at `alertmanager:9093`.
- **Render** (`docker/alertmanager/render-config.sh`) interpolates receiver credentials from the environment at container start. The committed `alertmanager.yml` is the **null** receiver (no outbound notify).
- **Azure hosted-beta overlay** currently disables Alertmanager via `profiles: [azure-disabled-observability]` (16 GiB VM sizing). Operator paging for PROD-ALERTING-01A uses GitHub Actions secret injection into the isolated Alertmanager stack (`scripts/alerting-operator-acceptance.sh`). Hosted-beta VM `.env` injection is a separate ops step and is not required for GitHub-secret acceptance.

```
Prometheus ──► Alertmanager ──► Slack webhook  (PARKIO_ALERT_SLACK_WEBHOOK_URL)
                         └──► generic webhook (PARKIO_ALERT_WEBHOOK_URL)
```

## Severity

| Label | Meaning | Repeat |
|-------|---------|--------|
| `critical` | P0 — page now (outage, data protection, or synthetic acceptance) | `1h` (`PARKIO_ALERT_REPEAT_CRITICAL`) |
| `warning` | P1 — investigate soon | `4h` (`PARKIO_ALERT_REPEAT_WARNING`) |

Every production alert has: stable `alertname`, `severity`, `summary`, `description`, `runbook_url`, and a `for:` duration. Labels must not carry secrets or PII.

## Routing / grouping

When a webhook is configured:

- `group_by`: `alertname`, `service`, `severity`, `component`
- `group_wait`: 30s (15s for critical)
- `group_interval`: 5m
- `repeat_interval`: 1h critical / 4h warning
- `send_resolved`: true

Critical and warning share the same destination so one operator channel sees both; grouping and inhibition reduce storms.

## Inhibition

- critical inhibits same-name warning (`alertname` + `service`/`component`)
- `GatewayDown` inhibits gateway 5xx/latency and generic `ServiceDown` for that service
- `CoreServiceDown` inhibits `ServiceDown` for the same service
- `PostgresDown` inhibits `DatabaseConnectionPoolExhausted`
- `KafkaBrokerUnavailable` inhibits lag/DLT alerts
- `HostDiskSpaceCritical` inhibits low/will-fill on the same mount
- `BackupFailed` inhibits `BackupStale` (same scope); `BackupOffsiteFailed` inhibits `BackupOffsiteStale`

Independent failures (e.g. Redis down while Postgres is up) still notify.

## Operator destination

Approved destinations (do **not** invent credentials):

1. Slack incoming webhook — `PARKIO_ALERT_SLACK_WEBHOOK_URL` + optional `PARKIO_ALERT_SLACK_CHANNEL`
2. Generic HTTPS webhook — `PARKIO_ALERT_WEBHOOK_URL` + optional `PARKIO_ALERT_WEBHOOK_SECRET` (Bearer)

Set them in the host env / `docker/.env` (gitignored), **or** as the GitHub Actions repository secret `PARKIO_ALERT_SLACK_WEBHOOK_URL` (Actions injects it into the operator-acceptance workflow; never commit the value). Restart Alertmanager after host-env changes so `render-config.sh` re-runs.

If neither is set, Alertmanager uses receiver `"null"`. Alerts still evaluate; **nobody is paged**. That is **not** production-capable notification.

## Secret model

| Secret | Where | Git |
|--------|--------|-----|
| Slack webhook URL | env / secret store | never |
| Generic webhook URL | env / secret store | never |
| Webhook bearer | `PARKIO_ALERT_WEBHOOK_SECRET` | never |
| SMTP (unused) | n/a | n/a |

`render-config.sh` does not echo URLs or tokens. Compose interpolates env into the Alertmanager **process** environment, not into committed YAML. GitHub Actions must not log these values; Observability validation uses `https://example.invalid/hooks/test` only.

## Silence / acknowledgement

- **Silence** in Alertmanager UI (`127.0.0.1:9093` via SSH tunnel): matchers on `alertname` / `service`. Use a comment and an expiry. Do not silence `BackupFailed` without a restore/backup ticket.
- There is no PagerDuty ack workflow yet. Treating Slack as the ack channel is an operational convention, not a product feature.
- **Do not** disable Prometheus rule evaluation to "quiet" an incident.

## Synthetic acceptance {#synthetic-acceptance}

Alert: `ParkioAlertingAcceptanceTest`.

1. Isolated catcher (plumbing): `./scripts/alerting-acceptance.sh` (no Slack secret).
2. Operator Slack (GitHub secret): `PARKIO_ALERT_SLACK_WEBHOOK_URL` + `./scripts/alerting-operator-acceptance.sh` or workflow **Alerting operator acceptance**. Then confirm in `#parkio-alert`: `FIRING RECEIVED` and `RESOLVED RECEIVED`.
3. Hosted-beta VM: only after the same env var is present on the host and Alertmanager is actually running (Azure overlay currently disables it).

This proves plumbing only. Infrastructure alert semantics are covered by `docker/prometheus/tests/alerts.test.yml`.

## Backup alerts

Metrics come from `scripts/lib/backup-common.sh` → `docker/prometheus/textfile/parkio_backup.prom` (node-exporter textfile collector). Success is **not** inferred from directory existence.

| Alert | Signal |
|-------|--------|
| `BackupFailed` | `parkio_backup_last_success == 0` |
| `BackupStale` | last attempt timestamp older than ~25h |
| `BackupOffsiteFailed` | production mode AND `parkio_backup_offsite_last_success == 0` |
| `BackupOffsiteStale` | production mode AND attempt timestamp older than ~25h |
| `BackupEncryptionDisabledInProduction` | production mode AND encryption gauge 0 |

Scopes: `hosted-beta` and `azure-hosted-beta`. Local/dev series do not page these.

## Testing

```bash
./scripts/observability-validate.sh   # promtool check config/rules + unit tests + amtool
./scripts/alerting-acceptance.sh      # isolated E2E delivery (Docker)
```

CI: `.github/workflows/observability-validation.yml` (no `continue-on-error`).

## Recovery

1. Fix the underlying failure (see [alert-response-runbook.md](./alert-response-runbook.md) and [backup-runbook.md](./backup-runbook.md)).
2. Confirm Prometheus alert state is `inactive`.
3. Confirm Alertmanager shows resolved (and Slack/webhook resolved message if `send_resolved: true`).
4. Do not delete Prometheus TSDB or Alertmanager silences to "clear" a real outage.

## What not to do

- Do not commit webhook URLs, tokens, or SMTP passwords.
- Do not disable TLS verification on webhook/SMTP clients.
- Do not fill a live disk to test `HostDiskSpaceCritical`.
- Do not stop hosted-beta Postgres/Redis/Kafka/MinIO to test alerts; use isolated compose or `promtool test rules`.
- Do not treat a null-receiver firing as operator notification.
