# Parkio production alerting

Operator paging path for hosted-beta / production-intended Compose:

**failure → Prometheus metric → alert rule → Alertmanager → operator receiver → human → resolve**

Grafana dashboards and Alertmanager "firing" in the UI are **not** acceptance of operator notification.

## Architecture

- **Prometheus** (`docker/prometheus/prometheus.yml`) scrapes services, blackbox probes, kafka-exporter, node-exporter (including backup **textfile** metrics), and evaluates `docker/prometheus/alerts.yml`.
- **Alertmanager** (`docker/alertmanager/`) receives alerts at `alertmanager:9093`.
- **Render** (`docker/alertmanager/render-config.sh`) interpolates receiver credentials from the environment at container start. The committed `alertmanager.yml` is the **null** receiver (no outbound notify).
- **Canonical Civo observability set** is `docker/compose.production.files` plus `docker/docker-compose.civo-alertmanager.yml`, always appended by `scripts/parkio-prod-compose.sh`. That overlay clears Alertmanager's disable profile and sets `PARKIO_ALERT_REQUIRE_RECEIVER=true`. Missing `PARKIO_ALERT_SLACK_WEBHOOK_URL` and `PARKIO_ALERT_WEBHOOK_URL` then makes `render-config.sh` exit before Alertmanager starts. Loki, Promtail and Tempo stay on `azure-disabled-observability`. `docker-compose.waitlist-ops-inbox.yml` stays an activation-only gateway overlay and is not part of this set.
- **Azure hosted-beta** (`scripts/lib/deploy-common.sh` profile `azure-hosted-beta`) does not use that Civo file. Alertmanager, Loki, Promtail and Tempo stay on `profiles: [azure-disabled-observability]` for the 16 GiB sizing path. Operator paging for PROD-ALERTING-01A uses GitHub Actions secret injection into the isolated Alertmanager stack (`scripts/alerting-operator-acceptance.sh`).

```
Prometheus ──► Alertmanager ──► Slack webhook  (PARKIO_ALERT_SLACK_WEBHOOK_URL)
                         ├──► generic webhook (PARKIO_ALERT_WEBHOOK_URL)
                         └──► heartbeat: Watchdog only ──► external dead-man's switch (PARKIO_ALERT_HEARTBEAT_URL)
```

## Severity

