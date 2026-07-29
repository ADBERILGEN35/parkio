# Capacity and Load Plan

Load profiles: DEVELOPMENT, BASELINING, STAGING, EXPECTED_PRODUCTION (PRODUCT INPUT), STRESS, SOAK.

Parking search: max-result-limit 50, max-radius 50000m (application.yml).

Scheduler batch sizes: see parkio.lifecycle.*.batch-size in parking application.yml.

k6 assets: benchmarks/k6/. performance-smoke.yml CI.

No arbitrary requests/sec claims without measurement.