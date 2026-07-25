-- Stale Parking Session handling: confirmation heartbeat + completion provenance.
-- No new EXPIRED status - forgotten ACTIVE sessions auto-complete to COMPLETED.

ALTER TABLE parking_sessions
    ADD COLUMN last_confirmed_at TIMESTAMP,
    ADD COLUMN completion_type VARCHAR(16);

UPDATE parking_sessions
SET last_confirmed_at = started_at
WHERE status = 'ACTIVE'
  AND last_confirmed_at IS NULL;

UPDATE parking_sessions
SET completion_type = 'MANUAL'
WHERE status IN ('COMPLETED', 'CANCELLED')
  AND completion_type IS NULL;

ALTER TABLE parking_sessions
    ADD CONSTRAINT ck_parking_sessions_completion_type
        CHECK (
            (status = 'ACTIVE' AND completion_type IS NULL)
            OR (status = 'COMPLETED' AND completion_type IN ('MANUAL', 'AUTO'))
            OR (status = 'CANCELLED' AND completion_type = 'MANUAL')
        );

CREATE INDEX idx_parking_sessions_stale_active
    ON parking_sessions (last_confirmed_at ASC, started_at ASC, id ASC)
    WHERE status = 'ACTIVE';
