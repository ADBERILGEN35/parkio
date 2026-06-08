-- A report filed by a user against a target. The UNIQUE constraint makes a repeat
-- report from the same reporter for the same target+reason idempotent/rejected.
-- `case_id` links to the case this report opened or fed into (nullable: non-serious
-- reports may not open a case yet).
CREATE TABLE user_reports (
    id               UUID         NOT NULL,
    reporter_user_id UUID         NOT NULL,
    target_type      VARCHAR(32)  NOT NULL,
    target_id        UUID         NOT NULL,
    reason           VARCHAR(32)  NOT NULL,
    description      VARCHAR(1000),
    case_id          UUID,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_reports PRIMARY KEY (id),
    CONSTRAINT uq_user_reports_reporter_target_reason
        UNIQUE (reporter_user_id, target_type, target_id, reason),
    CONSTRAINT fk_user_reports_case FOREIGN KEY (case_id) REFERENCES moderation_cases (id)
);

CREATE INDEX idx_user_reports_reporter ON user_reports (reporter_user_id, created_at DESC);
CREATE INDEX idx_user_reports_target ON user_reports (target_type, target_id);
