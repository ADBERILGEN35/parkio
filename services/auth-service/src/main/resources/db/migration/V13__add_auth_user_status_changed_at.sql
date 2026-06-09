-- auth-service now syncs account status from moderation's UserSuspended/UserRestored
-- events (parkio.moderation.action). `status_changed_at` records the occurredAt of the
-- last applied status event so an older, out-of-order restore/suspend can never
-- override a newer status decision. NULL = status never changed by a moderation event.
ALTER TABLE auth_users
    ADD COLUMN status_changed_at TIMESTAMPTZ;

-- Suspension revokes a user's active refresh tokens; the revocation query filters by
-- user and active state.
CREATE INDEX idx_refresh_tokens_user_active
    ON refresh_tokens (user_id)
    WHERE revoked = FALSE;
