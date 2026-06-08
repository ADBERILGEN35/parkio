-- Append-only record of a violation/penalty applied to a user as the outcome of a
-- case. moderation-service records the decision here and emits an event; it does not
-- mutate user/gamification data directly. `user_id` is the offending authUserId.
CREATE TABLE user_violations (
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    case_id     UUID         NOT NULL,
    reason      VARCHAR(32)  NOT NULL,
    severity    VARCHAR(16)  NOT NULL,
    action      VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_violations PRIMARY KEY (id),
    CONSTRAINT fk_user_violations_case FOREIGN KEY (case_id) REFERENCES moderation_cases (id)
);

CREATE INDEX idx_user_violations_user ON user_violations (user_id, created_at DESC);
