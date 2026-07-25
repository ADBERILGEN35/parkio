-- Evolve stale Parking Session handling: reminder stages, completion reason, retention indexes.

ALTER TABLE parking_sessions
    ADD COLUMN reminder_stage SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN completion_reason VARCHAR(32);

UPDATE parking_sessions
SET completion_reason = 'MANUAL'
WHERE status IN ('COMPLETED', 'CANCELLED')
  AND completion_type = 'MANUAL'
  AND completion_reason IS NULL;

UPDATE parking_sessions
SET completion_reason = 'AUTO_TIMEOUT'
WHERE status = 'COMPLETED'
  AND completion_type = 'AUTO'
  AND completion_reason IS NULL;

ALTER TABLE parking_sessions
    DROP CONSTRAINT IF EXISTS ck_parking_sessions_completion_type;

ALTER TABLE parking_sessions
    ADD CONSTRAINT ck_parking_sessions_completion_type
        CHECK (
            (status = 'ACTIVE' AND completion_type IS NULL AND completion_reason IS NULL)
            OR (status = 'COMPLETED'
                AND completion_type IN ('MANUAL', 'AUTO')
                AND completion_reason IN ('MANUAL', 'AUTO_TIMEOUT', 'USER_CONFIRMATION', 'ADMIN', 'SYSTEM', 'API', 'MIGRATION'))
            OR (status = 'CANCELLED'
                AND completion_type = 'MANUAL'
                AND completion_reason IN ('MANUAL', 'USER_CONFIRMATION', 'ADMIN', 'SYSTEM', 'API', 'MIGRATION'))
        );

ALTER TABLE parking_sessions
    ADD CONSTRAINT ck_parking_sessions_reminder_stage
        CHECK (reminder_stage >= 0 AND reminder_stage <= 2);

CREATE INDEX idx_parking_sessions_reminder_candidates
    ON parking_sessions (last_confirmed_at ASC, started_at ASC, id ASC)
    WHERE status = 'ACTIVE' AND reminder_stage < 2;

CREATE INDEX idx_parking_sessions_terminal_ended
    ON parking_sessions (ended_at ASC)
    WHERE status IN ('COMPLETED', 'CANCELLED');