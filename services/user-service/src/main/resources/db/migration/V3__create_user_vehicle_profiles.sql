-- Optional vehicle profile (1:1 with a profile). `plate` is private and never
-- exposed in public profiles.
CREATE TABLE user_vehicle_profiles (
    id              UUID        NOT NULL,
    user_profile_id UUID        NOT NULL,
    vehicle_type    VARCHAR(32),
    plate           VARCHAR(16),
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_vehicle_profiles PRIMARY KEY (id),
    CONSTRAINT uq_user_vehicle_profiles_profile UNIQUE (user_profile_id),
    CONSTRAINT fk_user_vehicle_profiles_profile FOREIGN KEY (user_profile_id)
        REFERENCES user_profiles (id) ON DELETE CASCADE
);
