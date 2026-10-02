-- U05 media erasure: one attempt at a time per job. Each attempt claims the job with a fresh
-- token and a lease (next_attempt_at holds the lease end while claimed). The transaction that
-- queues the SUCCESS ACK only proceeds while the job still carries that token and the lease has
-- not expired, so an attempt whose claim expired or was reclaimed by another worker never
-- finalizes. NULL means unclaimed. Additive only.
ALTER TABLE media_erasure_jobs ADD COLUMN claim_token UUID;
