-- Documents the parking-spot status values used by the AI publication gate.
-- status is already VARCHAR(32) with no CHECK constraint (see V2); this migration
-- is intentionally a no-op DDL comment for operational clarity.
--
-- New statuses (server-authoritative AI gate):
--   PENDING_VALIDATION - created, waiting for AI; not publicly discoverable
--   PENDING_REVIEW     - AI uncertain/warning; not publicly discoverable
-- Existing:
--   ACTIVE, VERIFIED, SUSPICIOUS, FILLED, EXPIRED, REJECTED
SELECT 1;