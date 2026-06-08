-- A moderation case: a unit of review about a target (a parking spot, user or media
-- object). `target_id`, `assigned_moderator_id` are EXTERNAL references (authUserId /
-- other services' ids) — no cross-service FK (ai-context/03). moderation-service
-- never mutates other services' tables; it emits events instead.
CREATE TABLE moderation_cases (
    id                    UUID         NOT NULL,
    target_type           VARCHAR(32)  NOT NULL,
    target_id             UUID         NOT NULL,
    reason                VARCHAR(32)  NOT NULL,
    severity              VARCHAR(16)  NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    assigned_moderator_id UUID,
    report_count          INTEGER      NOT NULL DEFAULT 0,
    resolution_action     VARCHAR(32),
    resolution_note       VARCHAR(1000),
    opened_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at           TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_moderation_cases PRIMARY KEY (id)
);

CREATE INDEX idx_moderation_cases_status ON moderation_cases (status, opened_at);
CREATE INDEX idx_moderation_cases_target ON moderation_cases (target_type, target_id);
