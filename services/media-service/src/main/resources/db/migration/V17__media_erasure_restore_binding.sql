-- U02 restore replay (docs/architecture/erasure-restore-replay-contract.md): an erasure job can
-- carry the restore binding of an isolated recovery instead of a live erase request. Its SUCCESS is
-- then the attempt-bound restore ACK, queued under the same rule as the live one: only once every
-- stored object of the user is confirmed gone. A job has exactly one binding; live jobs are
-- unchanged.
ALTER TABLE media_erasure_jobs ALTER COLUMN erasure_request_id DROP NOT NULL;
ALTER TABLE media_erasure_jobs ADD COLUMN recovery_attempt_id UUID;
ALTER TABLE media_erasure_jobs ADD COLUMN restored_dataset_id VARCHAR(256);
ALTER TABLE media_erasure_jobs ADD COLUMN erasure_set_digest VARCHAR(64);
ALTER TABLE media_erasure_jobs ADD CONSTRAINT ck_media_erasure_jobs_binding CHECK (
    (erasure_request_id IS NOT NULL
        AND recovery_attempt_id IS NULL AND restored_dataset_id IS NULL AND erasure_set_digest IS NULL)
    OR (erasure_request_id IS NULL
        AND recovery_attempt_id IS NOT NULL AND restored_dataset_id IS NOT NULL AND erasure_set_digest IS NOT NULL));
