#  kafka outage or lag.Groups[1].Value + kafka outage or lag.Groups[2].Value.ToUpper() afka kafka outage or lag.Groups[1].Value + kafka outage or lag.Groups[2].Value.ToUpper() utage kafka outage or lag.Groups[1].Value + kafka outage or lag.Groups[2].Value.ToUpper() r kafka outage or lag.Groups[1].Value + kafka outage or lag.Groups[2].Value.ToUpper() ag

| Field | Detail |
|-------|--------|
| Severity | SEV-2 |
| Owner | Platform |
| Alert | See docker/prometheus/alerts.yml or operational-readiness-alert-rules.yml |

## Symptom

Outbox age high; consumer lag

## Impact

Async processing delayed

## Immediate safety action

Do not acknowledge poison messages manually

## Diagnostics

- Grafana: parkio-operational-readiness, parkio-api-health
- Prometheus: http://localhost:9090
- Logs: Loki via Grafana
- Compose: docker compose ps (from docker/)

## Containment

Restart kafka; drain outbox

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