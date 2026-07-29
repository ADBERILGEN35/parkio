#  shadow job backlog.Groups[1].Value + shadow job backlog.Groups[2].Value.ToUpper() hadow shadow job backlog.Groups[1].Value + shadow job backlog.Groups[2].Value.ToUpper() ob shadow job backlog.Groups[1].Value + shadow job backlog.Groups[2].Value.ToUpper() acklog

| Field | Detail |
|-------|--------|
| Severity | SEV-3 |
| Owner | Platform |
| Alert | See docker/prometheus/alerts.yml or operational-readiness-alert-rules.yml |

## Symptom

Shadow scheduler lag

## Impact

Internal analytics stale

## Immediate safety action

Disable scheduler via kill switch

## Diagnostics

- Grafana: parkio-operational-readiness, parkio-api-health
- Prometheus: http://localhost:9090
- Logs: Loki via Grafana
- Compose: docker compose ps (from docker/)

## Containment

Clear backlog in batches

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