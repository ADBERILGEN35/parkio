-- A user's appeal against a resolved case that affected them. One appeal per
-- (case, user). `case_id` references the case (intra-service FK).
CREATE TABLE appeals (
    id                    UUID         NOT NULL,
    appeal_user_id        UUID         NOT NULL,
    case_id               UUID         NOT NULL,
    note                  VARCHAR(1000),
    status                VARCHAR(16)  NOT NULL,
    resolver_moderator_id UUID,
    resolution_note       VARCHAR(1000),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at           TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_appeals PRIMARY KEY (id),
    CONSTRAINT uq_appeals_case_user UNIQUE (case_id, appeal_user_id),
    CONSTRAINT fk_appeals_case FOREIGN KEY (case_id) REFERENCES moderation_cases (id)
);

CREATE INDEX idx_appeals_user ON appeals (appeal_user_id, created_at DESC);
CREATE INDEX idx_appeals_status ON appeals (status, created_at);
