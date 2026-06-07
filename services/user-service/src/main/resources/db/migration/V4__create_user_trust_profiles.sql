-- Trust + gamification PROJECTION (1:1 with a profile). These fields are read
-- copies maintained from gamification/moderation events (ai-context/02, 03);
-- user-service never computes scores itself. Defaults: trust 100 / HIGH_TRUST,
-- 0 points, level 1.
CREATE TABLE user_trust_profiles (
    id              UUID        NOT NULL,
    user_profile_id UUID        NOT NULL,
    trust_score     INT         NOT NULL DEFAULT 100,
    trust_band      VARCHAR(32) NOT NULL DEFAULT 'HIGH_TRUST',
    total_points    BIGINT      NOT NULL DEFAULT 0,
    current_level   INT         NOT NULL DEFAULT 1,
    version         BIGINT      NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_trust_profiles PRIMARY KEY (id),
    CONSTRAINT uq_user_trust_profiles_profile UNIQUE (user_profile_id),
    CONSTRAINT fk_user_trust_profiles_profile FOREIGN KEY (user_profile_id)
        REFERENCES user_profiles (id) ON DELETE CASCADE
);
