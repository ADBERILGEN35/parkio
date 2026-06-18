-- Absolute session lifetime cap for refresh-token families.
--
-- Each family records when it started (familyStartedAt). Rotation preserves it, so a
-- family is force-expired once it exceeds parkio.security.jwt.refresh-absolute-ttl
-- (default 90 days), independent of the sliding per-token refresh TTL. Past the cap the
-- refresh endpoint rejects with the generic INVALID_REFRESH_TOKEN and revokes the family.
--
-- Backfill: existing tokens adopt their own creation time as the family start. This is
-- conservative — pre-existing sessions keep at most the same absolute window from their
-- creation, never longer.
ALTER TABLE refresh_tokens
    ADD COLUMN family_started_at TIMESTAMPTZ;

UPDATE refresh_tokens
SET family_started_at = created_at
WHERE family_started_at IS NULL;

ALTER TABLE refresh_tokens
    ALTER COLUMN family_started_at SET NOT NULL;
