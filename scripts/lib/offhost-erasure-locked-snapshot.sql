-- Lock-held commit-visibility snapshot of auth.erased_user_tombstones.
-- Do not treat an unlocked SELECT plus wall-clock / query-time as coverage.
--
-- Isolation is explicit READ COMMITTED: SHARE MODE waits for in-flight
-- INSERTs and blocks new ones; the following SELECT then sees every row
-- whose inserting transaction has committed. coveredThrough is
-- clock_timestamp() while the lock is still held — a commit horizon on
-- the auth database clock, not a bound on erased_at.
--
-- lock_timeout / statement_timeout are bounded. ON_ERROR_STOP plus this
-- transaction means a timeout or error aborts: no COMMIT, no publishable
-- snapshot. Callers must COMMIT/ROLLBACK (release the lock) before any
-- remote persist. This script is not installed by backup-common.sh.

\set ON_ERROR_STOP on

BEGIN;
SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
SET LOCAL lock_timeout = '12s';
SET LOCAL statement_timeout = '20s';

LOCK TABLE erased_user_tombstones IN SHARE MODE;

SELECT json_build_object(
    'visibilityProtocol', 'table-share-lock',
    'isolation', 'read committed',
    'coveredThrough', to_char(
        clock_timestamp() AT TIME ZONE 'UTC',
        'YYYY-MM-DD"T"HH24:MI:SS"Z"'
    ),
    'entries', COALESCE((
        SELECT json_agg(
            json_build_object(
                'authUserId', auth_user_id,
                'erasedAt', to_char(
                    erased_at AT TIME ZONE 'UTC',
                    'YYYY-MM-DD"T"HH24:MI:SS"Z"'
                )
            )
            ORDER BY auth_user_id
        )
        FROM erased_user_tombstones
    ), '[]'::json)
) AS snapshot;

COMMIT;
