# Alert response runbook (R5.2)

Operator guide for Prometheus alerts on hosted-beta. Alerts route through Alertmanager
when `PARKIO_ALERT_SLACK_WEBHOOK_URL` or `PARKIO_ALERT_WEBHOOK_URL` is set in `docker/.env`.
Paging architecture, secrets, silences, and the synthetic probe:
[alerting.md](./alerting.md).

## First steps (any alert)

1. Open Grafana (`http://127.0.0.1:3000` via SSH tunnel on the VPS).
2. Check **Parkio - API Health** and **Parkio - Hosted Beta Overview** dashboards.
3. `docker compose -f docker/docker-compose.yml -f docker/docker-compose.apps.yml -f docker/docker-compose.hosted-beta.yml ps`
4. Inspect firing alert in Prometheus → **Alerts** or Alertmanager UI (`:9093`).

## GatewayDown {#gatewaydown}

- Confirm `gateway-service` container is up and healthy.
- Check recent deploy (`deploy-artifacts/current.json` git SHA vs `git rev-parse HEAD`).
- Review gateway logs in Loki (Grafana → **Parkio - Client and API Errors**).
- If OOM: check **Parkio - JVM** and host memory alerts.

## CoreServiceDown {#coreservicedown}

- Identify which core service is down from the alert label.
- `docker logs parkio-<service>-1 --tail 200`
- Common causes: Flyway migration failure, DB unreachable, Kafka down, bad env secret.
- Roll back if caused by a bad deploy: `scripts/rollback-hosted-beta.sh`.

## Gateway5xx / GatewayHigh5xxRate {#gateway5xx}

- Correlate with **CoreServiceDown** or **PostgresDown**.
- Check downstream service logs for stack traces.
- Verify Kafka and outbox dashboards if errors mention messaging.

## GatewayHighLatencyP95 / GatewayLatencyP95High {#gatewayhighlatencyp95}

- Check host CPU/memory (**Parkio - Hosted Beta Overview** host row).
- Check DB pool pending connections (**Parkio - PostgreSQL**).
- Check Kafka consumer lag (**Parkio - Kafka**).

## KafkaConsumerLagHigh / KafkaConsumerLagSustained {#kafkaconsumerlagsustained}

- Identify `consumergroup` and `topic` from the alert.
- Restart the affected service after confirming Kafka is healthy.
- If lag persists, inspect poison messages / DLT topics.

## Outbox backlog / dead-letter {#outboxbacklog}

- **Parkio - Outbox and DLQ** dashboard.
- For dead-letters: `scripts/outbox-deadletter-recovery.sh`
- For Kafka DLT: `scripts/kafka-dlt-redrive.sh` (dry-run first).

## Infra: Postgres / Redis / MinIO / ClamAV {#postgresdown}

| Alert | Check |
|-------|--------|
| PostgresDown | `docker compose ps postgres-*`; disk space; volume mounts |
| RedisDown | `docker exec parkio-redis redis-cli ping` |
| MinIODown | `curl http://127.0.0.1:9000/minio/health/live` (tunnel) |
| ClamAVDown | First boot can take minutes for virus DB; check `docker logs parkio-clamav` |

## Host disk / memory {#host}

- See `docs/architecture/observability-metrics.md` host runbooks.
- Prune Docker images/logs, verify backup retention, check Loki/Tempo volume growth.

## Parking session stale lifecycle {#parkingsessionschedulerfailures}

Alerts: `ParkingSessionSchedulerFailures`, `ParkingSessionAutoCompleteSpike`,
`ParkingSessionSchedulerFailuresSustained` (formerly misnamed
`ParkingSessionReminderPublicationIdleWhileActive` — it tracks **repeated
scheduler failures**, not idle reminder publication).

Full playbooks: [parking-session-stale-runbook.md](./parking-session-stale-runbook.md).

Quick checks:

```bash
curl -fsS http://127.0.0.1:8083/actuator/prometheus | rg parking_sessions_
docker logs parkio-parking-service-1 --tail 200 | rg -i 'Stale parking-session|AUTO_COMPLETED|REMINDER_SENT'
docker compose -f docker/docker-compose.yml -f docker/docker-compose.apps.yml -f docker/docker-compose.hosted-beta.yml \
  ps parking-service notification-service analytics-service kafka postgres-parking
```

```promql
increase(parking_sessions_scheduler_failed_total{service="parking-service"}[30m])
increase(parking_sessions_auto_completed_total{service="parking-service"}[1h])
parkio_outbox_unpublished_count{service="parking-service"}
max by (consumergroup) (kafka_consumergroup_lag{topic="parkio.parking.session"})
```

Feature-flag rollback (recreate parking-service after env change):
`PARKIO_PARKING_SESSION_STALE_ENABLED=false` or disable reminders /
auto-complete individually (see stale runbook).

## HighJvmMemoryUsage {#highjvmmemoryusage}

- Heap >90% of max for 10m. Inspect Grafana **Parkio - JVM** and `docker stats`.
- Safe first action: confirm no leak (steady climb vs a traffic spike). Restarting a JVM drops the symptom and loses in-memory state — do that only after checking health and current requests.
- Do not raise heap without checking host `HostHighMemoryUsage*` (10 JVMs on one VPS).
- Escalation: roll back a recent deploy if the climb started after it.

## BackupFailed / BackupStale / offsite / encryption {#backupfailed}

- Meaning: last backup **attempt metrics** say failed, stale, offsite-failed, or encryption off in production mode. File existence is not success.
- Inspect: `parkio_backup_*` in Prometheus, `/var/log/parkio-backup.log`, `backup-artifacts/`.
- Safe first action: re-run `scripts/backup-hosted-beta.sh` with the intended `BACKUP_PRODUCTION_MODE` still set.
- Do not: disable encryption, skip offsite, or "fix" the alert by writing a fake `parkio_backup.prom`.
- Escalation: [backup-runbook.md](./backup-runbook.md), [restore-runbook.md](./restore-runbook.md).

## ParkioAlertingAcceptanceTest {#parkioalertingacceptancetest}

- Meaning: synthetic plumbing probe is armed. Not a product outage.
- Inspect: who armed `parkio_alerting_acceptance_test`.
- Safe first action: set the gauge to 0 / remove the textfile.
- Do not leave it firing on hosted-beta.
- Procedure: [alerting.md](./alerting.md#synthetic-acceptance).

## Escalation

- Capture alert labels, Grafana screenshots, and `docker compose ps`.
- If data loss suspected, stop writes and consult [restore-runbook.md](./restore-runbook.md).
- Do not paste webhook URLs, tokens, or DB passwords into tickets or chat.
