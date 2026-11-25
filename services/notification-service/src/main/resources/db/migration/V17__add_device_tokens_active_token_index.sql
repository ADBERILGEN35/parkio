-- #601: every push-token registration runs the token handover,
--   UPDATE device_tokens SET active = false, ... WHERE token = ? AND user_id <> ? AND active = true,
-- while it holds the token's advisory lock. No index led with token (only uq_device_tokens_user_token
-- (user_id, token) and idx_device_tokens_user), so each registration scanned the whole table.
--
-- Expand-only: one new partial index, nothing else changes. It holds the active rows, which is the
-- handover's predicate; deactivated rows, kept for delivery history, stay out of it. It is not unique,
-- so existing rows, duplicates included, are left as they are.
--
-- Not CONCURRENTLY. device_tokens holds one row per registered app install, so the build takes
-- milliseconds, under a SHARE lock that blocks writes to device_tokens but not reads. CREATE INDEX
-- CONCURRENTLY cannot run in Flyway's migration transaction. As a non-transactional migration it would also
-- need spring.flyway.postgresql.transactional-lock=false, or it waits on Flyway's own lock transaction, and a
-- failed build leaves an INVALID index that has to be dropped by hand.
--
-- lock_timeout bounds the wait for the SHARE lock behind a long writer: the migration fails and the service
-- does not start, instead of new registrations queueing behind it. set_config(..., true) is SET LOCAL as a
-- function: it ends with Flyway's migration transaction, and it stays silent where a file is run without
-- a transaction (the restore-drill fixture applies the scripts with psql). IF NOT EXISTS lets an operator
-- create the same index CONCURRENTLY before a deploy, should the table ever be large.
SELECT set_config('lock_timeout', '5s', true);

CREATE INDEX IF NOT EXISTS idx_device_tokens_active_token
    ON device_tokens (token)
    WHERE active;
