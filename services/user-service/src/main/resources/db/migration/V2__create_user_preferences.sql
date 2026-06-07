-- Per-user preferences (1:1 with a profile). FK stays within user-service's DB.
CREATE TABLE user_preferences (
    id                      UUID        NOT NULL,
    user_profile_id         UUID        NOT NULL,
    preferred_radius_meters INT         NOT NULL DEFAULT 1000,
    notifications_enabled   BOOLEAN     NOT NULL DEFAULT TRUE,
    version                 BIGINT      NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_preferences PRIMARY KEY (id),
    CONSTRAINT uq_user_preferences_profile UNIQUE (user_profile_id),
    CONSTRAINT fk_user_preferences_profile FOREIGN KEY (user_profile_id)
        REFERENCES user_profiles (id) ON DELETE CASCADE
);
