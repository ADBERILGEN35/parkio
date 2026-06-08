-- Push device tokens per user. A token is UNIQUE per (user, token); tokens are
-- deactivated (active = false) rather than deleted, so delivery history stays intact.
CREATE TABLE device_tokens (
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    token       VARCHAR(512) NOT NULL,
    platform    VARCHAR(16)  NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_device_tokens PRIMARY KEY (id),
    CONSTRAINT uq_device_tokens_user_token UNIQUE (user_id, token)
);

CREATE INDEX idx_device_tokens_user ON device_tokens (user_id);
