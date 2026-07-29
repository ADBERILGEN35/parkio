#  reward shadow anomaly.Groups[1].Value + reward shadow anomaly.Groups[2].Value.ToUpper() eward reward shadow anomaly.Groups[1].Value + reward shadow anomaly.Groups[2].Value.ToUpper() hadow reward shadow anomaly.Groups[1].Value + reward shadow anomaly.Groups[2].Value.ToUpper() nomaly

| Field | Detail |
|-------|--------|
| Severity | SEV-3 |
| Owner | Platform |
| Alert | See docker/prometheus/alerts.yml or operational-readiness-alert-rules.yml |

## Symptom

Reward shadow duplicates/failures

## Impact

No user balance change (pending only)

## Immediate safety action

Disable reward-shadow job

## Diagnostics

- Grafana: parkio-operational-readiness, parkio-api-health
- Prometheus: http://localhost:9090
- Logs: Loki via Grafana
- Compose: docker compose ps (from docker/)

## Containment

Reconcile pending ledger

## Rollback

See ../rollback-runbook.md and ../kill-switch-catalogue.md.

## Recovery verification

- /actuator/health/readiness green for affected services
- Error rate normalized 15m
- Run smoke: scripts/smoke-hosted-beta.sh (hosted-beta)

## Escalation

SEV-1: incident commander within 15m per ../incident-management.md

## Data reconciliation

Document any backlog replay (outbox, Kafka DLT) per ../dlq-redrive-runbook.md.

## Follow-up

Post-incident review if SEV-2+.