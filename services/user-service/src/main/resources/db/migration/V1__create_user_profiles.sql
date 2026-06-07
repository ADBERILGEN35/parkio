-- Core user profile owned by user-service. `auth_user_id` is an EXTERNAL
-- reference to auth-service's account id — no cross-service foreign key
-- (ai-context/03). PII (email, phone) lives here / in auth only.
CREATE TABLE user_profiles (
    id            UUID         NOT NULL,
    auth_user_id  UUID         NOT NULL,
    email         VARCHAR(255),
    display_name  VARCHAR(50)  NOT NULL,
    phone_number  VARCHAR(32),
    city          VARCHAR(100),
    status        VARCHAR(32)  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_profiles PRIMARY KEY (id),
    CONSTRAINT uq_user_profiles_auth_user_id UNIQUE (auth_user_id)
);
