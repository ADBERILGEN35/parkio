-- =============================================================================
-- Parking session stale lifecycle indexes — read-only validation
-- =============================================================================
-- Expect three rows, all indisvalid = true, after Flyway V17+V18 or after a
-- CONCURRENTLY rebuild. Docs: parking-session-indexes-concurrent.md
-- =============================================================================

SELECT
    i.relname AS index_name,
    ix.indisvalid,
    ix.indisready,
    pg_size_pretty(pg_relation_size(i.oid)) AS index_size,
    pg_get_indexdef(ix.indexrelid) AS index_def
FROM pg_class t
JOIN pg_index ix ON ix.indrelid = t.oid
JOIN pg_class i ON i.oid = ix.indexrelid
WHERE t.relname = 'parking_sessions'
  AND i.relname IN (
      'idx_parking_sessions_stale_active',
      'idx_parking_sessions_reminder_candidates',
      'idx_parking_sessions_terminal_ended'
  )
ORDER BY i.relname;

-- ACTIVE / terminal row counts (context for rebuild cost)
SELECT status, count(*) AS row_count
FROM parking_sessions
GROUP BY status
ORDER BY status;