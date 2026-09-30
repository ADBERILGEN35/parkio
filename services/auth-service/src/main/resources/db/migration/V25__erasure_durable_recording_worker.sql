-- Restart-safe durable-recording worker metadata (default-off worker; no V24 rewrite).

ALTER TABLE erasure_requests
    ADD COLUMN durable_retry_attempt_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE erasure_requests
    ADD COLUMN durable_retry_next_at TIMESTAMPTZ;

ALTER TABLE erasure_requests
    ADD COLUMN durable_worker_claim_token UUID;

ALTER TABLE erasure_requests
    ADD COLUMN durable_worker_claim_expires_at TIMESTAMPTZ;

CREATE INDEX idx_erasure_requests_durable_worker_pending
    ON erasure_requests (durable_retry_next_at, requested_at)
    WHERE durable_recording_status IN ('PENDING_DURABLE', 'DURABLY_RECORDED')
      AND status NOT IN ('COMPLETE', 'FAILED_RETRYING');
