-- WP-SPA-14B: privacy-safe ranking evaluation snapshots + outcome correlation.
-- No user, facility, spot, session, destination, or coordinate columns.

CREATE TABLE ranking_evaluations (
    evaluation_id              UUID         NOT NULL,
    created_at                 TIMESTAMPTZ  NOT NULL,
    expires_at                 TIMESTAMPTZ  NOT NULL,
    ranking_version            VARCHAR(64)  NOT NULL,
    ranking_status             VARCHAR(32)  NOT NULL,
    shadow_ranker_version      VARCHAR(64),
    feature_schema_version     VARCHAR(64)  NOT NULL,
    candidate_count            INTEGER      NOT NULL,
    inventory_partial          BOOLEAN      NOT NULL,
    inventory_composition      VARCHAR(32)  NOT NULL,
    deterministic_order_json   TEXT         NOT NULL,
    shadow_order_json          TEXT,
    features_json              TEXT         NOT NULL,
    top1_agreement             BOOLEAN,
    top3_overlap               INTEGER,
    CONSTRAINT pk_ranking_evaluations PRIMARY KEY (evaluation_id),
    CONSTRAINT chk_ranking_evaluations_candidate_count CHECK (candidate_count >= 0),
    CONSTRAINT chk_ranking_evaluations_top3_overlap
        CHECK (top3_overlap IS NULL OR (top3_overlap >= 0 AND top3_overlap <= 3))
);

CREATE INDEX idx_ranking_evaluations_expires_at ON ranking_evaluations (expires_at);
CREATE INDEX idx_ranking_evaluations_created_at ON ranking_evaluations (created_at);

CREATE TABLE ranking_evaluation_outcomes (
    id                  BIGSERIAL    NOT NULL,
    evaluation_id       UUID         NOT NULL,
    candidate_ordinal   INTEGER      NOT NULL,
    outcome_type        VARCHAR(64)  NOT NULL,
    occurred_at         TIMESTAMPTZ  NOT NULL,
    platform            VARCHAR(16)  NOT NULL,
    latency_bucket      VARCHAR(16),
    CONSTRAINT pk_ranking_evaluation_outcomes PRIMARY KEY (id),
    CONSTRAINT fk_ranking_evaluation_outcomes_evaluation
        FOREIGN KEY (evaluation_id) REFERENCES ranking_evaluations (evaluation_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_ranking_evaluation_outcomes_dedupe
        UNIQUE (evaluation_id, candidate_ordinal, outcome_type),
    CONSTRAINT chk_ranking_evaluation_outcomes_ordinal CHECK (candidate_ordinal >= 0),
    CONSTRAINT chk_ranking_evaluation_outcomes_platform
        CHECK (platform IN ('WEB', 'MOBILE_V2', 'UNKNOWN')),
    CONSTRAINT chk_ranking_evaluation_outcomes_type
        CHECK (outcome_type IN (
            'RECOMMENDATION_SELECTED',
            'NAVIGATION_STARTED',
            'PARKING_SESSION_STARTED',
            'RETURN_TO_CAR_STARTED',
            'PARKING_SESSION_ENDED'
        ))
);

CREATE INDEX idx_ranking_evaluation_outcomes_evaluation_id
    ON ranking_evaluation_outcomes (evaluation_id);
CREATE INDEX idx_ranking_evaluation_outcomes_occurred_at
    ON ranking_evaluation_outcomes (occurred_at);
