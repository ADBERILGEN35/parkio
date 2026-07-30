# Municipal parking source runbook (IZUM)

## Manual sync (hosted-beta / staging)

1. Ensure Flyway V28 applied.
2. Set:
   - `parkio.municipal.enabled=true`
   - `parkio.municipal.izum.enabled=true`
   - `parkio.municipal.manual-sync-enabled=true`
3. Trigger sync via municipal manual sync endpoint (source key `izmir-izum-otoparklar`).
4. Confirm `municipal_source_sync_runs.status` SUCCESS/PARTIAL_SUCCESS.
5. Confirm nearby facilities return `freshness=LIVE|AGING` with non-null `availableSpaces` only then.

## Scheduler

Enable only after soak:
`parkio.municipal.izum.scheduler-enabled=true`

## Rollback

1. Disable `parkio.municipal.izum.enabled` and scheduler.
2. Facilities remain; occupancy ages to STALE and free spaces disappear from API.
3. Do not drop V28 tables in production without a dedicated migration plan.

## Local fixture development

Use `src/test/resources/fixtures/municipal/izum/otoparklar-sample.json`.
Do not point CI at `openapi.izmir.bel.tr`.