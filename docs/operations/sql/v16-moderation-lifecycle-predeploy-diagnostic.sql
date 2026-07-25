-- =============================================================================
-- WP-MOD-01 pre-deployment diagnostic (READ ONLY)
-- =============================================================================
-- Run against a production/staging parking DB *before* deploying V16.
-- Does not mutate any rows. Use the companion remediation script only after review.
--
-- Expected defaults after WP-MOD-01:
--   review-timeout       = 15 minutes
--   max-publishable-age  = 30 minutes
-- =============================================================================

-- 1) How many spots would V16 leave as EXPIRED-but-never-published?
--    (These used to be auto-rescued; they now stay EXPIRED until ops remediates.)
SELECT count(*) AS expired_while_still_pending
  FROM parking_spots s
 WHERE s.status = 'EXPIRED'
   AND EXISTS (
       SELECT 1
         FROM parking_spot_status_history h
        WHERE h.spot_id = s.id
          AND h.new_status = 'EXPIRED'
          AND h.previous_status IN ('PENDING_VALIDATION', 'PENDING_REVIEW')
   );

-- 2) Detail of those rows, with freshness relative to max-publishable-age (30m).
SELECT s.id,
       s.status,
       s.created_at,
       s.expires_at,
       s.updated_at,
       now() - s.created_at AS age,
       CASE
           WHEN s.created_at > now() - INTERVAL '30 minutes' THEN 'still_publishable'
           ELSE 'too_old_to_publish'
       END AS remediation_advice
  FROM parking_spots s
 WHERE s.status = 'EXPIRED'
   AND EXISTS (
       SELECT 1
         FROM parking_spot_status_history h
        WHERE h.spot_id = s.id
          AND h.new_status = 'EXPIRED'
          AND h.previous_status IN ('PENDING_VALIDATION', 'PENDING_REVIEW')
   )
 ORDER BY s.created_at;

-- 3) Currently pending rows and whether they are already past max-publishable-age.
SELECT s.id,
       s.status,
       s.created_at,
       now() - s.created_at AS age,
       CASE
           WHEN s.created_at > now() - INTERVAL '30 minutes' THEN 'still_publishable'
           ELSE 'will_fail_stale_on_approval_or_timeout'
       END AS publishability
  FROM parking_spots s
 WHERE s.status IN ('PENDING_VALIDATION', 'PENDING_REVIEW')
 ORDER BY s.created_at;

-- 4) Published spots that lack an ACTIVE history trail (V16 will backfill activated_at
--    from created_at for ACTIVE/VERIFIED/SUSPICIOUS/FILLED only).
SELECT s.id, s.status, s.created_at
  FROM parking_spots s
 WHERE s.status IN ('ACTIVE', 'VERIFIED', 'SUSPICIOUS', 'FILLED')
   AND NOT EXISTS (
       SELECT 1
         FROM parking_spot_status_history h
        WHERE h.spot_id = s.id
          AND h.new_status = 'ACTIVE'
   );

-- 5) Status distribution snapshot for before/after comparison.
SELECT status, count(*) AS n
  FROM parking_spots
 GROUP BY status
 ORDER BY status;
