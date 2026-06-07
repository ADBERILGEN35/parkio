-- Core authentication user records owned by auth-service.
-- UUID primary key; email and phone_number are unique; `version` enables
-- JPA optimistic locking. Passwords are stored only as BCrypt hashes.
CREATE TABLE auth_users (
    id            UUID         NOT NULL,
    email         VARCHAR(255) NOT NULL,
    phone_number  VARCHAR(32)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_auth_users PRIMARY KEY (id),
    CONSTRAINT uq_auth_users_email UNIQUE (email),
    CONSTRAINT uq_auth_users_phone_number UNIQUE (phone_number)
);
