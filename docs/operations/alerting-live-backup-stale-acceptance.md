# Live backup-stale alert acceptance plan (U06, CL-F04)

Asana [U06][P1] 1219001731640694: an authorized synthetic live acceptance that a stale backup
condition fires `BackupStale`, that one notification reaches the operator receiver, that the
resolve is delivered, that nothing is delivered twice, and that a receiver failure is visible.

This document is the preparation: the plan, the synthetic trigger, what is observed, and the
rollback. Running it on `parkio-civo-prod` is a separate, explicitly authorized step. Nothing
here is executed by CI against a live host.

## Why the earlier probe was not this acceptance

The 2026-10-01 probe posted a synthetic `ParkioProbeCivoAlertmanager` alert straight into
Alertmanager's API. It showed that Alertmanager reached Slack (FIRING and RESOLVED were seen
in `#parkio-alert`), but it did not exercise a rule, it did not touch the backup path, it
could not read Slack timestamps, and it did not show a receiver failure. The task's criteria
need the rule to fire from a metric.

## The chain under test

From the dated inventory (`agent-tools/parkio-u06-alarm-chain-inventory/INVENTORY.md`, section 9):

- backup cron `30 3 * * *` (root) → `scripts/lib/backup-metrics.py` → `/opt/parkio/docker/prometheus/textfile/parkio_backup.prom`, one series set with `scope="azure-hosted-beta"`;
- node-exporter `v1.8.2` textfile collector (`/opt/parkio/docker/prometheus/textfile` → `/textfile-collector`);
- Prometheus `v2.54.1`, rule `BackupStale`: `time() - parkio_backup_last_timestamp_seconds{scope=~"hosted-beta|azure-hosted-beta|invite-production"} > 90000`, held `1h`, `severity="critical"`;
- Alertmanager `v0.27.0` (started out of band by `scripts/install-civo-alert-slack-webhook.sh`), route `critical`: `group_wait 15s`, `group_interval 5m`, `repeat_interval 1h`, `send_resolved: true`, receiver Slack `#parkio-alert`;
- a human in `#parkio-alert`.

## Synthetic trigger: a second textfile, a scope the host does not use

`scripts/alerting-live-backup-stale-acceptance.sh arm` writes
`/opt/parkio/docker/prometheus/textfile/parkio_backup_synthetic_stale.prom`: the eight
`parkio_backup_*` gauges for a production scope that the host does **not** use (on Civo:
`invite-production`, chosen automatically as the first of `invite-production`, `hosted-beta`,
`azure-hosted-beta` that is absent from the real file), with

- `parkio_backup_last_timestamp_seconds` 26 h in the past (93 600 s; the rule needs more than 90 000),
- `parkio_backup_last_success 1`, `offsite_last_success 1`, `encryption_enabled 1`, `databases_failed 0`,
- `parkio_backup_production_mode 0`.

So, for that scope, `BackupStale` is the only rule that can fire: `BackupFailed` (and its
inhibition of `BackupStale`) needs `last_success 0`; `BackupOffsiteStale`,
`BackupOffsiteFailed` and `BackupEncryptionDisabledInProduction` need `production_mode 1`;
`BackupRequiredSeriesMissing` needs `production_mode 1` without a paired gauge;
`BackupTelemetryAbsent` is about the absence of all production series. The real
`azure-hosted-beta` series is untouched, so the real backup health keeps alerting normally and
the 03:30 backup run, which rewrites `parkio_backup.prom` only, cannot overwrite the synthetic
file.

node-exporter accepts the second file: on `prom/node-exporter:v1.8.2` with both files present,
`/metrics` exposes both scopes and `node_textfile_scrape_error 0` (checked on
2026-10-07 with the same metric families and HELP text; evidence in
`agent-tools/parkio-u06-live-backup-stale-plan/`). The preflight checks the live
`node_textfile_scrape_error` before arming and refuses if any `Backup*` alert is already pending
or firing.

The Slack message does **not** show the scope: the rendered template prints the summary
("Hosted-beta backup is stale"), the environment, the runbook link and the alert name, so the
synthetic FIRING and RESOLVED messages look exactly like a real `BackupStale`. The announcement in
`#parkio-alert` before arming and after disarming is the only disambiguation; it is a required step,
not a courtesy.

## Procedure (operator, on the host, under authorization)

**Prerequisite (host change, separately authorized): the host must run the current alerting
configuration.** The delivery-failure rules (`docker/prometheus/alerts.yml`, group
`parkio-alert-delivery`) and the Prometheus `alertmanager` scrape job (`docker/prometheus/prometheus.yml`)
were added on 2026-10-04 (`c87003e9`). The host's last recorded configuration predates both: the
2026-09-30 inventory recorded `prometheus.yml` at `bf9cad51` (27 scrape targets, no `alertmanager` job,
75 alert rules) and the 2026-10-01 apply kept the host `alerts.yml` unchanged. On that configuration
`delivery-rules` fails (both rules not loaded, no `alertmanager_notifications_failed_total` series) and
`preflight` fails (no `alertmanager_notifications_total` series, so a delivery could never be observed).
Deploying those two files and recreating Prometheus is a host change that needs its own authorization;
it is a release input, not part of this acceptance. Run `delivery-rules` first: it is the read-only check
that the prerequisite holds.

