-- U05 moderation erasure completeness. Erasure rewrites a user's identities to the sentinel
-- 00000000-0000-4000-8000-000000000001 and retains the rows (PRIV-001 policy). The full UNIQUE
-- constraints below made that impossible when the sentinel already held an equivalent row:
--   * two erased users reported the same target for the same reason,
--   * two erased users appealed the same case,
--   * one reporter reported two erased users (target_id -> sentinel) for the same reason.
-- The erase then either skipped the row (leaving the real user id behind while still reporting
-- SUCCESS) or failed on the constraint. Uniqueness stays enforced for real users only: the
-- application checks for duplicates before inserting, and a real-user duplicate still violates
-- these partial indexes. Existing data already satisfies the stronger constraints.
ALTER TABLE user_reports DROP CONSTRAINT uq_user_reports_reporter_target_reason;
CREATE UNIQUE INDEX uq_user_reports_reporter_target_reason
    ON user_reports (reporter_user_id, target_type, target_id, reason)
    WHERE reporter_user_id <> '00000000-0000-4000-8000-000000000001'
      AND target_id <> '00000000-0000-4000-8000-000000000001';

ALTER TABLE appeals DROP CONSTRAINT uq_appeals_case_user;
CREATE UNIQUE INDEX uq_appeals_case_user
    ON appeals (case_id, appeal_user_id)
    WHERE appeal_user_id <> '00000000-0000-4000-8000-000000000001';
