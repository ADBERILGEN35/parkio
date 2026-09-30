-- U05 media erasure: SUCCESS may be reported only after the metadata erase committed AND every
-- stored object of the user is confirmed deleted (or already absent). Object deletion is external
-- I/O, so it runs outside the metadata transaction and its progress is recorded durably here:
--   * media_files.object_deleted_at: when the stored object was confirmed deleted/absent;
--   * media_erasure_jobs: one row per consumed erase request event (keyed by the deterministic
--     ACK event id of the shared contract), retried with backoff until every object of the user
--     is confirmed, then the ACK is queued in the outbox in the same transaction as ACK_QUEUED.
-- Additive only; existing rows keep object_deleted_at NULL, so an erase re-confirms them
-- (deleting an absent object is a no-op for S3/MinIO).
ALTER TABLE media_files ADD COLUMN object_deleted_at TIMESTAMPTZ;

CREATE TABLE media_erasure_jobs (
    ack_event_id       UUID         NOT NULL,
    erasure_request_id UUID         NOT NULL,
    auth_user_id       UUID         NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    attempts           INTEGER      NOT NULL DEFAULT 0,
    last_error         VARCHAR(512),
    next_attempt_at    TIMESTAMPTZ  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    completed_at       TIMESTAMPTZ,
    CONSTRAINT pk_media_erasure_jobs PRIMARY KEY (ack_event_id),
    CONSTRAINT ck_media_erasure_jobs_status CHECK (status IN ('PENDING_OBJECTS', 'ACK_QUEUED')),
    CONSTRAINT ck_media_erasure_jobs_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_media_erasure_jobs_due ON media_erasure_jobs (status, next_attempt_at);
CREATE INDEX idx_media_files_owner_object_pending ON media_files (owner_user_id)
    WHERE object_deleted_at IS NULL;