All commands run as the deploying user on `parkio-civo-prod`, from `/opt/parkio`, with
`PARKIO_PROMETHEUS_TEXTFILE_DIR=/opt/parkio/docker/prometheus/textfile` and
`PARKIO_LIVE_EVIDENCE_DIR` pointing outside the checkout (for example
`~/acceptance/<date>-backup-stale`). The user must be able to create a file in the textfile
directory (`arm` fails closed at `mktemp` otherwise). The inventory recorded the directory as a host
bind but not its owner; check it with `stat -c '%U %a' /opt/parkio/docker/prometheus/textfile` before
the window. Prometheus and Alertmanager are read on loopback
(`127.0.0.1:9090`, `127.0.0.1:9093`, the hosted-beta overlay's published ports), so the operator
either runs the script on the host or through the SSH tunnel with `PARKIO_LIVE_PROM_URL` /
`PARKIO_LIVE_AM_URL`; the preflight fails closed if either is unreachable. The script never prints
a webhook URL and never reads `docker/.env`. A poll that cannot read Prometheus or Alertmanager is
recorded as `poll_ok=0` and concludes nothing; a first poll that cannot read them refuses to start
observing, so a transient error can never become a false baseline.

0. **Prerequisite check** (read-only): `scripts/alerting-live-backup-stale-acceptance.sh delivery-rules`. Stop on any `FAIL`: the host does not yet run the current alerting configuration (see the prerequisite above).
1. **Announce** in `#parkio-alert`: synthetic `BackupStale` for scope `invite-production` for about 90 minutes; FIRING and RESOLVED messages are expected; the real backup is not affected.
2. **Preflight** (read-only): `scripts/alerting-live-backup-stale-acceptance.sh preflight`. It records host, real scope, synthetic scope, readiness of both services, the loaded rule, `node_textfile_scrape_error`, the absence of active `Backup*` alerts, the receiver type and channel, and the notification counters as the baseline (`live-backup-stale-acceptance/events.jsonl`, type `preflight`). Stop on any `FAIL`.
3. **Arm**: `PARKIO_LIVE_ALERT_ACCEPTANCE=ALERTING-LIVE-BACKUP-STALE scripts/alerting-live-backup-stale-acceptance.sh arm --yes`. Records the arm time and the stale timestamp.
4. **Observe to firing**: `scripts/alerting-live-backup-stale-acceptance.sh observe --until firing`. One JSON line every 30 s with the Prometheus state (`pending` after the first scrape, `firing` after the 1 h hold), the Alertmanager state, receiver, fingerprint and `startsAt`/`endsAt`, the other active alerts, and both notification counters. It stops when the alert is firing, held by Alertmanager, and `alertmanager_notifications_total` has increased since the baseline: that increase, within 15 s of the firing (`group_wait`), is the delivery. Expect about 61 to 66 minutes.
5. **Record the Slack FIRING message**: time from the Slack client (screenshot without the webhook, and the message timestamp or permalink); if a Slack read credential is available to the session, read the `#parkio-alert` message timestamp with it and store it beside the evidence. Note any other alert that fired during the window (the script lists them as `other_active`), because each one also increments the counter.
6. **Disarm**: `PARKIO_LIVE_ALERT_ACCEPTANCE=ALERTING-LIVE-BACKUP-STALE scripts/alerting-live-backup-stale-acceptance.sh disarm --yes`. The file is removed; the series disappear at the next scrape; the alert turns inactive in Prometheus and Alertmanager sends RESOLVED at its next group flush (within `group_interval`, 5 min, plus `resolve_timeout`).
7. **Observe to resolved**: `scripts/alerting-live-backup-stale-acceptance.sh observe --until resolved`. The tool reads the scope it armed from `armed-scope` in the evidence directory, so the file's removal does not change what it watches. Stops when the alert is inactive in Prometheus, gone from Alertmanager, and the counter increased again. Record the Slack RESOLVED message as in step 5.
8. **No duplicate**: over the whole window the counter must increase by exactly two for this alert group (FIRING once, RESOLVED once) once other alerts seen in `other_active` are accounted for. Two caveats: the critical route groups by `alertname`, `service`, `severity` and `component`, not by `scope`, so a real `BackupStale` on the real scope in the same window would join the same group (the preflight refuses to arm while any `Backup*` alert is active); and `other_active` lists only alerts that are active at poll time, so a RESOLVED notification of another alert in the window also increments the counter without appearing there. Read `events.jsonl` with both in mind. `repeat_interval` for critical is 1 h; disarming within an hour of the firing guarantees there is no repeat. If the window is extended past one hour, one repeat is expected and is not a duplicate.
9. **Evidence**: `events.jsonl` (preflight, arm, polls with the Alertmanager alert's fingerprint, `startsAt` and `endsAt` in the `alertmanager_alert` field, milestones, disarm), the Slack timestamps, and the SHA-256 manifest go into `agent-tools/parkio-u06-live-backup-stale-acceptance/`. The fingerprint is the alert ID the task asks for. Record the running image digests of Prometheus, Alertmanager and node-exporter (`docker inspect --format '{{.Image}}'`) with the date.

Timing: the whole run takes about 90 minutes; the only live change is one extra file in the
textfile directory. Do not run it between 03:00 and 04:00 UTC (backup window): not because of
a conflict, but so a real backup result cannot be confused with the synthetic one.

## Receiver failure visibility (owner decision)

The criterion "receiver failure surfaces" is met in source by `AlertmanagerNotificationsFailing`
(two given-up notifications per integration in 15 min) and proved in the isolated acceptance,
where the catcher answers 503. Proving it live means breaking the real Slack receiver on the
host for 10 to 15 minutes, during which real alerts are not delivered and the failure alert
itself only reaches the Prometheus and Alertmanager UIs (it travels the broken path). Options:

- **A (recommended, prepared 2026-10-08):** accept the isolated proof plus a live read-only check of
  the delivery-failure rules, and rely on the heartbeat / dead-man's switch (`alerting.md#heartbeat`)
  as the independent path once the owner activates it. No live outage window. **Prerequisite:** the
  host runs the current alerting configuration (see the procedure's prerequisite: a separately
  authorized host change); on the host's last recorded configuration this check fails. The live part
  is then one read-only command on the host, run in the same authorized session as the acceptance:

      PARKIO_LIVE_EVIDENCE_DIR=<evidence dir> scripts/alerting-live-backup-stale-acceptance.sh delivery-rules

  It passes only when `AlertmanagerNotificationsFailing` and `PrometheusNotificationsFailing` are
  loaded, healthy (no evaluation error), evaluated within the last 10 minutes and inactive, and the
  counters they read (`alertmanager_notifications_failed_total`, `prometheus_notifications_errors_total`)
  have series. It records the host, the Prometheus version and, per rule, its group and loaded
  expression in the evidence, and prints no URL. The isolated half is the
  alerting acceptance in CI (`scripts/alerting-acceptance.sh`: the catcher answers 503 and
  `AlertmanagerNotificationsFailing` fires for `integration="webhook"`).
  **Scope note:** A does not observe a receiver failure on the live host, so it does not satisfy the
  original criterion "receiver failure surfaces" as written; accepting it is an owner scope decision.
  Its independent path (the heartbeat) counts only once the heartbeat is activated.
- **B (currently excluded by the owner's 2026-10-07 ruling "Do not interrupt the live alert receiver";
  available only if the owner lifts it):** a scheduled live break: set `PARKIO_ALERT_SLACK_WEBHOOK_URL` to an unroutable
  `https://127.0.0.1:9/` in the host env, recreate Alertmanager, arm the synthetic alert as
  above, watch `alertmanager_notifications_failed_total{integration="slack"}` reach 2 and
  `AlertmanagerNotificationsFailing` fire in Prometheus, then restore the real URL and recreate
  Alertmanager again. Real alerts are blind for the window; needs its own authorization and a
  rollback rehearsal of the env edit.

## Rollback

- Disarm removes the one synthetic file; nothing else was changed. If the script is unavailable,
  `rm /opt/parkio/docker/prometheus/textfile/parkio_backup_synthetic_stale.prom` has the same
  effect; the file carries a marker line naming this tool.
- Never edit, move or delete `parkio_backup.prom`.
- No silence is created and none is needed; the alert resolves by itself after disarm.
- If the FIRING message does not arrive within 20 minutes of the Prometheus `firing` state,
  disarm, keep the evidence, and treat it as a delivery defect to investigate (counters, the
  Alertmanager log with the URL redacted, `AlertmanagerNotificationsFailing`).

## What this proves and does not prove

Proves: the metric → rule → Alertmanager → Slack path delivers FIRING and RESOLVED exactly once
for a real rule on the live host, with dated timestamps and the running image identities.
Does not prove: delivery while the host or Alertmanager is down (that is the heartbeat), the
behaviour of the real backup job, or anything about a second receiver.

## Authorization boundaries

Live execution (steps 3 to 7, and option B) needs explicit authorization naming the host and the
window. The run sends real Slack messages. No production data, backup, restore, firewall,
container or secret changes; the real backup telemetry is never modified.

## Tests

`scripts/test-alerting-live-backup-stale-acceptance.sh` runs the tool against a fake Prometheus,
Alertmanager and host name: scope selection and refusals, confirmation token, the synthetic file
content and the untouched real file, observe semantics (a firing alert without a counter increase
is not a delivery), disarm's marker check, that neither the output nor the evidence ever
contains the receiver URL, preflight's refusal of a missing notification series, and the
`delivery-rules` refusals (missing, duplicated, unhealthy, firing, never or long-ago evaluated rules,
UTC-offset timestamps, missing counters, an answer over 128 KiB). It runs in the Observability
validation workflow.
