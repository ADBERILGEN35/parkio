-- Default-off durable recording status for persist-before-COMPLETE.
-- NULL means the flag was off or the row predates this column.
-- Public status stays on erasure_requests.status (IN_PROGRESS / COMPLETE).

ALTER TABLE erasure_requests
    ADD COLUMN durable_recording_status VARCHAR(32);

ALTER TABLE erasure_requests
    ADD CONSTRAINT chk_erasure_requests_durable_recording
    CHECK (
        durable_recording_status IS NULL
        OR durable_recording_status IN ('PENDING_DURABLE', 'DURABLY_RECORDED')
    );
