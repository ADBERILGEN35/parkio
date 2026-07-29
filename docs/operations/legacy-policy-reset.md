# Legacy parking policy reset (operator runbook)

## Purpose

Quietly move pre-`2026-07-photo-policy-v3-recall` parking inventory to `REJECTED` with
machine-readable reason `LEGACY_POLICY_RESET` after the recall-oriented AI gate is live.

This is **not** an AI content judgment. It does **not** emit `ParkingSpotRejectedByModerator`
(no owner penalty / notification storm).

In admin UI, migrated spots keep domain status `REJECTED` but display as
**“Reddedildi — Sistem politika geçişi”** / **“Rejected — System policy migration”**
with section title **“Sistem politika geçişi”**. See
[parking-spot-rejection-reasons.md](../architecture/parking-spot-rejection-reasons.md).

## Eligibility

| Status | Action |
|---|---|
| `PENDING_VALIDATION`, `PENDING_REVIEW`, `ACTIVE`, `VERIFIED`, `SUSPICIOUS` with `last_ai_policy_version` ≠ target (or null) | → `REJECTED` + `LEGACY_POLICY_RESET` |
| Already `REJECTED` | unchanged |
| `FILLED` / `EXPIRED` / `REVIEW_FAILED` | unchanged |
| Spots already evaluated under `2026-07-photo-policy-v3-recall` | unchanged |

Idempotency: rows with `rejection_reason_code = LEGACY_POLICY_RESET` are skipped on re-run.

## Prerequisites

1. Deploy schema `V27__parking_spot_rejection_metadata.sql` and application code that writes
   `last_ai_policy_version` / rejection metadata.
2. Confirm new submissions use policy `2026-07-photo-policy-v3-recall`.
3. Do **not** run against hosted-beta until unit/integration tests pass and an operator
   explicitly chooses to execute.

## Dry-run

```bash
export PARKIO_PARKING_LEGACY_POLICY_RESET_ENABLED=true
export PARKIO_PARKING_LEGACY_POLICY_RESET_DRY_RUN=true
export PARKIO_PARKING_LEGACY_POLICY_RESET_TARGET=2026-07-photo-policy-v3-recall
./scripts/parking/legacy-policy-reset.sh
```

Boot parking-service with those env vars (or `docker compose run --rm parking-service`).
Look for log line `Legacy policy reset DRY_RUN` with:

- eligible count
- status breakdown
- policy-version breakdown
- skipped rejected / terminal / new-policy counts

Dry-run performs **zero** writes.

## Execute

```bash
export PARKIO_PARKING_LEGACY_POLICY_RESET_ENABLED=true
export PARKIO_PARKING_LEGACY_POLICY_RESET_DRY_RUN=false
export PARKIO_PARKING_LEGACY_POLICY_RESET_BATCH_SIZE=500
./scripts/parking/legacy-policy-reset.sh
```

Re-run safely: second execution should report `updated=0` for already-migrated rows.

## Rollback

There is **no** automatic un-reject. Prefer forward-fix (new submissions under v3-recall).
Status restore requires backup restore or a separately approved targeted reverse — do not
script bulk un-reject without product approval.
