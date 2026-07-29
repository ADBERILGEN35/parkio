#  decision authority anomaly.Groups[1].Value + decision authority anomaly.Groups[2].Value.ToUpper() ecision decision authority anomaly.Groups[1].Value + decision authority anomaly.Groups[2].Value.ToUpper() uthority decision authority anomaly.Groups[1].Value + decision authority anomaly.Groups[2].Value.ToUpper() nomaly

| Field | Detail |
|-------|--------|
| Severity | SEV-1 |
| Owner | Platform |
| Alert | See docker/prometheus/alerts.yml or operational-readiness-alert-rules.yml |

## Symptom

Unexpected authority apply

## Impact

Publication may change

## Immediate safety action

IMMEDIATE: authority.enabled=false canary=0

## Diagnostics

- Grafana: parkio-operational-readiness, parkio-api-health
- Prometheus: http://localhost:9090
- Logs: Loki via Grafana
- Compose: docker compose ps (from docker/)

## Containment

Rollback config; incident review

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