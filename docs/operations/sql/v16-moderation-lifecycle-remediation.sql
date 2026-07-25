-- =============================================================================
-- WP-MOD-01 operational remediation (MUTATING - review before run)
-- =============================================================================
-- NOT a Flyway migration. Run only after the read-only diagnostic shows rows that
-- (a) expired directly from a pending status AND (b) are still within
-- max-publishable-age (default 30 minutes from created_at).
--
-- Rows older than max-publishable-age must NOT be resurrected: republishing would
-- advertise a fresh TTL for an availability report that is already untrustworthy.
-- Leave those as EXPIRED, or (optionally) mark them REVIEW_FAILED for owner UX.
--
-- Wrap in a transaction and verify row counts before COMMIT.
-- =============================================================================

BEGIN;

-- Preview the candidates this script will touch.
SELECT s.id, s.created_at, now() - s.created_at AS age
  FROM parking_spots s
 WHERE s.status = 'EXPIRED'
   AND s.created_at > now() - INTERVAL '30 minutes'
   AND EXISTS (
       SELECT 1
         FROM parking_spot_status_history h
        WHERE h.spot_id = s.id
          AND h.new_status = 'EXPIRED'
          AND h.previous_status IN ('PENDING_VALIDATION', 'PENDING_REVIEW')
   );

-- Re-open only still-fresh incorrectly-expired pending spots.
UPDATE parking_spots s
   SET status                 = 'PENDING_VALIDATION',
       activated_at           = NULL,
       expires_at             = NULL,
       moderation_decided_at  = NULL,
       moderation_deadline_at = LEAST(
               now() + INTERVAL '15 minutes',
               s.created_at + INTERVAL '30 minutes'
           ),
       updated_at             = now()
 WHERE s.status = 'EXPIRED'
   AND s.created_at > now() - INTERVAL '30 minutes'
   AND EXISTS (
       SELECT 1
         FROM parking_spot_status_history h
        WHERE h.spot_id = s.id
          AND h.new_status = 'EXPIRED'
          AND h.previous_status IN ('PENDING_VALIDATION', 'PENDING_REVIEW')
   );

-- Verify counts, then COMMIT; or ROLLBACK;
