#  nearby search latency.Groups[1].Value + nearby search latency.Groups[2].Value.ToUpper() earby nearby search latency.Groups[1].Value + nearby search latency.Groups[2].Value.ToUpper() earch nearby search latency.Groups[1].Value + nearby search latency.Groups[2].Value.ToUpper() atency

| Field | Detail |
|-------|--------|
| Severity | SEV-2 |
| Owner | Platform |
| Alert | See docker/prometheus/alerts.yml or operational-readiness-alert-rules.yml |

## Symptom

p95 search latency elevated

## Impact

Map discovery slow

## Immediate safety action

Disable exposure shadow if enabled

## Diagnostics

- Grafana: parkio-operational-readiness, parkio-api-health
- Prometheus: http://localhost:9090
- Logs: Loki via Grafana
- Compose: docker compose ps (from docker/)

## Containment

Tune pool / query indexes

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