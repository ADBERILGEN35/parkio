#  parking database outage.Groups[1].Value + parking database outage.Groups[2].Value.ToUpper() arking parking database outage.Groups[1].Value + parking database outage.Groups[2].Value.ToUpper() atabase parking database outage.Groups[1].Value + parking database outage.Groups[2].Value.ToUpper() utage

| Field | Detail |
|-------|--------|
| Severity | SEV-1 |
| Owner | Platform |
| Alert | See docker/prometheus/alerts.yml or operational-readiness-alert-rules.yml |

## Symptom

Parking readiness down

## Impact

Search and mutations unavailable

## Immediate safety action

Stop deploys; fail closed at gateway

## Diagnostics

- Grafana: parkio-operational-readiness, parkio-api-health
- Prometheus: http://localhost:9090
- Logs: Loki via Grafana
- Compose: docker compose ps (from docker/)

## Containment

Restore from backup if corruption

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