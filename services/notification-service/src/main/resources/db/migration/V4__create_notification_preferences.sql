-- Per-user delivery-channel preferences (a projection/local copy; user-service owns
-- the profile). `user_id` is the authUserId. Defaults: all channels enabled.
CREATE TABLE notification_preferences (
    user_id          UUID        NOT NULL,
    push_enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    email_enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    in_app_enabled   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_notification_preferences PRIMARY KEY (user_id)
);
