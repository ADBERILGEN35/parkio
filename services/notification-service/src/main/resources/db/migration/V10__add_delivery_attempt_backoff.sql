-- Retry backoff + cluster-safe polling for push delivery attempts.
-- next_attempt_at is the earliest instant the delivery worker may (re)try a
-- PENDING attempt; failures push it into the future with exponential backoff.
-- Terminal rows (SENT/FAILED/SKIPPED) carry NULL.
ALTER TABLE notification_delivery_attempts
    ADD COLUMN next_attempt_at TIMESTAMPTZ;

-- Existing PENDING rows become due immediately.
UPDATE notification_delivery_attempts
SET next_attempt_at = created_at
WHERE status = 'PENDING';

-- Replace the broad status index with a partial index matching the worker's
-- polling predicate (status = 'PENDING' AND next_attempt_at <= now()), keeping
-- the index small: terminal rows dominate the table over time.
DROP INDEX idx_nda_status_created;
CREATE INDEX idx_nda_pending_next_attempt
    ON notification_delivery_attempts (next_attempt_at)
    WHERE status = 'PENDING';
