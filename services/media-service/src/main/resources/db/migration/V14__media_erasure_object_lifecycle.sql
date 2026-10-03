-- U05 media erasure: SUCCESS may be reported only after the metadata erase committed AND every
-- stored object of the user (every version and delete marker) is confirmed gone. Object deletion
-- is external I/O, so it runs outside the metadata transaction and its pending state is recorded
-- here: one row per consumed erase request event, keyed by the deterministic ACK event id of the
-- shared contract, holding the request and the user id the remaining work needs, retried with
-- backoff. Each media row is deleted once its object is confirmed gone, and the transaction that
-- queues the ACK deletes the job row, so neither media metadata nor job state outlives the erasure.
-- Additive only.
CREATE TABLE media_erasure_jobs (
    ack_event_id       UUID         NOT NULL,
    erasure_request_id UUID         NOT NULL,
    auth_user_id       UUID         NOT NULL,
    attempts           INTEGER      NOT NULL DEFAULT 0,
    last_error         VARCHAR(512),
    next_attempt_at    TIMESTAMPTZ  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_media_erasure_jobs PRIMARY KEY (ack_event_id),
    CONSTRAINT ck_media_erasure_jobs_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_media_erasure_jobs_due ON media_erasure_jobs (next_attempt_at);
