# New Relic log pilot — operator guide

**Status:** Source-ready. Log-first workflows do **not** require APM or tracing screens.  
**Default:** pilot **disabled**. Profile `nr-log-pilot` is opt-in.

## Open the log view

### With New Relic (when non-prod license is configured)

1. Open New Relic → **Logs**.
2. Filter `pipeline:"nr-log-pilot"` (Parkio pilot marker).
3. Optionally filter `environment:"<nonprod>"`.

> Examples below marked **NOT_EXECUTED_AGAINST_NR** were validated only against the local mock Log API unless noted.

### With local mock (isolated validation)

```bash
docker compose -f docker/docker-compose.newrelic-log-pilot.yml --profile nr-log-pilot up -d
# Mock store: GET http://127.0.0.1:18089/dump
# Fluent Bit health: GET http://127.0.0.1:2020/api/v1/health
```

## Select environment and service

**NRQL (NOT_EXECUTED_AGAINST_NR — template):**

```sql
SELECT * FROM Log
WHERE pipeline = 'nr-log-pilot'
  AND environment = 'nonprod-pilot'
  AND service = 'gateway-service'
SINCE 1 hour ago
```

Services in pilot: `gateway-service`, `auth-service`, `parking-service` only.

## Find recent errors

```sql
SELECT timestamp, service, message, correlation_id, error_code
FROM Log
WHERE pipeline = 'nr-log-pilot' AND level = 'ERROR'
SINCE 1 hour ago
LIMIT 50
```

## Search a request / correlation ID

Use only IDs that appear in logs (MDC `correlationId=`). Do not invent trace IDs.

```sql
SELECT timestamp, service, level, message
FROM Log
WHERE pipeline = 'nr-log-pilot' AND correlation_id = '<id>'
SINCE 1 day ago
```

## Read a complete exception

Multiline Java stacks are joined via Fluent Bit built-in `java` multiline parser before export. Search the ERROR line’s `correlation_id`, then expand the `message` field (may include `Caused by:` / `at …` frames).

## Compare logs around a release

Filter `release_id` (set from `PARKIO_GIT_SHA` at collector start — **source SHA, not image digest**):

```sql
SELECT count(*) FROM Log
WHERE pipeline = 'nr-log-pilot'
FACET release_id, level
SINCE 1 day ago
```

## Detect missing logs / collector problems

1. Fluent Bit HTTP health: `http://127.0.0.1:2020/api/v1/health` (local).
2. Mock stats: `http://127.0.0.1:18089/stats` → `accepted` should increase under load.
3. If NR silent: check `PARKIO_NR_LOG_HOST` region endpoint, license key present, TLS on, and collector logs for HTTP non-2xx.
4. Overflow: `storage.total_limit_size 50M` — oldest chunks dropped (at-least-once, loss possible).

## Disable pilot

```bash
docker compose -f docker/docker-compose.newrelic-log-pilot.yml --profile nr-log-pilot stop fluent-bit-nr-pilot
```

Applications continue; Promtail/Loki/Alertmanager unchanged.
