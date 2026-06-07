-- Append-only history of trust-score changes (projection audit trail).
CREATE TABLE user_trust_score_history (
    id              UUID        NOT NULL,
    user_profile_id UUID        NOT NULL,
    previous_score  INT,
    new_score       INT         NOT NULL,
    reason          VARCHAR(64) NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_trust_score_history PRIMARY KEY (id),
    CONSTRAINT fk_user_trust_score_history_profile FOREIGN KEY (user_profile_id)
        REFERENCES user_profiles (id) ON DELETE CASCADE
);

CREATE INDEX idx_user_trust_score_history_profile
    ON user_trust_score_history (user_profile_id, occurred_at);
