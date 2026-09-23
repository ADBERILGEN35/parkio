-- Lock-held commit-visibility snapshot of auth.erased_user_tombstones.
-- Do not treat an unlocked SELECT plus wall-clock / query-time as coverage.
--
-- erased_at is assigned at request start (application Clock) and becomes
-- durable only when the surrounding transaction commits. A concurrent
-- requestDeletion can therefore have erased_at earlier than this watermark
-- and still be invisible until COMMIT. SHARE MODE waits for those writers
-- and blocks new inserts until this transaction ends.
--
-- coveredThrough is clock_timestamp() after the read, while the lock is
-- held. It is a commit horizon on the auth database clock: every tombstone
-- whose inserting transaction committed before that instant is in entries.
-- It is not a bound on erased_at, not a persist clock, and not authenticity.
--
-- This script is not installed by backup-common.sh or restore-drill-01.sh.

BEGIN;

LOCK TABLE erased_user_tombstones IN SHARE MODE;

SELECT json_build_object(
    'visibilityProtocol', 'table-share-lock',
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
