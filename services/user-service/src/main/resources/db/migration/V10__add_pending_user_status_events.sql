-- Moderation status events (UserSuspended/UserRestored) can arrive before the
-- profile is provisioned from UserRegistered (different Kafka topics — no
-- cross-topic ordering). They must never be lost:
--
-- 1. `pending_user_status_events` parks a status event whose profile does not
--    exist yet. `id` is the moderation event's eventId (natural dedup). When the
--    profile is later provisioned, the latest pending event by occurred_at is
--    applied and the user's rows are deleted.
-- 2. `user_profiles.last_status_event_at` records the occurred_at of the last
--    applied moderation status event, so an older, out-of-order suspend/restore
--    can never override a newer status decision.
CREATE TABLE pending_user_status_events (
    id            UUID         NOT NULL,
    auth_user_id  UUID         NOT NULL,
    target_status VARCHAR(32)  NOT NULL,
    occurred_at   TIMESTAMPTZ  NOT NULL,
    case_id       UUID,
    recorded_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_pending_user_status_events PRIMARY KEY (id)
);

CREATE INDEX idx_pending_user_status_events_user
    ON pending_user_status_events (auth_user_id);

ALTER TABLE user_profiles
    ADD COLUMN last_status_event_at TIMESTAMPTZ;
