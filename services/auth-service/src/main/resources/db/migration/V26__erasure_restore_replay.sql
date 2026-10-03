-- U02 restore replay: participant ACKs bound to a recovery attempt, restored dataset and
-- erasure set (docs/architecture/erasure-restore-replay-contract.md). Separate from
-- erasure_service_acks, which are request-scoped and never authorize a restore.

CREATE TABLE erasure_restore_attempts (
    recovery_attempt_id UUID         NOT NULL,
    restored_dataset_id VARCHAR(256) NOT NULL,
    erasure_set_digest  VARCHAR(64)  NOT NULL,
    user_count          INTEGER      NOT NULL,
    started_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_erasure_restore_attempts PRIMARY KEY (recovery_attempt_id),
    CONSTRAINT chk_erasure_restore_attempts_user_count CHECK (user_count >= 0)
);

CREATE TABLE erasure_restore_attempt_users (
    recovery_attempt_id UUID        NOT NULL,
    auth_user_id        UUID        NOT NULL,
    erased_at           TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_erasure_restore_attempt_users PRIMARY KEY (recovery_attempt_id, auth_user_id),
    CONSTRAINT fk_erasure_restore_attempt_users_attempt
        FOREIGN KEY (recovery_attempt_id) REFERENCES erasure_restore_attempts (recovery_attempt_id)
);

CREATE TABLE erasure_restore_acks (
    recovery_attempt_id UUID        NOT NULL,
    auth_user_id        UUID        NOT NULL,
    service_name        VARCHAR(64) NOT NULL,
    status              VARCHAR(16) NOT NULL,
    acked_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_erasure_restore_acks PRIMARY KEY (recovery_attempt_id, auth_user_id, service_name),
    CONSTRAINT fk_erasure_restore_acks_attempt_user
        FOREIGN KEY (recovery_attempt_id, auth_user_id)
        REFERENCES erasure_restore_attempt_users (recovery_attempt_id, auth_user_id),
    CONSTRAINT chk_erasure_restore_acks_status CHECK (status IN ('SUCCESS', 'FAILED'))
);