| Label | Meaning | Repeat |
|-------|---------|--------|
| `critical` | P0 — page now (outage, data protection, or synthetic acceptance) | `1h` (`PARKIO_ALERT_REPEAT_CRITICAL`) |
| `warning` | P1 — investigate soon | `4h` (`PARKIO_ALERT_REPEAT_WARNING`) |
| `heartbeat` | Not a page. Only `Watchdog` carries it; it goes to the [external dead-man's switch](#heartbeat), never to the operator channel | `2m` (`PARKIO_ALERT_HEARTBEAT_REPEAT`) |

Every production alert has: stable `alertname`, `severity`, `summary`, `description`, `runbook_url`, and a `for:` duration (the one exception is `Watchdog`, which is always firing by design; see [Heartbeat](#heartbeat)). Labels must not carry secrets or PII.

## Routing / grouping

When a webhook is configured:

- `group_by`: `alertname`, `service`, `severity`, `component` (one Slack message per group; identical unlabeled metrics scraped under different `service`/`job` labels become duplicate groups — the isolated acceptance stack now keeps `parkio_alerting_acceptance_test` on a single job, and the rule uses `max(...)`)
- `group_wait`: 30s (15s for critical)
- `group_interval`: 5m
- `repeat_interval`: 1h critical / 4h warning
- `send_resolved`: true

Critical and warning share the same destination so one operator channel sees both; grouping and inhibition reduce storms. Slack `text` is status-aware: resolved notifications must not reuse firing-only annotation copy.

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

## Delivery failures

Two alerts watch the delivery path itself (U06, CL-F04):

| Alert | Signal | Meaning |
|-------|--------|---------|
| `AlertmanagerNotificationsFailing` | `alertmanager_notifications_failed_total`, excluding `reason="contextCanceled"` (a shutdown with notifications in flight): at least two given-up notifications per `integration` in 15m | The receiver keeps failing (webhook down, Slack webhook revoked, bad credentials). A single failure is retried at the next group flush and does not fire. In production, where `group_interval` is 5m, the second failure and so the alert come about 6 to 11 minutes after the receiver breaks. |
| `PrometheusNotificationsFailing` | `prometheus_notifications_errors_total`: a failed send in every 2m window, held for 5m | Alertmanager is down or rejecting alerts. The counter counts failed batches, about one per rule group with active alerts, so it measures duration, not count. An outage of up to about three minutes does not fire. It resolves about 2 minutes after sends succeed again. |

**What the 5-minute hold misses.** The hold of `PrometheusNotificationsFailing` restarts whenever 2 minutes pass without a failed send.
- Failures spaced more than about 2 minutes apart never fire. In the #232 review's run, an Alertmanager that was down 60 s out of every 120 s stayed pending for 10 minutes. Prometheus re-sends every active alert once Alertmanager is back, so those alerts are delayed, not lost.
- A single 3-minute outage peaked at about 4m10s pending, about 50 s below the hold. Outages of about 3m15s to 4m fire briefly after Alertmanager is back (runbook step 4 in `docs/architecture/observability-metrics.md`).
- These boundaries hold for Prometheus 2.x, whose range selectors include both ends. Prometheus 3.x makes them left-open, which moves each boundary by one sample: failures 120 s apart fire on 2.54 and may stop firing. After an upgrade, re-run `docker/prometheus/tests/alert-delivery.test.yml`, whose 15-second cases pin these boundaries, and repeat the calibration.

`prometheus_notifications_dropped_total` is not alerted on. With one Alertmanager it grows together with the errors above, and at start-up Prometheus drops what fires before it has found Alertmanager.

For the first alert, Prometheus scrapes Alertmanager's own metrics (job `alertmanager` in `docker/prometheus/prometheus.yml`).

**They travel the path they watch.**
- Production renders one integration, Slack or the generic webhook, never both.
- When that integration fails, `AlertmanagerNotificationsFailing` is routed to the same failing receiver.
- When Alertmanager is down, `PrometheusNotificationsFailing` cannot reach anyone.
- Both still show in the Prometheus and Alertmanager UIs. Getting them to a person needs a second, independent path: the [heartbeat](#heartbeat) to a dead-man's switch outside the hosted-beta failure domain. The switch product is still an operator decision (recorded in that section); the rule and route are in place.

On Azure hosted-beta, Alertmanager is disabled. There, `PrometheusNotificationsFailing` fires five minutes after any alert becomes active, and then keeps itself active. That is accurate: alerts have nowhere to go on that path.

## Heartbeat / dead-man's switch {#heartbeat}

The delivery-failure alerts travel the path they watch, so they cannot report a host, Prometheus or Alertmanager that is simply gone. The heartbeat closes that gap from outside the hosted-beta failure domain (U06, CL-F04).

**Rule.** `Watchdog` (`docker/prometheus/alerts.yml`, group `parkio-heartbeat`) is `vector(1)`: it always fires, with `severity="heartbeat"` and `service="observability"`. Its unit test is `docker/prometheus/tests/heartbeat.test.yml`.

**Route.** The first Alertmanager route matches `alertname="Watchdog"`, groups it alone (`group_by: ["alertname"]`, `group_wait: 0s`, `group_interval: 30s`) and re-sends it once `PARKIO_ALERT_HEARTBEAT_REPEAT` (default `2m`) has elapsed. Alertmanager re-sends at the first flush after the repeat has elapsed, so the observed period is the repeat rounded up to the next 30 s flush: 2m to 2m30s with the default, 30 s in the isolated run with its 20 s repeat. It goes to receiver `heartbeat`: a webhook `POST` to `PARKIO_ALERT_HEARTBEAT_URL`, optional Bearer `PARKIO_ALERT_HEARTBEAT_SECRET`, `send_resolved: false`. With no URL the route ends at the `null` receiver. Because the route comes first and does not `continue`, `Watchdog` never matches the critical or warning routes and never reaches Slack or the operator webhook. A heartbeat alone is not an operator receiver: `PARKIO_ALERT_REQUIRE_RECEIVER` still refuses to start without Slack or the generic webhook, and a heartbeat-only render keeps every operator route on `null`.

**External receiver contract.** The receiver is a dead-man's switch hosted outside this host and its network. It expects one `POST` about every `PARKIO_ALERT_HEARTBEAT_REPEAT` (plus up to one 30 s flush) and pages the operator through its own channel when none arrives for its grace period. Set the grace to at least two periods plus the flush: with the default 2m, 6m. Any request body must be accepted; the switch must not depend on the Alertmanager payload. The product is an operator decision; record it here.

| Item | Value |
|---|---|
| External monitor | **Pending operator decision.** Recommendation: a hosted heartbeat/ping service with one URL per check and its own paging channel (Healthchecks-style). It needs no new image and no inbound access to the host |
| Expected period | `PARKIO_ALERT_HEARTBEAT_REPEAT` = `2m`, observed as 2m to 2m30s |
| Grace before paging | at least `6m` (two periods plus the flush) |
| Who is paged on silence | the operator on call, through the monitor's own channel, not through this Alertmanager |

**What silence means.** No heartbeat for the grace period: Prometheus is not evaluating, Alertmanager is not sending, the host or its egress is down, or the heartbeat URL or credential is wrong. It does not mean the `Watchdog` alert resolved. Response: [alert-response-runbook.md#watchdog](./alert-response-runbook.md#watchdog). While the pipeline is silent, no other alert reaches the operator either.

**Detection latency.** When Alertmanager or the host stops, the heartbeat stops at once. When only Prometheus stops, Alertmanager keeps re-sending the last `Watchdog` it received until that alert's `endsAt` passes: Prometheus sets it about four evaluation intervals ahead, so about four minutes at the production interval. Silence at the switch therefore starts up to four minutes after Prometheus dies, and the page comes one grace period later. In the isolated run (5 s evaluation, 10 s resend) that tail is under a minute.

**The URL is a credential.** For a ping-style switch, whoever knows `PARKIO_ALERT_HEARTBEAT_URL` can keep the switch quiet. On a non-2xx answer Alertmanager v0.27.0 logs the full URL (transport errors are redacted), and on stacks that run promtail that log reaches Loki. Treat the Alertmanager log like a secret-bearing log: redact URLs before sharing (`sed -E 's#https?://[^[:space:]"]+#<url>#g'`). The optional Bearer `PARKIO_ALERT_HEARTBEAT_SECRET` is never logged.

**Receiver failure is visible.** A heartbeat `POST` that fails counts in `alertmanager_notifications_failed_total{integration="webhook"}`, so a broken switch URL fires `AlertmanagerNotificationsFailing` to the operator channel while that channel still works.

**Activation (separately authorized).** Set `PARKIO_ALERT_HEARTBEAT_URL` (and the optional secret) in the host env, recreate Alertmanager so `render-config.sh` re-runs, confirm the switch shows a fresh ping, then fill the table above. `scripts/preflight-hosted-beta.sh` warns while the URL is empty and refuses a placeholder or non-HTTPS value. Drill and restore stacks must never carry the heartbeat URL (`scripts/lib/restore-drill-isolation-preflight.py` refuses it): a drill pinging the production switch would hide a real outage. Do not point two stacks at one switch URL.

**Tests.** `observability-validate.sh` renders the configuration with and without the URL and asks `amtool config routes test` which receiver `Watchdog`, a critical and a warning alert get. The isolated acceptance (`scripts/alerting-acceptance.sh`) proves periodic delivery to the heartbeat catcher only, no `Watchdog` at the operator receiver, that stopping Alertmanager halts the heartbeat, that it resumes on restart, and that stopping Prometheus halts it once the alert times out.

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

The isolated run also proves the delivery-failure alerts:
- while delivery is healthy, neither fires;
- with the catcher answering 503, `AlertmanagerNotificationsFailing{integration="webhook"}` fires;
- with Alertmanager stopped, `PrometheusNotificationsFailing` fires after its 5-minute hold.

And the [heartbeat](#heartbeat):
- `Watchdog` reaches the heartbeat catcher periodically and never the operator catcher;
- it halts while Alertmanager is stopped and resumes after the restart;
- it halts after Prometheus is stopped, once Alertmanager times the alert out.

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
