# Parkio k6 Performance Harness

This directory contains repeatable load probes for the Parkio gateway. They are
measurement tools only; they do not change application behavior or production
configuration.

## HTTP Benchmark

Run against a local or Compose stack with seeded non-production credentials:

```bash
PARKIO_BASE_URL=http://localhost:8080 \
PARKIO_K6_EMAIL=user@real-e2e.parkio.local \
PARKIO_K6_PASSWORD='StrongParkio123' \
k6 run --summary-export benchmarks/reports/http-load-summary.json benchmarks/k6/http-load.js
```

Pair a run with a Prometheus snapshot:

```bash
PROMETHEUS_URL=http://localhost:9090 \
bash benchmarks/scripts/collect-prometheus-baseline.sh benchmarks/reports/prometheus-baseline
```

Default traffic covers login, refresh, logout, profile reads, gamification,
notifications, nearby search, geocoding, spot details when data exists,
moderation user reads, and personal analytics reads.

Optional write-heavy probes are disabled by default because they create durable
media, parking, outbox and analytics data:

```bash
PARKIO_K6_ENABLE_UPLOAD=true k6 run benchmarks/k6/http-load.js
```

Admin-only analytics endpoints are also disabled by default:

```bash
PARKIO_K6_ENABLE_ADMIN=true k6 run benchmarks/k6/http-load.js
```

## Inputs

| Variable | Default | Purpose |
| --- | --- | --- |
| `PARKIO_BASE_URL` | `http://localhost:8080` | Gateway origin. |
| `PARKIO_K6_EMAIL` | required | Seeded test user email. |
| `PARKIO_K6_PASSWORD` | required | Seeded test user password. |
| `PARKIO_K6_VUS` | `10` | Authenticated read-flow virtual users. |
| `PARKIO_K6_DURATION` | `2m` | Steady-state duration. |
| `PARKIO_K6_AUTH_RATE` | `2` | Login/refresh/logout arrivals per second. |
| `PARKIO_K6_ENABLE_UPLOAD` | `false` | Enables media upload and spot creation. |
| `PARKIO_K6_ENABLE_ADMIN` | `false` | Enables admin analytics probes. |

## CI Behavior

`.github/workflows/performance-smoke.yml` starts the local Compose stack on a
GitHub-hosted Linux runner, seeds a dedicated USER account, runs this k6 script
at a small smoke-test load, and uploads summaries plus compose logs on failure.

The CI smoke is not a capacity test. Use it to catch gross performance/runtime
regressions and to verify the benchmark harness still runs. Capacity numbers
must come from longer controlled runs on known hardware.
