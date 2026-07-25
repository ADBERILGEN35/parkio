-- =============================================================================
-- Parking session stale lifecycle indexes — CREATE INDEX CONCURRENTLY
-- =============================================================================
-- Matching Flyway V17 / V18 index names and definitions.
-- Run OUTSIDE a transaction (psql default autocommit). PostgreSQL 14+.
-- Prefer validate first: parking-session-indexes-validate.sql
-- Docs: parking-session-indexes-concurrent.md
-- =============================================================================

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_parking_sessions_stale_active
    ON parking_sessions (last_confirmed_at ASC, started_at ASC, id ASC)
    WHERE status = 'ACTIVE';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_parking_sessions_reminder_candidates
    ON parking_sessions (last_confirmed_at ASC, started_at ASC, id ASC)
    WHERE status = 'ACTIVE' AND reminder_stage < 2;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_parking_sessions_terminal_ended
    ON parking_sessions (ended_at ASC)
    WHERE status IN ('COMPLETED', 'CANCELLED');

ANALYZE parking_sessions;