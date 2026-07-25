-- Moderation lifecycle columns for parking_spots (additive; V2..V15 are untouched).
--
-- Fixes the defect where a spot's advertised lifetime was consumed while it waited on
-- moderation: `expires_at` was stamped at creation and never recomputed on approval, so a
-- slow pipeline could expire a spot before it was ever visible.
--
-- Design decisions baked into this migration:
--   * `expires_at` becomes nullable. While pending it is NULL (no placeholder far-future
--     value that could leak into countdowns, caches, or client math). It is set exactly
--     once at publication.
--   * Historical "expired while still pending" rows are NOT auto-rescued here. Resurrecting
--     them into PENDING would risk republishing availability reports that are already too
--     old to trust. Operators run the diagnostic in the runbook, then the separately
--     reviewed remediation script if a row is still within max-publishable-age.
--
--   activated_at           publication instant; the TTL start. NULL while pending. Being
--                          NULL/NOT-NULL is the idempotence key that makes the expiry
--                          computation happen exactly once per spot.
--   moderation_deadline_at when the pipeline must have reached a verdict. Drives the
--                          bounded-retry / terminal-failure job.
--   moderation_attempts    bounded count of AI publication-gate re-requests.
--   moderation_decided_at  watermark for the out-of-order guard: a verdict older than
--                          this is ignored rather than overwriting a newer state.
--   moderation_request_id  upstream event id, carried for tracing/structured logs.
--
-- The new terminal status value REVIEW_FAILED needs no DDL: `status` is VARCHAR(32) with
-- no CHECK constraint (V2) and V14 already documents the value set.
ALTER TABLE parking_spots
    ADD COLUMN activated_at           TIMESTAMPTZ,
    ADD COLUMN moderation_deadline_at TIMESTAMPTZ,
    ADD COLUMN moderation_attempts    INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN moderation_decided_at  TIMESTAMPTZ,
    ADD COLUMN moderation_request_id  UUID;

-- Pending spots have no running visibility window; NULL is the honest representation.
ALTER TABLE parking_spots
    ALTER COLUMN expires_at DROP NOT NULL;

UPDATE parking_spots
   SET expires_at = NULL
 WHERE status IN ('PENDING_VALIDATION', 'PENDING_REVIEW');

-- Backfill the publication instant from the append-only status history, which records the
-- exact moment each spot first became ACTIVE. This is authoritative; `updated_at` is not
-- (it tracks the *latest* change, which for an expired spot is its expiry, not its
-- publication).
UPDATE parking_spots s
   SET activated_at = h.first_active_at
  FROM (
      SELECT spot_id, MIN(created_at) AS first_active_at
        FROM parking_spot_status_history
       WHERE new_status = 'ACTIVE'
       GROUP BY spot_id
  ) h
 WHERE s.id = h.spot_id
   AND s.activated_at IS NULL;

-- Legacy/seeded rows that reached a published state without a history trail: fall back to
-- creation time so the expired-before-approved invariant gauge does not false-alarm on
-- data that predates the audit trail. REJECTED / REVIEW_FAILED / never-published EXPIRED
-- rows keep a NULL activated_at (they were genuinely never published).
UPDATE parking_spots
   SET activated_at = created_at
 WHERE activated_at IS NULL
   AND status IN ('ACTIVE', 'VERIFIED', 'SUSPICIOUS', 'FILLED');

-- Any spot that has left the pending statuses has, by definition, been decided.
UPDATE parking_spots
   SET moderation_decided_at = updated_at
 WHERE moderation_decided_at IS NULL
   AND status NOT IN ('PENDING_VALIDATION', 'PENDING_REVIEW');

-- Still-pending spots get a short remaining review window from now, capped by the
-- max-publishable-age ceiling measured from creation (defaults: 15m review / 30m age).
-- Already-stale pending rows therefore become immediately overdue for the timeout job
-- and move to REVIEW_FAILED rather than being published late.
UPDATE parking_spots
   SET moderation_deadline_at = LEAST(
           now() + INTERVAL '15 minutes',
           created_at + INTERVAL '30 minutes'
       )
 WHERE moderation_deadline_at IS NULL
   AND status IN ('PENDING_VALIDATION', 'PENDING_REVIEW');

-- Non-pending rows settle at creation (deadline is irrelevant once decided).
UPDATE parking_spots
   SET moderation_deadline_at = created_at
 WHERE moderation_deadline_at IS NULL;

ALTER TABLE parking_spots
    ALTER COLUMN moderation_deadline_at SET NOT NULL;

-- Drives the moderation timeout/retry job's claim query (findModerationTimeoutCandidates).
CREATE INDEX idx_parking_spots_moderation_deadline
    ON parking_spots (moderation_deadline_at)
 WHERE status IN ('PENDING_VALIDATION', 'PENDING_REVIEW');

-- Supports the expired-before-approved invariant gauge, which must always read zero.
CREATE INDEX idx_parking_spots_activated_at
    ON parking_spots (activated_at);
