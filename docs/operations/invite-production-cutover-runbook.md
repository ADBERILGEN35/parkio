# Invite-production cutover runbook — prepared, do not execute in 01A

This runbook requires separate `PROD-DEPLOY-01B` authorization, an approved
window, a completed canonical checklist, and named operator/observer roles. It
contains no dual-write phase.

1. Confirm the exact candidate SHA/manifest and enable maintenance; block writes.
2. Take the final old-database backup without changing its data.
3. Verify the final backup is encrypted, checksummed, and uploaded offsite.
4. Dump source data once; record source timestamp and row-count evidence.
5. Restore into the already migrated managed databases under controlled restore
   identities; never restore into hosted-beta accidentally.
6. Validate row counts, constraints, service invariants, PostGIS, and checksums.
7. Replay the account-erasure tombstone ledger and prove erased identities do
   not become active.
8. Switch production database secret versions; do not change code or images.
9. Boot services in controlled batches and verify Flyway/Hikari/connection budget.
10. Run API/JWKS/auth/user/parking/provider/recommendation/deterministic-ranking,
    backup, alerting, and privacy smoke.
11. Hold an operator-only stage with maintenance still active; watch errors,
    DB connections, provider health, Kafka lag, and paging.
12. Only after explicit go/no-go, switch DNS/edge and enable the named invite
    cohort. Broad public access remains forbidden.

Stop immediately on integrity mismatch, tombstone replay failure, unexplained CI
or runtime red, invalid TLS/CORS, gateway exposure, missing paging, or backup
failure. Follow the rollback boundary document; never improvise a reverse sync.
