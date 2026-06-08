-- Append-only audit log of moderator decisions on cases. Every case resolution
-- writes one row; rows are never updated or deleted.
CREATE TABLE moderation_decisions (
    id            UUID         NOT NULL,
    case_id       UUID         NOT NULL,
    moderator_id  UUID         NOT NULL,
    action        VARCHAR(32)  NOT NULL,
    note          VARCHAR(1000),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_moderation_decisions PRIMARY KEY (id),
    CONSTRAINT fk_moderation_decisions_case FOREIGN KEY (case_id) REFERENCES moderation_cases (id)
);

CREATE INDEX idx_moderation_decisions_case ON moderation_decisions (case_id, created_at);
