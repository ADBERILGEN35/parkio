-- =============================================================================
-- Parking session stale lifecycle indexes — DROP INDEX CONCURRENTLY
-- =============================================================================
-- Rollback / pre-rebuild for V17 / V18 index names.
-- Run OUTSIDE a transaction. PostgreSQL 14+.
-- Docs: parking-session-indexes-concurrent.md
-- =============================================================================

DROP INDEX CONCURRENTLY IF EXISTS idx_parking_sessions_stale_active;
DROP INDEX CONCURRENTLY IF EXISTS idx_parking_sessions_reminder_candidates;
DROP INDEX CONCURRENTLY IF EXISTS idx_parking_sessions_terminal_ended;