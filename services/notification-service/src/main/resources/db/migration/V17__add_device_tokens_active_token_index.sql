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
-- a transaction (the restore-drill fixture applies the scripts with psql).
--
-- IF NOT EXISTS lets an operator create the same index CONCURRENTLY before a deploy, should the table ever
-- be large. It accepts any index of that name, though, so the DO block then refuses one that cannot serve:
-- INVALID or not ready, as a failed or interrupted CREATE INDEX CONCURRENTLY leaves it, or defined in any
-- other way. Drop such an index and deploy again.
SELECT set_config('lock_timeout', '5s', true);

CREATE INDEX IF NOT EXISTS idx_device_tokens_active_token
    ON device_tokens (token)
    WHERE active;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_index i
        WHERE i.indexrelid = to_regclass('idx_device_tokens_active_token')
          AND i.indrelid = to_regclass('device_tokens')
          AND i.indisvalid
          AND i.indisready
          AND regexp_replace(pg_get_indexdef(i.indexrelid), ' ON \S+ USING ', ' ON device_tokens USING ')
              = 'CREATE INDEX idx_device_tokens_active_token ON device_tokens USING btree (token) WHERE active'
    ) THEN
        RAISE EXCEPTION 'idx_device_tokens_active_token is not a valid btree index on device_tokens (token) WHERE active: drop it and deploy again';
    END IF;
END
$$;
