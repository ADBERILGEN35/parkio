#  exposure latency regression.Groups[1].Value + exposure latency regression.Groups[2].Value.ToUpper() xposure exposure latency regression.Groups[1].Value + exposure latency regression.Groups[2].Value.ToUpper() atency exposure latency regression.Groups[1].Value + exposure latency regression.Groups[2].Value.ToUpper() egression

| Field | Detail |
|-------|--------|
| Severity | SEV-2 |
| Owner | Platform |
| Alert | See docker/prometheus/alerts.yml or operational-readiness-alert-rules.yml |

## Symptom

Search latency with shadow on

## Impact

UX degradation

## Immediate safety action

Disable exposure-shadow.enabled

## Diagnostics

- Grafana: parkio-operational-readiness, parkio-api-health
- Prometheus: http://localhost:9090
- Logs: Loki via Grafana
- Compose: docker compose ps (from docker/)

## Containment

Verify search order unchanged

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